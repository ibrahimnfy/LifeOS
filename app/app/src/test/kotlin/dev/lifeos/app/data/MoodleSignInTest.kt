package dev.lifeos.app.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same values as bridge/tests/test_moodle_login.py: site, passport "pp", token "tok-123". */
class MoodleSignInTest {
    private val site = "https://moodle.example.invalid"
    private val address = "moodlemobile://token=YTRjYzNiODU2M2NlZDVkYzA5YmFiNTZlM2M1MzgzNmM6Ojp0b2stMTIzOjo6cHJpdi00NTY="

    /** Signed for the http:// form of the site, and without base64 padding. */
    private val httpAddress = "moodlemobile://token=ZmNkZjhlMGRiNzcxMGM1YjJmMzQ1NzBkZTMyZDcyOWM6Ojp0b2stMTIzOjo6cHJpdi00NTY"

    @Test
    fun `an address of this attempt is accepted, others are not`() {
        assertTrue(MoodleSignIn.belongsTo(address, site, "pp"))
        assertTrue(MoodleSignIn.belongsTo(address, "$site/", "pp"))
        assertTrue(MoodleSignIn.belongsTo(httpAddress, site, "pp"))
        assertFalse(MoodleSignIn.belongsTo(address, site, "another passport"))
        assertFalse(MoodleSignIn.belongsTo(address, "https://elsewhere.invalid", "pp"))
        assertFalse(MoodleSignIn.belongsTo("moodlemobile://token=", site, "pp"))
        assertFalse(MoodleSignIn.belongsTo("moodlemobile://token=%%%", site, "pp"))
        assertFalse(MoodleSignIn.belongsTo(address, site, ""))
        // Only the site's part and no token: not a sign-in.
        assertFalse(MoodleSignIn.belongsTo("moodlemobile://token=YWJj", site, "pp"))
    }

    @Test
    fun `only the sign-in scheme is taken as a redirect`() {
        assertTrue(MoodleSignIn.isRedirect(address))
        assertFalse(MoodleSignIn.isRedirect(null))
        assertFalse(MoodleSignIn.isRedirect("https://moodle.example.invalid/?token=x"))
        assertFalse(MoodleSignIn.isRedirect("moodlemobile://other"))
    }

    @Test
    fun `start and finish are direct requests with the bridge's fields`() = runTest {
        val transport = FakeTransport()
        transport.answer(
            "POST /moodle/signin/start",
            200,
            """{"link":"$site/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=pp&urlscheme=moodlemobile","wwwroot":"$site","passport":"pp","scheme":"moodlemobile","expires_in":600}""",
        )
        transport.answer("POST /moodle/signin/finish", 200, """{"stored":true,"release":"4.1.9","missing":[]}""")
        val api = Api(transport)
        val start = BridgeJson.decodeFromJsonElement(SignInStart.serializer(), api.call("POST", "/moodle/signin/start"))
        assertEquals("pp", start.passport)
        assertEquals(600L, start.expiresIn)
        assertTrue(MoodleSignIn.belongsTo(address, start.wwwroot, start.passport))
        val done = BridgeJson.decodeFromJsonElement(
            SignInDone.serializer(),
            api.call("POST", "/moodle/signin/finish", body = buildJsonObject { put("address", JsonPrimitive(address)) }),
        )
        assertTrue(done.stored)
        assertEquals(JsonPrimitive(address), transport.calls.last().body!!.jsonObject["address"])
    }
}
