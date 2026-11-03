package com.ekeditor.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** EK.bak = ZIP with Base64 UTF-8 *.sav. Every edit is atomic and validated. */
object EkBackup {
    private val savePath = Regex("""saves/\d+/(?:game2?|auto2?)\.sav""")
    private val goldPattern = Regex("""(?m)^(\t\tgold:\s*)(\d+)(\s*)$""")
    private val traitPattern = Regex("""(?m)^(\t{4}extraTraitPoints:\s*)(\d+)(\s*)$""")
    private val skillPattern = Regex("""(?m)^(\t{4}bonusPoints:\s*)(\d+)(\s*)$""")
    private val anyTraitPattern = Regex("""(?m)^(\t+extraTraitPoints:\s*)(\d+)(\s*)$""")
    private val anySkillPattern = Regex("""(?m)^(\t+bonusPoints:\s*)(\d+)(\s*)$""")
    private val backpackPattern = Regex("""(?m)^(\tbackpack: \{\s*\n\t\titemList: \[)([^\]]*)(\]\s*\n\t\tstackUnits: \[)([^\]]*)(\])""")
    private val spentTraitPattern = Regex("""(?m)^\t{4}spentPoints:\s*\[([^\]]*)\]""")
    private val playerLevelPattern = Regex("""(?m)^\t{4}cached_level:\s*(\d+)\s*$""")
    private val anySpentTraitPattern = Regex("""(?m)^\t+spentPoints:\s*\[([^\]]*)\]""")
    private val anyLevelPattern = Regex("""(?m)^\t+cached_level:\s*(\d+)\s*$""")
    private val learnedSkillLevelPattern = Regex("""(?m)^\t+level:\s*(\d+)\s*$""")
    private val learnedSkillIdPattern = Regex("""(?m)^\t+skillID:\s*(\S+)\s*$""")
    private val activeCompanionPattern = Regex("""(?m)^\t\tactiveCompanion:\s*([^\r\n]*)""")

    data class CompanionInfo(
        val name: String, val traitPoints: Int, val skillPoints: Int,
        val freeTraitPoints: Int? = null, val freeSkillPoints: Int? = null,
        val level: Int? = null, val spentTraitPoints: Int? = null,
        val spentSkillPoints: Int? = null
    )
    data class SaveInfo(
        val path: String,
        val hero: String,
        val characterClass: String,
        val gold: Long,
        val traitPoints: Int,
        val skillPoints: Int,
        val freeSlots: Int,
        val inventory: Map<Int, Int>,
        val companion: CompanionInfo? = null,
        val inventorySlots: List<InventorySlot> = emptyList(),
        /** Calculated available points: earned by level + bonuses − points spent. */
        val freeTraitPoints: Int? = null,
        val characterLevel: Int? = null,
        val spentTraitPoints: Int? = null,
        val freeSkillPoints: Int? = null,
        val spentSkillPoints: Int? = null
    ) {
        /** Each saves/0, saves/1, saves/2... folder belongs to a separate character. */
        val characterSlot: Int
            get() = path.substringAfter("saves/").substringBefore('/').toInt()
    }

    data class CharacterInfo(
        val slot: Int,
        val name: String,
        val characterClass: String,
        val level: Int?,
        val savePaths: Set<String>
    )

    /** Character order comes from save folder IDs, not names or file counts. */
    fun characters(saves: List<SaveInfo>): List<CharacterInfo> =
        saves.groupBy { it.characterSlot }.toSortedMap().map { (slot, group) ->
            val primary = group.firstOrNull { it.path.endsWith("/auto.sav") }
                ?: group.firstOrNull { it.path.endsWith("/game.sav") }
                ?: group.first()
            CharacterInfo(slot, primary.hero, primary.characterClass,
                primary.characterLevel, group.map { it.path }.toSet())
        }

