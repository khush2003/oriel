package app.oriel.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision on a downloaded APK. Reading a real archive's certificates needs PackageManager
 * on a device, so the identities come from a fake [PackageInspector], the seam the app reads them through.
 */
class UpdateVerifierTest {
    private val ours = setOf("1f".repeat(32))
    private val installed = ApkIdentity("app.oriel", 18, ours)
    private val good = ApkIdentity("app.oriel", 19, ours)
    private val sha = "ab".repeat(32)

    private class FakeInspector(val installed: ApkIdentity?, val archive: ApkIdentity?) : PackageInspector {
        override fun installed() = installed
        override fun archive(file: File) = archive
    }

    private fun decide(archive: ApkIdentity?, expected: String? = sha, actual: String = sha, mine: ApkIdentity? = installed): Verdict {
        val pm = FakeInspector(mine, archive)
        return UpdateVerifier.decide(expected, actual, pm.archive(File("update.apk")), pm.installed())
    }

    @Test fun aMatchingUpdatePasses() {
        assertEquals(Verdict.OK, decide(good))
        assertEquals(Verdict.OK, decide(good, expected = sha.uppercase()))
    }

    @Test fun hashMismatchIsRejected() {
        assertEquals(Verdict.HASH_MISMATCH, decide(good, actual = "cd".repeat(32)))
        assertEquals(Verdict.HASH_MISMATCH, decide(good, actual = sha.dropLast(1)))
    }

    @Test fun anApkMissingFromTheChecksumsIsRejected() {
        assertEquals(Verdict.NOT_IN_CHECKSUMS, decide(good, expected = null))
    }

    @Test fun certificateMismatchIsRejected() {
        assertEquals(Verdict.SIGNER_MISMATCH, decide(good.copy(signers = setOf("2e".repeat(32)))))
        // An extra signer alongside ours is still not the same set.
        assertEquals(Verdict.SIGNER_MISMATCH, decide(good.copy(signers = ours + "2e".repeat(32))))
        assertEquals(Verdict.SIGNER_MISMATCH, decide(good.copy(signers = emptySet()), mine = installed.copy(signers = emptySet())))
    }

    @Test fun anotherPackageIsRejected() {
        assertEquals(Verdict.WRONG_PACKAGE, decide(good.copy(packageName = "app.oriel.debug")))
    }

    @Test fun anOlderOrSameVersionCodeIsRejected() {
        assertEquals(Verdict.NOT_NEWER, decide(good.copy(versionCode = 18)))
        assertEquals(Verdict.NOT_NEWER, decide(good.copy(versionCode = 3)))
    }

    @Test fun anUnreadableArchiveIsRejected() {
        assertEquals(Verdict.UNREADABLE, decide(null))
        assertEquals(Verdict.UNREADABLE, decide(good, mine = null))
    }
}
