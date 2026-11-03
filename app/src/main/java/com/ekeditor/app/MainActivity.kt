package com.ekeditor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView

/** Offline EK.bak editor: all saves modified together; the original backup is preserved. */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_OPEN = 100
        private const val REQUEST_EXPORT = 101
        private const val GAME_PATH = "/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak"
        private const val ORIGINAL_CACHE = "ek_session_original.bak"
        private const val MODIFIED_CACHE = "ek_session_modified.bak"
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
    private var saves = emptyList<EkBackup.SaveInfo>()
    private var appliedChanges = 0
    private lateinit var catalog: ItemCatalog
    private var selectedItem: ItemCatalog.Item? = null
    private var selectedPotion: ItemCatalog.Item? = null
    private val editButtons = mutableListOf<Button>()

    private lateinit var status: TextView
    private lateinit var sourceDetails: TextView
    private lateinit var goldCurrent: TextView
    private lateinit var goldInput: EditText
    private lateinit var traitCurrent: TextView
    private lateinit var traitDeltaInput: EditText
    private lateinit var skillCurrent: TextView
    private lateinit var skillDeltaInput: EditText
    private lateinit var companionCard: LinearLayout
    private lateinit var companionTitle: TextView
    private lateinit var companionTraitCurrent: TextView
    private lateinit var companionSkillCurrent: TextView
    private lateinit var companionTraitInput: EditText
    private lateinit var companionSkillInput: EditText
    private lateinit var itemsCurrent: TextView
    private lateinit var itemSelectionLabel: TextView
    private lateinit var potionSelectionLabel: TextView
    private lateinit var itemAmount: EditText
    private lateinit var potionAmount: EditText
    private lateinit var lastChange: TextView
    private lateinit var exportButton: Button
    private lateinit var originalButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = ItemCatalog(this)
        createInterface()
        if (savedInstanceState != null && savedInstanceState.getBoolean("has_session")) {
            try {
                val original = openFileInput(ORIGINAL_CACHE).use { it.readBytes() }
                val edited = openFileInput(MODIFIED_CACHE).use { it.readBytes() }
                val parsed = EkBackup.inspect(edited)
                originalBytes = original
                editedBytes = edited
                saves = parsed
                appliedChanges = savedInstanceState.getInt("applied_changes")
                selectedItem = catalog.items.firstOrNull { it.id == savedInstanceState.getInt("item_id", -1) }
                selectedPotion = catalog.items.firstOrNull { it.id == savedInstanceState.getInt("potion_id", -1) }
                itemSelectionLabel.text = selectedItem?.name ?: "Nenhum item selecionado"
                potionSelectionLabel.text = selectedPotion?.name ?: "Nenhuma poção selecionada"
                lastChange.text = "Sessão restaurada com $appliedChanges alteração(ões)."
                updateEnabled()
                refreshPreview(true)
                setStatus("✓ Edições recuperadas. Exporte quando terminar.", true)
            } catch (_: Exception) {
                setStatus("Selecione EK.bak para começar.", false)
            }
        } else setStatus("Selecione EK.bak para começar.", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val original = originalBytes
        val edited = editedBytes
        if (original != null && edited != null) {
            openFileOutput(ORIGINAL_CACHE, MODE_PRIVATE).use { it.write(original) }
            openFileOutput(MODIFIED_CACHE, MODE_PRIVATE).use { it.write(edited) }
            outState.putBoolean("has_session", true)
            outState.putInt("applied_changes", appliedChanges)
            outState.putInt("item_id", selectedItem?.id ?: -1)
            outState.putInt("potion_id", selectedPotion?.id ?: -1)
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig) // keep the loaded save and unexported edits on rotation
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    private fun shape(fill: Int, stroke: Int = GOLD): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(13).toFloat(); setStroke(dp(1), stroke)
    }
    private fun text(value: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    private fun section(title: String, detail: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = shape(PANEL, Color.rgb(91, 75, 54))
        addView(text(title, 18f, GOLD, true))
        if (detail.isNotEmpty()) addView(text(detail, 12f, MUTED), margin(3, 9))
    }
    private fun number(label: String, initial: String = ""): EditText = EditText(this).apply {
        setText(initial); hint = label; setHintTextColor(MUTED); setTextColor(TEXT)
        setSingleLine(true); textSize = 16f; inputType = InputType.TYPE_CLASS_NUMBER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = shape(Color.rgb(11, 17, 20), Color.rgb(77, 72, 62))
    }
    private fun action(label: String, primary: Boolean = false, callback: () -> Unit): Button =
        Button(this).apply {
            text = label; textSize = 14f; isAllCaps = false; setTextColor(TEXT)
            background = shape(if (primary) GREEN else Color.rgb(46, 57, 65),
                if (primary) Color.rgb(118, 160, 95) else Color.rgb(90, 101, 104))
            setOnClickListener { callback() }
        }
    private fun editAction(label: String, callback: () -> Unit): Button = action(label, true, callback).also {
        it.isEnabled = false; editButtons += it
    }
    private fun createInterface() {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(10), dp(15), dp(24))
        }
        scroll.addView(body); setContentView(scroll)
        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ek_banner)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }, margin(0, 12))
        body.addView(text("Editor de saves feito por old390", 16f, GOLD, true).apply {
            gravity = Gravity.CENTER
        }, margin(0, 3))
        body.addView(text("github.com/old390/EK-Save-Editor", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/old390/EK-Save-Editor"))) }
        }, margin(0, 12))

        val files = section("📁  Encontrar save", "Crie um backup no jogo e importe o EK.bak pelo gerenciador de arquivos.")
        status = text("", 13f, GOLD, true)
        sourceDetails = text("Nenhum backup importado", 13f, MUTED)
        files.addView(status, margin(2, 7))
        files.addView(sourceDetails, margin(5, 8))
        files.addView(action("📂  Selecionar EK.bak") { importBackup() }, margin(2, 5))
        body.addView(files, margin(0, 12))

        val inventory = section("🎒  Inventário, itens e poções", "Exibe uma única lista de referência do auto-save. Todas as alterações atingem todos os saves.")
        itemsCurrent = text("Nenhum inventário carregado", 13f, TEXT)
        inventory.addView(itemsCurrent, margin(0, 12))
        inventory.addView(text("Adicionar item do catálogo", 13f, GOLD, true))
        itemSelectionLabel = text("Nenhum item selecionado", 13f, TEXT)
        inventory.addView(itemSelectionLabel, margin(7, 3))
        inventory.addView(action("🔎  Procurar item por nome ou ID") { chooseCatalog(false) }, margin(3, 5))
        itemAmount = number("Quantidade de itens", "1")
        inventory.addView(itemAmount)
        inventory.addView(editAction("✓  Aplicar item em todos os saves") {
            val item = selectedItem ?: return@editAction notifyError("Escolha um item no catálogo")
            val amount = numberValue(itemAmount, "Quantidade", 999, 1).toInt()
            applyChange("${item.name} × $amount") { paths -> EkBackup.EditPlan(paths, items = listOf(EkBackup.ItemAdd(item.id, amount))) }
        }, margin(5, 10))
        inventory.addView(text("Adicionar poção ou consumível", 13f, GOLD, true))
        potionSelectionLabel = text("Nenhuma poção selecionada", 13f, TEXT)
        inventory.addView(potionSelectionLabel, margin(7, 3))
        inventory.addView(action("🧪  Escolher poção na lista") { chooseCatalog(true) }, margin(3, 5))
        potionAmount = number("Quantidade de poções", "1")
        inventory.addView(potionAmount)
        inventory.addView(editAction("✓  Aplicar poção em todos os saves") {
            val item = selectedPotion ?: return@editAction notifyError("Escolha uma poção na lista")
            val amount = numberValue(potionAmount, "Quantidade", 999, 1).toInt()
            applyChange("${item.name} × $amount") { paths -> EkBackup.EditPlan(paths, items = listOf(EkBackup.ItemAdd(item.id, amount))) }
        }, margin(5, 9))
        inventory.addView(text("⚠ Itens colocados com inventário cheio podem substituir itens existentes ou até causar perda do inventário. Confira os espaços livres antes de adicionar itens. O editor bloqueará a inserção quando não houver espaço disponível.", 12f, MUTED))
        body.addView(inventory, margin(0, 12))

        val gold = section("🪙  Ouro", "Defina a quantidade de ouro e toque em Aplicar. A mudança aparece imediatamente.")
        goldCurrent = text("Ouro atual: —", 14f, TEXT, true)
        goldInput = number("Novo valor de ouro")
        gold.addView(goldCurrent)
        gold.addView(goldInput, margin(8, 5))
        gold.addView(editAction("✓  Aplicar ouro a todos") {
            val value = numberValue(goldInput, "Ouro", 999_999_999)
            applyChange("Ouro") { paths -> EkBackup.EditPlan(paths, gold = value) }
        }, margin(4, 0))
        body.addView(gold, margin(0, 12))

        val attributes = section("💪  Atributos", "Cada toque em Aplicar SOMA a quantidade informada aos pontos livres. Zerar não remove pontos já distribuídos.")
        traitCurrent = text("Pontos livres atuais: —", 14f, TEXT, true)
        traitDeltaInput = number("Pontos para adicionar", "10")
        attributes.addView(traitCurrent)
        attributes.addView(traitDeltaInput, margin(8, 5))
        attributes.addView(editAction("+  Somar pontos de atributos") {
            val amount = numberValue(traitDeltaInput, "Atributos", 999, 1).toInt()
            applyChange("+$amount atributos") { paths -> EkBackup.EditPlan(paths, traitDelta = amount) }
        }, margin(4, 6))
        attributes.addView(editAction("↺  Zerar pontos livres de atributos") {
            confirmReset("Zerar os pontos livres de atributos do personagem em todos os saves?") {
                applyChange("Atributos zerados") { paths -> EkBackup.EditPlan(paths, resetTraits = true) }
            }
        })
        body.addView(attributes, margin(0, 12))

        val skills = section("✨  Habilidades", "Cada toque em Aplicar soma mais pontos livres ao total existente.")
        skillCurrent = text("Pontos livres atuais: —", 14f, TEXT, true)
        skillDeltaInput = number("Pontos para adicionar", "10")
        skills.addView(skillCurrent)
        skills.addView(skillDeltaInput, margin(8, 5))
        skills.addView(editAction("+  Somar pontos de habilidades") {
            val amount = numberValue(skillDeltaInput, "Habilidades", 999, 1).toInt()
            applyChange("+$amount habilidades") { paths -> EkBackup.EditPlan(paths, skillDelta = amount) }
        }, margin(4, 0))
        body.addView(skills, margin(0, 12))

        companionCard = section("🛡️  Companheiro ativo", "Só são mostrados os atributos e habilidades do companheiro ativo. Aplicar soma pontos em todos os saves em que ele estiver ativo.")
        companionTitle = text("Companheiro", 14f, GOLD, true)
        companionTraitCurrent = text("Atributos livres: —", 14f, TEXT)
        companionSkillCurrent = text("Habilidades livres: —", 14f, TEXT)
        companionTraitInput = number("Atributos para adicionar", "10")
        companionSkillInput = number("Habilidades para adicionar", "10")
        companionCard.addView(companionTitle)
        companionCard.addView(companionTraitCurrent, margin(7, 5))
        companionCard.addView(companionTraitInput)
        companionCard.addView(editAction("+  Somar atributos do companheiro") {
            val n = numberValue(companionTraitInput, "Atributos do companheiro", 999, 1).toInt()
            applyChange("+$n atributos do companheiro") { paths -> EkBackup.EditPlan(paths, companionTraitDelta = n) }
        }, margin(4, 5))
        companionCard.addView(editAction("↺  Zerar atributos livres do companheiro") {
            confirmReset("Zerar os pontos livres de atributos do companheiro ativo?") {
                applyChange("Atributos do companheiro zerados") { paths -> EkBackup.EditPlan(paths, resetCompanionTraits = true) }
            }
        })
        companionCard.addView(companionSkillCurrent, margin(10, 5))
        companionCard.addView(companionSkillInput)
        companionCard.addView(editAction("+  Somar habilidades do companheiro") {
            val n = numberValue(companionSkillInput, "Habilidades do companheiro", 999, 1).toInt()
            applyChange("+$n habilidades do companheiro") { paths -> EkBackup.EditPlan(paths, companionSkillDelta = n) }
        }, margin(4, 0))
        companionCard.visibility = View.GONE
        body.addView(companionCard, margin(0, 4))
        body.addView(text("Quando houver um companheiro ativo no backup, os campos de atributos e habilidades dele aparecerão aqui.", 12f, MUTED), margin(4, 12))

        val export = section("🛡️  Exportar e restaurar", "As mudanças ficam no aplicativo até você exportar o EK.bak e restaurá-lo pelo próprio jogo.")
        lastChange = text("Nenhuma alteração aplicada nesta sessão.", 13f, MUTED)
        export.addView(lastChange, margin(2, 9))
        exportButton = action("💾  Exportar EK.bak modificado", true) {
            val bytes = editedBytes ?: return@action notifyError("Importe o EK.bak primeiro")
            if (appliedChanges == 0) return@action notifyError("Aplique pelo menos uma alteração")
            writeToPicker(bytes, "EK.bak")
        }
        export.addView(exportButton, margin(5, 7))
        originalButton = action("🗂️  Salvar cópia do original") {
            writeToPicker(originalBytes ?: return@action notifyError("Carregue um backup"), "EK_original_preservado.bak")
        }
        export.addView(originalButton, margin(2, 5))
        export.addView(text("Depois de exportar, mova EK.bak para:\n$GAME_PATH\nAbra o jogo e use RESTAURAR BACKUP. Preserve o arquivo original fora da pasta do jogo.", 12f, MUTED), margin(7, 0))
        body.addView(export, margin(0, 15))
        body.addView(text("Aplicativo independente; não afiliado aos criadores do jogo. Catálogo de IDs: comunidade Steam (2021).", 11f, MUTED).apply { gravity = Gravity.CENTER })
        updateEnabled()
    }
    private fun confirmReset(message: String, callback: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Confirmar zeramento")
            .setMessage(message).setNegativeButton("Cancelar", null)
            .setPositiveButton("Zerar") { _, _ -> callback() }.show()
    }
    private fun chooseCatalog(onlyPotions: Boolean) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(this).apply {
            hint = "Pesquisar nome ou ID"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val results = ListView(this)
        dialogView.addView(search)
        dialogView.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (onlyPotions) "Escolher poção" else "Catálogo de itens")
            .setView(dialogView).setNegativeButton("Cancelar", null).create()
        var displayed = emptyList<ItemCatalog.Item>()
        fun refresh(query: String) {
            displayed = catalog.find(query, onlyPotions).take(250)
            val labels = displayed.map { "${it.name}  ·  ID ${it.id}" }
            results.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        results.setOnItemClickListener { _, _, position, _ ->
            val chosen = displayed[position]
            if (onlyPotions) { selectedPotion = chosen; potionSelectionLabel.text = chosen.name }
            else { selectedItem = chosen; itemSelectionLabel.text = chosen.name }
            dialog.dismiss()
        }
        refresh("")
        dialog.show()
    }
    private fun setStatus(message: String, positive: Boolean) {
        status.text = message
        status.setTextColor(if (positive) Color.rgb(129, 214, 136) else GOLD)
    }
    private fun notifyError(message: String) {
        setStatus("Erro: $message", false)
        AlertDialog.Builder(this).setTitle("EK Save Editor")
            .setMessage(message).setPositiveButton("Entendi", null).show()
    }
    private fun numberValue(field: EditText, label: String, max: Long, min: Long = 0): Long {
        val n = field.text.toString().trim().toLongOrNull() ?: error("Informe um número em '$label'")
        require(n in min..max) { "$label precisa estar entre $min e $max" }
        return n
    }
    private fun allPaths(): Set<String> {
        require(saves.isNotEmpty()) { "Importe EK.bak antes" }
        return saves.map { it.path }.toSet()
    }
    private fun applyChange(label: String, plan: (Set<String>) -> EkBackup.EditPlan) {
        try {
            val source = editedBytes ?: error("Importe EK.bak primeiro")
            val paths = allPaths()
            val result = EkBackup.apply(source, plan(paths)) // transactional: no partial app changes on failure
            val readback = EkBackup.inspect(result)
            editedBytes = result
            saves = readback
            appliedChanges++
            lastChange.text = "✓ $label aplicado em ${paths.size} saves. $appliedChanges alteração(ões) em memória. Exporte o EK.bak para salvar."
            lastChange.setTextColor(Color.rgb(129, 214, 136))
            refreshPreview(false)
            updateEnabled()
            setStatus("✓ $label aplicado em todos os saves. Confira o resultado abaixo.", true)
        } catch (e: Exception) { notifyError(e.message ?: "Não foi possível aplicar") }
    }
    private fun previewSave(): EkBackup.SaveInfo? = saves.firstOrNull { it.path.endsWith("/auto.sav") }
        ?: saves.firstOrNull { it.path.endsWith("/game.sav") }
        ?: saves.firstOrNull()
    private fun refreshPreview(fillGold: Boolean) {
        val representative = previewSave()
        if (representative == null) {
            goldCurrent.text = "Ouro atual: —"
            traitCurrent.text = "Pontos livres atuais: —"
            skillCurrent.text = "Pontos livres atuais: —"
            itemsCurrent.text = "Nenhum inventário carregado"
            companionCard.visibility = View.GONE
            return
        }
        fun <T> value(label: String, values: List<T>, reference: T): String {
            return "$label: $reference${if (values.distinct().size > 1) " (outros saves diferem)" else ""}"
        }
        goldCurrent.text = value("Ouro atual", saves.map { it.gold }, representative.gold)
        traitCurrent.text = value("Pontos livres atuais", saves.map { it.traitPoints }, representative.traitPoints)
        skillCurrent.text = value("Pontos livres atuais", saves.map { it.skillPoints }, representative.skillPoints)
        if (fillGold) goldInput.setText(representative.gold.toString())
        val minimumFree = saves.minOf { it.freeSlots }
        val details = representative.inventory.toList().sortedBy { catalog.name(it.first).lowercase() }
            .joinToString("\n") { (id, count) -> "• ${catalog.name(id)} × $count" }
        itemsCurrent.text = "Inventário de referência: ${representative.hero} (auto-save)\n" +
            "Espaços livres no inventário exibido: ${representative.freeSlots}\n" +
            "Espaços livres mínimos entre todos os saves: $minimumFree\n\n" +
            (details.ifBlank { "Inventário vazio" })
        val companions = saves.mapNotNull { it.companion }
        if (companions.isNotEmpty()) {
            val companion = representative.companion ?: companions.first()
            companionTitle.text = companion.name
            companionTraitCurrent.text = value("Atributos livres", companions.map { it.traitPoints }, companion.traitPoints)
            companionSkillCurrent.text = value("Habilidades livres", companions.map { it.skillPoints }, companion.skillPoints)
            companionCard.visibility = View.VISIBLE
        } else companionCard.visibility = View.GONE
        sourceDetails.text = "${saves.size} saves identificados. ${if (appliedChanges == 0) "Nenhuma edição aplicada." else "$appliedChanges edição(ões) aplicada(s) em todos."}"
    }
    private fun updateEnabled() {
        val present = editedBytes != null
        editButtons.forEach { it.isEnabled = present }
        originalButton.isEnabled = originalBytes != null
        exportButton.isEnabled = present && appliedChanges > 0
    }
    private fun loadBytes(data: ByteArray) {
        saves = EkBackup.inspect(data)
        originalBytes = data.copyOf()
        editedBytes = data.copyOf()
        appliedChanges = 0
        lastChange.text = "Nenhuma alteração aplicada nesta sessão."
        lastChange.setTextColor(MUTED)
        refreshPreview(true)
        updateEnabled()
        setStatus("✓ EK.bak reconhecido. Todas as edições atingirão todos os saves.", true)
    }
    private fun importBackup() {
        fun choose() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_OPEN)
        }
        if (appliedChanges == 0) choose()
        else AlertDialog.Builder(this).setTitle("Descartar modificações?")
            .setMessage("Há edições não exportadas. Exporte o backup antes de importar outro arquivo se quiser preservá-las.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Abrir outro backup") { _, _ -> choose() }.show()
    }
    private fun writeToPicker(bytes: ByteArray, name: String) {
        pendingExport = bytes.copyOf()
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQUEST_EXPORT)
    }
    @Deprecated("For Android 8+ compatibility")
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
                        ?: error("Não foi possível abrir EK.bak")
                    loadBytes(bytes)
                }
                REQUEST_EXPORT -> {
                    val bytes = pendingExport ?: error("Exportação cancelada")
                    contentResolver.openOutputStream(data.data!!, "w")?.use { it.write(bytes); it.flush() }
                        ?: error("Não foi possível gravar o backup")
                    pendingExport = null
                    setStatus("✓ Backup exportado. Agora restaure pelo menu do jogo.", true)
                    AlertDialog.Builder(this).setTitle("Arquivo salvo")
                        .setMessage("O EK.bak foi exportado. Copie o arquivo modificado para:\n$GAME_PATH\n\nDepois, abra Exiled Kingdoms e escolha RESTAURAR BACKUP. O arquivo original deve ficar preservado.")
                        .setPositiveButton("Entendi", null).show()
                }
            }
        } catch (e: Exception) { notifyError("Falha no arquivo: ${e.message}") }
    }
}
