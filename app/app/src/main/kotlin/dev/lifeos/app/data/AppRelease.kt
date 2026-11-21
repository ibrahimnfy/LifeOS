package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** `GET /app/release`: the newest release build registered on the computer (#138). */
@Serializable
data class AppRelease(
    @SerialName("version_code") val versionCode: Long = 0,
    @SerialName("version_name") val versionName: String = "",
    val size: Long = 0,
    val sha256: String = "",
    @SerialName("cert_sha256") val certSha256: String = "",
    @SerialName("registered_at") val registeredAt: String? = null,
)

/** `POST /app/release/offer`: the release offered for the sealed file transfer (15 minutes). */
@Serializable
data class ReleaseOffer(
    val offer: String,
    val name: String = "",
    val type: String? = null,
    val size: Long = 0,
    @SerialName("session_id") val sessionId: String = "app-release",
    @SerialName("version_code") val versionCode: Long = 0,
    @SerialName("version_name") val versionName: String = "",
    val sha256: String = "",
    @SerialName("cert_sha256") val certSha256: String = "",
) {
    fun fileOffer(): FileOffer = FileOffer(offer, name, type, size, sessionId)
}

/** Why an update did not reach the installer, or the installer did not finish it. */
enum class UpdateProblem { UNREACHABLE, DAMAGED, NOT_SIGNED_ALIKE, INSTALL }

/** The rules of a self-update, without Android classes. */
object ReleaseRules {
    /** Only a newer version code is an update. */
    fun isNewer(release: AppRelease?, ownVersionCode: Long): Boolean = release != null && release.versionCode > ownVersionCode

    /**
     * A build signed with another key (every debug build, or a build made elsewhere) cannot be
     * installed over this one: Android refuses it. Known before anything is downloaded.
     */
    fun canUpdateItself(ownCertSha256: String?, release: AppRelease): Boolean =
        ownCertSha256 != null && sameDigest(ownCertSha256, release.certSha256)

    /**
     * The downloaded file may go to the installer only when its size and SHA-256 are the
     * announced ones and its signing certificate is both the announced one and this app's own.
     */
    fun verified(file: File, offer: ReleaseOffer, apkCertSha256: String?, ownCertSha256: String?): Boolean =
        file.isFile && file.length() == offer.size && sameDigest(sha256(file), offer.sha256) &&
            apkCertSha256 != null && ownCertSha256 != null &&
            sameDigest(apkCertSha256, offer.certSha256) && sameDigest(apkCertSha256, ownCertSha256)

    /** Why [verified] said no: the file itself, or its signer. */
    fun problemOf(file: File, offer: ReleaseOffer, apkCertSha256: String?, ownCertSha256: String?): UpdateProblem = when {
        !file.isFile || file.length() != offer.size || !sameDigest(sha256(file), offer.sha256) -> UpdateProblem.DAMAGED
        apkCertSha256 == null -> UpdateProblem.DAMAGED
        else -> UpdateProblem.NOT_SIGNED_ALIKE
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    /** The SHA-256 of a DER certificate, as the bridge writes it (`cert_sha256`). */
    fun certificateDigest(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(der))

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun sameDigest(a: String, b: String): Boolean =
        a.isNotEmpty() && MessageDigest.isEqual(a.trim().lowercase().toByteArray(), b.trim().lowercase().toByteArray())
}
