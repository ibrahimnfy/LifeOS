package dev.lifeos.app.lock

/** How long the app may be out of sight before it asks for the fingerprint or PIN again. */
enum class LockTimeout(val storedValue: String, val millis: Long) {
    IMMEDIATELY("0", 0),
    ONE_MINUTE("60", 60_000),
    FIVE_MINUTES("300", 300_000);

    /** Whether the app must lock when it returns after having been hidden since [hiddenAtMillis]. */
    fun shouldLock(hiddenAtMillis: Long?, nowMillis: Long, graceMillis: Long = 0): Boolean {
        if (hiddenAtMillis == null) return false
        val away = nowMillis - hiddenAtMillis
        // A clock that jumped backwards locks: never trust a negative interval.
        return away < 0 || away >= maxOf(millis, graceMillis)
    }

    companion object {
        val Default = ONE_MINUTE

        /**
         * How long a system screen the owner opened from inside the unlocked app (speech
         * recognition, a file picker) may cover it without a lock, whatever the chosen time.
         */
        const val SYSTEM_SCREEN_GRACE_MILLIS = 90_000L

        fun fromStored(value: String?): LockTimeout = entries.firstOrNull { it.storedValue == value } ?: Default
    }
}
