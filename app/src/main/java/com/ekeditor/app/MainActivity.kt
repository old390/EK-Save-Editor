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
    /** All characters remain in the same backup; only the selected character's saves can be edited. */
    private var allSaves = emptyList<EkBackup.SaveInfo>()
    private var selectedCharacterSlot = 0
    private lateinit var characterButton: Button
    private lateinit var characterSummary: TextView
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
                allSaves = parsed
                selectedCharacterSlot = savedInstanceState.getInt("selected_character_slot", 0)
                if (parsed.none { it.characterSlot == selectedCharacterSlot })
                    selectedCharacterSlot = parsed.first().characterSlot
                saves = parsed.filter { it.characterSlot == selectedCharacterSlot }
                appliedChanges = savedInstanceState.getInt("applied_changes")
                selectedItem = catalog.items.firstOrNull { it.id == savedInstanceState.getInt("item_id", -1) }
                selectedPotion = catalog.items.firstOrNull { it.id == savedInstanceState.getInt("potion_id", -1) }
                itemSelectionLabel.text = selectedItem?.name ?: "No item selected"
                potionSelectionLabel.text = selectedPotion?.name ?: "No potion selected"
                lastChange.text = "Session restored with $appliedChanges change(s)."
                updateEnabled()
                refreshPreview(true)
                setStatus("✓ Edits restored. Export the backup when you're done.", true)
            } catch (_: Exception) {
                setStatus("Select EK.bak to get started.", false)
            }
        } else setStatus("Select EK.bak to get started.", false)
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
            outState.putInt("selected_character_slot", selectedCharacterSlot)
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
        // Catch invalid input and show the user an error instead of crashing.
        try { callback() } catch (e: Exception) {
            notifyError(e.message ?: "Check the values before applying.")
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
        body.addView(text("Save editor by old390 • v0.7.0", 16f, GOLD, true).apply {
            gravity = Gravity.CENTER
        }, margin(0, 3))
        body.addView(text("github.com/old390/EK-Save-Editor", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/old390/EK-Save-Editor"))) }
        }, margin(0, 12))

        val files = section("📁  Open backup", "Create an in-game backup, then import EK.bak with the Android file picker.")
        status = text("", 13f, GOLD, true)
        sourceDetails = text("No backup imported", 13f, MUTED)
        files.addView(status, margin(2, 7))
        files.addView(sourceDetails, margin(5, 8))
        files.addView(action("📂  Select EK.bak") { importBackup() }, margin(2, 5))
        files.addView(action("🔎  Review all saves for this character") { showAllSaveAudit() }, margin(1, 6))
        body.addView(files, margin(0, 12))

        val characters = section("👤  Choose character",
            "The backup may contain multiple characters. Choose one to view their inventory, " +
                "companion, attributes, skills, and vaults. Changes only affect that character's saves.")
        characterButton = action("Select character") { showCharacterMenu() }.apply {
            isEnabled = false
        }
        characterSummary = text("Import EK.bak to find your characters.", 13f, MUTED)
        characters.addView(characterButton, margin(2, 5))
        characters.addView(characterSummary, margin(6, 3))
        body.addView(characters, margin(0, 12))

        val inventory = section("🎒  Inventory, Items & Potions", "A visual grid of actual inventory slots from a reference save. Edits are applied to all saves for the selected character.")
        itemsCurrent = text("No inventory loaded", 13f, TEXT)
        inventory.addView(itemsCurrent, margin(0, 7))
        inventoryGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inventory.addView(inventoryGrid, margin(1, 5))
        inventory.addView(text("Tap an item to add more, change its quantity, delete it, or replace it. Tap an empty slot to insert an item. If another save has a different item or a full inventory, you can review and confirm a replacement. Red item names indicate non-stackable items (one per slot).", 11f, MUTED), margin(4, 12))
        inventory.addView(text("Add an item from the catalog", 13f, GOLD, true))
        itemSelectionLabel = text("No item selected", 13f, TEXT)
        inventory.addView(itemSelectionLabel, margin(7, 3))
        inventory.addView(action("🔎  Search items by name or ID") { chooseCatalog(false) }, margin(3, 5))
        itemAmount = number("Item quantity", "1")
        inventory.addView(itemAmount)
        inventory.addView(editAction("✓  Add item to character's saves") {
            val item = selectedItem ?: return@editAction notifyError("Choose an item from the catalog")
            val amount = numberValue(itemAmount, "Quantity", 999, 1).toInt()
            addOrChooseReplacement(item, amount)
        }, margin(5, 10))
        inventory.addView(text("Add a potion or consumable", 13f, GOLD, true))
        potionSelectionLabel = text("No potion selected", 13f, TEXT)
        inventory.addView(potionSelectionLabel, margin(7, 3))
        inventory.addView(action("🧪  Choose a potion") { chooseCatalog(true) }, margin(3, 5))
        potionAmount = number("Potion quantity", "1")
        inventory.addView(potionAmount)
        inventory.addView(editAction("✓  Add potion to character's saves") {
            val item = selectedPotion ?: return@editAction notifyError("Choose a potion from the list")
            val amount = numberValue(potionAmount, "Quantity", 999, 1).toInt()
            addOrChooseReplacement(item, amount)
        }, margin(5, 9))
        inventory.addView(text("⚠ If any save has a full inventory, adding a new item may require a confirmed replacement. You'll see exactly what would be replaced in each save. Nothing is deleted automatically. Keep your original EK.bak.", 12f, MUTED))
        body.addView(inventory, margin(0, 12))

        val vaultSection = section("🏛️  Town Hall Vaults",
            "Choose from four vaults, each with 20 separate slots. " +
                "The grid shows a reference save and verifies changes across all saves for this character. " +
                "Tap items to add copies, set quantities, delete, or replace them.")
        vaultSelector = action("🗃️  Choose vault") { showVaultMenu() }
        vaultSummary = text("Import EK.bak to view vaults.", 13f, TEXT)
        vaultGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vaultUnlockButton = editAction("🔓  Unlock selected vault") {
            AlertDialog.Builder(this).setTitle("Unlock vault $selectedVault?")
                .setMessage("Unlocks this vault in the selected character's saves without spending gold. " +
                    "This edits the backup; it is not an in-game purchase. Continue?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Desbloquear") { _, _ ->
                    applyVaultChange("Vault $selectedVault unlocked",
                        VaultStorage.Change(selectedVault, VaultStorage.Action.UNLOCK))
                }.show()
        }
        vaultSection.addView(vaultSelector, margin(2, 5))
        vaultSection.addView(vaultSummary, margin(9, 6))
        vaultSection.addView(vaultGrid, margin(3, 4))
        vaultUnlockButton.visibility = View.GONE
        vaultSection.addView(vaultUnlockButton, margin(7, 4))
        vaultSection.addView(text("⚠ Vault contents may differ between saves. " +
            "Before replacing anything, you'll see the item in each save. " +
            "Red items are limited to one per slot.", 11f, MUTED), margin(5, 5))
        body.addView(vaultSection, margin(0, 12))

        val gold = section("🪙  Gold", "Enter the gold amount and tap Apply. The change appears immediately.")
        goldCurrent = text("Current gold: —", 14f, TEXT, true)
        goldInput = number("New gold amount")
        gold.addView(goldCurrent)
        gold.addView(goldInput, margin(8, 5))
        gold.addView(editAction("✓  Apply gold to this character") {
            val value = numberValue(goldInput, "Gold", 999_999_999)
            applyChange("Gold") { paths -> EkBackup.EditPlan(paths, gold = value) }
        }, margin(4, 0))
        body.addView(gold, margin(0, 12))

        val attributes = section("💪  Attributes", "Available points = points earned by leveling + bonus points − points already spent. Each Add action increases the value across this character's saves.")
        traitCurrent = text("Available points: —", 14f, TEXT, true)
        traitDeltaInput = number("Points to add", "10")
        attributes.addView(traitCurrent)
        attributes.addView(traitDeltaInput, margin(8, 5))
        attributes.addView(editAction("+  Add attribute points") {
            val amount = numberValue(traitDeltaInput, "Attributes", 999, 1).toInt()
            applyChange("+$amount attribute points") { paths -> EkBackup.EditPlan(paths, traitDelta = amount) }
        }, margin(4, 6))
        attributes.addView(editAction("↺  Reset unspent attribute points") {
            confirmReset("Reset this character's unspent attribute points in every save?") {
                applyChange("Attribute points reset") { paths -> EkBackup.EditPlan(paths, resetTraits = true) }
            }
        })
        body.addView(attributes, margin(0, 12))

        val skills = section("✨  Skills", "Available points = points earned by leveling + bonus points − costs of learned skills. Adding points does not remove learned skills.")
        skillCurrent = text("Available skill points: —", 14f, TEXT, true)
        skillDeltaInput = number("Points to add", "10")
        skills.addView(skillCurrent)
        skills.addView(skillDeltaInput, margin(8, 5))
        skills.addView(editAction("+  Add skill points") {
            val amount = numberValue(skillDeltaInput, "Skills", 999, 1).toInt()
            applyChange("+$amount skill points") { paths -> EkBackup.EditPlan(paths, skillDelta = amount) }
        }, margin(4, 6))
        skills.addView(editAction("↺  Reset unspent skill points") {
            confirmReset("Reset this character's unspent skill points in every save?") {
                applyChange("Skill points reset") { paths -> EkBackup.EditPlan(paths, resetSkills = true) }
            }
        })
        body.addView(skills, margin(0, 12))

        companionCard = section("🛡️  Active companion", "Available attribute and skill points for the active companion. Learned skills and allocated attributes are preserved.")
        companionTitle = text("Companion", 14f, GOLD, true)
        companionTraitCurrent = text("Available attribute points: —", 14f, TEXT)
        companionSkillCurrent = text("Available skill points: —", 14f, TEXT)
        companionTraitInput = number("Attribute points to add", "10")
        companionSkillInput = number("Skill points to add", "10")
        companionCard.addView(companionTitle)
        companionCard.addView(companionTraitCurrent, margin(7, 5))
        companionCard.addView(companionTraitInput)
        companionCard.addView(editAction("+  Add companion attribute points") {
            val n = numberValue(companionTraitInput, "Companion attributes", 999, 1).toInt()
            applyChange("+$n companion attribute points") { paths -> EkBackup.EditPlan(paths, companionTraitDelta = n) }
        }, margin(4, 5))
        companionCard.addView(editAction("↺  Reset companion attribute points") {
            confirmReset("Reset the active companion's unspent attribute points?") {
                applyChange("Companion attribute points reset") { paths -> EkBackup.EditPlan(paths, resetCompanionTraits = true) }
            }
        })
        companionCard.addView(companionSkillCurrent, margin(10, 5))
        companionCard.addView(companionSkillInput)
        companionCard.addView(editAction("+  Add companion skill points") {
            val n = numberValue(companionSkillInput, "Skills do companheiro", 999, 1).toInt()
            applyChange("+$n companion skill points") { paths -> EkBackup.EditPlan(paths, companionSkillDelta = n) }
        }, margin(4, 6))
        companionCard.addView(editAction("↺  Reset companion skill points") {
            confirmReset("Reset the active companion's unspent skill points in every save?") {
                applyChange("Companion skill points reset") { paths -> EkBackup.EditPlan(paths, resetCompanionSkills = true) }
            }
        })
        companionCard.visibility = View.GONE
        body.addView(companionCard, margin(0, 4))
        body.addView(text("If the selected character has an active companion, their attribute and skill points will appear here.", 12f, MUTED), margin(4, 12))

        val export = section("🛡️  Export & Restore", "Changes remain in the editor until you export EK.bak and restore it in the game. Review your changes before exporting.")
        lastChange = text("No changes made in this session.", 13f, MUTED)
        export.addView(lastChange, margin(2, 9))
        exportButton = action("💾  Export edited EK.bak", true) {
            val bytes = editedBytes ?: return@action notifyError("Import EK.bak first")
            if (appliedChanges == 0) return@action notifyError("Make at least one change before exporting")
            writeToPicker(bytes, "EK.bak")
        }
        export.addView(exportButton, margin(5, 7))
        originalButton = action("🗂️  Save a copy of the original") {
            writeToPicker(originalBytes ?: return@action notifyError("Load a backup first"), "EK_original_backup.bak")
        }
        export.addView(originalButton, margin(2, 5))
        export.addView(text("After exporting, move EK.bak to:\n$GAME_PATH\nOpen the game and select RESTORE BACKUP. Keep a copy of the original file outside the game's folder.", 12f, MUTED), margin(7, 0))
        body.addView(export, margin(0, 15))
        body.addView(text("Independent app; not affiliated with the game's developers. Item IDs: Steam community catalog (2021).", 11f, MUTED).apply { gravity = Gravity.CENTER })
        updateEnabled()
    }
    private fun selectedCharacterInfo(): EkBackup.CharacterInfo? =
        EkBackup.characters(allSaves).firstOrNull { it.slot == selectedCharacterSlot }

    private fun updateCharacterSummary() {
        val characters = EkBackup.characters(allSaves)
        val selected = selectedCharacterInfo()
        characterButton.text = if (selected == null) "👤  Select character"
            else "👤  ${selected.name} · switch character"
        characterSummary.text = if (selected == null) {
            "Import EK.bak to find your characters."
        } else {
            "${selected.name} · ${selected.characterClass} · " +
                "Level ${selected.level ?: "?"} · ${selected.savePaths.size} save(s)\n" +
                "${characters.size} character(s) found. " +
                "Changes will only affect files in saves/${selected.slot}/."
        }
    }

    private fun showCharacterMenu() {
        val available = EkBackup.characters(allSaves)
        if (available.isEmpty()) return notifyError("Import EK.bak first")
        val labels = available.map { character ->
            "${character.name} · ${character.characterClass} · Level ${character.level ?: "?"} " +
                "(${character.savePaths.size} saves)"
        }.toTypedArray()
        val current = available.indexOfFirst { it.slot == selectedCharacterSlot }
        AlertDialog.Builder(this).setTitle("Select character")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val chosen = available[which]
                selectedCharacterSlot = chosen.slot
                saves = allSaves.filter { it.characterSlot == chosen.slot }
                selectedVault = 1
                dialog.dismiss()
                refreshPreview(true)
                setStatus("✓ ${chosen.name} selected. Only their ${saves.size} saves will be edited.", true)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showAllSaveAudit() {
        try {
            val currentBytes = editedBytes ?: return notifyError("Import EK.bak first")
            val updated = EkBackup.inspect(currentBytes).filter {
                it.characterSlot == selectedCharacterSlot
            }
            val originals = originalBytes?.let {
                EkBackup.inspect(it).associateBy { info -> info.path }
            } ?: emptyMap()
            val details = updated.joinToString("\n\n") { info ->
                val previous = originals[info.path]
                val shortPath = info.path.substringAfter("saves/")
                fun format(old: Any?, now: Any) = if (old == null || old == now) "$now" else "$old → $now"
                "• $shortPath\n" +
                    "Gold: ${format(previous?.gold, info.gold)}\n" +
                    "Available attribute points: ${format(previous?.freeTraitPoints, info.freeTraitPoints ?: "unavailable")}\n" +
                    "Extra attribute points: ${format(previous?.traitPoints, info.traitPoints)}\n" +
                    "Spent attribute points: ${info.spentTraitPoints ?: "?"}\n" +
                    "Available skill points: ${format(previous?.freeSkillPoints, info.freeSkillPoints ?: "unavailable")}\n" +
                    "Bonus skill points: ${format(previous?.skillPoints, info.skillPoints)}\n" +
                    "Spent skill points: ${info.spentSkillPoints ?: "?"}\n" +
                    "Companion: ${info.companion?.name ?: "none"}" +
                    (info.companion?.let { c ->
                        "\n  Available attributes: ${c.freeTraitPoints ?: "unavailable"}" +
                        " | Available skills: ${c.freeSkillPoints ?: "unavailable"}"
                    } ?: "")
            }
            AlertDialog.Builder(this)
                .setTitle("Review for ${selectedCharacterInfo()?.name ?: "character"}: ${updated.size} saves")
                .setMessage("These values are stored in the editor's in-memory backup. Export the file and restore it in the game to apply them.\n\n$details")
                .setPositiveButton("OK", null).show()
        } catch (e: Exception) { notifyError("Review failed: ${e.message}") }
    }

    private fun vaultData(): Map<String, List<VaultStorage.VaultInfo>> {
        val data = editedBytes ?: return emptyMap()
        val paths = saves.map { it.path }.toSet()
        return EkBackup.inspectVaults(data).filterKeys { it in paths }
    }

    private fun selectedVaultData(): Map<String, VaultStorage.VaultInfo> =
        vaultData().mapNotNull { (path, vaults) ->
            vaults.firstOrNull { it.number == selectedVault }?.let { path to it }
        }.toMap()

    private fun showVaultMenu() {
        if (editedBytes == null) return notifyError("Import EK.bak first")
        val bySave = vaultData()
        val labels = (1..4).map { number ->
            val vaults = bySave.values.mapNotNull { v -> v.firstOrNull { it.number == number } }
            val state = when {
                vaults.isEmpty() -> "not found"
                vaults.all { it.unlocked } -> "unlocked"
                vaults.none { it.unlocked } -> "locked"
                else -> "different across saves"
            }
            "Vault $number · $state"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose vault da Prefeitura")
            .setSingleChoiceItems(labels, selectedVault - 1) { dialog, choice ->
                selectedVault = choice + 1
                dialog.dismiss()
                refreshVaultPreview()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refreshVaultPreview() {
        vaultGrid.removeAllViews()
        vaultSelector.text = "🗃️  Choose vault · Vault $selectedVault"
        val bySave = try { selectedVaultData() } catch (e: Exception) {
            vaultSummary.text = "Vault unavailable: ${e.message}"
            vaultUnlockButton.visibility = View.GONE
            return
        }
        if (bySave.isEmpty()) {
            vaultSummary.text = "No vaults were found in this backup."
            vaultUnlockButton.visibility = View.GONE
            return
        }
        val preview = previewSave()?.path?.let { bySave[it] } ?: bySave.values.first()
        val allUnlocked = bySave.size == saves.size && bySave.values.all { it.unlocked }
        val minimumFree = bySave.values.minOf { it.free }
        vaultSummary.text =
            "Vault $selectedVault · ${if (allUnlocked) "Unlocked" else "Locked in some saves"}\n" +
            "Occupied: ${preview.occupied}/20 · Free in this save: ${preview.free} · " +
            "Minimum free slots across this character's saves: $minimumFree"
        vaultUnlockButton.visibility = if (allUnlocked) View.GONE else View.VISIBLE
        if (!allUnlocked) {
            vaultGrid.addView(text("Unlock this vault in every save for the selected character before editing its contents.", 12f, MUTED))
            return
        }
        val slots = preview.slots
        for (block in slots.withIndex().toList().chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((index, slot) in block) {
                val occupied = slot.id != 0
                val label = if (occupied) catalog.name(slot.id) else "Empty"
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
                    contentDescription = "Vault $selectedVault, slot ${index + 1}: $label"
                    setOnClickListener {
                        val vaultNumber = selectedVault
                        if (occupied) showVaultSlotActions(vaultNumber, index, slot, label)
                        else chooseCatalog(false) { item ->
                            askVaultItemQuantity("Add ${item.name}", item) { count ->
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
        val field = number("Quantity", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(7), dp(16), dp(7))
            addView(text(if (!ItemRules.isStackable(item.id))
                "Non-stackable item: each copy requires its own slot."
            else "Maximum stack size: 999.", 12f,
                if (!ItemRules.isStackable(item.id)) NON_STACKABLE_RED else MUTED))
            addView(field, margin(4, 5))
        }
        AlertDialog.Builder(this).setTitle(heading)
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                try {
                    val max = if (limitToOne) ItemRules.maxPerSlot(item.id) else 999
                    val amount = numberValue(field, "Vault quantity", max.toLong(), 1).toInt()
                    onReady(amount)
                } catch (e: Exception) { notifyError(e.message ?: "Invalid quantity") }
            }.show()
    }

    private fun addIntoVaultSpace(
        vault: Int, index: Int, item: ItemCatalog.Item, count: Int
    ) {
        val bySave = selectedVaultData()
        if (bySave.size != saves.size) return notifyError("This vault is missing from one or more saves for the selected character")
        val exists = bySave.values.any { it.slots[index].id != 0 }
        if (exists) {
            confirmVaultReplacement(vault, index, item, count)
        } else {
            applyVaultChange("Vault $vault: ${item.name} ×$count",
                VaultStorage.Change(vault, VaultStorage.Action.INSERT,
                    index = index, itemId = item.id, amount = count))
        }
    }

    private fun confirmVaultReplacement(
        vault: Int, index: Int, item: ItemCatalog.Item, amount: Int
    ) {
        if (!ItemRules.isStackable(item.id) && amount != 1) {
            return notifyError("Replacing a slot allows only one copy of this item. " +
                "Use empty slots to add more copies.")
        }
        val expected = selectedVaultData().mapValues { (_, vaultInfo) -> vaultInfo.slots[index] }
        if (expected.keys != saves.map { it.path }.toSet()) {
            return notifyError("Vault $vault is missing from one or more selected saves")
        }
        val review = expected.entries.joinToString("\n") { (path, slot) ->
            val name = if (slot.id == 0) "(empty)"
                else "${catalog.name(slot.id)} ×${slot.count}"
            "• ${path.substringAfterLast('/')}: $name"
        }
        AlertDialog.Builder(this)
            .setTitle("Confirm replacement in Vault $vault")
            .setMessage("Slot ${index + 1} will receive ${item.name} ×$amount in all of this character's saves.\n\n" +
                "Items that will be replaced:\n$review\n\n" +
                "Only this slot will be changed in each save.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm replacement") { _, _ ->
                applyVaultChange("Vault $vault: replacement in slot ${index + 1}",
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
        if (occurrence < 0) return notifyError("The vault slot has changed. Select it again.")
        val stackable = ItemRules.isStackable(slot.id)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        layout.addView(text("Vault $vault · Slot ${index + 1} · " +
            if (stackable) "${slot.count} unit(s)" else "Non-stackable item", 12f, MUTED))
        lateinit var dialog: AlertDialog
        fun option(name: String, handler: () -> Unit) {
            layout.addView(action(name) { dialog.dismiss(); handler() }, margin(2, 4))
        }
        val item = ItemCatalog.Item(slot.id, label, "")
        option(if (stackable) "＋  Add unidades" else "＋  Duplicate copy") {
            askVaultItemQuantity("Add $label", item) { n ->
                applyVaultChange("Vault $vault: +$n $label",
                    VaultStorage.Change(vault, VaultStorage.Action.ADD,
                        index = index, itemId = slot.id, amount = n, occurrence = occurrence))
            }
        }
        if (stackable) option("✎  Set quantity") {
            askVaultItemQuantity("Set quantity of $label", item) { n ->
                applyVaultChange("Vault $vault: $label ×$n",
                    VaultStorage.Change(vault, VaultStorage.Action.SET,
                        index = index, itemId = slot.id, amount = n, occurrence = occurrence))
            }
        }
        option("🗑  Delete copy / stack") {
            AlertDialog.Builder(this)
                .setTitle("Delete $label from Vault $vault?")
                .setMessage("Deletes the selected occurrence of this item in each save where it exists. " +
                    "Other copies and vaults will remain unchanged.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    applyVaultChange("Vault $vault: deleted $label",
                        VaultStorage.Change(vault, VaultStorage.Action.DELETE,
                            index = index, itemId = slot.id, occurrence = occurrence))
                }.show()
        }
        option("↔  Replace with another item") {
            chooseCatalog(false) { replacement ->
                askVaultItemQuantity("Replace with ${replacement.name}", replacement, true) { n ->
                    confirmVaultReplacement(vault, index, replacement, n)
                }
            }
        }
        dialog = AlertDialog.Builder(this).setTitle(label)
            .setView(layout).setNegativeButton("Close", null).create()
        dialog.show()
    }

    /** Visual display of actual item slots using generic category symbols. */
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
                val label = if (occupied) catalog.name(slot.id) else "Empty"
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
                    contentDescription = "Slot ${index + 1}: $label. Tap for options."
                    setOnClickListener {
                        if (occupied) showInventorySlotActions(index, slot, label)
                        else chooseCatalog(false) { item -> showInsertIntoSlot(index, item) }
                    }
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                })
            }
            // Keep the last row evenly spaced when the item count is not divisible by four.
            repeat(4 - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, dp(96), 1f)) }
            inventoryGrid.addView(row, margin(0, 0))
        }
    }

    /**
     * Se algum save estiver cheio E sem o item escolhido, a inserção sem
     * perda é impossível. Exibe os slots disponíveis para substituição.
     */
    private fun addOrChooseReplacement(item: ItemCatalog.Item, amount: Int) {
        val blocked = saves.filter { item.id !in it.inventory && it.freeSlots == 0 }
        if (blocked.isEmpty()) {
            applyChange("${item.name} × $amount") { paths ->
                EkBackup.EditPlan(paths, items = listOf(EkBackup.ItemAdd(item.id, amount)))
            }
            return
        }
        AlertDialog.Builder(this).setTitle("Inventory full in ${blocked.size} save(s)")
            .setMessage("The item does not exist e não há slot disponível em: " +
                blocked.joinToString(", ") { it.path.substringAfterLast("/") } +
                ".\n\nYou can choose a grid slot to replace in all of this character's saves. " +
                "The editor will show which items would be removed before you confirm.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose slot") { _, _ ->
                chooseReplacementSlot(item, amount)
            }.show()
    }

    private fun chooseReplacementSlot(item: ItemCatalog.Item, amount: Int) {
        val ref = previewSave() ?: return notifyError("Import EK.bak first")
        val list = ref.inventorySlots.mapIndexed { idx, slot ->
            "Slot ${idx + 1}: " + if (slot.id == 0) "(empty)" else
                "${catalog.name(slot.id)} ×${slot.count}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose slot for ${item.name}")
            .setItems(list) { _, index -> confirmSlotReplacement(index, item, amount) }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * Atualiza a célula escolhida em todos os saves mesmo com inventários
     * diferentes, somente após confirmação com resumo por arquivo.
     */
    private fun confirmSlotReplacement(index: Int, item: ItemCatalog.Item, amount: Int) {
        val all = saves.associate { info ->
            info.path to (info.inventorySlots.getOrNull(index)
                ?: return notifyError("Slot ${index + 1} does not exist in ${info.path}"))
        }
        val overwritten = all.filterValues { it.id != 0 && it.id != item.id }
        val preview = all.entries.joinToString("\n") { (path, slot) ->
            val name = if (slot.id == 0) "(empty)"
                else "${catalog.name(slot.id)} ×${slot.count}"
            "• ${path.substringAfterLast('/')}: $name"
        }
        val warning = if (overwritten.isNotEmpty()) {
            "\n\nWARNING: ${overwritten.size} save(s) will lose the item currently in that slot."
        } else "\n\nNo other item ID will be removed."
        AlertDialog.Builder(this)
            .setTitle("Confirm slot ${index + 1}")
            .setMessage("The item ${item.name} ×$amount will be placed in slot ${index + 1} " +
                "in ALL of this character's saves.\n\nCurrent contents:\n$preview$warning" +
                "\n\nThe original backup will not be modified.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Confirm replacement") { _, _ ->
                applyChange("${item.name} ×$amount in slot ${index + 1}") { paths ->
                    EkBackup.EditPlan(paths, slotReplacements = listOf(
                        EkBackup.SlotReplace(index, item.id, amount, all)
                    ))
                }
            }.show()
    }

    /** Abre o catálogo a partir de uma célula vazia da grade. */
    private fun showInsertIntoSlot(index: Int, item: ItemCatalog.Item) {
        val input = number("Quantity", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(text(item.name, 13f, GOLD, true))
            addView(input, margin(7, 2))
        }
        AlertDialog.Builder(this).setTitle("Add item to slot ${index + 1}")
            .setMessage("If this slot is occupied in any save, you will be asked to confirm the replacement.")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ ->
                try {
                    val amount = numberValue(input, "New item quantity", 999, 1).toInt()
                    val occupiedSomewhere = saves.any { info ->
                        info.inventorySlots.getOrNull(index)?.id != 0
                    }
                    if (occupiedSomewhere) {
                        confirmSlotReplacement(index, item, amount)
                    } else {
                        applyChange("Added ${item.name} ×$amount in slot ${index + 1}") { paths ->
                            EkBackup.EditPlan(paths, slotInserts = listOf(
                                EkBackup.SlotInsert(index, item.id, amount)))
                        }
                    }
                } catch (e: Exception) { notifyError(e.message ?: "Could not add the item") }
            }.show()
    }

    /**
     * Do not mix AlertDialog.setMessage with setItems: some Android devices
     * hide the options. Use four actual buttons in a custom dialog.
     */
    private fun showInventorySlotActions(index: Int, slot: EkBackup.InventorySlot, label: String) {
        val stackable = ItemRules.isStackable(slot.id)
        val selectedSlots = previewSave()?.inventorySlots.orEmpty()
        // Copy index among identical item IDs: first = 0, second = 1, and so on.
        // This lets the grid modify a specific copy across the character's saves,
        // even when inventory layouts differ.
        val occurrence = selectedSlots.take(index + 1).count { it.id == slot.id } - 1
        if (occurrence < 0 || selectedSlots.getOrNull(index)?.id != slot.id) {
            return notifyError("The inventory grid has changed. Tap the updated item again.")
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(7), dp(18), dp(7))
        }
        val status = if (stackable)
            "Slot ${index + 1} · ${slot.count} unit(s)\nStackable: up to 999 per slot."
        else
            "Slot ${index + 1} · Non-stackable item (1 per slot).\nNew copies use separate slots."
        layout.addView(text(status, 13f, MUTED), margin(0, 9))
        lateinit var dialog: AlertDialog
        fun option(labelText: String, handler: () -> Unit) {
            layout.addView(action(labelText) {
                dialog.dismiss()
                handler()
            }, margin(2, 4))
        }
        option(if (stackable) "＋  Add more units" else "＋  Add another copy") {
            val unitary = !stackable
            val input = number(if (unitary) "How many copies?" else "How many units?", "1")
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), dp(8))
                addView(text(if (unitary) "Each copy will use a separate empty slot in every save."
                             else "Units will be added to the stack in each save.", 12f, MUTED))
                addView(input, margin(4, 3))
            }
            AlertDialog.Builder(this)
                .setTitle(if (unitary) "Add copies of $label" else "Add units of $label")
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply") { _, _ ->
                    try {
                        val amount = numberValue(input, "Quantity", 999, 1).toInt()
                        applyChange("+$amount ${if (unitary) "copy/copies" else "unit(s)"} of $label") { paths ->
                            EkBackup.EditPlan(paths, slotEdits = listOf(
                                EkBackup.SlotEdit(index, slot.id, slot.count, amount,
                                    addQuantity = true, occurrence = occurrence)))
                        }
                    } catch (e: Exception) { notifyError(e.message ?: "Invalid quantity") }
                }.show()
        }
        if (stackable) option("✎  Set quantity do stack") {
            val input = number("Desired quantity", slot.count.toString())
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(7), dp(18), dp(7))
                addView(text("This value will replace the item's stack size in each save.", 12f, MUTED))
                addView(input, margin(4, 3))
            }
            AlertDialog.Builder(this).setTitle("Set quantity of $label")
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply") { _, _ ->
                    try {
                        val amount = numberValue(input, "Quantity", 999, 1).toInt()
                        applyChange("Quantity of $label = $amount") { paths ->
                            EkBackup.EditPlan(paths, slotEdits = listOf(
                                EkBackup.SlotEdit(index, slot.id, slot.count, amount,
                                    occurrence = occurrence)))
                        }
                    } catch (e: Exception) { notifyError(e.message ?: "Invalid quantity") }
                }.show()
        }
        option("🗑  Delete item") {
            AlertDialog.Builder(this).setTitle("Delete $label?")
                .setMessage("One occurrence of this ID will be removed from each save where it exists. " +
                    "No other item will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    applyChange("Deleted $label") { paths ->
                        EkBackup.EditPlan(paths, slotEdits = listOf(
                            EkBackup.SlotEdit(index, slot.id, slot.count, 0,
                                occurrence = occurrence)))
                    }
                }.show()
        }
        option("↔  Replace with another item") {
            chooseCatalog(false) { item -> showReplacementQuantity(index, item) }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setView(layout)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun showReplacementQuantity(index: Int, item: ItemCatalog.Item) {
        val input = number("Quantity", "1")
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(text(item.name, 13f, GOLD, true))
            addView(input, margin(5, 2))
        }
        AlertDialog.Builder(this).setTitle("Replace slot ${index + 1}")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                try {
                    val amount = numberValue(input, "Quantity", 999, 1).toInt()
                    confirmSlotReplacement(index, item, amount)
                } catch (e: Exception) { notifyError(e.message ?: "Invalid quantity") }
            }.show()
    }

    private fun confirmReset(message: String, callback: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Confirm reset")
            .setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ -> callback() }.show()
    }
    private fun chooseCatalog(onlyPotions: Boolean, onPicked: ((ItemCatalog.Item) -> Unit)? = null) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(this).apply {
            hint = "Search by name or ID"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val results = ListView(this)
        dialogView.addView(search)
        dialogView.addView(results, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (onlyPotions) "Choose potion" else "Item catalog")
            .setView(dialogView).setNegativeButton("Cancel", null).create()
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
        setStatus("Error: $message", false)
        AlertDialog.Builder(this).setTitle("EK Save Editor")
            .setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun numberValue(field: EditText, label: String, max: Long, min: Long = 0): Long {
        val raw = field.text.toString()
        if (raw.isBlank()) field.error = "Enter a value before applying"
        return EkBackup.parseNumberInput(raw, label, max, min)
    }
    private fun allPaths(): Set<String> {
        require(saves.isNotEmpty()) { "Import EK.bak first" }
        return saves.map { it.path }.toSet()
    }
    private fun applyChange(label: String, plan: (Set<String>) -> EkBackup.EditPlan) {
        try {
            val source = editedBytes ?: error("Import EK.bak first")
            val paths = allPaths()
            val requested = plan(paths)
            val before = saves.associateBy { it.path }
            val result = EkBackup.apply(source, requested) // sem alterações parciais
            val readback = EkBackup.inspect(result)
            val selectedReadback = readback.filter { it.characterSlot == selectedCharacterSlot }
            require(selectedReadback.map { it.path }.toSet() == paths) {
                "The selected character's saves do not match"
            }
            val first = selectedReadback.first()
            val previous = before[first.path]
            val traitReport = when {
                requested.traitDelta != null || requested.resetTraits -> {
                    if (previous?.freeTraitPoints != null && first.freeTraitPoints != null)
                        " Attributes: ${previous.freeTraitPoints} → ${first.freeTraitPoints}."
                    else " Review the bonus attribute points across this character's saves."
                }
                requested.skillDelta != null || requested.resetSkills -> {
                    if (previous?.freeSkillPoints != null && first.freeSkillPoints != null)
                        " Skills: ${previous.freeSkillPoints} → ${first.freeSkillPoints}."
                    else " Review the bonus skill points across this character's saves."
                }
                else -> ""
            }
            editedBytes = result
            allSaves = readback
            saves = selectedReadback
            appliedChanges++
            lastChange.text = "✓ $label verified in ${paths.size}/${paths.size} saves.$traitReport " +
                "$appliedChanges in-memory change(s). Export EK.bak to save them."
            lastChange.setTextColor(Color.rgb(129, 214, 136))
            refreshPreview(false)
            updateEnabled()
            setStatus("✓ $label applied to all selected saves. Review the result below.", true)
        } catch (e: Exception) { notifyError(e.message ?: "Could not apply the change") }
    }
    private fun previewSave(): EkBackup.SaveInfo? = saves.firstOrNull { it.path.endsWith("/auto.sav") }
        ?: saves.firstOrNull { it.path.endsWith("/game.sav") }
        ?: saves.firstOrNull()
    private fun refreshPreview(fillGold: Boolean) {
        val representative = previewSave()
        updateCharacterSummary()
        if (representative == null) {
            goldCurrent.text = "Current gold: —"
            traitCurrent.text = "Available points: —"
            skillCurrent.text = "Available skill points: —"
            itemsCurrent.text = "No inventory loaded"
            inventoryGrid.removeAllViews()
            vaultGrid.removeAllViews()
            vaultSummary.text = "Import EK.bak to view vaults."
            vaultUnlockButton.visibility = View.GONE
            companionCard.visibility = View.GONE
            return
        }
        fun <T> value(label: String, values: List<T>, reference: T): String {
            return "$label: $reference${if (values.distinct().size > 1) " (other saves differ)" else ""}"
        }
        goldCurrent.text = value("Current gold", saves.map { it.gold }, representative.gold)
        traitCurrent.text = if (representative.freeTraitPoints == null) {
            "Available points: cannot be calculated (missing level or spent-point history).\n" +
                "Recorded extra points: ${representative.traitPoints}"
        } else {
            value("Available points", saves.map { it.freeTraitPoints }, representative.freeTraitPoints) +
                "\nBonus points in backup: ${representative.traitPoints}" +
                " • Level ${representative.characterLevel}" +
                " • Spent: ${representative.spentTraitPoints}"
        }
        skillCurrent.text = if (representative.freeSkillPoints == null) {
            "Available points: cannot be calculated (unknown skill cost).\n" +
                "Bonus skill points in backup: ${representative.skillPoints}"
        } else {
            value("Available points", saves.map { it.freeSkillPoints }, representative.freeSkillPoints) +
                "\nBonus skill points: ${representative.skillPoints}" +
                " • Spent: ${representative.spentSkillPoints}"
        }
        if (fillGold) goldInput.setText(representative.gold.toString())
        val minimumFree = saves.minOf { it.freeSlots }
        itemsCurrent.text = "Reference inventory: ${representative.hero}\n" +
            "${representative.inventorySlots.size} slots • ${representative.freeSlots} free in this save\n" +
            "Minimum free slots across selected saves: $minimumFree"
        refreshInventoryGrid(representative)
        refreshVaultPreview()
        val companions = saves.mapNotNull { it.companion }
        if (companions.isNotEmpty()) {
            val companion = representative.companion ?: companions.first()
            companionTitle.text = companion.name
            companionTraitCurrent.text = if (companion.freeTraitPoints == null) {
                "Available attributes: unavailable • extra bonus points: ${companion.traitPoints}"
            } else {
                value("Available attribute points", companions.map { it.freeTraitPoints }, companion.freeTraitPoints) +
                    "\nBonus points: ${companion.traitPoints} • Spent: ${companion.spentTraitPoints}"
            }
            companionSkillCurrent.text = if (companion.freeSkillPoints == null) {
                "Available skills: unavailable • extra bonus points: ${companion.skillPoints}"
            } else {
                value("Available skill points", companions.map { it.freeSkillPoints }, companion.freeSkillPoints) +
                    "\nBonus points: ${companion.skillPoints} • Spent: ${companion.spentSkillPoints}"
            }
            companionCard.visibility = View.VISIBLE
        } else companionCard.visibility = View.GONE
        sourceDetails.text = "${saves.size} saves for the selected character · " +
            "${EkBackup.characters(allSaves).size} character(s) in EK.bak. " +
            if (appliedChanges == 0) "No edits applied." else "$appliedChanges edit(s) applied to the backup."
    }
    private fun updateEnabled() {
        val present = editedBytes != null
        characterButton.isEnabled = present
        editButtons.forEach { it.isEnabled = present }
        originalButton.isEnabled = originalBytes != null
        exportButton.isEnabled = present && appliedChanges > 0
    }
    private fun loadBytes(data: ByteArray) {
        allSaves = EkBackup.inspect(data)
        selectedCharacterSlot = EkBackup.characters(allSaves).first().slot
        saves = allSaves.filter { it.characterSlot == selectedCharacterSlot }
        originalBytes = data.copyOf()
        editedBytes = data.copyOf()
        appliedChanges = 0
        lastChange.text = "No changes made in this session."
        lastChange.setTextColor(MUTED)
        refreshPreview(true)
        updateEnabled()
        setStatus("✓ EK.bak loaded. Edits will only affect the selected character's saves.", true)
    }
    private fun importBackup() {
        fun choose() {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, REQUEST_OPEN)
        }
        if (appliedChanges == 0) choose()
        else AlertDialog.Builder(this).setTitle("Discard unsaved changes?")
            .setMessage("You have edits that have not been exported. Export the backup before opening another file if you want to keep them.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open another backup") { _, _ -> choose() }.show()
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
                        ?: error("Could not open EK.bak")
                    loadBytes(bytes)
                }
                REQUEST_EXPORT -> {
                    val bytes = pendingExport ?: error("Export canceled")
                    contentResolver.openOutputStream(data.data!!, "w")?.use { it.write(bytes); it.flush() }
                        ?: error("Could not write the backup")
                    // Verify the exported document; a verification failure does not affect the original backup.
                    val savedBytes = contentResolver.openInputStream(data.data!!)?.use { it.readBytes() }
                        ?: error("File saved but could not be read back for verification")
                    require(savedBytes.contentEquals(bytes)) { "The exported backup differs from the edited data" }
                    val checked = EkBackup.inspect(savedBytes)
                    require(checked.map { it.path }.toSet() == saves.map { it.path }.toSet()) {
                        "The export does not contain all expected saves"
                    }
                    pendingExport = null
                    setStatus("✓ EK.bak exported and verified (${checked.size}/${saves.size} saves).", true)
                    AlertDialog.Builder(this).setTitle("File saved and verified")
                        .setMessage("Export verified: ${checked.size} saves in the exported EK.bak.\n\nCopy the edited file to:\n$GAME_PATH\n\nThen open Exiled Kingdoms and select RESTORE BACKUP. Check the final filename in your file manager. Some apps append numbers if EK.bak already exists.")
                        .setPositiveButton("OK", null).show()
                }
            }
        } catch (e: Exception) { notifyError("File operation failed: ${e.message}") }
    }
}
