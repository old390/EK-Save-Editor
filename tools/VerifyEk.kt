import com.ekeditor.app.EkBackup
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile

fun main(args: Array<String>) {
    val file = File(args[0]); val input = file.readBytes()
    val slots = EkBackup.inspect(input)
    check(slots.size == 4) { "Esperados quatro saves de exemplo" }
    println("Lidos: " + slots.joinToString { "${it.path} (${it.hero}, ${it.gold} ouro)" })
    val selected = setOf(slots.first().path)
    val changed = EkBackup.apply(input, EkBackup.EditPlan(selected, 1234567L, 55, 66,
        listOf(EkBackup.ItemAdd(554), EkBackup.ItemAdd(750), EkBackup.ItemAdd(5070, 999))))
    val parsed = EkBackup.inspect(changed)
    check(parsed[0].gold == 1234567L)
    check(parsed[0].traitPoints == 55 && parsed[0].skillPoints == 66)
    check(parsed.drop(1) == slots.drop(1))
    // Ensure non-target ZIP entries are byte-identical after processing.
    val tmp = File.createTempFile("ek-verify", ".bak")
    try {
        tmp.writeBytes(changed)
        ZipFile(file).use { original -> ZipFile(tmp).use { edited ->
            val paths = original.entries().asSequence().map { it.name }.toList()
            for (name in paths) {
                if (name !in selected) {
                    val a = original.getInputStream(original.getEntry(name)).readBytes()
                    val b = edited.getInputStream(edited.getEntry(name)).readBytes()
                    check(a.contentEquals(b)) { "Entrada alterada indevidamente: $name" }
                }
            }
            val text = String(Base64.getDecoder().decode(edited.getInputStream(edited.getEntry(slots.first().path)).readBytes()))
            check(text.contains("554, 750, 5070")) { "Itens não aplicados" }
            check(text.contains("999"))
        }}
    } finally { tmp.delete() }
    val all = EkBackup.apply(input, EkBackup.EditPlan(slots.map{it.path}.toSet(), gold=9999999L))
    check(EkBackup.inspect(all).all { it.gold==9999999L })
    println("OK: edição individual, quatro saves, preservação de arquivos, pontos, itens e ouro.")
}
