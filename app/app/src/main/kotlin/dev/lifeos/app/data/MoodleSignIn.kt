package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/** `POST /moodle/signin/start`: the site's sign-in link and the one-time passport of this attempt. */
@Serializable
data class SignInStart(
    val link: String = "",
    val wwwroot: String = "",
    val passport: String = "",
    val scheme: String = "moodlemobile",
    @SerialName("expires_in") val expiresIn: Long = 600,
)

/** `POST /moodle/signin/finish`: what the computer found out about the new token (never the token). */
@Serializable
data class SignInDone(val stored: Boolean = false, val release: String? = null, val missing: List<String> = emptyList())

/**
 * The phone's side of the Moodle sign-in (#100), as the official mobile app does it: the site's
 * page ends on `moodlemobile://token=<base64(md5(site + passport) ::: token ::: …)>`. The first
 * part proves that the address belongs to this attempt; only then is it sent to the computer,
 * which checks it again, tests the token and stores it. The app keeps nothing of it.
 */
object MoodleSignIn {
    /** Whether [address] is the end of a sign-in at all (the address scheme the site uses). */
    fun isRedirect(address: String?, scheme: String = "moodlemobile"): Boolean =
        address != null && address.startsWith("$scheme://") && address.contains("token=")

    /**
     * True when [address] carries a token that belongs to the attempt with [passport] at the
     * site [wwwroot] (`https://` and `http://` are both accepted, as the official app does).
     */
    fun belongsTo(address: String, wwwroot: String, passport: String): Boolean {
        val encoded = address.substringAfter("token=", "").substringBefore("&").trim()
        if (encoded.isEmpty() || passport.isEmpty() || wwwroot.isEmpty()) return false
        val decoded = try {
            String(Base64.getDecoder().decode(encoded + "=".repeat((4 - encoded.length % 4) % 4)), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val parts = decoded.split(":::")
        if (parts.size < 2 || parts[1].isEmpty()) return false
        val root = wwwroot.trimEnd('/')
        val roots = setOf(root, root.replaceFirst("https://", "http://"))
        return roots.any { MessageDigest.isEqual(md5(it + passport).toByteArray(), parts[0].toByteArray()) }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
