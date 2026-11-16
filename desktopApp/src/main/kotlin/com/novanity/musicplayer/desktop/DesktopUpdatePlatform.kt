package com.novanity.musicplayer.desktop

import com.novanity.musicplayer.update.InstallOutcome
import com.novanity.musicplayer.update.UpdateAsset
import com.novanity.musicplayer.update.UpdateConfig
import com.novanity.musicplayer.update.UpdateInfo
import com.novanity.musicplayer.update.UpdatePlatform
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Windows updates: download the new MSI, verify it, then hand over to a tiny script that waits for
 * this app to exit, runs the MSI as an in-place upgrade (same UpgradeCode, progress bar only) and
 * starts the new version.
 */
class DesktopUpdatePlatform(private val exitApp: () -> Unit) : UpdatePlatform {
    override val platformKey = "windows"
    override val currentVersion: String = System.getProperty("app.version")?.takeIf { it.isNotBlank() } ?: "0.0.0"
    override val currentVersionCode: Int = 0

    override suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) { UpdateCore.fetch(url) }

    override suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val dir = File(DesktopPaths.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val dest = File(dir, File(asset.file).name)
        UpdateCore.download(asset.url, dest, asset.size, onProgress)
        dest.absolutePath
    }

    override fun sha256(path: String): String = UpdateCore.sha256(File(path))

    override fun verifySignature(data: ByteArray, signatureBase64: String): Boolean =
        UpdateCore.verifyEd25519(UpdateConfig.PUBLIC_KEY, data, signatureBase64)

    override suspend fun install(path: String, info: UpdateInfo): InstallOutcome = withContext(Dispatchers.IO) {
        val msi = File(path)
        require(msi.extension.equals("msi", ignoreCase = true)) { "Unexpected installer type" }
        if (!DesktopPaths.isWindows) {
            // Dev builds on other systems: just show the file.
            runCatching { Desktop.getDesktop().open(msi.parentFile) }
            return@withContext InstallOutcome.STARTED
        }
        val app = System.getProperty("jpackage.app-path") // the installed VanityPlayer.exe
        val pid = ProcessHandle.current().pid()
        val script = File(msi.parentFile, "install-update.cmd")
        script.writeText(
            buildString {
                appendLine("@echo off")
                // Wait for this process to exit so its files can be replaced.
                appendLine(":wait")
                // (ping as a 1 s pause: "timeout" refuses to run without a console window.)
                appendLine("tasklist /FI \"PID eq $pid\" 2>nul | find \"$pid\" >nul && (ping -n 2 127.0.0.1 >nul & goto wait)")
                // Upgrades in place: same folder, so shortcuts, pins and settings stay as they are.
                appendLine("start \"\" /wait msiexec /i \"${msi.absolutePath}\" /passive /norestart")
                // The installer puts a desktop shortcut back every time. Only a first install
                // should make one: remove any Vanity Player shortcut that wasn't there before.
                desktopFolder()?.let { desktop ->
                    val kept = vanityShortcuts(desktop)
                    val keep = kept.joinToString("") { " if /I not \"%%~nxf\"==\"${cmd(it)}\"" }
                    appendLine("for %%f in (\"${cmd(desktop.absolutePath)}\\*Vanity*Player*.lnk\") do$keep del /q \"%%f\"")
                }
                // Relaunch the updated app; never pop up an error if it has moved.
                val fallback = "%LOCALAPPDATA%\\VanityPlayer\\VanityPlayer.exe"
                // (No parenthesised blocks: a ")" in the user's folder name would break them.)
                if (app != null) appendLine("if exist \"$app\" start \"\" \"$app\" & goto :eof")
                appendLine("if exist \"$fallback\" start \"\" \"$fallback\"")
            }
        )
        // Run the script directly, with its output discarded. The app has no console, so the
        // script gets none either: no command window appears (launching it through "start" opened
        // one that stayed on screen).
        ProcessBuilder("cmd.exe", "/c", script.absolutePath)
            .directory(msi.parentFile)
            .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        exitApp()
        InstallOutcome.STARTED
    }

    /** The user's desktop folder (wherever Windows keeps it, e.g. in OneDrive), or null if unknown. */
    private fun desktopFolder(): File? = runCatching {
        javax.swing.filechooser.FileSystemView.getFileSystemView().homeDirectory.takeIf { it.isDirectory && it.name.isNotEmpty() }
    }.getOrNull() ?: System.getenv("USERPROFILE")?.let { File(it, "Desktop") }?.takeIf { it.isDirectory }

    /** The Vanity Player shortcuts on the desktop right now (by file name). */
    private fun vanityShortcuts(desktop: File): List<String> =
        desktop.listFiles().orEmpty().map { it.name }.filter { isVanityShortcut(it) }

    /** Text for a line of the update script: "%" would otherwise be read as a variable. */
    private fun cmd(text: String) = text.replace("%", "%%")

    companion object {
        /** Matches the shortcut names the installer uses ("VanityPlayer.lnk", "Vanity Player.lnk"). */
        internal fun isVanityShortcut(name: String): Boolean =
            Regex("(?i).*vanity.*player.*\\.lnk").matches(name)
    }
}
