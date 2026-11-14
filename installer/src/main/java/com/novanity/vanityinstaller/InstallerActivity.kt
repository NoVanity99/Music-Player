package com.novanity.vanityinstaller

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import com.novanity.musicplayer.updatecore.UpdateCore
import org.json.JSONObject
import java.io.File

/**
 * Installs the newest Vanity Player. It asks GitHub Releases which version is the latest, checks
 * that answer's signature, downloads the APK, checks its SHA-256, and hands it to Android's own
 * installer. Because it always asks, this installer itself never goes out of date.
 */
class InstallerActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var remove: Button

    private var apk: File? = null
    private var waitingForPermission = false
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (28 * dp).toInt()
        status = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        detail = TextView(this).apply { textSize = 15f; setTextColor(0xFFA1A1A9.toInt()); gravity = Gravity.CENTER; setPadding(0, (10 * dp).toInt(), 0, (22 * dp).toInt()) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        action = Button(this).apply { visibility = View.GONE }
        remove = Button(this).apply {
            text = "Remove this installer"
            visibility = View.GONE
            setOnClickListener { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) }
        }
        val title = TextView(this).apply {
            text = "Vanity Player"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (30 * dp).toInt())
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF0B0B0D.toInt())
                setPadding(pad, pad, pad, pad)
                listOf(title, status, detail, progress, action, remove).forEach {
                    addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
                }
            }
        )
        start()
    }

    override fun onResume() {
        super.onResume()
        // Back from "Install unknown apps", or from Android's installer.
        if (waitingForPermission && packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            apk?.let(::install)
        } else if (!working && apk != null && installedVersion() != null) {
            showInstalled()
        }
    }

    private fun installedVersion(): Long? = runCatching {
        packageManager.getPackageInfo(PLAYER_PACKAGE, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
    }.getOrNull()

    private fun show(title: String, text: String, busy: Boolean, button: String? = null, onClick: (() -> Unit)? = null) {
        status.text = title
        detail.text = text
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        action.visibility = if (button != null) View.VISIBLE else View.GONE
        action.text = button
        action.setOnClickListener { onClick?.invoke() }
    }

    private fun start() {
        working = true
        remove.visibility = View.GONE
        progress.isIndeterminate = true
        show("Finding the latest version…", "", busy = true)
        Thread {
            try {
                val manifestBytes = UpdateCore.fetch(MANIFEST_URL)
                val signature = UpdateCore.fetch("$MANIFEST_URL.sig").toString(Charsets.UTF_8).trim()
                check(UpdateCore.verifyEd25519(PUBLIC_KEY, manifestBytes, signature)) { "The download information couldn't be verified." }
                val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
                val version = manifest.getString("version")
                val code = manifest.getLong("versionCode")
                val asset = manifest.getJSONObject("android")
                val installed = installedVersion()
                if (installed != null && installed >= code) {
                    working = false
                    runOnUiThread { showInstalled() }
                    return@Thread
                }
                val size = asset.getLong("size")
                runOnUiThread {
                    progress.isIndeterminate = false
                    show("Downloading Vanity Player $version", "${size / 1_000_000} MB", busy = true)
                }
                val dir = File(cacheDir, "download").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "VanityPlayer.apk")
                var last = -1
                UpdateCore.download(asset.getString("url"), file, size) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != last) {
                        last = percent
                        runOnUiThread { progress.progress = percent }
                    }
                }
                check(UpdateCore.sha256(file).equals(asset.getString("sha256"), ignoreCase = true)) { "The download was damaged. Please try again." }
                apk = file
                working = false
                runOnUiThread { install(file) }
            } catch (e: Exception) {
                working = false
                val offline = e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException
                runOnUiThread {
                    show(
                        "Couldn't download",
                        if (offline) "Check your internet connection and try again." else (e.message ?: "Something went wrong. Please try again."),
                        busy = false, button = "Try again", onClick = ::start,
                    )
                }
            }
        }.start()
    }

    private fun install(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            waitingForPermission = true
            show(
                "One permission needed",
                "Android asks before an app may install another. Tap Continue, allow it for this installer, then come back.",
                busy = false, button = "Continue",
            ) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
            return
        }
        show("Ready to install", "Tap Install when Android asks.", busy = false, button = "Install") { install(file) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun showInstalled() {
        show("Vanity Player is installed", "It keeps itself up to date from now on. You can remove this installer.", busy = false, button = "Open Vanity Player") {
            packageManager.getLaunchIntentForPackage(PLAYER_PACKAGE)?.let(::startActivity)
        }
        remove.visibility = View.VISIBLE
    }

    private companion object {
        const val PLAYER_PACKAGE = "com.novanity.musicplayer"

        // The same release manifest and signing key the player's own updater uses
        // (shared/.../update/Updates.kt).
        const val MANIFEST_URL = "https://github.com/NoVanity99/Music-Player/releases/latest/download/latest.json"
        const val PUBLIC_KEY = "/UThUdL7+HzUpC/UVmCKD79rwqq3SEqfHmNpeti7Px0="
    }
}
