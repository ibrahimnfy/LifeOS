package dev.lifeos.app.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockTimeoutTest {
    @Test
    fun `immediately locks whenever the app was hidden`() {
        assertTrue(LockTimeout.IMMEDIATELY.shouldLock(hiddenAtMillis = 1000, nowMillis = 1000))
        assertFalse(LockTimeout.IMMEDIATELY.shouldLock(hiddenAtMillis = null, nowMillis = 1000))
    }

    @Test
    fun `a timeout locks from its limit on`() {
        assertFalse(LockTimeout.ONE_MINUTE.shouldLock(0, 59_999))
        assertTrue(LockTimeout.ONE_MINUTE.shouldLock(0, 60_000))
        assertFalse(LockTimeout.FIVE_MINUTES.shouldLock(0, 299_999))
        assertTrue(LockTimeout.FIVE_MINUTES.shouldLock(0, 300_000))
    }

    @Test
    fun `a system screen the owner opened from the app may cover it for the grace time`() {
        val grace = LockTimeout.SYSTEM_SCREEN_GRACE_MILLIS
        assertFalse(LockTimeout.IMMEDIATELY.shouldLock(0, grace - 1, grace))
        assertTrue(LockTimeout.IMMEDIATELY.shouldLock(0, grace, grace))
        // A longer chosen time stays the longer one.
        assertFalse(LockTimeout.FIVE_MINUTES.shouldLock(0, grace, grace))
        assertTrue(LockTimeout.IMMEDIATELY.shouldLock(hiddenAtMillis = 5000, nowMillis = 4000, graceMillis = grace))
    }

    @Test
    fun `a clock that went backwards locks`() {
        assertTrue(LockTimeout.FIVE_MINUTES.shouldLock(hiddenAtMillis = 5000, nowMillis = 4000))
    }

    @Test
    fun `stored values round trip and unknown ones give the default`() {
        LockTimeout.entries.forEach { assertEquals(it, LockTimeout.fromStored(it.storedValue)) }
        assertEquals(LockTimeout.ONE_MINUTE, LockTimeout.fromStored(null))
        assertEquals(LockTimeout.ONE_MINUTE, LockTimeout.fromStored("7"))
    }
}
