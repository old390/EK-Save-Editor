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
    private val activeCompanionPattern = Regex("""(?m)^\t\tactiveCompanion:\s*([^\r\n]*)""")

    data class CompanionInfo(val name: String, val traitPoints: Int, val skillPoints: Int)
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
        val spentTraitPoints: Int? = null
    )
    /** Item na posição real do inventário, preservando inclusive espaços vazios. */
    data class InventorySlot(val id: Int, val count: Int)
    data class ItemAdd(val id: Int, val count: Int = 1)
    data class EditPlan(
        val targetPaths: Set<String>,
        val gold: Long? = null,
        val traitPoints: Int? = null,
        val skillPoints: Int? = null,
        val items: List<ItemAdd> = emptyList(),
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
                traitDelta != null || skillDelta != null || resetTraits || resetSkills || companionTraitDelta != null ||
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
        return CompanionInfo(
            Regex("""(?m)^\t+name:\s*(.+)$""").find(obj)?.groupValues?.get(1)?.trim() ?: "Companheiro",
            one(obj, anyTraitPattern, "atributos do companheiro"),
            one(obj, anySkillPattern, "habilidades do companheiro")
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
            spentTraitPoints = balance?.spent
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
        val trait = increment(one(currentParty, anyTraitPattern, "atributos do companheiro"),
            plan.companionTraitDelta, plan.resetCompanionTraits, "Atributos do companheiro")
        val skill = increment(one(currentParty, anySkillPattern, "habilidades do companheiro"),
            plan.companionSkillDelta, plan.resetCompanionSkills, "Habilidades do companheiro")
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
            if (trait != null) part = replaceNumber(part, anyTraitPattern, trait)
            if (skill != null) part = replaceNumber(part, anySkillPattern, skill)
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
        val skillValue = if (plan.skillPoints != null) plan.skillPoints else
            increment(one(player, skillPattern, "habilidades"), plan.skillDelta, plan.resetSkills, "Habilidades")
        if (traitValue != null) player = replaceNumber(player, traitPattern, traitValue)
        if (skillValue != null) player = replaceNumber(player, skillPattern, skillValue)
        var modified = text.replaceRange(playerRange.first, playerRange.last + 1, player)
        modified = rewriteCompanion(modified, plan)
        if (plan.items.isNotEmpty()) {
            val pack = backpack(modified)
            val ids = numbers(pack.groupValues[2])
            val units = numbers(pack.groupValues[4])
            require(ids.size == units.size) { "Inventário incompatível" }
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
                if (plan.resetSkills) require(now.skillPoints == 0)
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
                if (plan.skillDelta != null) require(now.skillPoints == prev.skillPoints + plan.skillDelta)
                if (plan.resetCompanionTraits && prev.companion != null) require(now.companion?.traitPoints == 0)
                if (plan.resetCompanionSkills && prev.companion != null) require(now.companion?.skillPoints == 0)
                if (plan.companionTraitDelta != null && prev.companion != null)
                    require(now.companion?.traitPoints == prev.companion.traitPoints + plan.companionTraitDelta)
                if (plan.companionSkillDelta != null && prev.companion != null)
                    require(now.companion?.skillPoints == prev.companion.skillPoints + plan.companionSkillDelta)
                for (item in plan.items) require(now.inventory[item.id] == item.count) { "Item não aplicado" }
            }
        }
        return result
    }
}
