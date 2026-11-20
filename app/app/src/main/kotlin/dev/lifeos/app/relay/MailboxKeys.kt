package dev.lifeos.app.relay

import java.util.Base64

/**
 * Mailbox entry names and values, as in the bridge's `mailbox.py`: keys are
 * `<13-digit unix milliseconds>-<8 hex digits>`, values are base64 of a sealed message.
 */
object MailboxKeys {
    private val KEY = Regex("[0-9]{13}-[0-9a-f]{8}")

    fun newKey(nowMillis: Long, random: ByteArray): String {
        require(random.size == 4 && nowMillis in 0..9_999_999_999_999L)
        return "%013d-%s".format(nowMillis, random.joinToString("") { "%02x".format(it) })
    }

    fun isKey(key: String): Boolean = KEY.matches(key)

    /** When a key was made (milliseconds), or null if it is not one of ours. */
    fun keyTime(key: String): Long? = if (isKey(key)) key.substring(0, 13).toLong() else null

    fun encode(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    fun decode(text: String?): ByteArray? = try {
        if (text == null) null else Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun toBridge(deviceHex: String): String = "lifeos/$deviceHex/b"
    fun toPhone(deviceHex: String): String = "lifeos/$deviceHex/p"
    fun hello(deviceHex: String): String = "pair/$deviceHex/hello"
}
