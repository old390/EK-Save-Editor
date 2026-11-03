package com.ekeditor.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** EK.bak = ZIP; individual *.sav entries contain Base64-encoded UTF-8 text.
 * This editor changes ONLY selected saves and keeps all other ZIP entries untouched in content.
 * Tested against the user's four-save backup (save format version 1.3.1219).
 */
object EkBackup {
    private val savePath = Regex("""saves/\d+/(?:game2?|auto2?)\.sav""")
    private val goldPattern = Regex("""(?m)^(\t\tgold:\s*)(\d+)(\s*)$""")
    private val traitPattern = Regex("""(?m)^(\t{4}extraTraitPoints:\s*)(\d+)(\s*)$""")
    private val skillPattern = Regex("""(?m)^(\t{4}bonusPoints:\s*)(\d+)(\s*)$""")
    private val backpackPattern = Regex("""(?m)^(\tbackpack: \{\s*\n\t\titemList: \[)([^\]]*)(\]\s*\n\t\tstackUnits: \[)([^\]]*)(\])""")

    data class SaveInfo(
        val path: String,
        val hero: String,
        val characterClass: String,
        val gold: Long,
        val traitPoints: Int,
        val skillPoints: Int,
        val freeSlots: Int
    )

    data class ItemAdd(val id: Int, val count: Int = 1)

    data class EditPlan(
        val targetPaths: Set<String>,
        val gold: Long? = null,
        val traitPoints: Int? = null,
        val skillPoints: Int? = null,
        val items: List<ItemAdd> = emptyList()
    ) {
        fun validate() {
            require(targetPaths.isNotEmpty()) { "Selecione ao menos um save" }
            require(gold != null || traitPoints != null || skillPoints != null || items.isNotEmpty()) {
                "Selecione ao menos uma modificação"
            }
            require(gold == null || gold in 0..999_999_999) { "Ouro fora do intervalo 0–999.999.999" }
            require(traitPoints == null || traitPoints in 0..999) { "Pontos de atributos inválidos" }
            require(skillPoints == null || skillPoints in 0..999) { "Pontos de habilidades inválidos" }
            for (item in items) {
                require(item.id in 1..999_999) { "ID de item inválido" }
                require(item.count in 1..999) { "Quantidade permitida: 1 a 999" }
            }
        }
    }

