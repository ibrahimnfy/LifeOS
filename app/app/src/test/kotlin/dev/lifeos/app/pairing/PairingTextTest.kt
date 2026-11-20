package dev.lifeos.app.pairing

import dev.lifeos.app.crypto.Vectors
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class PairingTextTest {
    private fun text(json: String, prefix: String = PairingText.PREFIX): String =
        prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private val device = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val relay = """{"k":"rtdb","u":"https://relay.example.invalid","a":"synthetic-api-key"}"""

    private fun json(d: String = device, s: String = secret, r: String = relay) = """{"d":"$d","s":"$s","r":$r}"""

    @Test
    fun `the bridge's pairing text is read`() {
        val offer = PairingText.parse(Vectors.pairingText)
        assertNotNull(offer)
        assertArrayEquals(Vectors.deviceId, offer!!.deviceId)
        assertArrayEquals(Vectors.secret, offer.secret)
        assertEquals(device, offer.deviceHex)
        assertEquals(Vectors.relay.getValue("u").jsonPrimitive.content, offer.relay.databaseUrl)
        assertEquals(Vectors.relay.getValue("a").jsonPrimitive.content, offer.relay.apiKey)
        assertEquals("rtdb", offer.relay.kind)
    }

    @Test
    fun `surrounding white space and a trailing slash of the address are tolerated`() {
        assertNotNull(PairingText.parse("  ${Vectors.pairingText}\n"))
        val offer = PairingText.parse(text(json(r = """{"k":"rtdb","u":"https://relay.example.invalid/","a":"k"}""")))
        assertEquals("https://relay.example.invalid", offer?.relay?.databaseUrl)
    }

    @Test
    fun `wrong prefix or version is rejected`() {
        val body = Vectors.pairingText.removePrefix(PairingText.PREFIX)
        for (prefix in listOf("", "lifeos-pair:2:", "lifeos-pair:", "LIFEOS-PAIR:1:", "lifeos-pair:1", "https://example.invalid/")) {
            assertNull(prefix, PairingText.parse(prefix + body))
        }
        assertNull(PairingText.parse(""))
        assertNull(PairingText.parse(PairingText.PREFIX))
    }

    @Test
    fun `wrong lengths are rejected`() {
        fun b64(size: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size))
        assertNull(PairingText.parse(text(json(s = b64(31)))))
        assertNull(PairingText.parse(text(json(s = b64(33)))))
        assertNull(PairingText.parse(text(json(s = ""))))
        assertNull(PairingText.parse(text(json(d = device.dropLast(2)))))
        assertNull(PairingText.parse(text(json(d = device + "00"))))
        assertNull(PairingText.parse(text(json(d = device.uppercase()))))
        assertNull(PairingText.parse(text(json(d = "zz" + device.drop(2)))))
        assertNull(PairingText.parse(PairingText.PREFIX + "A".repeat(3000)))
    }

    @Test
    fun `broken encodings and shapes are rejected`() {
        assertNull(PairingText.parse(PairingText.PREFIX + "***"))
        assertNull(PairingText.parse(Vectors.pairingText + "="))
        assertNull(PairingText.parse(text("not json")))
        assertNull(PairingText.parse(text("[]")))
        assertNull(PairingText.parse(text("""{"d":"$device","s":"$secret"}""")))
        assertNull(PairingText.parse(text("""{"d":1,"s":"$secret","r":$relay}""")))
        assertNull(PairingText.parse(text(json(s = "+/+/"))))
    }

    @Test
    fun `only an https relay of the known kind is accepted`() {
        fun relay(k: String = "rtdb", u: String = "https://relay.example.invalid", a: String = "key") =
            PairingText.parse(text(json(r = """{"k":"$k","u":"$u","a":"$a"}""")))
        assertNotNull(relay())
        assertNull(relay(k = "memory"))
        assertNull(relay(u = "http://relay.example.invalid"))
        assertNull(relay(u = "https://user@relay.example.invalid"))
        assertNull(relay(u = "https://relay.example.invalid/?x=1"))
        assertNull(relay(u = "https://relay.example.invalid#f"))
        assertNull(relay(u = "https://"))
        assertNull(relay(a = ""))
        assertNull(relay(a = "key&auth=1"))
    }
}
