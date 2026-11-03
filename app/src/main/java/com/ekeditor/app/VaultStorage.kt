package com.ekeditor.app

/**
 * Read and edit the four Town Hall vaults.
 * worldContainers (vault..vault4) are independent of the backpack.
 * Only the selected container is changed; the rest of the save is preserved.
 */
object VaultStorage {
    enum class Action { UNLOCK, ADD, SET, DELETE, INSERT, REPLACE }

    data class VaultInfo(
        val number: Int,
        val unlocked: Boolean,
        val slots: List<EkBackup.InventorySlot>
    ) {
        val occupied: Int get() = slots.count { it.id != 0 }
        val free: Int get() = slots.count { it.id == 0 }
        fun total(id: Int): Int = slots.filter { it.id == id }.sumOf { it.count }
    }

    data class Change(
        val vault: Int,
        val action: Action,
        val index: Int = -1,
        val itemId: Int = 0,
        val amount: Int = 0,
        val occurrence: Int = 0,
        val expectedByPath: Map<String, EkBackup.InventorySlot> = emptyMap()
    ) {
        fun validate(paths: Set<String>) {
            require(vault in 1..4) { "Select a valid vault" }
            if (action == Action.UNLOCK) return
            require(index in 0..19 && itemId in 1..999_999 && amount in 0..999 &&
                occurrence >= 0) { "Invalid vault item data" }
            if (action in setOf(Action.ADD, Action.SET, Action.INSERT, Action.REPLACE))
                require(amount in 1..999) { "Invalid quantity" }
            if (action == Action.REPLACE)
                require(expectedByPath.keys == paths) { "Incomplete confirmation for the selected character's saves" }
        }
    }

    // Format validated against the .sav files in a real game backup.
    private val containerPattern = Regex(
        """(?m)(^\t\t\{\s*\n\t\t\tid:\s*(vault[234]?)\s*\n\t\t\titems:\s*\{\s*\n\t\t\t\titemList:\s*\[)([^\]]*)(\]\s*\n\t\t\t\tstackUnits:\s*\[)([^\]]*)(\])"""
    )

    private fun vaultId(number: Int) = if (number == 1) "vault" else "vault" + number
    private fun flag(number: Int) = if (number == 1) "hasVault" else "hasVault" + number

    private fun findContainer(text: String, number: Int): MatchResult? =
        containerPattern.findAll(text).firstOrNull { it.groupValues[2] == vaultId(number) }

    private fun parseNumbers(raw: String): MutableList<Int> =
        raw.split(',').map {
            it.trim().toIntOrNull() ?: error("Invalid vault value")
        }.toMutableList()

    private fun slots(match: MatchResult): List<EkBackup.InventorySlot> {
        val ids = parseNumbers(match.groupValues[3])
        val counts = parseNumbers(match.groupValues[5])
        require(ids.size == 20 && counts.size == 20) {
            "This vault does not have 20 compatible slots. Edit canceled."
        }
        require(ids.all { it >= 0 } && counts.all { it >= 0 }) {
            "Negative values found in the vault"
        }
        return ids.zip(counts).map { (id, count) -> EkBackup.InventorySlot(id, count) }
    }

    private fun unlocked(text: String, number: Int): Boolean {
        val pattern = Regex("""(?m)^\t""" + Regex.escape(flag(number)) + """:\s*(true|false)[ \t]*$""")
        val matches = pattern.findAll(text).toList()
        require(matches.size == 1) { "Vault unlock status for $number was not found" }
        return matches.single().groupValues[1] == "true"
    }

    fun read(text: String): List<VaultInfo> =
        (1..4).mapNotNull { number ->
            val match = findContainer(text, number) ?: return@mapNotNull null
            VaultInfo(number, unlocked(text, number), slots(match))
        }

    private fun chosen(text: String, number: Int): VaultInfo =
        read(text).singleOrNull { it.number == number }
            ?: error("Vault $number not found in this save. No changes made.")

    private fun selectedSlot(old: List<EkBackup.InventorySlot>, change: Change): Int? =
        old.indices.filter { old[it].id == change.itemId }.getOrNull(change.occurrence)

    private fun insert(
        ids: MutableList<Int>,
        counts: MutableList<Int>,
        id: Int, amount: Int, path: String, preferred: Int? = null
    ) {
        val free = ids.indices.filter { ids[it] == 0 }.toMutableList()
        if (preferred != null) {
            require(preferred in free) {
                "Slot " + (preferred + 1) + " is occupied in the vault in " + path
            }
            free.remove(preferred)
            free.add(0, preferred)
        }
        if (!ItemRules.isStackable(id)) {
            require(free.size >= amount) {
                "Insufficient vault space in " + path + ": non-stackable items require " + amount + " free slots; only " + free.size + " available"
            }
            for (i in free.take(amount)) { ids[i] = id; counts[i] = 1 }
        } else {
            require(free.isNotEmpty()) {
                "Vault full in " + path + ". Choose an item and explicitly confirm the replacement."
            }
            ids[free.first()] = id
            counts[free.first()] = amount
        }
    }

