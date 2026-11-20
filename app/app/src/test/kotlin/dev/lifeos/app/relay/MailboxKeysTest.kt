package dev.lifeos.app.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxKeysTest {
    @Test
    fun `keys are thirteen digits of milliseconds, a dash and eight hex digits`() {
        val key = MailboxKeys.newKey(1_800_000_000_500, byteArrayOf(0x0a, 0xff.toByte(), 0x00, 0x7f))
        assertEquals("1800000000500-0aff007f", key)
        assertTrue(MailboxKeys.isKey(key))
        assertEquals(1_800_000_000_500, MailboxKeys.keyTime(key))
        assertEquals("0000000000042-00000000", MailboxKeys.newKey(42, ByteArray(4)))
    }

    @Test
    fun `keys sort by time`() {
        val earlier = MailboxKeys.newKey(999_999_999_999, byteArrayOf(-1, -1, -1, -1))
        val later = MailboxKeys.newKey(1_000_000_000_000, ByteArray(4))
        assertTrue(earlier < later)
    }

    @Test
    fun `foreign keys are recognised`() {
        for (key in listOf("strange-key", "", "1800000000500-0AFF007F", "180000000050-0aff007f", "1800000000500-0aff007", "1800000000500_0aff007f")) {
            assertFalse(key, MailboxKeys.isKey(key))
            assertNull(key, MailboxKeys.keyTime(key))
        }
        assertThrows(IllegalArgumentException::class.java) { MailboxKeys.newKey(1, ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { MailboxKeys.newKey(-1, ByteArray(4)) }
    }

    @Test
    fun `values are standard base64 and anything else is nothing`() {
        assertEquals("AAH/", MailboxKeys.encode(byteArrayOf(0, 1, -1)))
        assertArrayEquals(byteArrayOf(0, 1, -1), MailboxKeys.decode("AAH/"))
        assertNull(MailboxKeys.decode("not base64 !!"))
        assertNull(MailboxKeys.decode(null))
    }

    @Test
    fun `folders follow the bridge's layout`() {
        assertEquals("lifeos/ab/b", MailboxKeys.toBridge("ab"))
        assertEquals("lifeos/ab/p", MailboxKeys.toPhone("ab"))
        assertEquals("pair/ab/hello", MailboxKeys.hello("ab"))
    }
}
