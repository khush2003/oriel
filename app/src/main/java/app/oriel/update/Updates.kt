package app.oriel.update

import android.annotation.SuppressLint
import androidx.core.content.edit
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.oriel.BuildConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Where an offered update has got to. */
sealed interface UpdateStep {
    data object Offer : UpdateStep
    data class Downloading(val read: Long, val total: Long) : UpdateStep {
        val fraction: Float get() = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : UpdateStep
    /** Verified and waiting on "Install unknown apps" for Oriel. */
    data object NeedsPermission : UpdateStep
    /** With Android's installer; its confirmation is up, or about to be. */
    data object Installing : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

data class UpdateState(
    /** False in debug builds: their key could never match a release's. */
    val supported: Boolean,
    val autoCheck: Boolean,
    val betaUpdates: Boolean,
    val lastChecked: Long,
    val checking: Boolean = false,
    val available: Release? = null,
    val apk: ReleaseAsset? = null,
    val prompt: Boolean = false,
    val step: UpdateStep = UpdateStep.Offer,
    /** The installer's confirmation, for the activity to start. */
    val confirm: Intent? = null,
)

/**
 * In-app updates from Oriel's GitHub releases: checked at most once a day at start (and when
 * asked), offered once per version, downloaded to the cache, verified against the release's
 * checksums and the installed app's signing certificate, then handed to Android's installer.
 * Kept apart from the app graph, with its own preferences, so it touches nothing else.
 */
class Updates private constructor(context: Context, private val inspector: PackageInspector) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val installed = SemVer.parse(BuildConfig.VERSION_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checker by lazy { UpdateChecker("Oriel/${BuildConfig.VERSION_NAME}") }
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(app.cacheDir, "updates")
    private var job: Job? = null
    /** The downloaded file that passed verification, for which version, and its hash. */
    @Volatile private var verified: Verified? = null

    private class Verified(val version: String, val file: File, val sha256: String)

    private val _state = MutableStateFlow(
        UpdateState(
            supported = SUPPORTED && installed != null,
            autoCheck = prefs.getBoolean(KEY_AUTO, true),
            // Someone on a beta keeps getting betas until they say otherwise.
            betaUpdates = prefs.getBoolean(KEY_BETA, installed?.isPreRelease == true),
            lastChecked = prefs.getLong(KEY_LAST, 0L),
        ),
    )
    val state: StateFlow<UpdateState> = _state

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-line results for the app's notice banner. */
    val notices: SharedFlow<String> = _notices

    /** Called once the UI is up; returns at once, the work is in the background. */
    fun onAppStart() {
        if (!_state.value.supported) return
        scope.launch {
            restore()
            // After the first screen has loaded: the check can wait, the feed can't.
            delay(START_DELAY_MS)
            if (_state.value.autoCheck && System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) >= DAY_MS) check(manual = false)
        }
    }

    fun checkNow() {
        if (!_state.value.supported || _state.value.checking) return
        scope.launch { check(manual = true) }
    }