    fun modify(text: String, change: Change, path: String): String {
        require(change.vault in 1..4)
        val match = findContainer(text, change.vault)
            ?: error("Vault " + change.vault + " missing in " + path)
        val initial = chosen(text, change.vault)
        if (change.action == Action.UNLOCK) {
            val field = Regex("""(?m)^(\t""" + Regex.escape(flag(change.vault)) + """:\s*)(true|false)([ \t]*)$""")
            val flagMatch = field.find(text) ?: error("Campo de desbloqueio missing in " + path)
            if (initial.unlocked) return text
            return text.replaceRange(flagMatch.range,
                flagMatch.groupValues[1] + "true" + flagMatch.groupValues[3])
        }
        require(initial.unlocked) {
            "Vault " + change.vault + " is locked in " + path + ". Unlock it before editing."
        }
        val ids = initial.slots.map { it.id }.toMutableList()
        val counts = initial.slots.map { it.count }.toMutableList()
        when (change.action) {
            Action.ADD -> {
                if (!ItemRules.isStackable(change.itemId)) {
                    insert(ids, counts, change.itemId, change.amount, path)
                } else {
                    val index = selectedSlot(initial.slots, change)
                    if (index == null) insert(ids, counts, change.itemId, change.amount, path)
                    else {
                        require(counts[index].toLong() + change.amount <= 999L) {
                            "Stack size would exceed 999 in " + path
                        }
                        counts[index] += change.amount
                    }
                }
            }
            Action.SET -> {
                ItemRules.ensureSlotQuantity(change.itemId, change.amount)
                val index = selectedSlot(initial.slots, change)
                if (index == null) insert(ids, counts, change.itemId, change.amount, path)
                else counts[index] = change.amount
            }
            Action.DELETE -> {
                val index = selectedSlot(initial.slots, change)
                if (index != null) { ids[index] = 0; counts[index] = 0 }
            }
            Action.INSERT -> insert(ids, counts, change.itemId, change.amount, path, change.index)
            Action.REPLACE -> {
                ItemRules.ensureSlotQuantity(change.itemId, change.amount)
                val expected = change.expectedByPath[path]
                    ?: error("Missing replacement confirmation for " + path)
                require(initial.slots[change.index] == expected) {
                    "Slot " + (change.index + 1) + " changed in " + path + ". Replacement canceled."
                }
                ids[change.index] = change.itemId
                counts[change.index] = change.amount
            }
            Action.UNLOCK -> error("Unexpected operation")
        }
        val groups = match.groupValues
        val updated = groups[1] + ids.joinToString(", ", " ", " ") +
            groups[4] + counts.joinToString(", ", " ", " ") + groups[6]
        return text.replaceRange(match.range, updated)
    }

    /**
     * Post-write verification: only the selected vault may change.
     * When replacing one slot, the other 19 must remain unchanged.
     */
    fun verify(before: String, after: String, change: Change, path: String) {
        val oldVaults = read(before).associateBy { it.number }
        val newVaults = read(after).associateBy { it.number }
        require(oldVaults.keys == newVaults.keys) {
            "The number of vaults changed in " + path
        }
        for ((n, v) in oldVaults) if (n != change.vault) {
            require(v == newVaults[n]) {
                "Vault " + n + " was unexpectedly modified in " + path
            }
        }
        val previous = oldVaults[change.vault] ?: error("Vault original ausente")
        val current = newVaults[change.vault] ?: error("Vault modificado ausente")
        if (change.action == Action.UNLOCK) {
            require(current.unlocked && previous.slots == current.slots) {
                "Could not unlock the vault without changing items"
            }
            return
        }
        require(previous.unlocked == current.unlocked) { "Vault unlock state unexpectedly changed" }
        val totalBefore = previous.total(change.itemId)
        val totalAfter = current.total(change.itemId)
        when (change.action) {
            Action.ADD, Action.INSERT -> require(totalAfter == totalBefore + change.amount) {
                "The addition to vault " + change.vault + " does not match the requested amount in " + path
            }
            Action.DELETE -> {
                val index = selectedSlot(previous.slots, change)
                val removed = if (index == null) 0 else previous.slots[index].count
                require(totalAfter == totalBefore - removed) {
                    "Deletion did not match the selected item in " + path
                }
            }
            Action.SET -> {
                val index = selectedSlot(previous.slots, change)
                val oldCount = if (index == null) 0 else previous.slots[index].count
                require(totalAfter == totalBefore - oldCount + change.amount) {
                    "Item quantity was not set correctly"
                }
            }
            Action.REPLACE -> {
                require(current.slots[change.index] == EkBackup.InventorySlot(change.itemId, change.amount)) {
                    "Replacement not applied in " + path
                }
                require(previous.slots.indices.filter { it != change.index }.all {
                    previous.slots[it] == current.slots[it]
                }) { "The replacement affected other vault slots" }
            }
            Action.UNLOCK -> Unit
        }
        if (change.action != Action.REPLACE) {
            // Without a confirmed replacement, no item with another ID may disappear.
            val originals = previous.slots.filter { it.id != 0 && it.id != change.itemId }
            val updated = current.slots.filter { it.id != 0 && it.id != change.itemId }
            require(originals == updated) { "Another vault item changed unexpectedly in " + path }
        }
    }
}
