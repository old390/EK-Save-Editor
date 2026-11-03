package com.ekeditor.app

/**
 * Leitura e edição dos quatro baús da Prefeitura.
 * Os containers worldContainers (vault..vault4) são independentes do backpack.
 * Apenas o container selecionado é alterado; o restante do save é preservado.
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
            require(vault in 1..4) { "Selecione um baú válido" }
            if (action == Action.UNLOCK) return
            require(index in 0..19 && itemId in 1..999_999 && amount in 0..999 &&
                occurrence >= 0) { "Dados do item do baú inválidos" }
            if (action in setOf(Action.ADD, Action.SET, Action.INSERT, Action.REPLACE))
                require(amount in 1..999) { "Quantidade inválida" }
            if (action == Action.REPLACE)
                require(expectedByPath.keys == paths) { "Confirmação incompleta dos quatro saves" }
        }
    }

    // O formato foi validado contra os quatro arquivos .sav de um backup real.
    private val containerPattern = Regex(
        """(?m)(^\t\t\{\s*\n\t\t\tid:\s*(vault[234]?)\s*\n\t\t\titems:\s*\{\s*\n\t\t\t\titemList:\s*\[)([^\]]*)(\]\s*\n\t\t\t\tstackUnits:\s*\[)([^\]]*)(\])"""
    )

    private fun vaultId(number: Int) = if (number == 1) "vault" else "vault" + number
    private fun flag(number: Int) = if (number == 1) "hasVault" else "hasVault" + number

    private fun findContainer(text: String, number: Int): MatchResult? =
        containerPattern.findAll(text).firstOrNull { it.groupValues[2] == vaultId(number) }

    private fun parseNumbers(raw: String): MutableList<Int> =
        raw.split(',').map {
            it.trim().toIntOrNull() ?: error("Valor de baú inválido")
        }.toMutableList()

    private fun slots(match: MatchResult): List<EkBackup.InventorySlot> {
        val ids = parseNumbers(match.groupValues[3])
        val counts = parseNumbers(match.groupValues[5])
        require(ids.size == 20 && counts.size == 20) {
            "O baú não possui 20 espaços compatíveis; alteração cancelada"
        }
        require(ids.all { it >= 0 } && counts.all { it >= 0 }) {
            "Valores negativos no baú"
        }
        return ids.zip(counts).map { (id, count) -> EkBackup.InventorySlot(id, count) }
    }

    private fun unlocked(text: String, number: Int): Boolean {
        val pattern = Regex("""(?m)^\t""" + Regex.escape(flag(number)) + """:\s*(true|false)[ \t]*$""")
        val matches = pattern.findAll(text).toList()
        require(matches.size == 1) { "Situação de desbloqueio do baú $number não encontrada" }
        return matches.single().groupValues[1] == "true"
    }

    fun read(text: String): List<VaultInfo> =
        (1..4).mapNotNull { number ->
            val match = findContainer(text, number) ?: return@mapNotNull null
            VaultInfo(number, unlocked(text, number), slots(match))
        }

    private fun chosen(text: String, number: Int): VaultInfo =
        read(text).singleOrNull { it.number == number }
            ?: error("Baú $number não localizado neste save. Nenhum dado modificado.")

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
                "Espaço " + (preferred + 1) + " ocupado no baú de " + path
            }
            free.remove(preferred)
            free.add(0, preferred)
        }
        if (!ItemRules.isStackable(id)) {
            require(free.size >= amount) {
                "Baú com espaço insuficiente em " + path + ": item unitário precisa de " + amount + " espaços livres; somente " + free.size + " disponíveis"
            }
            for (i in free.take(amount)) { ids[i] = id; counts[i] = 1 }
        } else {
            require(free.isNotEmpty()) {
                "Baú cheio em " + path + ". Escolha substituição com confirmação."
            }
            ids[free.first()] = id
            counts[free.first()] = amount
        }
    }

    fun modify(text: String, change: Change, path: String): String {
        require(change.vault in 1..4)
        val match = findContainer(text, change.vault)
            ?: error("Baú " + change.vault + " ausente em " + path)
        val initial = chosen(text, change.vault)
        if (change.action == Action.UNLOCK) {
            val field = Regex("""(?m)^(\t""" + Regex.escape(flag(change.vault)) + """:\s*)(true|false)([ \t]*)$""")
            val flagMatch = field.find(text) ?: error("Campo de desbloqueio ausente em " + path)
            if (initial.unlocked) return text
            return text.replaceRange(flagMatch.range,
                flagMatch.groupValues[1] + "true" + flagMatch.groupValues[3])
        }
        require(initial.unlocked) {
            "Baú " + change.vault + " bloqueado em " + path + ". Desbloqueie antes de editar."
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
                            "A pilha excederia 999 unidades em " + path
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
                    ?: error("Falta confirmação para " + path)
                require(initial.slots[change.index] == expected) {
                    "O espaço " + (change.index + 1) + " mudou em " + path + ". Substituição cancelada."
                }
                ids[change.index] = change.itemId
                counts[change.index] = change.amount
            }
            Action.UNLOCK -> error("Ação inesperada")
        }
        val groups = match.groupValues
        val updated = groups[1] + ids.joinToString(", ", " ", " ") +
            groups[4] + counts.joinToString(", ", " ", " ") + groups[6]
        return text.replaceRange(match.range, updated)
    }

    /**
     * Verificação pós-gravação: só o baú escolhido pode mudar.
     * Em uma troca, os outros 19 espaços devem permanecer idênticos.
     */
    fun verify(before: String, after: String, change: Change, path: String) {
        val oldVaults = read(before).associateBy { it.number }
        val newVaults = read(after).associateBy { it.number }
        require(oldVaults.keys == newVaults.keys) {
            "A quantidade de baús mudou em " + path
        }
        for ((n, v) in oldVaults) if (n != change.vault) {
            require(v == newVaults[n]) {
                "Baú " + n + " foi alterado inesperadamente em " + path
            }
        }
        val previous = oldVaults[change.vault] ?: error("Baú original ausente")
        val current = newVaults[change.vault] ?: error("Baú modificado ausente")
        if (change.action == Action.UNLOCK) {
            require(current.unlocked && previous.slots == current.slots) {
                "Falha ao desbloquear baú sem alterar os itens"
            }
            return
        }
        require(previous.unlocked == current.unlocked) { "Estado do baú mudou inesperadamente" }
        val totalBefore = previous.total(change.itemId)
        val totalAfter = current.total(change.itemId)
        when (change.action) {
            Action.ADD, Action.INSERT -> require(totalAfter == totalBefore + change.amount) {
                "A adição no baú " + change.vault + " não corresponde ao solicitado em " + path
            }
            Action.DELETE -> {
                val index = selectedSlot(previous.slots, change)
                val removed = if (index == null) 0 else previous.slots[index].count
                require(totalAfter == totalBefore - removed) {
                    "A exclusão não corresponde ao item selecionado em " + path
                }
            }
            Action.SET -> {
                val index = selectedSlot(previous.slots, change)
                val oldCount = if (index == null) 0 else previous.slots[index].count
                require(totalAfter == totalBefore - oldCount + change.amount) {
                    "A quantidade do item não foi definida corretamente"
                }
            }
            Action.REPLACE -> {
                require(current.slots[change.index] == EkBackup.InventorySlot(change.itemId, change.amount)) {
                    "Troca não aplicada em " + path
                }
                require(previous.slots.indices.filter { it != change.index }.all {
                    previous.slots[it] == current.slots[it]
                }) { "A troca afetou outras posições do baú" }
            }
            Action.UNLOCK -> Unit
        }
        if (change.action != Action.REPLACE) {
            // Sem substituição confirmada, nenhum item de outro ID pode sumir.
            val originals = previous.slots.filter { it.id != 0 && it.id != change.itemId }
            val updated = current.slots.filter { it.id != 0 && it.id != change.itemId }
            require(originals == updated) { "Outro item do baú mudou inesperadamente em " + path }
        }
    }
}
