package com.ekeditor.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs inside Shizuku UserService with shell UID, not the app's normal UID.
 * The service may access ONLY the known Exiled Kingdoms backup path.
 * It prepares EK.bak for the in-game restore menu; it DOES NOT edit live saves.
 * Shell permissions / SELinux differ by Android version; failures are reported.
 */
class EkFileUserService : IEkFileService.Stub() {
    private val gameDir = File("/storage/emulated/0/Android/data/net.fdgames.ek.android/files")
    private val source get() = File(gameDir, "EK.bak")

    override fun readBackup(): ByteArray {
        val f = source
        require(f.isFile) { "EK.bak não encontrado: ${f.absolutePath}" }
        require(f.length() in 1..700_000) { "EK.bak grande demais para o Shizuku; use importação manual" }
        return f.readBytes()
    }

    override fun createBackup(): String {
        val data = readBackup()
        val name = "EK-original-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + ".bak"
        val backupFile = File(gameDir, name)
        FileOutputStream(backupFile).use { f ->
            f.write(data)
            f.fd.sync()
        }
        check(backupFile.readBytes().contentEquals(data)) { "Falha ao verificar cópia de segurança" }
        return backupFile.absolutePath
    }

    override fun prepareRestoreBackup(bytes: ByteArray, expectedSha256: ByteArray): String {
        require(bytes.size in 1..700_000) { "Arquivo grande demais para o canal Shizuku; use a exportação manual" }
        require(expectedSha256.size == 32) { "Hash de origem inválido" }
        val summary = EkBackup.inspect(bytes)
        require(summary.isNotEmpty()) { "Arquivo de save não reconhecido" }
        val original = readBackup()
        require(MessageDigest.getInstance("SHA-256").digest(original).contentEquals(expectedSha256)) {
            "EK.bak mudou desde a leitura. Recarregue o arquivo antes de gravar."
        }
        val savedBackupPath = createBackup()
        val destination = source
        val temp = File.createTempFile("EK-new-", ".tmp", gameDir)
        try {
            FileOutputStream(temp).use { f ->
                f.write(bytes)
                f.fd.sync()
            }
            check(temp.readBytes().contentEquals(bytes)) { "Falha de integridade ao escrever arquivo temporário" }
            check(MessageDigest.getInstance("SHA-256").digest(readBackup()).contentEquals(expectedSha256)) {
                "EK.bak foi modificado externamente; gravação cancelada. Backup: $savedBackupPath"
            }
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(destination.readBytes().contentEquals(bytes)) { "Falha ao verificar arquivo modificado. Recupere o backup: $savedBackupPath" }
            return savedBackupPath
        } finally {
            temp.delete()
        }
    }
}
