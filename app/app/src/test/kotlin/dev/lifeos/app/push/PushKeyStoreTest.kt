package dev.lifeos.app.push

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Vectors
import dev.lifeos.app.crypto.testSodium
import dev.lifeos.app.pairing.SecretVault
import dev.lifeos.app.pairing.VaultException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class PushKeyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A stand-in for the Keystore key: reverses the bytes, and can be destroyed. */
    private class FakeVault : SecretVault {
        var destroyed = false
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray): ByteArray {
            if (destroyed) throw VaultException(VaultException.Reason.LOST)
            return sealed.reversedArray()
        }
        override fun destroy() {
            destroyed = true
        }
    }

    private val vault = FakeVault()
    private fun store() = PushKeyStore(File(folder.root, "push")) { vault }
    private val e2e = E2e(testSodium)
    private val pushKey = e2e.deriveKey(Vectors.secret, Direction.PUSH)

    @Test
    fun `the push key is kept sealed and opens the bridge's hints after a restart, without the device secret`() {
        assertFalse(store().has())
        assertNull(store().load())
        assertTrue(store().save(pushKey, Vectors.deviceId))
        // Never stored in the clear.
        assertFalse(File(folder.root, "push/push.key").readBytes().contentEquals(pushKey))
        val (key, device) = store().load()!!
        assertArrayEquals(pushKey, key)
        val restarted = store()
        val hint = PushHints.open(e2e, key, device, Base64.getEncoder().encodeToString(Vectors.named("hint_answer").blob), restarted::acceptCounter)
        assertEquals("answer", hint?.event)
        assertEquals("session-1", hint?.ref)
    }

    @Test
    fun `the push counter only moves forward and survives a restart`() {
        assertTrue(store().acceptCounter(5))
        assertFalse(store().acceptCounter(5))
        assertFalse(store().acceptCounter(4))
        assertTrue(store().acceptCounter(6))
    }

    @Test
    fun `a changed push address is sent again, an unchanged one is not`() {
        val store = store()
        assertNull(store.sentToken())
        store.markTokenSent("token-a")
        assertEquals("token-a", store().sentToken())
        store.markTokenSent(null)
        assertNull(store.sentToken())
    }

    @Test
    fun `unpairing removes the key, the counter and the address, and destroys the vault key`() {
        val store = store()
        store.save(pushKey, Vectors.deviceId)
        store.acceptCounter(9)
        store.markTokenSent("token-a")
        store.wipe()
        assertFalse(store.has())
        assertNull(store.load())
        assertNull(store.sentToken())
        assertTrue(store.acceptCounter(1))
        assertTrue(vault.destroyed)
    }

    @Test
    fun `without a usable vault nothing is kept`() {
        val broken = PushKeyStore(File(folder.root, "other")) { null }
        assertFalse(broken.save(pushKey, Vectors.deviceId))
        assertNull(broken.load())
    }
}
