package dev.lifeos.app.pairing

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import dev.lifeos.app.relay.FakeMailbox
import dev.lifeos.app.relay.MailboxException
import dev.lifeos.app.relay.MailboxKeys
import dev.lifeos.app.relay.RelayAuth
import dev.lifeos.app.relay.RelayAuthException
import dev.lifeos.app.relay.RelayTokens
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingTest {
    private val e2e = E2e(testSodium)
    private val box = FakeMailbox()
    private val offer = PairingText.parse(Vectors.pairingText)!!
    private val helloPath = MailboxKeys.hello(offer.deviceHex)
    private val folder = MailboxKeys.toPhone(offer.deviceHex)
    private var access = false

    private class Auth(var fail: RelayAuthException? = null) : RelayAuth {
        val deleted = mutableListOf<String>()
        var signUps = 0

        override suspend fun signUp(): RelayTokens {
            fail?.let { throw it }
            signUps++
            return RelayTokens("id-token", "refresh-token", "syntheticUid123", 3600)
        }

        override suspend fun refresh(refreshToken: String): RelayTokens = throw IllegalStateException()

        override suspend fun deleteAccount(idToken: String) {
            deleted += idToken
        }
    }

    private val auth = Auth()

    init {
        box.readDenied = { it.startsWith(folder) && !access }
        box.writeOnce = { it == helloPath }
    }

    private fun TestScope.now(): Long = 1_800_000_000_000 + testScheduler.currentTime

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun TestScope.fromBridge(payload: String, rid: String = "pair", kind: Int = Kind.END, secret: ByteArray = offer.secret, counter: Long = 1): String =
        MailboxKeys.encode(e2e.seal(secret, Header(offer.deviceId, Direction.BRIDGE_TO_PHONE, counter, kind), now() / 1000, rid, json(payload)))

    private suspend fun TestScope.pair(codes: MutableList<String> = mutableListOf(), label: String = "My  phone"): Paired =
        Pairing(e2e) { now() }.pair(offer, label, auth, { box }, { codes += it })

    private suspend fun TestScope.failure(): PairingFailure.Reason {
        try {
            pair()
        } catch (error: PairingFailure) {
            return error.reason
        }
        fail("expected a PairingFailure")
        throw IllegalStateException()
    }

    /** The computer: waits for the hello, "compares", then grants access and answers. */
    private fun TestScope.computer(confirmAfterMillis: Long, codeSeen: MutableList<String>) = backgroundScope.launch {
        while (helloPath !in box.tree) delay(100)
        val hello = MailboxKeys.decode(box.tree.getValue(helloPath))!!
        codeSeen += e2e.comparisonCode(offer.secret, hello)
        delay(confirmAfterMillis)
        access = true
        box.tree["$folder/${MailboxKeys.newKey(now(), ByteArray(4))}"] = fromBridge("""{"paired":true,"name":"Örnek telefon"}""")
    }

    @Test
    fun `pairing shows the computer's code and completes after confirmation`() = runTest {
        val computerCodes = mutableListOf<String>()
        val phoneCodes = mutableListOf<String>()
        computer(confirmAfterMillis = 20_000, computerCodes)
        val paired = pair(phoneCodes)

        assertEquals(computerCodes, phoneCodes)
        assertEquals(1, phoneCodes.size)
        assertEquals("Örnek telefon", paired.name)
        assertEquals(1, paired.sendCounter)
        assertEquals(1, paired.receivedCounter)
        assertEquals("syntheticUid123", paired.tokens.uid)
        // The hello: counter 1, kind HELLO, the relay account id and the label inside.
        val hello = e2e.open(offer.secret, MailboxKeys.decode(box.recorded.single().second)!!)
        assertEquals(helloPath, box.recorded.single().first)
        assertEquals(1, hello.header.counter)
        assertEquals(Kind.HELLO, hello.header.kind)
        assertEquals(Direction.PHONE_TO_BRIDGE, hello.header.direction)
        assertEquals(json("""{"uid":"syntheticUid123","name":"My phone"}"""), hello.payload)
        assertTrue(kotlin.math.abs(hello.timestamp - now() / 1000) < 60)
        // The first message was taken out of the mailbox; the relay account is kept.
        assertTrue(box.children(folder).isEmpty())
        assertEquals(emptyList<String>(), auth.deleted)
    }

    @Test
    fun `without confirmation pairing ends after two minutes and the relay account is removed`() = runTest {
        val start = now()
        assertEquals(PairingFailure.Reason.TIMEOUT, failure())
        assertTrue(now() - start in 120_000..123_000)
        assertEquals(1, box.recorded.size)
        assertEquals(listOf("id-token"), auth.deleted)
    }

    @Test
    fun `a code that was already used is refused`() = runTest {
        box.tree[helloPath] = "someone else's hello"
        assertEquals(PairingFailure.Reason.REFUSED, failure())
        assertEquals(listOf("id-token"), auth.deleted)
    }

    @Test
    fun `the hello is sealed once and the same bytes are sent again after a failure`() = runTest {
        box.putFailures += MailboxException.Reason.NOT_SENT
        box.putFailures += MailboxException.Reason.UNREACHABLE
        box.failedPutsArrive = true
        val computerCodes = mutableListOf<String>()
        val phoneCodes = mutableListOf<String>()
        computer(confirmAfterMillis = 1_000, computerCodes)
        pair(phoneCodes)
        // Tries: not sent; sent but the answer was lost; refused because it is already there.
        assertEquals(1, box.recorded.size)
        assertEquals(1, box.recorded.map { it.second }.toSet().size)
        assertEquals(computerCodes, phoneCodes)
    }

    @Test
    fun `forged or unexpected first messages are ignored`() = runTest {
        access = true
        fun key(n: Int) = MailboxKeys.newKey(now() + n, ByteArray(4))
        box.tree["$folder/${key(0)}"] = "not base64 !!"
        box.tree["$folder/${key(1)}"] = fromBridge("""{"paired":true,"name":"x"}""", secret = ByteArray(32) { 3 })
        box.tree["$folder/${key(2)}"] = fromBridge("""{"paired":true,"name":"x"}""", rid = "other")
        box.tree["$folder/${key(3)}"] = fromBridge("""{"paired":false}""")
        box.tree["$folder/${key(4)}"] = fromBridge("""{"paired":true}""", kind = Kind.CHUNK)
        box.tree["$folder/${key(5)}"] = MailboxKeys.encode(MailboxKeys.decode(Vectors.named("hello").blob.let(MailboxKeys::encode))!!)
        assertEquals(PairingFailure.Reason.TIMEOUT, failure())
    }

    @Test
    fun `an unreachable account service is reported and nothing is written`() = runTest {
        auth.fail = RelayAuthException(refused = false)
        assertEquals(PairingFailure.Reason.UNREACHABLE, failure())
        auth.fail = RelayAuthException(refused = true)
        assertEquals(PairingFailure.Reason.REFUSED, failure())
        assertTrue(box.recorded.isEmpty())
    }

    @Test
    fun `labels are cleaned like on the computer`() {
        assertEquals("My phone", Pairing.cleanLabel("  My \n  phone "))
        assertEquals("Phone", Pairing.cleanLabel("   "))
        assertEquals(40, Pairing.cleanLabel("x".repeat(100)).length)
    }
}
