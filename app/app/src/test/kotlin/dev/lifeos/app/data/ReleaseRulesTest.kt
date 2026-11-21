package dev.lifeos.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReleaseRulesTest {
    private val ownCert = ReleaseRules.certificateDigest("release certificate".toByteArray())
    private val otherCert = ReleaseRules.certificateDigest("debug certificate".toByteArray())

    private fun apk(bytes: ByteArray): File = Files.createTempFile("release", ".apk").toFile().apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    private fun offer(file: File, sha256: String = ReleaseRules.sha256(file), cert: String = ownCert) = ReleaseOffer(
        offer = "o1",
        name = "lifeos-42.apk",
        size = file.length(),
        versionCode = 42,
        versionName = "0.1.42",
        sha256 = sha256,
        certSha256 = cert,
    )

    @Test
    fun `the bridge's release and offer are read`() {
        val release = BridgeJson.decodeFromString(
            AppRelease.serializer(),
            """{"version_code": 42, "version_name": "0.1.42", "size": 1234, "sha256": "ab", "cert_sha256": "CD", "registered_at": "2026-01-01T08:00:00+00:00"}""",
        )
        assertEquals(AppRelease(42, "0.1.42", 1234, "ab", "CD", "2026-01-01T08:00:00+00:00"), release)
        val offer = BridgeJson.decodeFromString(
            ReleaseOffer.serializer(),
            """{"offer": "k1", "name": "lifeos-42.apk", "type": "application/vnd.android.package-archive", "size": 1234,
               "session_id": "app-release", "version_code": 42, "version_name": "0.1.42", "sha256": "ab", "cert_sha256": "cd",
               "registered_at": "2026-01-01T08:00:00+00:00"}""",
        )
        assertEquals(FileOffer("k1", "lifeos-42.apk", "application/vnd.android.package-archive", 1234, "app-release"), offer.fileOffer())
    }

    @Test
    fun `only a newer version code is an update`() {
        val release = AppRelease(versionCode = 42, certSha256 = ownCert)
        assertTrue(ReleaseRules.isNewer(release, 41))
        assertFalse(ReleaseRules.isNewer(release, 42))
        assertFalse(ReleaseRules.isNewer(release, 43))
        assertFalse(ReleaseRules.isNewer(null, 1))
    }

    @Test
    fun `a build signed with another key knows before downloading that it cannot update itself`() {
        assertTrue(ReleaseRules.canUpdateItself(ownCert, AppRelease(versionCode = 42, certSha256 = ownCert.uppercase())))
        assertFalse(ReleaseRules.canUpdateItself(otherCert, AppRelease(versionCode = 42, certSha256 = ownCert)))
        assertFalse(ReleaseRules.canUpdateItself(null, AppRelease(versionCode = 42, certSha256 = ownCert)))
        assertFalse(ReleaseRules.canUpdateItself(ownCert, AppRelease(versionCode = 42, certSha256 = "")))
    }

    @Test
    fun `the file goes to the installer only when size, hash and both certificates match`() {
        val file = apk("an apk".toByteArray())
        assertTrue(ReleaseRules.verified(file, offer(file), ownCert, ownCert))
        // Another hash, another size, another announced signer, a file signed by someone else.
        assertFalse(ReleaseRules.verified(file, offer(file, sha256 = "00".repeat(32)), ownCert, ownCert))
        assertEquals(UpdateProblem.DAMAGED, ReleaseRules.problemOf(file, offer(file, sha256 = "00".repeat(32)), ownCert, ownCert))
        assertFalse(ReleaseRules.verified(file, offer(file).copy(size = 1), ownCert, ownCert))
        assertFalse(ReleaseRules.verified(file, offer(file, cert = otherCert), ownCert, ownCert))
        assertFalse(ReleaseRules.verified(file, offer(file, cert = otherCert), otherCert, ownCert))
        assertEquals(UpdateProblem.NOT_SIGNED_ALIKE, ReleaseRules.problemOf(file, offer(file, cert = otherCert), otherCert, ownCert))
        // The announced signer matches the file, but this app is signed with another key (a debug build).
        assertFalse(ReleaseRules.verified(file, offer(file, cert = otherCert), otherCert, otherCert.reversed()))
        // No readable signature at all.
        assertFalse(ReleaseRules.verified(file, offer(file), null, ownCert))
        assertEquals(UpdateProblem.DAMAGED, ReleaseRules.problemOf(file, offer(file), null, ownCert))
        file.delete()
        assertFalse(ReleaseRules.verified(file, offer = offer(apk(ByteArray(0))), apkCertSha256 = ownCert, ownCertSha256 = ownCert))
    }

    @Test
    fun `the hash is the hex SHA-256 the bridge writes`() {
        val file = apk("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReleaseRules.sha256(file))
    }
}
