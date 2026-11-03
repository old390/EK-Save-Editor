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
        /** Pontos disponíveis calculados: total por nível + extras − gastos. */
        val freeTraitPoints: Int? = null,
        val characterLevel: Int? = null,
        val spentTraitPoints: Int? = null,
        val freeSkillPoints: Int? = null,
        val spentSkillPoints: Int? = null
    )
    /** Item na posição real do inventário, preservando inclusive espaços vazios. */
    data class InventorySlot(val id: Int, val count: Int)
    data class ItemAdd(val id: Int, val count: Int = 1)
    /** Um stack por ação. Confere o ID em todos os saves antes de substituir. */
    data class SlotEdit(val index: Int, val expectedId: Int, val expectedCount: Int, val quantity: Int)
    data class EditPlan(
        val targetPaths: Set<String>,
        val gold: Long? = null,
        val traitPoints: Int? = null,
        val skillPoints: Int? = null,
        val items: List<ItemAdd> = emptyList(),
        val slotEdits: List<SlotEdit> = emptyList(),
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
                slotEdits.isNotEmpty() || traitDelta != null || skillDelta != null || resetTraits || resetSkills || companionTraitDelta != null ||
                companionSkillDelta != null || resetCompanionTraits || resetCompanionSkills) { "Nenhuma alteração selecionada" }
            require(gold == null || gold in 0..999_999_999) { "Ouro fora do intervalo" }
            require(traitPoints == null || traitPoints in 0..999) { "Atributos fora do intervalo" }
            require(skillPoints == null || skillPoints in 0..999) { "Habilidades fora do intervalo" }
            require(listOf(traitPoints != null, traitDelta != null, resetTraits).count { it } <= 1) { "Escolha um tipo de edição de atributos" }
            require(listOf(skillPoints != null, skillDelta != null, resetSkills).count { it } <= 1) { "Escolha um tipo de edição de habilidades" }
            require(!(resetCompanionTraits && companionTraitDelta != null)) { "Escolha zerar ou adicionar atributos do companheiro" }
            require(!(resetCompanionSkills && companionSkillDelta != null)) { "Escolha zerar ou adicionar habilidades do companheiro" }
            for (n in listOf(traitDelta, skillDelta, companionTraitDelta, companionSkillDelta)) {
                require(n == null || n in 1..999) { "Acrescente entre 1 e 999 pontos" }
            }
            for (item in items) {
                require(item.id in 1..999_999 && item.count in 1..999) { "ID ou quantidade de item inválidos" }
            }
            for (edit in slotEdits) {
                require(edit.index >= 0 && edit.expectedId in 1..999_999 &&
                    edit.expectedCount in 1..999 && edit.quantity in 0..999) { "Edição de item inválida" }
            }
        }
    }
    /** Validação independente da interface, permitindo testes de valores vazios e inválidos. */
    fun parseNumberInput(raw: String, label: String, max: Long, min: Long = 0): Long {
        val clean = raw.trim()
        require(clean.isNotEmpty()) { "Digite um valor em '$label' antes de aplicar." }
        val number = clean.toLongOrNull() ?: throw IllegalArgumentException("Informe um número inteiro válido em '$label'.")
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
            "Arquivo inválido: selecione EK.bak"
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
        require(it.contains("\nversion:") && it.contains("\ngamedata: {")) { "Save incompatível" }
    }
    private fun braceClose(text: String, open: Int): Int {
        require(open >= 0 && text[open] == '{') { "Bloco incompatível" }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        error("Bloco incompleto")
    }
    private fun block(text: String, marker: String, start: Int = 0): IntRange? {
        val index = text.indexOf(marker, start)
        if (index < 0) return null
        val open = text.indexOf('{', index + marker.length - 1)
        require(open >= 0) { "Bloco incompleto: $marker" }
        return open..braceClose(text, open)
    }
    private fun playerBounds(text: String): IntRange {
        val gamedata = text.indexOf("\ngamedata: {")
        require(gamedata >= 0) { "Dados do personagem ausentes" }
        return block(text, "\n\tplayer: {", gamedata) ?: error("Personagem não encontrado")
    }
    private fun one(text: String, pattern: Regex, key: String): Int = pattern.find(text)?.groupValues?.get(2)?.toInt()
        ?: error("Campo $key não encontrado")
    private fun gold(text: String): Long = goldPattern.find(text)?.groupValues?.get(2)?.toLong()
        ?: error("Ouro não encontrado")
    private data class TraitBalance(
        val level: Int, val byLevel: Int, val extra: Int, val spent: Int, val free: Int
    )

    /** 4 no primeiro nível, 2 por nível até o 15, e 3 do 16 em diante. */
    private fun traitPointsFromLevel(level: Int): Int {
        require(level in 1..999) { "Nível de personagem inválido" }
        return 4 + (level - 1).coerceAtMost(14) * 2 + (level - 15).coerceAtLeast(0) * 3
    }

    private fun playerTraitBalance(player: String): TraitBalance? {
        val level = playerLevelPattern.find(player)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (level !in 1..999) return null
        val raw = spentTraitPattern.find(player)?.groupValues?.get(1) ?: return null
        val points = raw.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (points.size != 6 || points.any { it < 0 }) return null
        val spent = points.sum()
        val extra = one(player, traitPattern, "atributos extras")
        val byLevel = traitPointsFromLevel(level)
        return TraitBalance(level, byLevel, extra, spent, byLevel + extra - spent)
    }

    /**
     * Custos por nível conforme Skill Table da wiki oficial.
     * Habilidades desconhecidas => saldo real desconhecido, nunca presumido.
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
        val byLevel = 4 + level - 1
        return TraitBalance(level, byLevel, bonus, spent, byLevel + bonus - spent)
    }

    /** Insere o campo extra/bonus ausente no bloco correto sem remover os outros. */
    private fun updateBonusField(character: String, regex: Regex, field: String, marker: String, value: Int): String {
        if (regex.containsMatchIn(character)) return replaceNumber(character, regex, value)
        val range = block(character, marker) ?: error("Bloco $marker ausente no personagem")
        val lineBegin = character.lastIndexOf('\n', range.first).let { if (it < 0) 0 else it + 1 }
        val indent = character.substring(lineBegin, range.first).takeWhile { it == '\t' }
        val insertion = "\t$field: $value\n$indent"
        return character.replaceRange(range.last, range.last, insertion)
    }

    private fun numbers(raw: String): MutableList<Int> = raw.split(',').map { it.trim().toInt() }.toMutableList()
    private fun backpack(text: String): MatchResult = backpackPattern.find(text) ?: error("Inventário não encontrado")

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
                    require(close < until) { "Lista de companheiros incompleta" }
                    result += pos..close
                    pos = close + 1
                }
                else -> pos++
            }
        }
        error("Lista de companheiros incompleta")
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
        require(ids.size == units.size) { "Inventário incompatível" }
        val balance = playerTraitBalance(player)
        val skillsBalance = skillBalance(player, false)
        SaveInfo(
            path = item.entry.name,
            hero = Regex("""(?m)^\t\tname: (.*)$""").find(player)?.groupValues?.get(1)?.trim() ?: "Personagem",
            characterClass = Regex("""(?m)^\t{4}characterClass: (\w+)\s*$""").find(player)?.groupValues?.get(1) ?: "?",
            gold = gold(player),
            traitPoints = one(player, traitPattern, "atributos"),
            skillPoints = one(player, skillPattern, "habilidades"),
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

    private fun replaceNumber(text: String, pattern: Regex, number: Number): String {
        val found = pattern.find(text) ?: error("Campo de pontos não encontrado")
        return text.replaceRange(found.groups[2]!!.range, number.toString())
    }
    private fun increment(current: Int, points: Int?, reset: Boolean, name: String): Int? {
        if (reset) return 0
        if (points == null) return null
        val result = current.toLong() + points
        require(result in 0..999) { "$name excede 999 pontos livres" }
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
                ?: error("Gastos do companheiro não identificados: zeramento cancelado")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) { "Não é seguro zerar atributos sem mexer nos pontos gastos" }
            newBonus
        } else increment(traitBonus, plan.companionTraitDelta, false, "Atributos do companheiro")
        val skill = if (plan.resetCompanionSkills) {
            val balance = skillBalance(currentParty, true)
                ?: error("Habilidades aprendidas não identificadas: zeramento cancelado")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) { "Não é seguro zerar habilidades sem remover níveis aprendidos" }
            newBonus
        } else increment(skillBonus, plan.companionSkillDelta, false, "Habilidades do companheiro")
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
    private fun rewrite(text: String, plan: EditPlan): String {
        val playerRange = playerBounds(text)
        var player = text.substring(playerRange)
        if (plan.gold != null) player = replaceNumber(player, goldPattern, plan.gold)
        val traitValue = when {
            plan.traitPoints != null -> plan.traitPoints
            plan.resetTraits -> {
                val progress = playerTraitBalance(player)
                    ?: error("Nível e pontos gastos indisponíveis: não é seguro zerar atributos.")
                val requiredExtra = progress.spent - progress.byLevel
                require(requiredExtra in 0..999) {
                    "Este personagem tem pontos do nível ainda livres. Não é possível zerá-los sem alterar pontos já distribuídos."
                }
                requiredExtra
            }
            else -> increment(one(player, traitPattern, "atributos extras"), plan.traitDelta, false, "Atributos")
        }
        val skillValue = if (plan.skillPoints != null) plan.skillPoints else if (plan.resetSkills) {
            val balance = skillBalance(player, false)
                ?: error("Não foi possível calcular habilidades aprendidas: zeramento cancelado")
            val newBonus = balance.spent - balance.byLevel
            require(newBonus in 0..999) {
                "Não é possível zerar pontos livres sem remover habilidades já aprendidas"
            }
            newBonus
        } else increment(one(player, skillPattern, "habilidades"), plan.skillDelta, false, "Habilidades")
        if (traitValue != null) player = replaceNumber(player, traitPattern, traitValue)
        if (skillValue != null) player = replaceNumber(player, skillPattern, skillValue)
        var modified = text.replaceRange(playerRange.first, playerRange.last + 1, player)
        modified = rewriteCompanion(modified, plan)
        if (plan.items.isNotEmpty() || plan.slotEdits.isNotEmpty()) {
            val pack = backpack(modified)
            val ids = numbers(pack.groupValues[2])
            val units = numbers(pack.groupValues[4])
            require(ids.size == units.size) { "Inventário incompatível" }
            for (edit in plan.slotEdits) {
                val index = when {
                    edit.index in ids.indices && ids[edit.index] == edit.expectedId -> edit.index
                    else -> {
                        val matches = ids.indices.filter { ids[it] == edit.expectedId }
                        require(matches.isNotEmpty()) {
                            "Item ID ${edit.expectedId} ausente em um dos saves. Nenhum save foi alterado."
                        }
                        val sameCount = matches.filter { units[it] == edit.expectedCount }
                        val candidates = if (sameCount.isNotEmpty()) sameCount else matches
                        require(candidates.size == 1) {
                            "Mais de um stack igual no mesmo save; operação ambígua e cancelada."
                        }
                        candidates.single()
                    }
                }
                if (edit.quantity == 0) {
                    ids[index] = 0
                    units[index] = 0
                } else {
                    units[index] = edit.quantity
                }
            }
            for ((id, count) in plan.items) {
                val slot = ids.indexOf(id).takeIf { it >= 0 } ?: ids.indexOf(0)
                require(slot >= 0) { "Inventário cheio! Não foi removido nenhum item. Libere espaço no jogo antes de adicionar." }
                ids[slot] = id
                units[slot] = count
            }
            val parts = pack.groupValues
            val patched = parts[1] + ids.joinToString(", ", " ", " ") +
                parts[3] + units.joinToString(", ", " ", " ") + parts[5]
            modified = modified.replaceRange(pack.range.first, pack.range.last + 1, patched)
        }
        return modified
    }
    fun apply(data: ByteArray, plan: EditPlan): ByteArray {
        plan.validate()
        val before = archive(data)
        val parsedBefore = inspect(data)
        require(plan.targetPaths.all { path -> parsedBefore.any { it.path == path } }) { "Save escolhido ausente" }
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
                    require(savePath.matches(source.entry.name)) { "Caminho inválido" }
                    modifiedNames += source.entry.name
                    Base64.getEncoder().encode(rewrite(plain(source.contents), plan).toByteArray(StandardCharsets.UTF_8))
                } else source.contents
                zip.write(content)
                zip.closeEntry()
            }
        }
        require(modifiedNames == plan.targetPaths) { "Faltou modificar um save" }
        val result = output.toByteArray()
        val after = archive(result)
        require(before.map { it.entry.name } == after.map { it.entry.name }) { "Arquivos ZIP diferentes" }
        for ((a,b) in before.zip(after)) if (a.entry.name !in modifiedNames) {
            require(a.contents.contentEquals(b.contents)) { "Arquivo não editado foi alterado" }
        }
        val parsedAfter = inspect(result)
        for (prev in parsedBefore) {
            val now = parsedAfter.single { it.path == prev.path }
            if (prev.path !in modifiedNames) require(now == prev) { "Save não selecionado modificado" }
            else {
                if (plan.gold != null) require(now.gold == plan.gold)
                if (plan.traitPoints != null) require(now.traitPoints == plan.traitPoints)
                if (plan.skillPoints != null) require(now.skillPoints == plan.skillPoints)
                if (plan.resetTraits) require(now.freeTraitPoints == 0) {
                    "Não foi possível zerar os pontos livres em ${prev.path}"
                }
                if (plan.resetSkills) require(now.freeSkillPoints == 0) {
                    "Pontos livres de habilidades não foram zerados em ${prev.path}"
                }
                if (plan.traitDelta != null) {
                    require(now.traitPoints == prev.traitPoints + plan.traitDelta) {
                        "Falha ao somar extras em ${prev.path}"
                    }
                    if (prev.freeTraitPoints != null) {
                        require(now.freeTraitPoints == prev.freeTraitPoints + plan.traitDelta) {
                            "O saldo real não aumentou como esperado em ${prev.path}"
                        }
                        require(now.spentTraitPoints == prev.spentTraitPoints) {
                            "Os pontos gastos foram alterados inesperadamente"
                        }
                    }
                }
                if (plan.skillDelta != null) {
                    require(now.skillPoints == prev.skillPoints + plan.skillDelta)
                    if (prev.freeSkillPoints != null) require(now.freeSkillPoints == prev.freeSkillPoints + plan.skillDelta) {
                        "Habilidades livres não aumentaram em ${prev.path}"
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
                    val prevStacks = prev.inventorySlots.filter { it.id == edit.expectedId }
                    val nextStacks = now.inventorySlots.filter { it.id == edit.expectedId }
                    require(prevStacks.size - (if (edit.quantity == 0) 1 else 0) == nextStacks.size) {
                        "Quantidade de stacks não confere após edição do inventário"
                    }
                    val oldTotal = prev.inventory[edit.expectedId] ?: 0
                    val newTotal = now.inventory[edit.expectedId] ?: 0
                    val selectedStack = prev.inventorySlots.getOrNull(edit.index)
                    val effectiveCount = if (selectedStack?.id == edit.expectedId) selectedStack.count else {
                        val sameCount = prevStacks.filter { it.count == edit.expectedCount }
                        if (sameCount.size == 1) edit.expectedCount else {
                            require(prevStacks.size == 1) { "Stack do item ambíguo" }
                            prevStacks.single().count
                        }
                    }
                    require(newTotal == oldTotal - effectiveCount + edit.quantity) {
                        "Falha ao validar quantidade do item ID ${edit.expectedId}"
                    }
                }
                for (item in plan.items) require(now.inventory[item.id] == item.count) { "Item não aplicado" }
            }
        }
        return result
    }
}
