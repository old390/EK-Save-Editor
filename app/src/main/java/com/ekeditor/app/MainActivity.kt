package com.ekeditor.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/** Native Android editor with manual import/export and optional Shizuku UserService.
 * No external storage permission is requested: documents use Android's file picker.
 */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_OPEN = 100
        private const val REQUEST_EXPORT = 101
        private const val REQUEST_SHIZUKU = 1976
        private const val GAME_PATH = "/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak"
        private val BG = Color.rgb(8, 13, 16)
        private val PANEL = Color.rgb(21, 27, 33)
        private val TEXT = Color.rgb(242, 237, 226)
        private val MUTED = Color.rgb(179, 182, 181)
        private val GOLD = Color.rgb(201, 165, 108)
        private val GREEN = Color.rgb(40, 106, 67)
    }

    private var sourceBytes: ByteArray? = null
    private var exportedBytes: ByteArray? = null
    private var slots = emptyList<EkBackup.SaveInfo>()
    private var service: IEkFileService? = null
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var sourceDetails: TextView
    private lateinit var slotSpinner: Spinner
    private lateinit var selectedGold: CheckBox
    private lateinit var goldAmount: EditText
    private lateinit var selectedTrait: CheckBox
    private lateinit var traitAmount: EditText
    private lateinit var selectedSkill: CheckBox
    private lateinit var skillAmount: EditText
    private lateinit var selectedTwoHand: CheckBox
    private lateinit var selectedOneHand: CheckBox
    private lateinit var selectedPotion: CheckBox
    private lateinit var potionAmount: EditText
    private lateinit var selectedCustom: CheckBox
    private lateinit var customId: EditText
    private lateinit var customCount: EditText

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) bindShizuku()
            else notifyError("Acesso ao Shizuku não autorizado. A importação manual continua disponível.")
        }
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IEkFileService.Stub.asInterface(binder)
            runOnUiThread { setStatus("Shizuku conectado. Lendo EK.bak…", true) }
            loadShizuku()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            runOnUiThread { setStatus("Shizuku desconectado. Use importação manual.", false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuListener)
        createInterface()
        setStatus("Selecione EK.bak ou conecte ao Shizuku.", false)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        io.shutdown()
        super.onDestroy()
    }

    private fun dp(px: Int): Int = (px * resources.displayMetrics.density + 0.5f).toInt()
    private fun margin(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    private fun shape(fill: Int, stroke: Int = GOLD): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun text(label: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = label
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun section(label: String, desc: String): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = shape(PANEL, Color.rgb(91, 75, 54))
        }
        inner.addView(text(label, 18f, GOLD, true))
        inner.addView(text(desc, 12f, MUTED), margin(3, 9))
        return inner
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(TEXT)
        setSingleLine(true)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_NUMBER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = shape(Color.rgb(11, 17, 20), Color.rgb(77, 72, 62))
    }

    private fun check(label: String): CheckBox = CheckBox(this).apply {
        text = label
        textSize = 14f
        setTextColor(TEXT)
        buttonTintList = android.content.res.ColorStateList.valueOf(GOLD)
        isChecked = false
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun action(label: String, color: Int = Color.rgb(46, 57, 65), handler: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setTextColor(TEXT)
            background = shape(color, if (color == GREEN) Color.rgb(118, 160, 95) else Color.rgb(90, 101, 104))
            setOnClickListener { handler() }
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
        body.addView(text("Editor de backups • v0.2.1", 15f, GOLD, true).apply { gravity = Gravity.CENTER }, margin(0, 13))

        val fileCard = section("📁  Arquivo de save", "Faça primeiro um backup atualizado dentro do jogo. Edite somente o EK.bak.")
        status = text("", 13f, GOLD, true)
        sourceDetails = text("Nenhum arquivo carregado", 13f, MUTED)
        fileCard.addView(status, margin(2, 7))
        fileCard.addView(sourceDetails, margin(5, 9))
        fileCard.addView(text("Arquivo do jogo: $GAME_PATH", 11f, MUTED))
        fileCard.addView(action("🔐  Conectar e abrir com Shizuku") { connectShizuku() }, margin(12, 5))
        fileCard.addView(action("📂  Importar EK.bak manualmente") { openFile() })
        body.addView(fileCard, margin(0, 12))

        val slotsCard = section("💾  Gerenciar saves", "Escolha um save ou aplique em todos os saves do arquivo.")
        slotSpinner = Spinner(this).apply {
            background = shape(Color.rgb(14, 21, 25))
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        slotsCard.addView(slotSpinner, margin(2, 4))
        body.addView(slotsCard, margin(0, 12))
        refreshSlots()

        val goldCard = section("🪙  Editar ouro", "Defina a quantidade exata de ouro do personagem.")
        selectedGold = check("Alterar ouro")
        goldAmount = field("Ouro", "9999999")
        goldCard.addView(selectedGold)
        goldCard.addView(goldAmount, margin(5, 3))
        body.addView(goldCard, margin(0, 12))

        val itemsCard = section("⚔️  Armas e consumíveis", "Itens selecionados por ID; a biblioteca pesquisável virá depois da validação no aparelho.")
        selectedTwoHand = check("Adamantite Greatsword — ID 554 (2 mãos)")
        selectedOneHand = check("Energy Blade — ID 750 (arma)")
        selectedPotion = check("Medipack — ID 5070")
        potionAmount = field("Quantidade", "999")
        itemsCard.addView(selectedTwoHand)
        itemsCard.addView(selectedOneHand)
        itemsCard.addView(selectedPotion)
        itemsCard.addView(text("Quantidade de Medipacks", 12f, MUTED), margin(5, 4))
        itemsCard.addView(potionAmount)
        selectedCustom = check("Adicionar item por ID personalizado")
        customId = field("ID do item", "")
        customCount = field("Quantidade", "1")
        itemsCard.addView(selectedCustom, margin(12, 4))
        itemsCard.addView(customId, margin(0, 5))
        itemsCard.addView(customCount, margin(0, 4))
        body.addView(itemsCard, margin(0, 12))

        val statsCard = section("💪  Pontos disponíveis", "Altera os pontos livres do personagem principal; não redistribui níveis já gastos.")
        selectedTrait = check("Alterar pontos de atributos")
        traitAmount = field("Pontos de atributos", "10")
        selectedSkill = check("Alterar pontos de habilidades")
        skillAmount = field("Pontos de habilidades", "10")
        statsCard.addView(selectedTrait)
        statsCard.addView(traitAmount, margin(0, 7))
        statsCard.addView(selectedSkill)
        statsCard.addView(skillAmount, margin(0, 3))
        body.addView(statsCard, margin(0, 12))

        val outputCard = section("🛡️  Exportar e restaurar", "1. Backup no jogo → 2. Editar → 3. Exportar EK.bak → 4. Restaurar no jogo.")
        outputCard.addView(action("✅  Exportar EK.bak para restauração", GREEN) { prepareExport(false) }, margin(3, 8))
        outputCard.addView(action("⚡  Colocar EK.bak no jogo via Shizuku", GREEN) { saveDirectly() }, margin(1, 8))
        outputCard.addView(action("🗂️  Guardar cópia do backup original") { backup() }, margin(1, 8))
        outputCard.addView(text("O Shizuku substitui apenas o arquivo EK.bak (backup de restauração), não os saves ativos. Restaure o backup no menu do jogo. Se o Shizuku falhar, exporte EK.bak e copie-o para a pasta do jogo manualmente.", 11f, MUTED), margin(6, 0))
        body.addView(outputCard, margin(0, 15))
        body.addView(text("Ferramenta independente, não afiliada aos criadores de Exiled Kingdoms.", 11f, MUTED).apply { gravity = Gravity.CENTER })
    }

    private fun setStatus(message: String, connected: Boolean) {
        status.text = message
        status.setTextColor(if (connected) Color.rgb(129, 214, 136) else GOLD)
    }

    private fun notifyError(message: String) {
        runOnUiThread {
            setStatus("Erro: $message", false)
            AlertDialog.Builder(this).setTitle("EK Save Editor").setMessage(message).setPositiveButton("Entendi", null).show()
        }
    }

    private fun loadBytes(bytes: ByteArray, origin: String) {
        try {
            val info = EkBackup.inspect(bytes)
            sourceBytes = bytes.copyOf()
            slots = info
            refreshSlots()
            val hero = info.first()
            sourceDetails.text = "$origin\n${info.size} saves • ${hero.hero} (${hero.characterClass})\nOuro: ${hero.gold} | Atributos: ${hero.traitPoints} | Habilidades: ${hero.skillPoints}"
            setStatus("✓ EK.bak reconhecido", true)
        } catch (e: Exception) { notifyError("Falha ao ler arquivo: ${e.message}") }
    }

    private fun refreshSlots() {
        val values = mutableListOf("Todos os saves")
        values += slots.map { "${it.path.substringAfterLast('/')} • ${it.hero}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, values)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        slotSpinner.adapter = adapter
    }

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    private fun writeToPicker(bytes: ByteArray, filename: String) {
        exportedBytes = bytes
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    @Deprecated("Handled for widest Android device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            when (requestCode) {
                REQUEST_OPEN -> {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Não foi possível abrir arquivo")
                    loadBytes(bytes, "Importação manual")
                }
                REQUEST_EXPORT -> {
                    val bytes = exportedBytes ?: error("Nenhum arquivo preparado")
                    contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes); it.flush() }
                        ?: error("Não foi possível criar arquivo")
                    exportedBytes = null
                    setStatus("✓ EK.bak exportado. Coloque-o na pasta de backups do jogo e restaure pelo menu de Exiled Kingdoms.", true)
                    AlertDialog.Builder(this).setTitle("Backup pronto para restauração")
                        .setMessage("O EK.bak modificado foi exportado. Preserve seu backup original em outro local; depois copie este arquivo para:\n$GAME_PATH\n\nAbra Exiled Kingdoms e use a opção de restaurar backup. A exportação sozinha não altera os saves ativos.")
                        .setPositiveButton("Entendi", null).show()
                }
            }
        } catch (e: Exception) { notifyError("Erro ao acessar arquivo: ${e.message}") }
    }

    private fun value(field: EditText, label: String, max: Long): Long {
        val raw = field.text.toString().trim()
        val result = raw.toLongOrNull() ?: error("Preencha '$label' com um número")
        require(result in 0..max) { "$label deve estar entre 0 e $max" }
        return result
    }

    private fun buildPlan(): EkBackup.EditPlan {
        val target = if (slotSpinner.selectedItemPosition == 0) {
            slots.map { it.path }.toSet()
        } else {
            setOf(slots[slotSpinner.selectedItemPosition - 1].path)
        }
        val additions = mutableListOf<EkBackup.ItemAdd>()
        if (selectedTwoHand.isChecked) additions += EkBackup.ItemAdd(554, 1)
        if (selectedOneHand.isChecked) additions += EkBackup.ItemAdd(750, 1)
        if (selectedPotion.isChecked) additions += EkBackup.ItemAdd(5070, value(potionAmount, "Medipacks", 999).toInt())
        if (selectedCustom.isChecked) additions += EkBackup.ItemAdd(
            value(customId, "ID personalizado", 999_999).toInt(),
            value(customCount, "Quantidade", 999).toInt()
        )
        return EkBackup.EditPlan(
            targetPaths = target,
            gold = if (selectedGold.isChecked) value(goldAmount, "Ouro", 999_999_999) else null,
            traitPoints = if (selectedTrait.isChecked) value(traitAmount, "Atributos", 999).toInt() else null,
            skillPoints = if (selectedSkill.isChecked) value(skillAmount, "Habilidades", 999).toInt() else null,
            items = additions
        )
    }

    private fun makeEdited(): ByteArray {
        val input = sourceBytes ?: error("Primeiro carregue EK.bak")
        return EkBackup.apply(input, buildPlan())
    }

    private fun prepareExport(isBackup: Boolean) {
        try {
            val bytes = if (isBackup) sourceBytes ?: error("Nenhum EK.bak carregado") else makeEdited()
            writeToPicker(bytes, if (isBackup) "EK_original_preservado.bak" else "EK.bak")
        } catch (e: Exception) { notifyError(e.message ?: "Falha ao preparar arquivo") }
    }

    private fun backup() {
        val remote = service
        if (remote == null) {
            prepareExport(true)
            return
        }
        io.execute {
            try {
                val path = remote.createBackup()
                runOnUiThread {
                    setStatus("✓ Backup criado no diretório do jogo", true)
                    AlertDialog.Builder(this).setTitle("Backup criado").setMessage(path).setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) { notifyError("Shizuku: ${e.message}. Também é possível exportar manualmente.") }
        }
    }

    private fun saveDirectly() {
        val remote = service ?: run {
            notifyError("Conecte o Shizuku primeiro. Caso não funcione, use Exportar EK.bak para restauração.")
            return
        }
        val bytes = try { makeEdited() } catch (e: Exception) {
            notifyError(e.message ?: "Edição inválida"); return
        }
        AlertDialog.Builder(this)
            .setTitle("Preparar restauração no jogo?")
            .setMessage("Confirme que criou um backup atualizado NO JOGO. O editor guardará uma cópia do EK.bak atual antes de colocar o arquivo modificado na pasta. Depois será NECESSÁRIO abrir Exiled Kingdoms e executar Restaurar backup. Feche o jogo antes de continuar.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar original e preparar") { _, _ ->
                io.execute {
                    try {
                        val original = sourceBytes ?: error("Backup de origem indisponível")
                        val backupPath = remote.prepareRestoreBackup(bytes, MessageDigest.getInstance("SHA-256").digest(original))
                        runOnUiThread {
                            sourceBytes = bytes
                            loadBytes(bytes, "Shizuku")
                            AlertDialog.Builder(this).setTitle("EK.bak pronto: restaure pelo jogo")
                                .setMessage("O backup modificado foi colocado na pasta de Exiled Kingdoms, mas os saves ativos AINDA NÃO foram modificados. Agora abra o jogo e escolha a opção de RESTAURAR backup.\n\nOriginal preservado em:\n$backupPath")
                                .setPositiveButton("OK", null).show()
                        }
                    } catch (e: Exception) { notifyError("Não foi possível preparar o EK.bak via Shizuku: ${e.message}. Exporte o arquivo manualmente. O backup original foi preservado quando a cópia foi possível.") }
                }
            }.show()
    }

    private fun connectShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                notifyError("Shizuku não está em execução. Inicie-o antes de conectar.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizuku()
            else if (Shizuku.shouldShowRequestPermissionRationale()) {
                notifyError("Autorize EK Save Editor dentro do aplicativo Shizuku.")
            } else Shizuku.requestPermission(REQUEST_SHIZUKU)
        } catch (e: Exception) { notifyError("Shizuku indisponível: ${e.message}") }
    }

    private fun bindShizuku() {
        try {
            setStatus("Conectando ao serviço Shizuku…", false)
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, EkFileUserService::class.java.name))
                .daemon(false)
                .processNameSuffix("ek")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, shizukuConnection)
        } catch (e: Exception) { notifyError("Falha ao conectar com Shizuku: ${e.message}") }
    }

    private fun loadShizuku() {
        val remote = service ?: return
        io.execute {
            try {
                val bytes = remote.readBackup()
                runOnUiThread { loadBytes(bytes, "Shizuku / pasta do jogo") }
            } catch (e: Exception) {
                notifyError("Sem acesso ao EK.bak via Shizuku: ${e.message}. Abra-o manualmente pelo gerenciador de arquivos.")
            }
        }
    }
}
