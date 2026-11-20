package dev.lifeos.app.pairing

import dev.lifeos.app.crypto.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** What the phone needs to reach the mailbox. Not secret, but it identifies the owner's relay. */
data class RelayInfo(val kind: String, val databaseUrl: String, val apiKey: String)

/** The content of a pairing QR code. It holds the device secret: never log or display it. */
class PairingOffer(val deviceId: ByteArray, val secret: ByteArray, val relay: RelayInfo) {
    val deviceHex: String get() = deviceId.joinToString("") { "%02x".format(it) }
}

/**
 * The text of the pairing QR code: `lifeos-pair:1:` followed by unpadded URL-safe base64 of
 * `{"d": <device id, hex>, "s": <secret, base64url>, "r": {"k": "rtdb", "u": <url>, "a": <key>}}`.
 */
object PairingText {
    const val PREFIX = "lifeos-pair:1:"
    private const val MAX_LENGTH = 2000
    private val API_KEY = Regex("[A-Za-z0-9_-]{1,200}")
    private val HEX = Regex("[0-9a-f]{${Wire.DEVICE_ID_BYTES * 2}}")

    /** The offer, or null for anything that is not exactly a version-1 pairing text. */
    fun parse(text: String): PairingOffer? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(PREFIX) || trimmed.length > MAX_LENGTH) return null
        return try {
            val json = String(unbase64Url(trimmed.substring(PREFIX.length)) ?: return null, Charsets.UTF_8)
            val data = Json.parseToJsonElement(json).jsonObject
            val device = data.string("d")?.takeIf { HEX.matches(it) } ?: return null
            val secret = unbase64Url(data.string("s") ?: return null) ?: return null
            if (secret.size != Wire.SECRET_BYTES) return null
            val relay = data["r"] as? JsonObject ?: return null
            val url = relay.string("u")?.trimEnd('/') ?: return null
            val key = relay.string("a") ?: return null
            if (relay.string("k") != "rtdb" || !validUrl(url) || !API_KEY.matches(key)) return null
            PairingOffer(device.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), secret, RelayInfo("rtdb", url, key))
        } catch (_: Exception) {
            null
        }
    }

    /** Only an HTTPS address of a host, without credentials, query or fragment. */
    private fun validUrl(url: String): Boolean {
        if (!url.startsWith("https://") || url.length > 300) return false
        val rest = url.substring("https://".length)
        return rest.isNotEmpty() && rest.none { it in "@?#\\ " || it.isWhitespace() } &&
            Regex("[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?").matches(rest)
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun unbase64Url(text: String): ByteArray? = try {
        if (text.any { it == '=' || it == '+' || it == '/' }) null else Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}
