package com.ekeditor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
        private val NON_STACKABLE_RED = Color.rgb(238, 96, 96)
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
    private lateinit var inventoryGrid: LinearLayout
    private var selectedVault = 1
    private lateinit var vaultSelector: Button
    private lateinit var vaultSummary: TextView
    private lateinit var vaultGrid: LinearLayout
    private lateinit var vaultUnlockButton: Button
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
        selectedVault = savedInstanceState?.getInt("selected_vault", 1)?.coerceIn(1, 4) ?: 1
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
            outState.putInt("selected_vault", selectedVault)
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
    private fun editAction(label: String, callback: () -> Unit): Button = action(label, true, {
        // Captura valores inválidos antes de encerrar a Activity: o usuário recebe um aviso.
        try { callback() } catch (e: Exception) {
            notifyError(e.message ?: "Verifique os valores antes de aplicar.")
        }
    }).also {
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
        body.addView(text("Editor de saves feito por old390 • v0.6.0", 16f, GOLD, true).apply {
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
        files.addView(action("🔎  Conferir valores de todos os saves") { showAllSaveAudit() }, margin(1, 6))
        body.addView(files, margin(0, 12))

        val inventory = section("🎒  Inventário, itens e poções", "Grade visual dos espaços reais de um save de referência. As edições continuam sendo aplicadas a todos os saves.")
        itemsCurrent = text("Nenhum inventário carregado", 13f, TEXT)
        inventory.addView(itemsCurrent, margin(0, 7))
        inventoryGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inventory.addView(inventoryGrid, margin(1, 5))
        inventory.addView(text("Toque em um item para somar, alterar a quantidade, excluir ou substituí-lo. Em um espaço vazio, escolha um item para inserir. Quando os saves tiverem itens diferentes ou o inventário estiver cheio, você poderá escolher uma substituição explícita antes de aplicar. Nomes em vermelho: itens não empilháveis (1 por espaço).", 11f, MUTED), margin(4, 12))
        inventory.addView(text("Adicionar item do catálogo", 13f, GOLD, true))
        itemSelectionLabel = text("Nenhum item selecionado", 13f, TEXT)
        inventory.addView(itemSelectionLabel, margin(7, 3))
        inventory.addView(action("🔎  Procurar item por nome ou ID") { chooseCatalog(false) }, margin(3, 5))
        itemAmount = number("Quantidade de itens", "1")
        inventory.addView(itemAmount)
        inventory.addView(editAction("✓  Aplicar item em todos os saves") {
            val item = selectedItem ?: return@editAction notifyError("Escolha um item no catálogo")
            val amount = numberValue(itemAmount, "Quantidade", 999, 1).toInt()
            addOrChooseReplacement(item, amount)
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
            addOrChooseReplacement(item, amount)
        }, margin(5, 9))
        inventory.addView(text("⚠ Quando um save estiver cheio, a inserção simples será bloqueada. Você poderá escolher uma posição e confirmar quais itens serão substituídos em cada save. Nada será apagado automaticamente. Preserve o EK.bak original.", 12f, MUTED))
        body.addView(inventory, margin(0, 12))

        val vaultSection = section("🏛️  Baús da Prefeitura",
            "Escolha entre os quatro baús. Cada um tem 20 espaços próprios. " +
                "A grade mostra um save de referência e aplica alterações verificadas em todos. " +
                "Toque nos itens para adicionar cópias, definir quantidade, excluir ou substituir.")
        vaultSelector = action("🗃️  Escolher baú") { showVaultMenu() }
        vaultSummary = text("Importe EK.bak para consultar os baús.", 13f, TEXT)
        vaultGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vaultUnlockButton = editAction("🔓  Desbloquear baú selecionado") {
            AlertDialog.Builder(this).setTitle("Desbloquear baú $selectedVault?")
                .setMessage("Ativa o baú nos quatro saves sem descontar ouro. " +
                    "É uma edição do backup, não uma compra no jogo. Prosseguir?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desbloquear") { _, _ ->
                    applyVaultChange("Baú $selectedVault desbloqueado",
                        VaultStorage.Change(selectedVault, VaultStorage.Action.UNLOCK))
                }.show()
        }
        vaultSection.addView(vaultSelector, margin(2, 5))
        vaultSection.addView(vaultSummary, margin(9, 6))
        vaultSection.addView(vaultGrid, margin(3, 4))
        vaultUnlockButton.visibility = View.GONE
        vaultSection.addView(vaultUnlockButton, margin(7, 4))
        vaultSection.addView(text("⚠ Os conteúdos podem variar entre saves. " +
            "Substituições exibem o item de cada save antes de confirmar. " +
            "Itens em vermelho ocupam apenas uma unidade por espaço.", 11f, MUTED), margin(5, 5))
        body.addView(vaultSection, margin(0, 12))

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

        val attributes = section("💪  Atributos", "Saldo real = pontos recebidos por nível + bônus extras − pontos já gastos. Cada toque em Somar acrescenta o número digitado em todos os saves.")
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

        val skills = section("✨  Habilidades", "Saldo real = pontos recebidos por nível + bônus − custo de cada habilidade aprendida. Somar acrescenta ao saldo real sem apagar habilidades.")
        skillCurrent = text("Habilidades livres atuais: —", 14f, TEXT, true)
        skillDeltaInput = number("Pontos para adicionar", "10")
        skills.addView(skillCurrent)
        skills.addView(skillDeltaInput, margin(8, 5))
        skills.addView(editAction("+  Somar pontos de habilidades") {
            val amount = numberValue(skillDeltaInput, "Habilidades", 999, 1).toInt()
            applyChange("+$amount habilidades") { paths -> EkBackup.EditPlan(paths, skillDelta = amount) }
        }, margin(4, 6))
        skills.addView(editAction("↺  Zerar pontos livres de habilidades") {
            confirmReset("Zerar os pontos livres de habilidades do personagem em todos os saves?") {
                applyChange("Habilidades zeradas") { paths -> EkBackup.EditPlan(paths, resetSkills = true) }
            }
        })
        body.addView(skills, margin(0, 12))

        companionCard = section("🛡️  Companheiro ativo", "Atributos e habilidades livres reais do companheiro ativo. São mantidas as habilidades aprendidas e os atributos já gastos.")
        companionTitle = text("Companheiro", 14f, GOLD, true)
        companionTraitCurrent = text("Atributos livres reais: —", 14f, TEXT)
        companionSkillCurrent = text("Habilidades livres reais: —", 14f, TEXT)
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
        }, margin(4, 6))
        companionCard.addView(editAction("↺  Zerar habilidades livres do companheiro") {
            confirmReset("Zerar os pontos livres de habilidades do companheiro ativo em todos os saves?") {
                applyChange("Habilidades do companheiro zeradas") { paths -> EkBackup.EditPlan(paths, resetCompanionSkills = true) }
            }
        })
        companionCard.visibility = View.GONE
        body.addView(companionCard, margin(0, 4))
        body.addView(text("Quando houver um companheiro ativo no backup, os campos de atributos e habilidades dele aparecerão aqui.", 12f, MUTED), margin(4, 12))

        val export = section("🛡️  Exportar e restaurar", "As mudanças ficam no aplicativo até exportar o EK.bak e restaurá-lo pelo jogo. Confira todos os saves antes de exportar.")
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
    private fun showAllSaveAudit() {
        try {
            val currentBytes = editedBytes ?: return notifyError("Importe EK.bak primeiro")
            val updated = EkBackup.inspect(currentBytes)
            val originals = originalBytes?.let { EkBackup.inspect(it).associateBy { info -> info.path } } ?: emptyMap()
            val details = updated.joinToString("\n\n") { info ->
                val previous = originals[info.path]
                val shortPath = info.path.substringAfter("saves/")
                fun format(old: Any?, now: Any) = if (old == null || old == now) "$now" else "$old → $now"
                "• $shortPath\n" +
                    "Ouro: ${format(previous?.gold, info.gold)}\n" +
                    "Atributos livres reais: ${format(previous?.freeTraitPoints, info.freeTraitPoints ?: "indisponível")}\n" +
                    "Bônus extras de atributos: ${format(previous?.traitPoints, info.traitPoints)}\n" +
                    "Atributos gastos: ${info.spentTraitPoints ?: "?"}\n" +
                    "Habilidades livres reais: ${format(previous?.freeSkillPoints, info.freeSkillPoints ?: "indisponível")}\n" +
                    "Bônus de habilidades: ${format(previous?.skillPoints, info.skillPoints)}\n" +
                    "Habilidades gastas: ${info.spentSkillPoints ?: "?"}\n" +
                    "Companheiro: ${info.companion?.name ?: "nenhum"}" +
                    (info.companion?.let { c ->
                        "\n  Atributos livres: ${c.freeTraitPoints ?: "indisponível"}" +
                        " | Habilidades livres: ${c.freeSkillPoints ?: "indisponível"}"
                    } ?: "")
            }
            AlertDialog.Builder(this)
                .setTitle("Conferência: ${updated.size}/${updated.size} saves")
                .setMessage("Estes são os valores gravados no backup em memória. Ainda será necessário exportar e restaurar pelo jogo.\n\n$details")
                .setPositiveButton("Entendi", null).show()
        } catch (e: Exception) { notifyError("Conferência falhou: ${e.message}") }
    }

    private fun vaultData(): Map<String, List<VaultStorage.VaultInfo>> {
        val data = editedBytes ?: return emptyMap()
        return EkBackup.inspectVaults(data)
    }

    private fun selectedVaultData(): Map<String, VaultStorage.VaultInfo> =
        vaultData().mapNotNull { (path, vaults) ->
            vaults.firstOrNull { it.number == selectedVault }?.let { path to it }
        }.toMap()

    private fun showVaultMenu() {
        if (editedBytes == null) return notifyError("Importe EK.bak primeiro")
        val bySave = vaultData()
        val labels = (1..4).map { number ->
            val vaults = bySave.values.mapNotNull { v -> v.firstOrNull { it.number == number } }
            val state = when {
                vaults.isEmpty() -> "não encontrado"
                vaults.all { it.unlocked } -> "desbloqueado"
                vaults.none { it.unlocked } -> "bloqueado"
                else -> "diferente entre saves"
            }
            "Baú $number · $state"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Escolher baú da Prefeitura")
            .setSingleChoiceItems(labels, selectedVault - 1) { dialog, choice ->
                selectedVault = choice + 1
                dialog.dismiss()
                refreshVaultPreview()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun refreshVaultPreview() {
        vaultGrid.removeAllViews()
        vaultSelector.text = "🗃️  Escolher baú · Baú $selectedVault"
        val bySave = try { selectedVaultData() } catch (e: Exception) {
            vaultSummary.text = "Baú indisponível: ${e.message}"
            vaultUnlockButton.visibility = View.GONE
            return
        }
        if (bySave.isEmpty()) {
            vaultSummary.text = "Nenhum baú encontrado no backup."
            vaultUnlockButton.visibility = View.GONE
            return
        }
        val preview = previewSave()?.path?.let { bySave[it] } ?: bySave.values.first()
        val allUnlocked = bySave.size == saves.size && bySave.values.all { it.unlocked }
        val minimumFree = bySave.values.minOf { it.free }
        vaultSummary.text =
            "Baú $selectedVault · ${if (allUnlocked) "Desbloqueado" else "Há saves com o baú bloqueado"}\n" +
            "Ocupados: ${preview.occupied}/20 · Livres neste save: ${preview.free} · " +
            "Mínimo de espaços livres nos saves: $minimumFree"
        vaultUnlockButton.visibility = if (allUnlocked) View.GONE else View.VISIBLE
        if (!allUnlocked) {
            vaultGrid.addView(text("Desbloqueie o baú nos saves antes de editar seu conteúdo.", 12f, MUTED))
            return
        }
        val slots = preview.slots
        for (block in slots.withIndex().toList().chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, slot) in block) {
                val occupied = slot.id != 0
                val label = if (occupied) catalog.name(slot.id) else "Vazio"
                val glyph = when {
                    !occupied -> "＋"
                    ItemRules.isStackable(slot.id) -> "🧪"
                    else -> "📦"
                }
                val displayed = "$glyph\n$label" +
                    if (occupied && slot.count > 1) "\n×${slot.count}" else ""
                val styled = SpannableString(displayed).apply {
                    setSpan(RelativeSizeSpan(1.5f), 0, glyph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (occupied && !ItemRules.isStackable(slot.id)) {
                        val begin = glyph.length + 1
                        setSpan(ForegroundColorSpan(NON_STACKABLE_RED), begin,
                            begin + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                row.addView(Button(this).apply {
                    text = styled
                    textSize = 10f
                    isAllCaps = false
                    gravity = Gravity.CENTER
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    setTextColor(if (occupied) TEXT else MUTED)
                    background = shape(
                        if (occupied) Color.rgb(43, 50, 57) else Color.rgb(15, 22, 27),
                        if (occupied) Color.rgb(150, 117, 73) else Color.rgb(48, 58, 62))
                    contentDescription = "Baú $selectedVault, espaço ${index + 1}: $label"
                    setOnClickListener {
                        val vaultNumber = selectedVault
                        if (occupied) showVaultSlotActions(vaultNumber, index, slot, label)
                        else chooseCatalog(false) { item ->
                            askVaultItemQuantity("Adicionar ${item.name}", item) { count ->
                                addIntoVaultSpace(vaultNumber, index, item, count)
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            vaultGrid.addView(row)
        }
    }

    private fun applyVaultChange(label: String, change: VaultStorage.Change) {
        applyChange(label) { paths ->
            EkBackup.EditPlan(paths, vaultChange = change)
        }
    }

    private fun askVaultItemQuantity(
        heading: String, item: ItemCatalog.Item,
        limitToOne: Boolean = false, onReady: (Int) -> Unit
    ) {
        val field = number("Quantidade", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(7), dp(16), dp(7))
            addView(text(if (!ItemRules.isStackable(item.id))
                "Item não empilhável: cada unidade ocupa um espaço próprio."
            else "Quantidade máxima por pilha: 999.", 12f,
                if (!ItemRules.isStackable(item.id)) NON_STACKABLE_RED else MUTED))
            addView(field, margin(4, 5))
        }
        AlertDialog.Builder(this).setTitle(heading)
            .setView(wrapper)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                try {
                    val max = if (limitToOne) ItemRules.maxPerSlot(item.id) else 999
                    val amount = numberValue(field, "Quantidade do baú", max.toLong(), 1).toInt()
                    onReady(amount)
                } catch (e: Exception) { notifyError(e.message ?: "Quantidade inválida") }
            }.show()
    }

    private fun addIntoVaultSpace(
        vault: Int, index: Int, item: ItemCatalog.Item, count: Int
    ) {
        val bySave = selectedVaultData()
        if (bySave.size != saves.size) return notifyError("O baú não existe em todos os saves")
        val exists = bySave.values.any { it.slots[index].id != 0 }
        if (exists) {
            confirmVaultReplacement(vault, index, item, count)
        } else {
            applyVaultChange("Baú $vault: ${item.name} ×$count",
                VaultStorage.Change(vault, VaultStorage.Action.INSERT,
                    index = index, itemId = item.id, amount = count))
        }
    }

    private fun confirmVaultReplacement(
        vault: Int, index: Int, item: ItemCatalog.Item, amount: Int
    ) {
        if (!ItemRules.isStackable(item.id) && amount != 1) {
            return notifyError("Substituir uma posição permite apenas uma unidade deste item. " +
                "Para mais cópias, utilize espaços vazios.")
        }
        val expected = selectedVaultData().mapValues { (_, vaultInfo) -> vaultInfo.slots[index] }
        if (expected.keys != saves.map { it.path }.toSet()) {
            return notifyError("Baú $vault não encontrado nos quatro saves")
        }
        val review = expected.entries.joinToString("\n") { (path, slot) ->
            val name = if (slot.id == 0) "(vazio)"
                else "${catalog.name(slot.id)} ×${slot.count}"
            "• ${path.substringAfterLast('/')}: $name"
        }
        AlertDialog.Builder(this)
            .setTitle("Confirmar troca no Baú $vault")
            .setMessage("Posição ${index + 1} receberá ${item.name} ×$amount em todos os saves.\n\n" +
                "Itens que serão substituídos:\n$review\n\n" +
                "Apenas esta posição será modificada em cada save.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar troca") { _, _ ->
                applyVaultChange("Baú $vault: troca na posição ${index + 1}",
                    VaultStorage.Change(vault, VaultStorage.Action.REPLACE,
                        index = index, itemId = item.id, amount = amount,
                        expectedByPath = expected))
            }.show()
    }

    private fun showVaultSlotActions(
        vault: Int, index: Int, slot: EkBackup.InventorySlot, label: String
    ) {
        val list = selectedVaultData()[previewSave()?.path]?.slots.orEmpty()
        val occurrence = list.take(index + 1).count { it.id == slot.id } - 1
        if (occurrence < 0) return notifyError("A posição do baú mudou; selecione novamente")
        val stackable = ItemRules.isStackable(slot.id)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        layout.addView(text("Baú $vault · Espaço ${index + 1} · " +
            if (stackable) "${slot.count} unidade(s)" else "Item unitário", 12f, MUTED))
        lateinit var dialog: AlertDialog
        fun option(name: String, handler: () -> Unit) {
            layout.addView(action(name) { dialog.dismiss(); handler() }, margin(2, 4))
        }
        val item = ItemCatalog.Item(slot.id, label, "")
        option(if (stackable) "＋  Adicionar unidades" else "＋  Duplicar cópia") {
            askVaultItemQuantity("Adicionar $label", item) { n ->
                applyVaultChange("Baú $vault: +$n $label",
                    VaultStorage.Change(vault, VaultStorage.Action.ADD,
                        index = index, itemId = slot.id, amount = n, occurrence = occurrence))
            }
        }
        if (stackable) option("✎  Definir quantidade") {
            askVaultItemQuantity("Definir quantidade de $label", item) { n ->
                applyVaultChange("Baú $vault: $label ×$n",
                    VaultStorage.Change(vault, VaultStorage.Action.SET,
                        index = index, itemId = slot.id, amount = n, occurrence = occurrence))
            }
        }
        option("🗑  Excluir cópia / pilha") {
            AlertDialog.Builder(this)
                .setTitle("Excluir $label do Baú $vault?")
                .setMessage("Exclui a ocorrência selecionada do item em cada save onde existir. " +
                    "As outras cópias e os outros baús serão preservados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir") { _, _ ->
                    applyVaultChange("Baú $vault: excluído $label",
                        VaultStorage.Change(vault, VaultStorage.Action.DELETE,
                            index = index, itemId = slot.id, occurrence = occurrence))
                }.show()
        }
        option("↔  Substituir por outro item") {
            chooseCatalog(false) { replacement ->
                askVaultItemQuantity("Substituir por ${replacement.name}", replacement, true) { n ->
                    confirmVaultReplacement(vault, index, replacement, n)
                }
            }
        }
        dialog = AlertDialog.Builder(this).setTitle(label)
            .setView(layout).setNegativeButton("Fechar", null).create()
        dialog.show()
    }

    /** Representação visual fiel às posições reais, com símbolos genéricos por tipo de item. */
    private fun refreshInventoryGrid(reference: EkBackup.SaveInfo) {
        inventoryGrid.removeAllViews()
        val slots = reference.inventorySlots
        fun iconFor(name: String, occupied: Boolean): String {
            if (!occupied) return "＋"
            val n = name.lowercase()
            return when {
                listOf("potion", "elixir", "medipack", "flask", "draught", "mead", "rum", "venom", "apple", "omelette").any { n.contains(it) } -> "🧪"
                listOf("sword", "dagger", "blade", "mace", "axe", "bow", "staff", "hammer", "spear").any { n.contains(it) } -> "⚔️"
                listOf("shield", "armor", "armour", "helmet", "boots", "gloves", "cloak", "robe", "chestplate").any { n.contains(it) } -> "🛡️"
                listOf("ring", "necklace", "amulet", "belt", "jewel").any { n.contains(it) } -> "💍"
                listOf("tome", "scroll", "book", "letter", "map").any { n.contains(it) } -> "📜"
                else -> "📦"
            }
        }
        for (group in slots.withIndex().toList().chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, slot) in group) {
                val occupied = slot.id != 0
                val label = if (occupied) catalog.name(slot.id) else "Vazio"
                // Button real: evita perder toques nos TextViews internos do antigo tile.
                val glyph = iconFor(label, occupied)
                val display = "$glyph\n$label" +
                    if (occupied && slot.count > 1) "\n×${slot.count}" else ""
                val styled = SpannableString(display).apply {
                    setSpan(RelativeSizeSpan(1.6f), 0, glyph.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (occupied && !ItemRules.isStackable(slot.id)) {
                        val begin = glyph.length + 1
                        setSpan(ForegroundColorSpan(NON_STACKABLE_RED), begin,
                            begin + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                val tile = Button(this).apply {
                    text = styled
                    textSize = 10f
                    isAllCaps = false
                    gravity = Gravity.CENTER
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    setTextColor(if (occupied) TEXT else MUTED)
                    background = shape(if (occupied) Color.rgb(43, 50, 57) else Color.rgb(15, 22, 27),
                        if (occupied) Color.rgb(150, 117, 73) else Color.rgb(48, 58, 62))
                    contentDescription = "Espaço ${index + 1}: $label. Toque para opções."
                    setOnClickListener {
                        if (occupied) showInventorySlotActions(index, slot, label)
                        else chooseCatalog(false) { item -> showInsertIntoSlot(index, item) }
                    }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            // Mantém a largura uniforme na última linha se houver slots parciais.
            repeat(4 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f)) }
            inventoryGrid.addView(row, margin(0, 0))
        }
    }

    /**
     * Se algum save estiver cheio E sem o item escolhido, a inserção sem
     * perda é impossível. Exibe os espaços disponíveis para substituição.
     */
    private fun addOrChooseReplacement(item: ItemCatalog.Item, amount: Int) {
        val blocked = saves.filter { item.id !in it.inventory && it.freeSlots == 0 }
        if (blocked.isEmpty()) {
            applyChange("${item.name} × $amount") { paths ->
                EkBackup.EditPlan(paths, items = listOf(EkBackup.ItemAdd(item.id, amount)))
            }
            return
        }
        AlertDialog.Builder(this).setTitle("Inventário cheio em ${blocked.size} save(s)")
            .setMessage("O item não existe e não há espaço disponível em: " +
                blocked.joinToString(", ") { it.path.substringAfterLast("/") } +
                ".\n\nVocê pode escolher uma posição da grade para substituir em todos os saves. " +
                "O editor mostrará os itens que serão removidos antes de confirmar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Escolher espaço") { _, _ ->
                chooseReplacementSlot(item, amount)
            }.show()
    }

    private fun chooseReplacementSlot(item: ItemCatalog.Item, amount: Int) {
        val ref = previewSave() ?: return notifyError("Importe EK.bak primeiro")
        val list = ref.inventorySlots.mapIndexed { idx, slot ->
            "Espaço ${idx + 1}: " + if (slot.id == 0) "(vazio)" else
                "${catalog.name(slot.id)} ×${slot.count}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Escolher espaço para ${item.name}")
            .setItems(list) { _, index -> confirmSlotReplacement(index, item, amount) }
            .setNegativeButton("Cancelar", null).show()
    }

    /**
     * Atualiza a célula escolhida em todos os saves mesmo com inventários
     * diferentes, somente após confirmação com resumo por arquivo.
     */
    private fun confirmSlotReplacement(index: Int, item: ItemCatalog.Item, amount: Int) {
        val all = saves.associate { info ->
            info.path to (info.inventorySlots.getOrNull(index)
                ?: return notifyError("Espaço ${index + 1} não existe em ${info.path}"))
        }
        val overwritten = all.filterValues { it.id != 0 && it.id != item.id }
        val preview = all.entries.joinToString("\n") { (path, slot) ->
            val name = if (slot.id == 0) "(vazio)"
                else "${catalog.name(slot.id)} ×${slot.count}"
            "• ${path.substringAfterLast('/')}: $name"
        }
        val warning = if (overwritten.isNotEmpty()) {
            "\n\nATENÇÃO: ${overwritten.size} save(s) perderão o item acima dessa posição."
        } else "\n\nNenhum ID diferente será removido."
        AlertDialog.Builder(this)
            .setTitle("Confirmar espaço ${index + 1}")
            .setMessage("O item ${item.name} ×$amount será colocado na posição ${index + 1} " +
                "de TODOS os saves.\n\nConteúdo atual:\n$preview$warning" +
                "\n\nO backup original não será modificado.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar substituição") { _, _ ->
                applyChange("${item.name} ×$amount na posição ${index + 1}") { paths ->
                    EkBackup.EditPlan(paths, slotReplacements = listOf(
                        EkBackup.SlotReplace(index, item.id, amount, all)
                    ))
                }
            }.show()
    }

    /** Abre o catálogo a partir de uma célula vazia da grade. */
    private fun showInsertIntoSlot(index: Int, item: ItemCatalog.Item) {
        val input = number("Quantidade", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(text(item.name, 13f, GOLD, true))
            addView(input, margin(7, 2))
        }
        AlertDialog.Builder(this).setTitle("Adicionar item ao espaço ${index + 1}")
            .setMessage("Se esta posição estiver ocupada em algum save, será exibida uma confirmação antes de substituí-la.")
            .setView(wrapper)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Adicionar") { _, _ ->
                try {
                    val amount = numberValue(input, "Quantidade do novo item", 999, 1).toInt()
                    val occupiedSomewhere = saves.any { info ->
                        info.inventorySlots.getOrNull(index)?.id != 0
                    }
                    if (occupiedSomewhere) {
                        confirmSlotReplacement(index, item, amount)
                    } else {
                        applyChange("Adicionado ${item.name} ×$amount no espaço ${index + 1}") { paths ->
                            EkBackup.EditPlan(paths, slotInserts = listOf(
                                EkBackup.SlotInsert(index, item.id, amount)))
                        }
                    }
                } catch (e: Exception) { notifyError(e.message ?: "Não foi possível adicionar o item") }
            }.show()
    }

    /**
     * Não combina AlertDialog.setMessage com setItems: alguns aparelhos
     * escondem as opções. Mostra quatro botões reais numa view customizada.
     */
    private fun showInventorySlotActions(index: Int, slot: EkBackup.InventorySlot, label: String) {
        val stackable = ItemRules.isStackable(slot.id)
        val selectedSlots = previewSave()?.inventorySlots.orEmpty()
        // Ordem da cópia entre itens de mesmo ID: primeira = 0, segunda = 1...
        // Assim a grade pode controlar uma cópia específica em todos os saves,
        // mesmo quando as posições de cada inventário são diferentes.
        val occurrence = selectedSlots.take(index + 1).count { it.id == slot.id } - 1
        if (occurrence < 0 || selectedSlots.getOrNull(index)?.id != slot.id) {
            return notifyError("A grade mudou. Toque novamente no item atualizado.")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(7), dp(18), dp(7))
        }
        val status = if (stackable)
            "Espaço ${index + 1} · ${slot.count} unidade(s)\nEmpilhável: até 999 por espaço."
        else
            "Espaço ${index + 1} · Item unitário (1 por espaço).\nNovas cópias ocupam espaços separados."
        layout.addView(text(status, 13f, MUTED), margin(0, 9))
        lateinit var dialog: AlertDialog
        fun option(labelText: String, handler: () -> Unit) {
            layout.addView(action(labelText) {
                dialog.dismiss()
                handler()
            }, margin(2, 4))
        }
        option(if (stackable) "＋  Adicionar mais unidades" else "＋  Adicionar outra cópia") {
            val unitary = !stackable
            val input = number(if (unitary) "Quantas cópias?" else "Quantas unidades?", "1")
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(8))
                addView(text(if (unitary) "Cada cópia ocupará um espaço vazio em cada save."
                             else "As unidades serão somadas ao stack em cada save.", 12f, MUTED))
                addView(input, margin(4, 3))
            }
            AlertDialog.Builder(this)
                .setTitle(if (unitary) "Adicionar cópias de $label" else "Somar unidades de $label")
                .setView(holder)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar") { _, _ ->
                    try {
                        val amount = numberValue(input, "Quantidade", 999, 1).toInt()
                        applyChange("+$amount ${if (unitary) "cópia(s)" else "unidade(s)"} de $label") { paths ->
                            EkBackup.EditPlan(paths, slotEdits = listOf(
                                EkBackup.SlotEdit(index, slot.id, slot.count, amount,
                                    addQuantity = true, occurrence = occurrence)))
                        }
                    } catch (e: Exception) { notifyError(e.message ?: "Quantidade inválida") }
                }.show()
        }
        if (stackable) option("✎  Definir quantidade do stack") {
            val input = number("Quantidade desejada", slot.count.toString())
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(7), dp(18), dp(7))
                addView(text("O valor substituirá a quantidade do item em cada save.", 12f, MUTED))
                addView(input, margin(4, 3))
            }
            AlertDialog.Builder(this).setTitle("Definir quantidade de $label")
                .setView(holder)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar") { _, _ ->
                    try {
                        val amount = numberValue(input, "Quantidade", 999, 1).toInt()
                        applyChange("Quantidade de $label = $amount") { paths ->
                            EkBackup.EditPlan(paths, slotEdits = listOf(
                                EkBackup.SlotEdit(index, slot.id, slot.count, amount,
                                    occurrence = occurrence)))
                        }
                    } catch (e: Exception) { notifyError(e.message ?: "Quantidade inválida") }
                }.show()
        }
        option("🗑  Excluir item") {
            AlertDialog.Builder(this).setTitle("Excluir $label?")
                .setMessage("Será removida uma ocorrência deste ID em cada save onde existir. " +
                    "Nenhum item diferente será removido.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir") { _, _ ->
                    applyChange("Excluído $label") { paths ->
                        EkBackup.EditPlan(paths, slotEdits = listOf(
                            EkBackup.SlotEdit(index, slot.id, slot.count, 0,
                                occurrence = occurrence)))
                    }
                }.show()
        }
        option("↔  Substituir por outro item") {
            chooseCatalog(false) { item -> showReplacementQuantity(index, item) }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setView(layout)
            .setNegativeButton("Fechar", null)
            .create()
        dialog.show()
    }

    private fun showReplacementQuantity(index: Int, item: ItemCatalog.Item) {
        val input = number("Quantidade", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(text(item.name, 13f, GOLD, true))
            addView(input, margin(5, 2))
        }
        AlertDialog.Builder(this).setTitle("Substituir espaço ${index + 1}")
            .setView(wrapper)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                try {
                    val amount = numberValue(input, "Quantidade", 999, 1).toInt()
                    confirmSlotReplacement(index, item, amount)
                } catch (e: Exception) { notifyError(e.message ?: "Quantidade inválida") }
            }.show()
    }

    private fun confirmReset(message: String, callback: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Confirmar zeramento")
            .setMessage(message).setNegativeButton("Cancelar", null)
            .setPositiveButton("Zerar") { _, _ -> callback() }.show()
    }
    private fun chooseCatalog(onlyPotions: Boolean, onPicked: ((ItemCatalog.Item) -> Unit)? = null) {
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
            results.adapter = object : ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, labels
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    (view as? TextView)?.setTextColor(
                        if (!ItemRules.isStackable(displayed[position].id)) NON_STACKABLE_RED else TEXT
                    )
                    return view
                }
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString() ?: "") }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        results.setOnItemClickListener { _, _, position, _ ->
            val chosen = displayed[position]
            dialog.dismiss()
            if (onPicked != null) onPicked(chosen)
            else if (onlyPotions) {
                selectedPotion = chosen
                potionSelectionLabel.text = chosen.name
                potionSelectionLabel.setTextColor(
                    if (ItemRules.isStackable(chosen.id)) TEXT else NON_STACKABLE_RED
                )
            } else {
                selectedItem = chosen
                itemSelectionLabel.text = chosen.name
                itemSelectionLabel.setTextColor(
                    if (ItemRules.isStackable(chosen.id)) TEXT else NON_STACKABLE_RED
                )
            }
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
        val raw = field.text.toString()
        if (raw.isBlank()) field.error = "Digite um valor antes de aplicar"
        return EkBackup.parseNumberInput(raw, label, max, min)
    }
    private fun allPaths(): Set<String> {
        require(saves.isNotEmpty()) { "Importe EK.bak antes" }
        return saves.map { it.path }.toSet()
    }
    private fun applyChange(label: String, plan: (Set<String>) -> EkBackup.EditPlan) {
        try {
            val source = editedBytes ?: error("Importe EK.bak primeiro")
            val paths = allPaths()
            val requested = plan(paths)
            val before = saves.associateBy { it.path }
            val result = EkBackup.apply(source, requested) // sem alterações parciais
            val readback = EkBackup.inspect(result)
            val first = readback.first()
            val previous = before[first.path]
            val traitReport = when {
                requested.traitDelta != null || requested.resetTraits -> {
                    if (previous?.freeTraitPoints != null && first.freeTraitPoints != null)
                        " Atributos: ${previous.freeTraitPoints} → ${first.freeTraitPoints}."
                    else " Confira o bônus de atributos em todos os saves."
                }
                requested.skillDelta != null || requested.resetSkills -> {
                    if (previous?.freeSkillPoints != null && first.freeSkillPoints != null)
                        " Habilidades: ${previous.freeSkillPoints} → ${first.freeSkillPoints}."
                    else " Confira o bônus de habilidades em todos os saves."
                }
                else -> ""
            }
            editedBytes = result
            saves = readback
            appliedChanges++
            lastChange.text = "✓ $label conferido em ${paths.size}/${paths.size} saves.$traitReport " +
                "$appliedChanges alteração(ões) em memória. Exporte EK.bak para salvar."
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
            skillCurrent.text = "Habilidades livres atuais: —"
            itemsCurrent.text = "Nenhum inventário carregado"
            inventoryGrid.removeAllViews()
            vaultGrid.removeAllViews()
            vaultSummary.text = "Importe EK.bak para consultar os baús."
            vaultUnlockButton.visibility = View.GONE
            companionCard.visibility = View.GONE
            return
        }
        fun <T> value(label: String, values: List<T>, reference: T): String {
            return "$label: $reference${if (values.distinct().size > 1) " (outros saves diferem)" else ""}"
        }
        goldCurrent.text = value("Ouro atual", saves.map { it.gold }, representative.gold)
        traitCurrent.text = if (representative.freeTraitPoints == null) {
            "Pontos livres: cálculo indisponível (nível ou histórico de gastos ausente).\n" +
                "Pontos extras registrados: ${representative.traitPoints}"
        } else {
            value("Pontos livres reais", saves.map { it.freeTraitPoints }, representative.freeTraitPoints) +
                "\nBônus extras no arquivo: ${representative.traitPoints}" +
                " • Nível ${representative.characterLevel}" +
                " • Gastos: ${representative.spentTraitPoints}"
        }
        skillCurrent.text = if (representative.freeSkillPoints == null) {
            "Pontos livres: cálculo indisponível (habilidade de custo não catalogado).\n" +
                "Bônus de habilidades no arquivo: ${representative.skillPoints}"
        } else {
            value("Pontos livres reais", saves.map { it.freeSkillPoints }, representative.freeSkillPoints) +
                "\nBônus de habilidades: ${representative.skillPoints}" +
                " • Gastos: ${representative.spentSkillPoints}"
        }
        if (fillGold) goldInput.setText(representative.gold.toString())
        val minimumFree = saves.minOf { it.freeSlots }
        itemsCurrent.text = "Inventário de referência: ${representative.hero}\n" +
            "${representative.inventorySlots.size} espaços • ${representative.freeSlots} livres neste save\n" +
            "Espaços livres mínimos entre todos os saves: $minimumFree"
        refreshInventoryGrid(representative)
        refreshVaultPreview()
        val companions = saves.mapNotNull { it.companion }
        if (companions.isNotEmpty()) {
            val companion = representative.companion ?: companions.first()
            companionTitle.text = companion.name
            companionTraitCurrent.text = if (companion.freeTraitPoints == null) {
                "Atributos livres: indisponível • bônus extras: ${companion.traitPoints}"
            } else {
                value("Atributos livres reais", companions.map { it.freeTraitPoints }, companion.freeTraitPoints) +
                    "\nBônus extras: ${companion.traitPoints} • Gastos: ${companion.spentTraitPoints}"
            }
            companionSkillCurrent.text = if (companion.freeSkillPoints == null) {
                "Habilidades livres: indisponível • bônus extras: ${companion.skillPoints}"
            } else {
                value("Habilidades livres reais", companions.map { it.freeSkillPoints }, companion.freeSkillPoints) +
                    "\nBônus extras: ${companion.skillPoints} • Gastos: ${companion.spentSkillPoints}"
            }
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
                    // Verifica o documento salvo; falhar nesta conferência não altera o backup original.
                    val savedBytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                        ?: error("Arquivo gravado, mas não foi possível reler para conferência")
                    require(savedBytes.contentEquals(bytes)) { "O backup exportado difere do arquivo editado" }
                    val checked = EkBackup.inspect(savedBytes)
                    require(checked.map { it.path }.toSet() == saves.map { it.path }.toSet()) {
                        "A exportação não contém todos os saves esperados"
                    }
                    pendingExport = null
                    setStatus("✓ EK.bak exportado e conferido (${checked.size}/${saves.size} saves).", true)
                    AlertDialog.Builder(this).setTitle("Arquivo salvo e conferido")
                        .setMessage("A escrita foi verificada: ${checked.size} saves no EK.bak exportado.\n\nCopie o arquivo modificado para:\n$GAME_PATH\n\nDepois abra Exiled Kingdoms e escolha RESTAURAR BACKUP. Confira o nome final do arquivo no gerenciador, pois alguns aplicativos acrescentam números quando já existe EK.bak.")
                        .setPositiveButton("Entendi", null).show()
                }
            }
        } catch (e: Exception) { notifyError("Falha no arquivo: ${e.message}") }
    }
}
