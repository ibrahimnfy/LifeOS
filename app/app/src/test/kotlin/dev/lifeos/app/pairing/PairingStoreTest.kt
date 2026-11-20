package dev.lifeos.app.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PairingStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val folder get() = File(temporary.root, "pairing")
    private val record = PairingRecord(
        deviceId = ByteArray(16) { (0xA0 + it).toByte() },
        name = "Örnek telefon",
        relay = RelayInfo("rtdb", "https://relay.example.invalid", "synthetic-api-key"),
        uid = "syntheticUid123",
        refreshToken = "refresh-1",
        pairedAtMillis = 1_800_000_000_000,
        vault = Vaults.AUTH,
    )

    @Test
    fun `nothing is stored at first`() {
        assertNull(PairingStore(folder).load())
        assertNull(PairingStore(folder).sealedSecret())
    }

    @Test
    fun `a saved pairing is read back by a new instance`() {
        PairingStore(folder).save(record, byteArrayOf(1, 2, 3), sent = 1, received = 1)
        val store = PairingStore(folder)
        val loaded = store.load()!!
        assertArrayEquals(record.deviceId, loaded.deviceId)
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", loaded.deviceHex)
        assertEquals(record.name, loaded.name)
        assertEquals(record.relay, loaded.relay)
        assertEquals(record.uid, loaded.uid)
        assertEquals(record.pairedAtMillis, loaded.pairedAtMillis)
        assertEquals(Vaults.AUTH, loaded.vault)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.sealedSecret())
        assertEquals(2, store.counters().nextSend())
        assertFalse(store.counters().acceptReceived(1))
    }

    @Test
    fun `the device secret is not in the readable files`() {
        // Only what the vault returned is written; the state file holds no key material.
        PairingStore(folder).save(record, "sealed-by-keystore".toByteArray(), 1, 1)
        val state = File(folder, "state.json").readText()
        assertFalse("secret" in state)
        assertEquals(setOf("secret.bin", "counters.json", "state.json"), folder.list()!!.toSet())
    }

    @Test
    fun `a new refresh token and the last contact are kept`() {
        val store = PairingStore(folder)
        store.save(record, byteArrayOf(1), 1, 1)
        assertNull(store.lastContactMillis())
        store.updateRefreshToken("refresh-2")
        store.recordContact(1_800_000_123_000)
        assertEquals("refresh-2", PairingStore(folder).load()!!.refreshToken)
        assertEquals(1_800_000_123_000, PairingStore(folder).lastContactMillis())
    }

    @Test
    fun `an incomplete or damaged pairing counts as none`() {
        val store = PairingStore(folder)
        store.save(record, byteArrayOf(1), 1, 1)
        File(folder, "state.json").writeText("{")
        assertNull(store.load())
        store.save(record, byteArrayOf(1), 1, 1)
        assertTrue(File(folder, "counters.json").delete())
        assertNull(store.load())
        assertThrows(IOException::class.java) { store.counters() }
    }

    @Test
    fun `unpairing leaves nothing behind`() {
        val store = PairingStore(folder)
        store.save(record, byteArrayOf(1, 2, 3), 1, 1)
        store.recordContact(5)
        store.wipe()
        assertFalse(folder.exists())
        assertNull(store.load())
        assertNull(store.sealedSecret())
        assertNull(store.lastContactMillis())
    }
}