    /** Item at an actual inventory slot, preserving empty slots as well. */
    data class InventorySlot(val id: Int, val count: Int)
    data class ItemAdd(val id: Int, val count: Int = 1)
    /**
     * Each selected copy is also identified by its order among
     * occurrences with the same ID (0 = first copy). Each save may have
     * the same copy at a different position. null maintains backward compatibility.
     */
    data class SlotEdit(
        val index: Int, val expectedId: Int, val expectedCount: Int, val quantity: Int,
        val addQuantity: Boolean = false,
        val occurrence: Int? = null
    )
    /** Insert only into the specified empty slot in ALL selected saves. */
    data class SlotInsert(val index: Int, val itemId: Int, val count: Int)
    /**
     * Substituicao explicitamente confirmada pelo usuario, com imagem
     * dos conteudos esperados de CADA save. Nunca substitua sem permissao.
     */
    data class SlotReplace(
        val index: Int,
        val itemId: Int,
        val count: Int,
        val expectedByPath: Map<String, InventorySlot>
    )
    data class EditPlan(
        val targetPaths: Set<String>,
        val gold: Long? = null,
        val traitPoints: Int? = null,
        val skillPoints: Int? = null,
        val items: List<ItemAdd> = emptyList(),
        val slotEdits: List<SlotEdit> = emptyList(),
        val slotInserts: List<SlotInsert> = emptyList(),
        val slotReplacements: List<SlotReplace> = emptyList(),
        val vaultChange: VaultStorage.Change? = null,
        val traitDelta: Int? = null,
        val skillDelta: Int? = null,
        val resetTraits: Boolean = false,
        val resetSkills: Boolean = false,
        val companionTraitDelta: Int? = null,
        val companionSkillDelta: Int? = null,
        val resetCompanionTraits: Boolean = false,
        val resetCompanionSkills: Boolean = false
    ) {
        fun validate() {
            require(targetPaths.isNotEmpty()) { "Backup sem saves" }
            require(gold != null || traitPoints != null || skillPoints != null || items.isNotEmpty() ||
                slotEdits.isNotEmpty() || slotInserts.isNotEmpty() || slotReplacements.isNotEmpty() || vaultChange != null || traitDelta != null || skillDelta != null || resetTraits || resetSkills || companionTraitDelta != null ||
                companionSkillDelta != null || resetCompanionTraits || resetCompanionSkills) { "No changes selected" }
            require(gold == null || gold in 0..999_999_999) { "Gold is out of range" }
            require(traitPoints == null || traitPoints in 0..999) { "Attribute points are out of range" }
            require(skillPoints == null || skillPoints in 0..999) { "Skill points are out of range" }
            require(listOf(traitPoints != null, traitDelta != null, resetTraits).count { it } <= 1) { "Choose only one attribute editing action" }
            require(listOf(skillPoints != null, skillDelta != null, resetSkills).count { it } <= 1) { "Choose only one skill editing action" }
            require(!(resetCompanionTraits && companionTraitDelta != null)) { "Choose either reset or add companion attributes" }
            require(!(resetCompanionSkills && companionSkillDelta != null)) { "Choose either reset or add companion skills" }
            for (n in listOf(traitDelta, skillDelta, companionTraitDelta, companionSkillDelta)) {
                require(n == null || n in 1..999) { "Add between 1 and 999 points" }
            }
            for (item in items) {
                require(item.id in 1..999_999 && item.count in 1..999) { "Invalid item ID or quantity" }
            }
            for (edit in slotEdits) {
                require(edit.index >= 0 && edit.expectedId in 1..999_999 &&
                    edit.expectedCount in 1..999 && edit.quantity in (if (edit.addQuantity) 1..999 else 0..999) &&
                    (edit.occurrence == null || edit.occurrence >= 0)) {
                    "Invalid item edit"
                }
            }
            for (insert in slotInserts) {
                require(insert.index >= 0 && insert.itemId in 1..999_999 && insert.count in 1..999) {
                    "Invalid item insertion data"
                }
            }
            for (replace in slotReplacements) {
                require(replace.index >= 0 && replace.itemId in 1..999_999 && replace.count in 1..999) {
                    "Invalid item replacement"
                }
                require(replace.expectedByPath.keys == targetPaths) {
                    "Expected inventory data does not cover all selected saves"
                }
            }
            vaultChange?.validate(targetPaths)
        }
    }
    /** UI-independent input validation, including empty and invalid values. */
    fun parseNumberInput(raw: String, label: String, max: Long, min: Long = 0): Long {
        val clean = raw.trim()
        require(clean.isNotEmpty()) { "Digite um valor em '$label' antes de aplicar." }
        val number = clean.toLongOrNull() ?: throw IllegalArgumentException("Enter a valid whole number for '$label'.")
        require(number in min..max) { "$label precisa estar entre $min e $max." }
        return number
    }

