package com.novanity.musicplayer.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import com.novanity.musicplayer.update.InstallOutcome
import com.novanity.musicplayer.update.UpdateAsset
import com.novanity.musicplayer.update.UpdateConfig
import com.novanity.musicplayer.update.UpdateInfo
import com.novanity.musicplayer.update.UpdatePlatform
import com.novanity.musicplayer.updatecore.UpdateCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Downloads the new APK, checks it really is a newer build of this app signed with our key, and opens the system installer. */
class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val pm = context.packageManager
    private val self: PackageInfo = pm.getPackageInfo(context.packageName, 0)

    override val platformKey = "android"
    override val currentVersion: String = self.versionName ?: "0"
    @Suppress("DEPRECATION")
    override val currentVersionCode: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) self.longVersionCode.toInt() else self.versionCode

    override suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) { UpdateCore.fetch(url) }

    override suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val dest = File(dir, File(asset.file).name)
        UpdateCore.download(asset.url, dest, asset.size, onProgress)
        dest.absolutePath
    }

    override fun sha256(path: String): String = UpdateCore.sha256(File(path))

    override fun verifySignature(data: ByteArray, signatureBase64: String): Boolean =
        UpdateCore.verifyEd25519(UpdateConfig.PUBLIC_KEY, data, signatureBase64)

    override suspend fun install(path: String, info: UpdateInfo): InstallOutcome {
        withContext(Dispatchers.IO) { checkApk(path, info) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            return InstallOutcome.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return InstallOutcome.STARTED
    }

    override fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** The APK must be this app, newer than what's installed, and signed with the same certificate. */
    @Suppress("DEPRECATION")
    private fun checkApk(path: String, info: UpdateInfo) {
        val apk = pm.getPackageArchiveInfo(path, 0) ?: error("The downloaded file isn't a valid app package")
        require(apk.packageName == context.packageName) { "The download is a different app" }
        val apkCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) apk.longVersionCode.toInt() else apk.versionCode
        require(apkCode > currentVersionCode && apkCode == info.versionCode) { "The download isn't the expected newer version" }
        // Read both certificates the same way. (PackageManager often reports no signers at all for a
        // downloaded APK, which made every update look like it had the wrong key.)
        val installed = ApkCertificates.of(File(context.applicationInfo.sourceDir))
        val update = ApkCertificates.of(File(path))
        require(update.isNotEmpty() && update == installed) {
            "The update isn't signed with this app's key, so Android couldn't install it over this version"
        }
    }
}

/** SHA-256 fingerprints of an APK's signing certificates, after checking its signature is valid. */
object ApkCertificates {
    fun of(apk: File): Set<String> {
        val result = runCatching {
            ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(Build.VERSION_CODES.O).build().verify()
        }.getOrNull()
        if (result == null || !result.isVerified) return emptySet()
        return result.signerCertificates.map { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
