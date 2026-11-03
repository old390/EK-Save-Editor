package com.ekeditor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
    private lateinit var inventoryGrid: LinearLayout
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
        body.addView(text("Editor de saves feito por old390 • v0.5.0", 16f, GOLD, true).apply {
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
        inventory.addView(text("Ícones simbólicos de categorias. Toque em um item ocupado para alterar a quantidade ou excluí-lo de todos os saves. A edição será bloqueada se houver risco de modificar um item diferente.", 11f, MUTED), margin(4, 12))
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

    /** Representação visual fiel às posições reais, com símbolos genéricos por tipo de item. */
    private fun refreshInventoryGrid(reference: EkBackup.SaveInfo) {
        inventoryGrid.removeAllViews()
        val slots = reference.inventorySlots
        fun iconFor(name: String, occupied: Boolean): String {
            if (!occupied) return "·"
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
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(3), dp(4), dp(3), dp(4))
                    background = shape(if (occupied) Color.rgb(43, 50, 57) else Color.rgb(15, 22, 27),
                        if (occupied) Color.rgb(150, 117, 73) else Color.rgb(48, 58, 62))
                    contentDescription = "Espaço ${index + 1}: $label"
                    if (occupied) setOnClickListener {
                        showInventorySlotActions(index, slot, label)
                    }
                }
                tile.addView(text(iconFor(label, occupied), 26f, if (occupied) TEXT else MUTED).apply {
                    gravity = Gravity.CENTER
                })
                tile.addView(text(label, 10f, if (occupied) TEXT else MUTED).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
                if (occupied && slot.count > 1) tile.addView(text("×${slot.count}", 10f, GOLD, true).apply {
                    gravity = Gravity.CENTER
                })
                row.addView(tile, LinearLayout.LayoutParams(0, dp(96), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            // Mantém a largura uniforme na última linha se houver slots parciais.
            repeat(4 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f)) }
            inventoryGrid.addView(row, margin(0, 0))
        }
    }

    private fun showInventorySlotActions(index: Int, slot: EkBackup.InventorySlot, label: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage("Espaço ${index + 1}. Quantidade atual: ${slot.count}.\n" +
                "A edição será aplicada ao mesmo item em todos os saves, mesmo se ele estiver em outra posição.")
            .setItems(arrayOf("Alterar quantidade", "Excluir item")) { _, choice ->
                when (choice) {
                    0 -> {
                        val input = number("Nova quantidade", slot.count.toString())
                        val wrapper = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(18), dp(8), dp(18), dp(8))
                            addView(text(label, 13f, MUTED))
                            addView(input, margin(5, 2))
                        }
                        AlertDialog.Builder(this).setTitle("Alterar quantidade")
                            .setMessage("O número informado substituirá a quantidade deste stack em todos os saves.")
                            .setView(wrapper)
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Aplicar") { _, _ ->
                                try {
                                    val amount = numberValue(input, "Quantidade do item", 999, 1).toInt()
                                    applyChange("Quantidade de $label → $amount") { paths ->
                                        EkBackup.EditPlan(paths, slotEdits = listOf(
                                            EkBackup.SlotEdit(index, slot.id, slot.count, amount)))
                                    }
                                } catch (e: Exception) {
                                    notifyError(e.message ?: "Quantidade inválida")
                                }
                            }.show()
                    }
                    1 -> {
                        AlertDialog.Builder(this).setTitle("Excluir $label?")
                            .setMessage("Será removido um stack desse item de cada save. " +
                                "O programa verifica o ID antes de excluir e cancela tudo se não localizar o item.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Excluir") { _, _ ->
                                applyChange("Exclusão de $label") { paths ->
                                    EkBackup.EditPlan(paths, slotEdits = listOf(
                                        EkBackup.SlotEdit(index, slot.id, slot.count, 0)))
                                }
                            }.show()
                    }
                }
            }.setNegativeButton("Fechar", null).show()
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