    private data class ArchiveItem(val entry: ZipEntry, val contents: ByteArray)

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= max) { "Entrada ZIP muito grande" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    private fun archive(data: ByteArray): List<ArchiveItem> {
        require(data.size in 1..20_000_000 && data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            "Invalid file: select EK.bak"
        }
        val files = mutableListOf<ArchiveItem>()
        val names = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.size < 2000 && names.add(entry.name)) { "ZIP duplicado ou grande demais" }
                val content = readLimited(zip, 4_000_000)
                total += content.size
                require(total <= 100_000_000) { "Backup descompactado muito grande" }
                files += ArchiveItem(entry, content)
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "ZIP vazio" }
        return files
    }
    private fun plain(contents: ByteArray): String = String(Base64.getMimeDecoder().decode(contents), StandardCharsets.UTF_8).also {
        require(it.contains("\nversion:") && it.contains("\ngamedata: {")) { "Incompatible save format" }
    }
    private fun braceClose(text: String, open: Int): Int {
        require(open >= 0 && text[open] == '{') { "Incompatible save block" }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        error("Incomplete save block")
    }
    private fun block(text: String, marker: String, start: Int = 0): IntRange? {
        val index = text.indexOf(marker, start)
        if (index < 0) return null
        val open = text.indexOf('{', index + marker.length - 1)
        require(open >= 0) { "Incomplete save block: $marker" }
        return open..braceClose(text, open)
    }
    private fun playerBounds(text: String): IntRange {
        val gamedata = text.indexOf("\ngamedata: {")
        require(gamedata >= 0) { "Character data is missing" }
        return block(text, "\n\tplayer: {", gamedata) ?: error("Character not found")
    }
    private fun one(text: String, pattern: Regex, key: String): Int = pattern.find(text)?.groupValues?.get(2)?.toInt()
        ?: error("Missing field: $key")
    private fun gold(text: String): Long = goldPattern.find(text)?.groupValues?.get(2)?.toLong()
        ?: error("Gold field not found")
    private data class TraitBalance(
        val level: Int, val byLevel: Int, val extra: Int, val spent: Int, val free: Int
    )

    /** 4 points at level 1, 2 per level through 15, and 3 per level from 16 onward. */
    private fun traitPointsFromLevel(level: Int): Int {
        require(level in 1..999) { "Invalid character level" }
        return 4 + (level - 1).coerceAtMost(14) * 2 + (level - 15).coerceAtLeast(0) * 3
    }

    private fun playerTraitBalance(player: String): TraitBalance? {
        val level = playerLevelPattern.find(player)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (level !in 1..999) return null
        val raw = spentTraitPattern.find(player)?.groupValues?.get(1) ?: return null
        val points = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (points.size != 6 || points.any { it < 0 }) return null
        val spent = points.sum()
        // A newly created character may not have an extraTraitPoints field.
        val extra = traitPattern.find(player)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val byLevel = traitPointsFromLevel(level)
        return TraitBalance(level, byLevel, extra, spent, byLevel + extra - spent)
    }

    /**
     * Point costs by rank, based on the game wiki's skill table.
     * Skills desconhecidas => saldo real desconhecido, nunca presumido.
     */
    private val skillCosts: Map<String, List<Int>> = """
        arbenos_might|1,2,3
        arcane_blade|2,2,2,2
        arcanist|2,2,2
        archery|1,2,3
        assassinate|1,2,3
        bash|1,1,2
        battle_prayer|1,2,3
        battle_rage|1,2,3
        beast_master|1,2,2
        bloodlust|1,2,3
        body_development|1,2,3
        charge|1,1,2,3
        cleave|1,2,3
        combustion|2,2,3
        crusader|1,2,3
        death_cloud|2,2,2
        death_ward|1,1,1
        disintegrate|1,2,3
        duel|1,2,3
        dungeoneering|1,1,1
        earth_mastery|2,2,3
        evasion|1,2,3
        explosive_traps|1,2,3
        extra_recovery|3,3
        fire_mastery|2,2,2
        fire_ward|1,1,1
        fireball|1,2,2,2
        flames_of_faith|1,2,3
        flurry|1,2,3
        fury|1,2,2
        gate|1,1
        gossip|1,1,1
        guardian_wolf|2,2,2
        heal_wounds|1,2,3
        heavyhand|1,2,3
        holy_shield|1,2,2,2
        ice_mastery|1,2,3
        ice_storm|1,2,2,2
        ice_ward|1,1,1
        infantry_training|2,3
        intervention|1,1,1
        kick|1,1,2
        lesser_summoning|2,2
        lightning_bolt|1,2,2,2
        mage_armor|1,2,2,2
        mage_barrier|1,2,3
        magical_training|1,1,1
        mana_surge|1,2,3
        massive_criticals|1,2,3
        nivarias_barrier|1,1,2
        nivaria_s_barrier|1,1,2
        poison_master|1,2,3
        precision_shots|1,2,3
        precision_strikes|1,2,3
        rapid_fire|1,2,3
        resilience|1,2,3
        retribution|2,3
        sacred_fire|1,2,2
        shield_expert|1,1,1
        shock_ward|1,1,1
        smoke_bomb|2,2,2
        sneak_attack|1,2,3
        spiritual_ward|1,1,1
        sprint|1,1,1
        stab|1,2,3,3
        staff_mastery|1,2,2,3
        stealth|1,2,3
        summoner|1,1,2
        thelumes_wisdom|1,1
        toxic_ward|1,1,1
        trap_master|1,2,3
        turn_undead|1,2,3
        two_handed_expert|1,2,3
        vampiric_blade|2,3,4
        wand_mastery|1,2,2,3
        whirlwind|1,2,3,3
    """.trimIndent().lines().filter { it.contains('|') }.associate { row ->
        val (name, values) = row.split('|', limit = 2)
        name to values.split(',').map { it.toInt() }
    }

    private data class SkillBalance(
        val level: Int, val byLevel: Int, val extra: Int, val spent: Int, val free: Int
    )

    private fun skillBalance(character: String, companion: Boolean): SkillBalance? {
        val level = anyLevelPattern.find(character)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (level !in 1..999 || !character.contains("skillSet: {")) return null
        val learned = arrayObjects(character, "characterSkills: [", 0, character.length)
        var spent = 0
        for (range in learned) {
            val learnedSkill = character.substring(range)
            val id = learnedSkillIdPattern.find(learnedSkill)?.groupValues?.get(1) ?: return null
            val rank = learnedSkillLevelPattern.find(learnedSkill)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val price = skillCosts[id] ?: return null
            if (rank !in 1..price.size) return null
            spent += price.take(rank).sum()
        }
        val extra = anySkillPattern.find(character)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val byLevel = if (companion) level else 1 + (level - 1) * 2
        return SkillBalance(level, byLevel, extra, spent, byLevel + extra - spent)
    }

    private fun companionTraitBalance(character: String): TraitBalance? {
        val level = anyLevelPattern.find(character)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (level !in 1..999) return null
        val spentRaw = anySpentTraitPattern.find(character)?.groupValues?.get(1) ?: return null
        val points = spentRaw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (points.size != 6 || points.any { it < 0 }) return null
        val spent = points.sum()
        val bonus = anyTraitPattern.find(character)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        // Companions do not receive the hero's four starting attribute points.
        // Example: level-14 Grissenda with 10 bonus and 24 spent has 14 + 10 - 24 = 0 free points.
        val byLevel = level
        return TraitBalance(level, byLevel, bonus, spent, byLevel + bonus - spent)
    }

    /** Insere o campo extra/bonus ausente no bloco correto sem remover os outros. */
    private fun updateBonusField(character: String, regex: Regex, field: String, marker: String, value: Int): String {
        if (regex.containsMatchIn(character)) return replaceNumber(character, regex, value)
        val range = block(character, marker) ?: error("Missing $marker block in character data")
        val lineBegin = character.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
        val indent = character.substring(lineBegin, range.first).takeWhile { it == '\t' }
        val insertion = "\t$field: $value\n$indent"
        return character.replaceRange(range.last, range.last, insertion)
    }

    private fun numbers(raw: String): MutableList<Int> = raw.split(',').map { it.trim().toInt() }.toMutableList()
    private fun backpack(text: String): MatchResult = backpackPattern.find(text) ?: error("Inventory not found")

    /** Find immediate brace-objects in an array such as party.companions or gamedata.npcs. */
    private fun arrayObjects(text: String, marker: String, from: Int, until: Int): List<IntRange> {
        val markerPos = text.indexOf(marker, from)
        if (markerPos < 0 || markerPos >= until) return emptyList()
        val listStart = text.indexOf('[', markerPos + marker.length - 1)
        if (listStart < 0 || listStart >= until) return emptyList()
        val result = mutableListOf<IntRange>()
        var pos = listStart + 1
        while (pos < until && pos < text.length) {
            when (text[pos]) {
                ']' -> return result
                '{' -> {
                    val close = braceClose(text, pos)
                    require(close < until) { "Incomplete companion list" }
                    result += pos..close
                    pos = close + 1
                }
                else -> pos++
            }
        }
        error("Incomplete companion list")
    }
    private fun activeCompanion(text: String): Pair<String, IntRange>? {
        val party = block(text, "\n\tparty: {") ?: return null
        val partyText = text.substring(party)
        val active = activeCompanionPattern.find(partyText)?.groupValues?.get(1)?.trim()?.trim('"') ?: return null
        if (active.isBlank() || active.lowercase() in setOf("none", "null", "no", "-1")) return null
        val companions = arrayObjects(text, "\n\t\tcompanions: [", party.first, party.last + 1)
        val selected = companions.firstOrNull { range ->
            val obj = text.substring(range)
            val spawn = Regex("""(?m)^\t+spawn_id:\s*(\S+)""").find(obj)?.groupValues?.get(1)
            val tag = Regex("""(?m)^\t+tag:\s*(\S+)""").find(obj)?.groupValues?.get(1)
            spawn.equals(active, true) || tag?.endsWith(active, true) == true
        } ?: return null
        return active to selected
    }
    private fun companionInfo(text: String): CompanionInfo? {
        val (_, range) = activeCompanion(text) ?: return null
        val obj = text.substring(range)
        val traits = companionTraitBalance(obj)
        val skills = skillBalance(obj, true)
        return CompanionInfo(
            Regex("""(?m)^\t+name:\s*(.+)$""").find(obj)?.groupValues?.get(1)?.trim() ?: "Companheiro",
            anyTraitPattern.find(obj)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            anySkillPattern.find(obj)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            freeTraitPoints = traits?.free,
            freeSkillPoints = skills?.free,
            level = traits?.level ?: skills?.level,
            spentTraitPoints = traits?.spent,
            spentSkillPoints = skills?.spent
        )
    }
    fun inspect(data: ByteArray): List<SaveInfo> = archive(data).filter { savePath.matches(it.entry.name) }.map { item ->
        val text = plain(item.contents)
        val player = text.substring(playerBounds(text))
        val pack = backpack(text)
        val ids = numbers(pack.groupValues[2])
        val units = numbers(pack.groupValues[4])
        require(ids.size == units.size) { "Incompatible inventory data" }
        val balance = playerTraitBalance(player)
        val skillsBalance = skillBalance(player, false)
        SaveInfo(
            path = item.entry.name,
            hero = Regex("""(?m)^\t\tname: (.*)$""").find(player)?.groupValues?.get(1)?.trim() ?: "Personagem",
            characterClass = Regex("""(?m)^\t{4}characterClass: (\w+)\s*$""").find(player)?.groupValues?.get(1) ?: "?",
            gold = gold(player),
            traitPoints = traitPattern.find(player)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            skillPoints = skillPattern.find(player)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            freeSlots = ids.count { it == 0 },
            inventory = ids.zip(units).filter { it.first > 0 }
                .groupBy({ it.first }, { it.second }).mapValues { (_, amounts) -> amounts.sum() },
            companion = companionInfo(text),
            inventorySlots = ids.zip(units).map { (id, count) -> InventorySlot(id, count) },
            freeTraitPoints = balance?.free,
            characterLevel = balance?.level,
            spentTraitPoints = balance?.spent,
            freeSkillPoints = skillsBalance?.free,
            spentSkillPoints = skillsBalance?.spent
        )
    }.also { require(it.isNotEmpty()) { "Nenhum save reconhecido no EK.bak" } }

    /** Identify four Town Hall vaults in each save without confusing them with the backpack. */
    fun inspectVaults(data: ByteArray): Map<String, List<VaultStorage.VaultInfo>> =
        archive(data).filter { savePath.matches(it.entry.name) }.associate { source ->
            source.entry.name to VaultStorage.read(plain(source.contents))
        }

    private fun replaceNumber(text: String, pattern: Regex, number: Number): String {
        val found = pattern.find(text) ?: error("Point field not found")
        return text.replaceRange(found.groups[2]!!.range, number.toString())
    }
    private fun increment(current: Int, points: Int?, reset: Boolean, name: String): Int? {
        if (reset) return 0
        if (points == null) return null
        val result = current.toLong() + points
        require(result in 0..999) { "$name exceeds 999 bonus points" }
        return result.toInt()
    }
    private fun rewriteCompanion(text: String, plan: EditPlan): String {
        if (plan.companionTraitDelta == null && plan.companionSkillDelta == null && !plan.resetCompanionTraits && !plan.resetCompanionSkills) return text
        val (active, partyRange) = activeCompanion(text) ?: return text // other saves may lack an active companion
        val currentParty = text.substring(partyRange)
        val traitBonus = anyTraitPattern.find(currentParty)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val skillBonus = anySkillPattern.find(currentParty)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val trait = if (plan.resetCompanionTraits) {
            val balance = companionTraitBalance(currentParty)
                ?: error("Could not determine the companion's spent points. Reset canceled.")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) { "Cannot safely reset attribute points without changing allocated points" }
            newBonus
        } else increment(traitBonus, plan.companionTraitDelta, false, "Companion attributes")
        val skill = if (plan.resetCompanionSkills) {
            val balance = skillBalance(currentParty, true)
                ?: error("Could not identify learned companion skills. Reset canceled.")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) { "Cannot safely reset skill points without removing learned skill ranks" }
            newBonus
        } else increment(skillBonus, plan.companionSkillDelta, false, "Companion skills")
        val blocks = mutableListOf(partyRange)
        // NPC copy of the active companion is kept consistent with party.companions.
        val gameData = block(text, "\ngamedata: {")
        if (gameData != null) {
            blocks += arrayObjects(text, "\n\tnpcs: [", gameData.first, gameData.last + 1).filter { range ->
                val obj = text.substring(range)
                Regex("""(?m)^\t+companionSpawn:\s*true\s*$""").containsMatchIn(obj) &&
                    Regex("""(?m)^\t+spawn_id:\s*${Regex.escape(active)}\s*$""").containsMatchIn(obj)
            }
        }
        var changed = text
        for (range in blocks.sortedByDescending { it.first }) {
            var part = changed.substring(range)
            if (trait != null) part = updateBonusField(part, anyTraitPattern, "extraTraitPoints", "traits: {", trait)
            if (skill != null) part = updateBonusField(part, anySkillPattern, "bonusPoints", "skillSet: {", skill)
            changed = changed.replaceRange(range.first, range.last + 1, part)
        }
        return changed
    }
    /**
     * Duplicates are located by OCCURRENCE ORDER within the same item ID, not
     * just by ID or stack quantity. Other interleaved items do not affect the order.
     * If another save has fewer copies, return null: deleting does not remove
     * anything in that save, while adding may create another copy if space allows.
     */
    private fun locateItem(ids: List<Int>, units: List<Int>, edit: SlotEdit): Int? {
        val sameId = ids.indices.filter { ids[it] == edit.expectedId }
        if (sameId.isEmpty()) return null
        if (edit.occurrence != null) return sameId.getOrNull(edit.occurrence)
        // Backward compatibility for edits made without an occurrence index.
        if (edit.index in ids.indices && ids[edit.index] == edit.expectedId) return edit.index
        val sameCount = sameId.filter { units[it] == edit.expectedCount }
        if (sameCount.size == 1) return sameCount.single()
        if (sameId.size == 1) return sameId.single()
        require(false) {
            "Duplicate item ID ${edit.expectedId}: select the copy again in the grid to identify its position."
        }
        return null
    }

    /**
     * Non-stackable item: each copy requires its own slot.
     * Allocation is transactional: insufficient capacity cancels the entire edit plan.
     */
    private fun addSingleCopies(
        ids: MutableList<Int>, units: MutableList<Int>, id: Int, count: Int, path: String,
        firstSlot: Int? = null
    ) {
        require(!ItemRules.isStackable(id)) { "Expected a non-stackable item" }
        val empty = ids.indices.filter { ids[it] == 0 }.toMutableList()
        if (firstSlot != null) {
            require(firstSlot in empty) {
                "Slot ${firstSlot + 1} is not empty in $path"
            }
            empty.remove(firstSlot)
            empty.add(0, firstSlot)
        }
        require(empty.size >= count) {
            "Non-stackable item requires $count slots, but only ${empty.size} are free in $path. " +
                "Do not put multiple copies of tomes or equipment into the same slot."
        }
        for (i in empty.take(count)) {
            ids[i] = id
            units[i] = 1
        }
    }

    private fun rewrite(text: String, plan: EditPlan, path: String): String {
        val playerRange = playerBounds(text)
        var player = text.substring(playerRange)
        if (plan.gold != null) player = replaceNumber(player, goldPattern, plan.gold)
        val traitValue = when {
            plan.traitPoints != null -> plan.traitPoints
            plan.resetTraits -> {
                val progress = playerTraitBalance(player)
                    ?: error("Level or spent-point data is missing; resetting attributes is unsafe.")
                val requiredExtra = progress.spent - progress.byLevel
                require(requiredExtra in 0..999) {
                    "This character still has unspent level-up points. They cannot be reset without changing allocated attributes."
                }
                requiredExtra
            }
            else -> increment(
                traitPattern.find(player)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                plan.traitDelta, false, "Attributes"
            )
        }
        val skillValue = if (plan.skillPoints != null) plan.skillPoints else if (plan.resetSkills) {
            val balance = skillBalance(player, false)
                ?: error("Could not calculate the costs of learned skills. Reset canceled.")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) {
                "Cannot reset unspent points without removing learned skills"
            }
            newBonus
        } else increment(
            skillPattern.find(player)?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            plan.skillDelta, false, "Skills"
        )
        if (traitValue != null)
            player = updateBonusField(player, traitPattern, "extraTraitPoints", "traits: {", traitValue)
        if (skillValue != null)
            player = updateBonusField(player, skillPattern, "bonusPoints", "skillSet: {", skillValue)
        var modified = text.replaceRange(playerRange.first, playerRange.last + 1, player)
        modified = rewriteCompanion(modified, plan)
        if (plan.items.isNotEmpty() || plan.slotEdits.isNotEmpty() || plan.slotInserts.isNotEmpty() || plan.slotReplacements.isNotEmpty()) {
            val pack = backpack(modified)
            val ids = numbers(pack.groupValues[2])
            val units = numbers(pack.groupValues[4])
            require(ids.size == units.size) { "Incompatible inventory data" }
            for (edit in plan.slotEdits) {
                val stackable = ItemRules.isStackable(edit.expectedId)
                // Duplicating a non-stackable item does not require locating the original:
                // just add N copies to N empty slots.
                if (edit.addQuantity && !stackable) {
                    addSingleCopies(ids, units, edit.expectedId, edit.quantity, path)
                    continue
                }
                val index = locateItem(ids, units, edit)
                if (index == null) {
                    if (edit.quantity == 0) continue
                    if (!stackable) {
                        ItemRules.ensureSlotQuantity(edit.expectedId, edit.quantity)
                        addSingleCopies(ids, units, edit.expectedId, 1, path)
                    } else {
                        val free = ids.indexOf(0)
                        require(free >= 0) {
                            "Inventory full in $path; no free slot for item ID ${edit.expectedId}"
                        }
                        ids[free] = edit.expectedId
                        units[free] = edit.quantity
                    }
                } else if (edit.addQuantity) {
                    require(units[index].toLong() + edit.quantity <= 999) {
                        "The item stack would exceed 999 units in $path"
                    }
                    units[index] += edit.quantity
                } else if (edit.quantity == 0) {
                    ids[index] = 0
                    units[index] = 0
                } else {
                    ItemRules.ensureSlotQuantity(edit.expectedId, edit.quantity)
                    units[index] = edit.quantity
                }
            }
            for (insert in plan.slotInserts) {
                require(insert.index in ids.indices && ids[insert.index] == 0) {
                    "Slot ${insert.index + 1} is occupied in $path; choose a confirmed replacement."
                }
                if (!ItemRules.isStackable(insert.itemId)) {
                    addSingleCopies(ids, units, insert.itemId, insert.count, path, insert.index)
                } else {
                    ids[insert.index] = insert.itemId
                    units[insert.index] = insert.count
                }
            }
            for ((id, count) in plan.items) {
                if (!ItemRules.isStackable(id)) {
                    addSingleCopies(ids, units, id, count, path)
                } else {
                    val slot = ids.indexOf(id).takeIf { it >= 0 } ?: ids.indexOf(0)
                    require(slot >= 0) {
                        "Inventory full in $path. Choose a slot and confirm the replacement."
                    }
                    ids[slot] = id
                    units[slot] = count
                }
            }
            for (replacement in plan.slotReplacements) {
                ItemRules.ensureSlotQuantity(replacement.itemId, replacement.count)
                val expected = replacement.expectedByPath[path]
                    ?: error("Save was not included in the confirmation: $path")
                require(replacement.index in ids.indices) { "Invalid slot in $path" }
                val i = replacement.index
                require(ids[i] == expected.id && units[i] == expected.count) {
                    "Slot ${i + 1} changed in $path since confirmation. Nothing was replaced."
                }
                ids[i] = replacement.itemId
                units[i] = replacement.count
            }
            val parts = pack.groupValues
            val patched = parts[1] + ids.joinToString(", ", " ", " ") +
                parts[3] + units.joinToString(", ", " ", " ") + parts[5]
            modified = modified.replaceRange(pack.range.first, pack.range.last + 1, patched)
        }
        if (plan.vaultChange != null) {
            modified = VaultStorage.modify(modified, plan.vaultChange, path)
        }
        return modified
    }
    fun apply(data: ByteArray, plan: EditPlan): ByteArray {
        plan.validate()
        val before = archive(data)
        val parsedBefore = inspect(data)
        require(plan.targetPaths.all { path -> parsedBefore.any { it.path == path } }) { "Selected save is missing" }
        val selectedSlots = parsedBefore.filter { it.path in plan.targetPaths }
            .map { it.characterSlot }.toSet()
        require(selectedSlots.size == 1) {
            "Select one character before editing any saves"
        }
        val modifiedNames = mutableSetOf<String>()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for (source in before) {
                val entry = ZipEntry(source.entry.name).apply {
                    time = source.entry.time
                    comment = source.entry.comment
                }
                zip.putNextEntry(entry)
                val content = if (source.entry.name in plan.targetPaths) {
                    require(savePath.matches(source.entry.name)) { "Invalid save path" }
                    modifiedNames += source.entry.name
                    Base64.getEncoder().encode(rewrite(plain(source.contents), plan, source.entry.name).toByteArray(StandardCharsets.UTF_8))
                } else source.contents
                zip.write(content)
                zip.closeEntry()
            }
        }
        require(modifiedNames == plan.targetPaths) { "Faltou modificar um save" }
        val result = output.toByteArray()
        if (plan.vaultChange != null) {
            val oldByName = before.associateBy { it.entry.name }
            val updatedByName = archive(result).associateBy { it.entry.name }
            for (path in plan.targetPaths) {
                VaultStorage.verify(
                    plain(oldByName.getValue(path).contents),
                    plain(updatedByName.getValue(path).contents),
                    plan.vaultChange, path
                )
            }
        }
        val after = archive(result)
        require(before.map { it.entry.name } == after.map { it.entry.name }) { "Arquivos ZIP diferentes" }
        for ((a,b) in before.zip(after)) if (a.entry.name !in modifiedNames) {
            require(a.contents.contentEquals(b.contents)) { "An unselected file was unexpectedly modified" }
        }
        val parsedAfter = inspect(result)
        for (prev in parsedBefore) {
            val now = parsedAfter.single { it.path == prev.path }
            if (prev.path !in modifiedNames) require(now == prev) { "An unselected save was modified" }
            else {
                if (plan.gold != null) require(now.gold == plan.gold)
                if (plan.traitPoints != null) require(now.traitPoints == plan.traitPoints)
                if (plan.skillPoints != null) require(now.skillPoints == plan.skillPoints)
                if (plan.resetTraits) require(now.freeTraitPoints == 0) {
                    "Could not reset unspent points in ${prev.path}"
                }
                if (plan.resetSkills) require(now.freeSkillPoints == 0) {
                    "Unspent skill points were not reset in ${prev.path}"
                }
                if (plan.traitDelta != null) {
                    require(now.traitPoints == prev.traitPoints + plan.traitDelta) {
                        "Could not add bonus points in ${prev.path}"
                    }
                    if (prev.freeTraitPoints != null) {
                        require(now.freeTraitPoints == prev.freeTraitPoints + plan.traitDelta) {
                            "Available points did not increase as expected in ${prev.path}"
                        }
                        require(now.spentTraitPoints == prev.spentTraitPoints) {
                            "Spent points were unexpectedly modified"
                        }
                    }
                }
                if (plan.skillDelta != null) {
                    require(now.skillPoints == prev.skillPoints + plan.skillDelta)
                    if (prev.freeSkillPoints != null) require(now.freeSkillPoints == prev.freeSkillPoints + plan.skillDelta) {
                        "Available skill points did not increase in ${prev.path}"
                    }
                }
                if (plan.resetCompanionTraits && prev.companion != null)
                    require(now.companion?.freeTraitPoints == 0)
                if (plan.resetCompanionSkills && prev.companion != null)
                    require(now.companion?.freeSkillPoints == 0)
                if (plan.companionTraitDelta != null && prev.companion != null) {
                    require(now.companion?.traitPoints == prev.companion.traitPoints + plan.companionTraitDelta)
                    if (prev.companion.freeTraitPoints != null)
                        require(now.companion?.freeTraitPoints == prev.companion.freeTraitPoints + plan.companionTraitDelta)
                }
                if (plan.companionSkillDelta != null && prev.companion != null) {
                    require(now.companion?.skillPoints == prev.companion.skillPoints + plan.companionSkillDelta)
                    if (prev.companion.freeSkillPoints != null)
                        require(now.companion?.freeSkillPoints == prev.companion.freeSkillPoints + plan.companionSkillDelta)
                }
                for (edit in plan.slotEdits) {
                    val originalIds = prev.inventorySlots.map { it.id }
                    val originalCounts = prev.inventorySlots.map { it.count }
                    // +N copies of non-stackable items do not depend on selecting
                    // an original stack, avoiding ambiguity between duplicate IDs.
                    val oldIndex = if (edit.addQuantity && !ItemRules.isStackable(edit.expectedId))
                        null else locateItem(originalIds, originalCounts, edit)
                    val oldCount = oldIndex?.let { prev.inventorySlots[it].count } ?: 0
                    val oldTotal = prev.inventory[edit.expectedId] ?: 0
                    val newTotal = now.inventory[edit.expectedId] ?: 0
                    val expectedTotal = when {
                        oldIndex == null && edit.quantity == 0 -> oldTotal
                        oldIndex == null -> oldTotal + edit.quantity
                        edit.addQuantity -> oldTotal + edit.quantity
                        else -> oldTotal - oldCount + edit.quantity
                    }
                    require(newTotal == expectedTotal) {
                        "Quantity of item ID ${edit.expectedId} did not match in ${prev.path}"
                    }
                    // Without an explicit replacement, no other item ID may disappear.
                    for ((id, amount) in prev.inventory) if (id != edit.expectedId) {
                        require(now.inventory[id] == amount) {
                            "Another item was unexpectedly modified in ${prev.path}"
                        }
                    }
                }
                for (insert in plan.slotInserts) {
                    val original = prev.inventorySlots.getOrNull(insert.index)
                    val current = now.inventorySlots.getOrNull(insert.index)
                    val perSlot = ItemRules.maxPerSlot(insert.itemId)
                    require(original?.id == 0 && current?.id == insert.itemId &&
                        current.count == if (perSlot == 1) 1 else insert.count) {
                        "The new item was not written correctly to slot ${insert.index + 1}"
                    }
                    val previousTotal = prev.inventory[insert.itemId] ?: 0
                    val newTotal = now.inventory[insert.itemId] ?: 0
                    require(newTotal == previousTotal + insert.count) {
                        "Inserted item quantity does not match the recorded slots"
                    }
                }
                for (replacement in plan.slotReplacements) {
                    val expected = replacement.expectedByPath[prev.path]
                        ?: error("No confirmation for ${prev.path}")
                    require(prev.inventorySlots.getOrNull(replacement.index) == expected) {
                        "Inventories changed since confirmation"
                    }
                    val targetSlot = now.inventorySlots.getOrNull(replacement.index)
                    require(targetSlot?.id == replacement.itemId && targetSlot.count == replacement.count) {
                        "Replacement was not saved in ${prev.path}"
                    }
                    prev.inventorySlots.indices.filter { it != replacement.index }.forEach { i ->
                        require(prev.inventorySlots[i] == now.inventorySlots[i]) {
                            "Another slot was unexpectedly modified in ${prev.path}"
                        }
                    }
                }
                for (item in plan.items) {
                    val count = now.inventory[item.id] ?: 0
                    val expected = if (ItemRules.isStackable(item.id)) item.count
                        else (prev.inventory[item.id] ?: 0) + item.count
                    require(count == expected) { "Item was not correctly applied to save ${prev.path}" }
                }
            }
        }
        return result
    }
}
