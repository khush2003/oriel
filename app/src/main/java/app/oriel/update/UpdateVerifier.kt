package app.oriel.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Who an APK says it is: its package, its versionCode, and the SHA-256 of each signing certificate. */
data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Reads identities from PackageManager. An interface so the decision can be tested without a real signed APK. */
interface PackageInspector {
    fun installed(): ApkIdentity?
    fun archive(file: File): ApkIdentity?
}

enum class Verdict { OK, NOT_IN_CHECKSUMS, HASH_MISMATCH, UNREADABLE, WRONG_PACKAGE, NOT_NEWER, SIGNER_MISMATCH }

object UpdateVerifier {
    /**
     * Whether a downloaded APK may be installed. Every check must pass: its hash is the one the
     * release lists for it, it's our package, it's newer than what's installed, and it's signed
     * by exactly the certificates the installed app is.
     */
    fun decide(expectedSha256: String?, actualSha256: String, archive: ApkIdentity?, installed: ApkIdentity?): Verdict {
        if (expectedSha256 == null) return Verdict.NOT_IN_CHECKSUMS
        // Constant-time compare: cheap, and nothing about a near miss leaks.
        if (!MessageDigest.isEqual(expectedSha256.lowercase().toByteArray(), actualSha256.lowercase().toByteArray())) return Verdict.HASH_MISMATCH
        if (archive == null || installed == null) return Verdict.UNREADABLE
        if (archive.packageName != installed.packageName) return Verdict.WRONG_PACKAGE
        if (archive.versionCode <= installed.versionCode) return Verdict.NOT_NEWER
        if (installed.signers.isEmpty() || archive.signers != installed.signers) return Verdict.SIGNER_MISMATCH
        return Verdict.OK
    }

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The real [PackageInspector]: signing certificates with GET_SIGNING_CERTIFICATES on Android 9+, GET_SIGNATURES before. */
class AndroidPackageInspector(private val pm: PackageManager, private val packageName: String) : PackageInspector {
    override fun installed(): ApkIdentity? = runCatching {
        identity(pm.getPackageInfo(packageName, flags()), modern = Build.VERSION.SDK_INT >= 28)
    }.getOrNull()

    override fun archive(file: File): ApkIdentity? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageArchiveInfo(file.path, flags()) ?: return null
            // Some Android 9 to 12 builds leave signingInfo out for an archive; then both sides are
            // compared by their legacy signatures instead, like with like.
            if (info.signingInfo != null) return identity(info, modern = true)
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: return null
        val archive = identity(info, modern = false) ?: return null
        @Suppress("DEPRECATION")
        val installed = identity(pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES), modern = false) ?: return null
        // The legacy path is only trusted if the installed app reads the same way through it.
        if (Build.VERSION.SDK_INT >= 28 && installed.signers != installed()?.signers) return null
        archive
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo, modern: Boolean): ApkIdentity? {
        val signatures: Array<Signature> = if (modern && Build.VERSION.SDK_INT >= 28) {
            // The certificates the contents are signed with now, not any it rotated away from.
            info.signingInfo?.apkContentsSigners ?: return null
        } else {
            info.signatures ?: return null
        }
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return ApkIdentity(info.packageName ?: return null, versionCode, signatures.map { UpdateVerifier.sha256Hex(it.toByteArray()) }.toSet())
    }
}
