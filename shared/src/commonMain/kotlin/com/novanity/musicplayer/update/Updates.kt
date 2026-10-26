package com.novanity.musicplayer.update

import androidx.compose.runtime.Immutable
import com.novanity.musicplayer.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where releases are published, and the key that signs their manifest. */
object UpdateConfig {
    const val OWNER = "NoVanity99"
    const val REPO = "Music-Player"
    const val MANIFEST_URL = "https://github.com/$OWNER/$REPO/releases/latest/download/latest.json"
    const val SIGNATURE_URL = "$MANIFEST_URL.sig"

    /** Ed25519 public key (raw 32 bytes, base64). The matching private key signs latest.json in CI. */
    const val PUBLIC_KEY = "/UThUdL7+HzUpC/UVmCKD79rwqq3SEqfHmNpeti7Px0="
}

/** latest.json, published with every release (see RELEASING.md). */
@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Int,
    val publishedAt: String? = null,
    val notes: String = "",
    val android: UpdateAsset? = null,
    val windows: UpdateAsset? = null,
)

@Serializable
data class UpdateAsset(val file: String, val url: String, val sha256: String, val size: Long)

@Immutable
data class UpdateInfo(val version: String, val versionCode: Int, val notes: String, val asset: UpdateAsset)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latestVersion: String?) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Verifying(val info: UpdateInfo) : UpdateState
    /** Android: "Install unknown apps" must be allowed for this app first. */
    data class NeedsPermission(val info: UpdateInfo, val path: String) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

enum class InstallOutcome { STARTED, NEEDS_PERMISSION }

/** What each platform provides to the update flow. */
interface UpdatePlatform {
    /** "android" or "windows": which asset in the manifest applies. */
    val platformKey: String
    val currentVersion: String
    val currentVersionCode: Int

    suspend fun fetch(url: String): ByteArray
    /** Downloads to a private location and returns its path. */
    suspend fun download(asset: UpdateAsset, onProgress: (Float) -> Unit): String
    fun sha256(path: String): String
    fun verifySignature(data: ByteArray, signatureBase64: String): Boolean
    /** Platform-specific checks (e.g. the APK is signed with our key), then launches the installer. */
    suspend fun install(path: String, info: UpdateInfo): InstallOutcome
    fun openInstallPermissionSettings() {}
}

/** Compares dotted versions numerically ("1.10.0" > "1.9.2"). */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    val pb = b.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/**
 * Checks GitHub Releases for a newer version, shows its notes, then downloads, verifies
 * (signed manifest + SHA-256) and installs it.
 */
class UpdateManager(
    private val platform: UpdatePlatform,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** True when the release-notes dialog should be shown. */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    val currentVersion: String get() = platform.currentVersion
    val autoCheck: StateFlow<Boolean> = settings.autoUpdateCheck

    private var job: Job? = null

    fun setAutoCheck(enabled: Boolean) = settings.setAutoUpdateCheck(enabled)

    /** Called at startup. */
    fun checkOnLaunch() {
        if (settings.autoUpdateCheck.value) check(userInitiated = false)
    }

    fun check(userInitiated: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching { findUpdate() }
            val info = result.getOrNull()
            _state.value = when {
                result.isFailure -> UpdateState.Failed(
                    if (userInitiated) "Couldn't check for updates. Check your internet connection." else "Update check failed",
                )
                info == null -> UpdateState.UpToDate(null)
                else -> UpdateState.Available(info)
            }
            if (info != null) {
                // Automatic checks prompt once per version; manual checks always show the notes.
                if (userInitiated || settings.lastPromptedVersion.value != info.version) {
                    settings.setLastPromptedVersion(info.version)
                    _prompt.value = true
                }
            } else if (result.isFailure && !userInitiated) {
                _state.value = UpdateState.Idle // stay quiet about background failures
            }
        }
    }

    private suspend fun findUpdate(): UpdateInfo? {
        val manifestBytes = platform.fetch(UpdateConfig.MANIFEST_URL)
        val signature = platform.fetch(UpdateConfig.SIGNATURE_URL).decodeToString().trim()
        require(platform.verifySignature(manifestBytes, signature)) { "Update manifest signature is invalid" }
        val manifest = json.decodeFromString(UpdateManifest.serializer(), manifestBytes.decodeToString())
        val asset = when (platform.platformKey) {
            "android" -> manifest.android
            else -> manifest.windows
        } ?: return null
        val newer = compareVersions(manifest.version, platform.currentVersion) > 0 &&
            (platform.platformKey != "android" || manifest.versionCode > platform.currentVersionCode)
        return if (newer) UpdateInfo(manifest.version, manifest.versionCode, manifest.notes, asset) else null
    }

    fun showNotes() {
        if (_state.value is UpdateState.Available) _prompt.value = true
    }

    fun dismissPrompt() {
        _prompt.value = false
    }

    fun install() {
        val info = (_state.value as? UpdateState.Available)?.info
            ?: (_state.value as? UpdateState.Failed)?.info
            ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val path = platform.download(info.asset) { p -> _state.value = UpdateState.Downloading(info, p) }
                _state.value = UpdateState.Verifying(info)
                val actual = platform.sha256(path)
                require(actual.equals(info.asset.sha256, ignoreCase = true)) { "Download is corrupted (checksum mismatch). Please try again." }
                startInstall(path, info)
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(t.message ?: "Update failed", info)
            }
        }
    }

    private suspend fun startInstall(path: String, info: UpdateInfo) {
        _state.value = UpdateState.Installing(info)
        when (platform.install(path, info)) {
            InstallOutcome.STARTED -> _prompt.value = false
            InstallOutcome.NEEDS_PERMISSION -> _state.value = UpdateState.NeedsPermission(info, path)
        }
    }

    fun openPermissionSettings() = platform.openInstallPermissionSettings()

    /** After the user returns from the "install unknown apps" screen. */
    fun resumeInstall() {
        val s = _state.value as? UpdateState.NeedsPermission ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                startInstall(s.path, s.info)
            } catch (t: Throwable) {
                _state.value = UpdateState.Failed(t.message ?: "Update failed", s.info)
            }
        }
    }
}
