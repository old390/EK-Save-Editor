package com.ekeditor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/** Editor offline de EK.bak: alterações são confirmadas na memória e exportadas pelo usuário. */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_OPEN = 100
        private const val REQUEST_EXPORT = 101
        private const val GAME_PATH = "/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak"
        private val BG = Color.rgb(8, 13, 16)
        private val PANEL = Color.rgb(21, 27, 33)
        private val TEXT = Color.rgb(242, 237, 226)
        private val MUTED = Color.rgb(179, 182, 181)
        private val GOLD = Color.rgb(201, 165, 108)
        private val GREEN = Color.rgb(40, 106, 67)
    }

    private var originalBytes: ByteArray? = null
    private var editedBytes: ByteArray? = null
    private var pendingExport: ByteArray? = null
    private var saves: List<EkBackup.SaveInfo> = emptyList()
    private var appliedChanges = 0
    private var lastAppliedItem: Int? = null
    private var refreshingSelection = false
    private val editButtons = mutableListOf<Button>()

    private lateinit var status: TextView
    private lateinit var sourceDetails: TextView
    private lateinit var slotSpinner: Spinner
    private lateinit var goldCurrent: TextView
    private lateinit var goldInput: EditText
    private lateinit var traitCurrent: TextView
    private lateinit var traitInput: EditText
    private lateinit var skillCurrent: TextView
    private lateinit var skillInput: EditText
    private lateinit var itemsCurrent: TextView
    private lateinit var potionInput: EditText
    private lateinit var customId: EditText
    private lateinit var customAmount: EditText
    private lateinit var lastChange: TextView
    private lateinit var exportButton: Button
    private lateinit var originalButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        setStatus("Selecione um arquivo EK.bak para começar.", false)
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density + .5f).toInt()
    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    private fun shape(fill: Int, outline: Int = GOLD): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), outline)
    }
    private fun text(label: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    private fun section(title: String, detail: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = shape(PANEL, Color.rgb(91, 75, 54))
        addView(text(title, 18f, GOLD, true))
        addView(text(detail, 12f, MUTED), margin(3, 9))
    }
    private fun number(hint: String, initial: String = ""): EditText = EditText(this).apply {
        setText(initial)
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        setSingleLine(true)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_NUMBER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = shape(Color.rgb(11, 17, 20), Color.rgb(77, 72, 62))
    }
    private fun action(label: String, primary: Boolean = false, handler: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(TEXT)
            background = shape(if (primary) GREEN else Color.rgb(46, 57, 65),
                if (primary) Color.rgb(118, 160, 95) else Color.rgb(90, 101, 104))
            setOnClickListener { handler() }
        }
    private fun editAction(label: String, handler: () -> Unit): Button = action(label, true, handler).also {
        it.isEnabled = false
        editButtons += it
    }

    private fun createInterface() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(10), dp(15), dp(24))
        }
        scroll.addView(body)
        setContentView(scroll)
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ek_banner)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, margin(0, 12))
        body.addView(text("Editor de backups • v0.3", 15f, GOLD, true).apply {
            gravity = Gravity.CENTER
        }, margin(0, 13))

        val fileCard = section("📁  Encontrar save", "Crie primeiro um backup atualizado DENTRO de Exiled Kingdoms.")
        status = text("", 13f, GOLD, true)
        sourceDetails = text("Nenhum backup importado", 13f, MUTED)
        fileCard.addView(status, margin(2, 7))
        fileCard.addView(sourceDetails, margin(5, 8))
        fileCard.addView(action("📂  Selecionar EK.bak") { importBackup() }, margin(2, 5))
        body.addView(fileCard, margin(0, 12))

        val savesCard = section("💾  Escolher saves", "As alterações só atingem o save escolhido ou todos, se você selecionar essa opção.")
        slotSpinner = Spinner(this).apply {
            background = shape(Color.rgb(14, 21, 25))
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        savesCard.addView(slotSpinner, margin(2, 6))
        body.addView(savesCard, margin(0, 12))
        slotSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!refreshingSelection) refreshPreview(fillFields = true)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val goldCard = section("🪙  Ouro", "Digite o novo valor, toque em Aplicar e confira a quantidade atualizada abaixo.")
        goldCurrent = text("Ouro atual: —", 14f, TEXT, true)
        goldInput = number("Novo valor de ouro", "")
        goldCard.addView(goldCurrent)
        goldCard.addView(goldInput, margin(8, 5))
        goldCard.addView(editAction("✓  Aplicar ouro") {
            applyChange("Ouro") { paths -> EkBackup.EditPlan(paths, gold = value(goldInput, "Ouro", 999_999_999)) }
        }, margin(4, 0))
        body.addView(goldCard, margin(0, 12))

        val attributes = section("💪  Atributos", "Edita pontos livres de atributo, não os pontos já distribuídos.")
        traitCurrent = text("Pontos de atributos atuais: —", 14f, TEXT, true)
        traitInput = number("Pontos livres de atributos")
        attributes.addView(traitCurrent)
        attributes.addView(traitInput, margin(8, 5))
        attributes.addView(editAction("✓  Aplicar atributos") {
            applyChange("Atributos") { paths ->
                EkBackup.EditPlan(paths, traitPoints = value(traitInput, "Atributos", 999).toInt())
            }
        }, margin(4, 0))
        body.addView(attributes, margin(0, 12))

        val skills = section("✨  Habilidades", "Edita pontos livres de habilidades, não as habilidades aprendidas.")
        skillCurrent = text("Pontos de habilidades atuais: —", 14f, TEXT, true)
        skillInput = number("Pontos livres de habilidades")
        skills.addView(skillCurrent)
        skills.addView(skillInput, margin(8, 5))
        skills.addView(editAction("✓  Aplicar habilidades") {
            applyChange("Habilidades") { paths ->
                EkBackup.EditPlan(paths, skillPoints = value(skillInput, "Habilidades", 999).toInt())
            }
        }, margin(4, 0))
        body.addView(skills, margin(0, 12))

        val items = section("⚔️  Inventário, armas e poções", "Cada botão adiciona o item ao backup em memória. As quantidades aplicadas serão mostradas aqui.")
        itemsCurrent = text("Itens no save: —", 13f, TEXT)
        items.addView(itemsCurrent, margin(0, 8))
        items.addView(editAction("✓  Adicionar Adamantite Greatsword (ID 554)") {
            applyItem(554, 1, "Espada de duas mãos")
        }, margin(4, 5))
        items.addView(editAction("✓  Adicionar Energy Blade (ID 750)") {
            applyItem(750, 1, "Energy Blade")
        }, margin(4, 8))
        items.addView(text("Quantidade de Medipacks (ID 5070)", 12f, MUTED))
        potionInput = number("Medipacks", "999")
        items.addView(potionInput, margin(5, 5))
        items.addView(editAction("✓  Aplicar poções") {
            val count = value(potionInput, "Medipacks", 999, 1).toInt()
            applyItem(5070, count, "Medipacks")
        }, margin(4, 10))
        items.addView(text("Adicionar outro item por ID", 12f, MUTED))
        customId = number("ID do item")
        customAmount = number("Quantidade", "1")
        items.addView(customId, margin(6, 4))
        items.addView(customAmount, margin(2, 5))
        items.addView(editAction("✓  Aplicar item personalizado") {
            val id = value(customId, "ID do item", 999_999, 1).toInt()
            val count = value(customAmount, "Quantidade", 999, 1).toInt()
            applyItem(id, count, "Item ID $id")
        }, margin(4, 0))
        body.addView(items, margin(0, 12))

        val export = section("🛡️  Exportar e restaurar", "Aplicar modifica o backup em memória. Para jogar com os novos valores, exporte EK.bak e restaure dentro do jogo.")
        lastChange = text("Nenhuma alteração aplicada nesta sessão.", 13f, MUTED)
        export.addView(lastChange, margin(2, 9))
        exportButton = action("💾  Exportar EK.bak modificado", true) {
            val bytes = editedBytes ?: return@action notifyError("Importe o EK.bak primeiro")
            if (appliedChanges == 0) return@action notifyError("Aplique pelo menos uma alteração antes de exportar")
            writeToPicker(bytes, "EK.bak")
        }
        exportButton.isEnabled = false
        export.addView(exportButton, margin(5, 7))
        originalButton = action("🗂️  Salvar cópia do EK.bak original") {
            writeToPicker(originalBytes ?: return@action notifyError("Importe um backup primeiro"), "EK_original_preservado.bak")
        }
        originalButton.isEnabled = false
        export.addView(originalButton, margin(2, 5))
        export.addView(text("Após exportar, mova o EK.bak modificado para:\n$GAME_PATH\nDepois abra o jogo e use RESTAURAR BACKUP. Preserve o original em outra pasta.", 12f, MUTED), margin(7, 0))
        body.addView(export, margin(0, 15))
        body.addView(text("Aplicativo independente; não afiliado aos criadores de Exiled Kingdoms.", 11f, MUTED).apply {
            gravity = Gravity.CENTER
        })
        refreshSlots()
        updateEnabled()
    }

    private fun setStatus(message: String, ok: Boolean) {
        status.text = message
        status.setTextColor(if (ok) Color.rgb(129, 214, 136) else GOLD)
    }
    private fun notifyError(message: String) {
        AlertDialog.Builder(this).setTitle("EK Save Editor")
            .setMessage(message).setPositiveButton("Entendi", null).show()
        setStatus("Erro: $message", false)
    }
    private fun value(edit: EditText, field: String, max: Long, min: Long = 0): Long {
        val number = edit.text.toString().trim().toLongOrNull()
            ?: error("Preencha '$field' com um número inteiro")
        require(number in min..max) { "$field deve estar entre $min e $max" }
        return number
    }
    private fun targets(): Set<String> {
        require(saves.isNotEmpty()) { "Importe EK.bak primeiro" }
        val index = slotSpinner.selectedItemPosition
        return if (index <= 0) saves.map { it.path }.toSet()
        else setOf(saves[index - 1].path)
    }
    private fun applyItem(id: Int, amount: Int, label: String) {
        if (applyChange("$label × $amount") { paths ->
                EkBackup.EditPlan(paths, items = listOf(EkBackup.ItemAdd(id, amount)))
            }) {
            lastAppliedItem = id
            refreshPreview(fillFields = false)
        }
    }
    private fun applyChange(label: String, plan: (Set<String>) -> EkBackup.EditPlan): Boolean {
        try {
            val current = editedBytes ?: error("Importe EK.bak primeiro")
            val chosen = targets()
            // Do not modify the editor state unless the entire archive rewrite validates.
            val revised = EkBackup.apply(current, plan(chosen))
            val parsed = EkBackup.inspect(revised)
            editedBytes = revised
            saves = parsed
            appliedChanges++
            lastChange.text = "✓ $label aplicado em ${chosen.size} save(s). $appliedChanges alteração(ões) na memória; exportação pendente."
            lastChange.setTextColor(Color.rgb(129, 214, 136))
            setStatus("✓ $label aplicado. Confira o valor abaixo e exporte quando terminar.", true)
            refreshPreview(fillFields = false)
            exportButton.isEnabled = true
            return true
        } catch (e: Exception) {
            notifyError(e.message ?: "Falha ao aplicar modificação")
            return false
        }
    }
    private fun updateEnabled() {
        val loaded = editedBytes != null
        editButtons.forEach { it.isEnabled = loaded }
        originalButton.isEnabled = originalBytes != null
        exportButton.isEnabled = loaded && appliedChanges > 0
    }
    private fun refreshSlots() {
        refreshingSelection = true
        val index = if (::slotSpinner.isInitialized) slotSpinner.selectedItemPosition else 0
        val values = mutableListOf("Todos os saves")
        values += saves.map { "${it.path.substringAfterLast('/')} • ${it.hero}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        slotSpinner.adapter = adapter
        slotSpinner.setSelection(index.coerceIn(0, values.lastIndex))
        refreshingSelection = false
        refreshPreview(fillFields = true)
    }
    private fun refreshPreview(fillFields: Boolean) {
        if (!::goldCurrent.isInitialized) return
        if (saves.isEmpty()) {
            goldCurrent.text = "Ouro atual: —"
            traitCurrent.text = "Pontos de atributos atuais: —"
            skillCurrent.text = "Pontos de habilidades atuais: —"
            itemsCurrent.text = "Itens no save: —"
            return
        }
        val chosen = targets()
        val selected = saves.filter { it.path in chosen }
        fun <T> display(values: List<T>): String = if (values.distinct().size == 1) values.first().toString() else "valores diferentes"
        goldCurrent.text = "Ouro atual: ${display(selected.map { it.gold })}"
        traitCurrent.text = "Pontos de atributos atuais: ${display(selected.map { it.traitPoints })}"
        skillCurrent.text = "Pontos de habilidades atuais: ${display(selected.map { it.skillPoints })}"
        if (fillFields) {
            goldInput.setText(selected.map { it.gold }.distinct().singleOrNull()?.toString() ?: "")
            traitInput.setText(selected.map { it.traitPoints }.distinct().singleOrNull()?.toString() ?: "")
            skillInput.setText(selected.map { it.skillPoints }.distinct().singleOrNull()?.toString() ?: "")
        }
        itemsCurrent.text = selected.joinToString("\n\n") { info ->
            val trackedIds = (listOf(554, 750, 5070) + listOfNotNull(lastAppliedItem)).distinct()
            val tracked = trackedIds.mapNotNull { id ->
                info.inventory[id]?.let { "$id × $it" }
            }
            "${info.path.substringAfterLast('/')} • ${info.hero}\n" +
                "Ouro ${info.gold} | Atributos ${info.traitPoints} | Habilidades ${info.skillPoints}\n" +
                "Itens editados: ${tracked.joinToString("; ").ifEmpty { "nenhum" }} | Espaços livres: ${info.freeSlots}" +
                if (info.inventory.isNotEmpty()) "\nItens (ID × quantidade): ${info.inventory.entries.take(12).joinToString("; ") { "${it.key} × ${it.value}" }}${if (info.inventory.size > 12) "…" else ""}" else ""
        }
        sourceDetails.text = "${saves.size} save(s) reconhecidos. ${if (appliedChanges == 0) "Backup original, sem edições." else "$appliedChanges alteração(ões) aplicadas nesta sessão."}"
    }
    private fun loadBytes(data: ByteArray) {
        val infos = EkBackup.inspect(data)
        originalBytes = data.copyOf()
        editedBytes = data.copyOf()
        saves = infos
        appliedChanges = 0
        lastAppliedItem = null
        lastChange.text = "Nenhuma alteração aplicada nesta sessão."
        lastChange.setTextColor(MUTED)
        refreshSlots()
        updateEnabled()
        setStatus("✓ EK.bak reconhecido. Edite e use Aplicar em cada campo.", true)
    }
    private fun importBackup() {
        fun openPicker() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_OPEN)
        }
        if (appliedChanges == 0) openPicker()
        else AlertDialog.Builder(this).setTitle("Descartar alterações não exportadas?")
            .setMessage("Ao abrir outro EK.bak, as alterações em memória serão perdidas. Exporte antes, se quiser preservá-las.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir novo backup") { _, _ -> openPicker() }.show()
    }
    private fun writeToPicker(bytes: ByteArray, filename: String) {
        pendingExport = bytes.copyOf()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, filename)
        }, REQUEST_EXPORT)
    }
    @Deprecated("Compatible with Android 8+ devices")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            if (requestCode == REQUEST_EXPORT) pendingExport = null
            return
        }
        try {
            when (requestCode) {
                REQUEST_OPEN -> {
                    val bytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                        ?: error("Não foi possível abrir o arquivo")
                    loadBytes(bytes)
                }
                REQUEST_EXPORT -> {
                    val bytes = pendingExport ?: error("Exportação cancelada")
                    contentResolver.openOutputStream(data.data!!, "w")?.use { it.write(bytes); it.flush() }
                        ?: error("Não foi possível gravar o arquivo")
                    pendingExport = null
                    setStatus("✓ Arquivo exportado! Restaure o EK.bak pelo menu do jogo.", true)
                    AlertDialog.Builder(this).setTitle("Arquivo salvo")
                        .setMessage("O arquivo foi exportado. Se for o EK.bak modificado, coloque-o em:\n$GAME_PATH\n\nDepois use a opção RESTAURAR BACKUP dentro de Exiled Kingdoms. O editor não altera os saves ativos automaticamente.")
                        .setPositiveButton("Entendi", null).show()
                }
            }
        } catch (e: Exception) { notifyError("Falha de arquivo: ${e.message}") }
    }
}