    fun setAutoCheck(on: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO, on) }
        _state.update { it.copy(autoCheck = on) }
    }

    fun setBetaUpdates(on: Boolean) {
        prefs.edit { putBoolean(KEY_BETA, on) }
        // What's on offer may no longer be wanted (a beta, with betas just turned off).
        if (!on && _state.value.available?.prerelease == true && _state.value.step == UpdateStep.Offer) clearAvailable()
        _state.update { it.copy(betaUpdates = on) }
    }

    fun open() = _state.update { if (it.available != null) it.copy(prompt = true) else it }

    /** "Later", or the sheet dismissed: quiet until tomorrow's check. */
    fun later() {
        cancel()
        prefs.edit { putLong(KEY_PROMPT_AFTER, System.currentTimeMillis() + DAY_MS) }
        _state.update { it.copy(prompt = false, step = if (it.step is UpdateStep.Failed) UpdateStep.Offer else it.step) }
    }

    /** "Skip this version": silent until a newer one. The About row still shows it. */
    fun skip() {
        _state.value.available?.let { prefs.edit { putString(KEY_SKIPPED, it.version) } }
        later()
    }

    fun update() {
        val s = _state.value
        val release = s.available ?: return
        val apk = s.apk ?: return
        if (job?.isActive == true) return
        if (verified?.let { it.version == release.version && it.file.exists() } == true) { job = scope.launch { install() }; return }
        job = scope.launch {
            try {
                dir.deleteRecursively()
                if (!dir.mkdirs() && !dir.isDirectory) throw IOException("No cache directory")
                val file = File(dir, "update.apk")
                setStep(UpdateStep.Downloading(0, apk.size))
                var shown = -1
                val sha = checker.download(apk, file) { read, total ->
                    val pct = if (total > 0) (read * 100 / total).toInt() else 0
                    if (pct != shown) { shown = pct; setStep(UpdateStep.Downloading(read, total)) }
                }
                setStep(UpdateStep.Verifying)
                val sums = Releases.checksumsOf(release)?.let(checker::checksums).orEmpty()
                val verdict = UpdateVerifier.decide(sums[apk.name], sha, inspector.archive(file), inspector.installed())
                if (verdict != Verdict.OK) {
                    file.delete()
                    setStep(UpdateStep.Failed(UNVERIFIED))
                    return@launch
                }
                verified = Verified(release.version, file, sha)
                install()
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                dir.deleteRecursively()
                setStep(UpdateStep.Failed(if (e is UpdateCheckException) "GitHub didn't send the update. Try again later." else "The download didn't finish. Check your connection and try again."))
            }
        }
    }

    /** Back from Android's settings: carries on if Oriel may now install apps. */
    fun onResume() {
        if (_state.value.step == UpdateStep.NeedsPermission && job?.isActive != true && UpdateInstaller.canInstall(app)) job = scope.launch { install() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update { if (it.step is UpdateStep.Downloading || it.step == UpdateStep.Verifying) it.copy(step = UpdateStep.Offer) else it }
    }

    /** The confirmation was started (or couldn't be); it isn't started twice. */
    fun confirmHandled() = _state.update { it.copy(confirm = null) }

    private fun install() {
        val v = verified?.takeIf { it.version == _state.value.available?.version && it.file.exists() } ?: return setStep(UpdateStep.Failed(UNVERIFIED))
        if (!UpdateInstaller.canInstall(app)) return setStep(UpdateStep.NeedsPermission)
        setStep(UpdateStep.Installing)
        try {
            // Written now: if the install goes through, this process ends before it hears so.
            _state.value.available?.let { prefs.edit(commit = true) { putString(KEY_INSTALLING, it.version) } }
            UpdateInstaller.install(app, v.file, v.sha256)
        } catch (e: Exception) {
            prefs.edit { remove(KEY_INSTALLING) }
            setStep(UpdateStep.Failed("The update couldn't be installed."))
        }
    }

    internal fun onInstallStatus(status: Int, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                if (confirm != null && UpdateInstaller.isSystemConfirmation(app, confirm)) _state.update { it.copy(confirm = confirm) }
                else setStep(UpdateStep.Failed("The update couldn't be installed."))
            PackageInstaller.STATUS_SUCCESS -> {
                verified?.file?.delete()
                verified = null
                _state.update { it.copy(prompt = false, step = UpdateStep.Offer) }
            }
            else -> {
                prefs.edit { remove(KEY_INSTALLING) }
                // Cancelled in Android's dialog: back to the offer, the verified file kept for another try.
                if (status == PackageInstaller.STATUS_FAILURE_ABORTED) setStep(UpdateStep.Offer)
                else setStep(UpdateStep.Failed(if (status == PackageInstaller.STATUS_FAILURE_STORAGE) "There isn't enough space to install the update." else "The update couldn't be installed."))
            }
        }
    }

    private suspend fun check(manual: Boolean) {
        val current = installed ?: return
        _state.update { it.copy(checking = true) }
        try {
            val releases = checker.releases()
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST, now) }
            val found = Releases.pickUpdate(releases, current, _state.value.betaUpdates)
            val apk = found?.let { Releases.apkFor(it, Build.SUPPORTED_ABIS.toList()) }
            if (found == null || apk == null) {
                clearAvailable()
                _state.update { it.copy(lastChecked = now) }
                if (manual) _notices.tryEmit("Oriel is up to date")
                return
            }
            prefs.edit { putString(KEY_AVAILABLE, json.encodeToString(Release.serializer(), found)) }
            val quiet = prefs.getString(KEY_SKIPPED, null) == found.version || now < prefs.getLong(KEY_PROMPT_AFTER, 0L)
            _state.update {
                // A newer release replaces one being downloaded only if nothing's under way.
                if (it.available?.version != found.version && it.step != UpdateStep.Offer && it.step !is UpdateStep.Failed) it.copy(lastChecked = now)
                else it.copy(lastChecked = now, available = found, apk = apk, prompt = it.prompt || manual || !quiet)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or rate-limited: tried again at the next start; only a manual check says so.
            if (manual) _notices.tryEmit("Couldn't check for updates right now")
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    /** What the last check found, and a note if the last install went through. */
    private fun restore() {
        val current = installed ?: return
        // Leftovers from an update that never finished.
        if (verified == null) dir.deleteRecursively()
        prefs.getString(KEY_INSTALLING, null)?.let { v ->
            prefs.edit { remove(KEY_INSTALLING) }
            if (v == current.toString()) _notices.tryEmit("Oriel is updated to $v")
        }
        val stored = prefs.getString(KEY_AVAILABLE, null)?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }
        val apk = stored?.let { Releases.apkFor(it, Build.SUPPORTED_ABIS.toList()) }
        if (stored == null || apk == null || SemVer.parse(stored.version)?.let { it > current } != true || (stored.prerelease && !_state.value.betaUpdates)) {
            clearAvailable()
            return
        }
        _state.update { if (it.available == null) it.copy(available = stored, apk = apk) else it }
    }

    private fun clearAvailable() {
        prefs.edit { remove(KEY_AVAILABLE) }
        _state.update { it.copy(available = null, apk = null, prompt = false, step = UpdateStep.Offer) }
    }

    private fun setStep(step: UpdateStep) = _state.update { it.copy(step = step) }

    companion object {
        /** Debug builds (app.oriel.debug) are signed with a key no release has. */
        val SUPPORTED = !BuildConfig.DEBUG
        const val UNVERIFIED = "This update couldn't be verified"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val START_DELAY_MS = 5_000L
        private const val KEY_AUTO = "auto"
        private const val KEY_BETA = "beta"
        private const val KEY_LAST = "lastCheck"
        private const val KEY_SKIPPED = "skipped"
        private const val KEY_PROMPT_AFTER = "promptAfter"
        private const val KEY_AVAILABLE = "available"
        private const val KEY_INSTALLING = "installing"

        // Holds the application context only, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: Updates? = null

        fun get(context: Context): Updates = instance ?: synchronized(this) {
            instance ?: Updates(context, AndroidPackageInspector(context.packageManager, context.packageName)).also { instance = it }
        }
    }
}
