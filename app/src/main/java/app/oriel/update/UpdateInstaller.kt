package app.oriel.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Hands a verified APK to Android's PackageInstaller. Android shows its own confirmation and checks the signature again. */
object UpdateInstaller {
    const val ACTION_STATUS = "app.oriel.update.INSTALL_STATUS"

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** "Install unknown apps" for Oriel alone. */
    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /**
     * Copies [apk] into a session and commits it. The bytes are hashed again on the way in and
     * the session abandoned unless they're still [sha256], so what's installed is what was verified.
     */
    fun install(context: Context, apk: File, sha256: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val digest = MessageDigest.getInstance("SHA-256")
                session.openWrite("oriel.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                    session.fsync(out)
                }
                val written = digest.digest().joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(written.toByteArray(), sha256.lowercase().toByteArray())) throw IOException("Update changed after it was verified")
                // Explicit, to our own unexported receiver. Mutable because the installer adds the status extras.
                val status = Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, status, flags).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(id) }
            throw t
        }
    }

    /**
     * Whether the confirmation the installer asks us to start is safe to start: it must open an
     * activity of a system app (the package installer), and grant no access to our files or URIs.
     */
    fun isSystemConfirmation(context: Context, intent: Intent): Boolean {
        val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        if (intent.flags and grants != 0) return false
        val pm = context.packageManager
        val target = intent.resolveActivity(pm)?.packageName ?: return false
        if (target == context.packageName) return false
        val info = runCatching { pm.getApplicationInfo(target, 0) }.getOrNull() ?: return false
        return info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }
}

/** Receives the installer's progress for our own sessions. Not exported: only the PendingIntent we made reaches it. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) else null
        Updates.get(context).onInstallStatus(status, confirm)
    }
}