    private data class ArchiveItem(val entry: ZipEntry, val contents: ByteArray)

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= max) { "Entrada ZIP muito grande" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun archive(data: ByteArray): List<ArchiveItem> {
        require(data.size in 1..20_000_000) { "Tamanho EK.bak inesperado" }
        require(data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            "O arquivo não parece ser ZIP/EK.bak"
        }
        val files = mutableListOf<ArchiveItem>()
        val seen = hashSetOf<String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.size < 2_000) { "Backup com entradas demais" }
                require(seen.add(entry.name)) { "Entrada ZIP duplicada: ${entry.name}" }
                val contents = readLimited(zip, 4_000_000)
                total += contents.size
                require(total <= 100_000_000) { "Backup descompactado muito grande" }
                files += ArchiveItem(entry, contents)
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "ZIP vazio ou inválido" }
        return files
    }

    private fun plain(contents: ByteArray): String {
        val result = String(Base64.getMimeDecoder().decode(contents), StandardCharsets.UTF_8)
        require(result.contains("\nversion:") && result.contains("\ngamedata: {")) {
            "Save não reconhecido: esperado Base64 com dados do Exiled Kingdoms"
        }
        return result
    }

    // Finds the exact gamedata.player block, not a companion/NPC named 'player'.
    private fun playerBounds(text: String): IntRange {
        val gameData = text.indexOf("\ngamedata: {")
        require(gameData >= 0) { "gamedata não encontrado" }
        val player = text.indexOf("\n\tplayer: {", gameData)
        require(player >= 0) { "Personagem principal não encontrado" }
        val open = text.indexOf('{', player)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..i
                }
            }
        }
        error("Bloco de personagem incompleto")
    }

    private fun one(text: String, regex: Regex, field: String): String =
        regex.find(text)?.groupValues?.get(2) ?: error("Campo '$field' não encontrado no personagem")

    private fun backpack(text: String): MatchResult =
        backpackPattern.find(text) ?: error("Inventário backpack não encontrado")

    private fun numbers(text: String): MutableList<Int> =
        text.split(',').map { it.trim().toInt() }.toMutableList()

    fun inspect(data: ByteArray): List<SaveInfo> = archive(data).filter { savePath.matches(it.entry.name) }.map { item ->
        val text = plain(item.contents)
        val player = text.substring(playerBounds(text))
        val name = Regex("""(?m)^\t\tname: (.*)$""").find(player)?.groupValues?.get(1)?.trim() ?: "Personagem"
        val classId = Regex("""(?m)^\t{4}characterClass: (\w+)\s*$""").find(player)?.groupValues?.get(1) ?: "?"
        val pack = backpack(text)
        val itemList = numbers(pack.groupValues[2])
        val units = numbers(pack.groupValues[4])
        require(itemList.size == units.size) { "Inventário com listas incompatíveis" }
        SaveInfo(
            path = item.entry.name,
            hero = name,
            characterClass = classId,
            gold = one(player, goldPattern, "gold").toLong(),
            traitPoints = one(player, traitPattern, "extraTraitPoints").toInt(),
            skillPoints = one(player, skillPattern, "bonusPoints").toInt(),
            freeSlots = itemList.count { it == 0 }
        )
    }.also { require(it.isNotEmpty()) { "Nenhum save game/auto encontrado no EK.bak" } }

    private fun rewrite(text: String, plan: EditPlan): String {
        val playerRange = playerBounds(text)
        val oldPlayer = text.substring(playerRange)
        var newPlayer = oldPlayer
        if (plan.gold != null) newPlayer = replaceNumber(newPlayer, goldPattern, plan.gold)
        if (plan.traitPoints != null) newPlayer = replaceNumber(newPlayer, traitPattern, plan.traitPoints)
        if (plan.skillPoints != null) newPlayer = replaceNumber(newPlayer, skillPattern, plan.skillPoints)
        var modified = text.replaceRange(playerRange.first, playerRange.last + 1, newPlayer)
        if (plan.items.isNotEmpty()) {
            val match = backpack(modified)
            val ids = numbers(match.groupValues[2])
            val quantities = numbers(match.groupValues[4])
            require(ids.size == quantities.size && ids.isNotEmpty()) { "Inventário incompatível" }
            for ((id, count) in plan.items) {
                var slot = ids.indexOf(id)
                if (slot < 0) slot = ids.indexOf(0)
                require(slot >= 0) { "Inventário cheio; não há espaço para o item ID $id" }
                ids[slot] = id
                quantities[slot] = count
            }
            val m = match.groupValues
            val replacement = m[1] + ids.joinToString(", ", " ", " ") +
                m[3] + quantities.joinToString(", ", " ", " ") + m[5]
            modified = modified.replaceRange(match.range.first, match.range.last + 1, replacement)
        }
        return modified
    }

    private fun replaceNumber(text: String, regex: Regex, number: Number): String {
        val m = regex.find(text) ?: error("Campo de edição não encontrado")
        return text.replaceRange(m.groups[2]!!.range, number.toString())
    }

    fun apply(data: ByteArray, plan: EditPlan): ByteArray {
        plan.validate()
        val entries = archive(data)
        val found = hashSetOf<String>()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (item in entries) {
                val newEntry = ZipEntry(item.entry.name)
                newEntry.time = item.entry.time
                newEntry.comment = item.entry.comment
                zip.putNextEntry(newEntry)
                val bytes = if (item.entry.name in plan.targetPaths) {
                    require(savePath.matches(item.entry.name)) { "Arquivo selecionado não é save" }
                    found += item.entry.name
                    Base64.getEncoder().encode(rewrite(plain(item.contents), plan).toByteArray(StandardCharsets.UTF_8))
                } else item.contents
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        require(found == plan.targetPaths) { "Um dos saves selecionados não foi encontrado: ${plan.targetPaths - found}" }
        val result = out.toByteArray()
        // Validate the exported archive before any caller is allowed to write it.
        val exportedEntries = archive(result)
        require(entries.map { it.entry.name } == exportedEntries.map { it.entry.name }) {
            "A estrutura do backup mudou durante a exportação"
        }
        for ((originalEntry, exportedEntry) in entries.zip(exportedEntries)) {
            if (originalEntry.entry.name !in found) {
                require(originalEntry.contents.contentEquals(exportedEntry.contents)) {
                    "Arquivo não selecionado mudou: ${originalEntry.entry.name}"
                }
            } else {
                val exportedText = plain(exportedEntry.contents)
                val exportedPlayer = exportedText.substring(playerBounds(exportedText))
                if (plan.gold != null) require(one(exportedPlayer, goldPattern, "gold").toLong() == plan.gold)
                if (plan.traitPoints != null) require(one(exportedPlayer, traitPattern, "extraTraitPoints").toInt() == plan.traitPoints)
                if (plan.skillPoints != null) require(one(exportedPlayer, skillPattern, "bonusPoints").toInt() == plan.skillPoints)
                if (plan.items.isNotEmpty()) {
                    val pack = backpack(exportedText)
                    val ids = numbers(pack.groupValues[2])
                    val counts = numbers(pack.groupValues[4])
                    for (item in plan.items) {
                        val slot = ids.indexOf(item.id)
                        require(slot >= 0 && counts[slot] == plan.items.last { it.id == item.id }.count) {
                            "Falha ao validar a inserção do item ID ${item.id}"
                        }
                    }
                }
            }
        }
        val before = inspect(data)
        val after = inspect(result)
        require(before.map { it.path } == after.map { it.path }) { "Lista de saves mudou inesperadamente" }
        for (old in before) {
            if (old.path !in found) {
                require(after.single { it.path == old.path } == old) { "Save não selecionado foi alterado" }
            }
        }
        return result
    }
}
