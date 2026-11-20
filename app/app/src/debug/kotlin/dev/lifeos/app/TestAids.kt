package dev.lifeos.app

import android.content.Intent
import dev.lifeos.app.pairing.KeystoreVault
import dev.lifeos.app.pairing.SecretVault

/**
 * Test aids of DEBUG builds only. This file is in `src/debug/`; release builds compile the
 * empty twin in `src/release/` instead, so none of this exists there.
 *
 * They let an automated check pair and use the connection without the camera and without the
 * owner's fingerprint:
 *
 *     adb shell am start -n dev.lifeos.app/.MainActivity --es lifeos.test.pairing_text '<text>'
 *
 * A pairing made this way is stored under a Keystore key that needs no confirmation, and the
 * app lock is skipped for it. A pairing made the normal way (camera) is never affected: its
 * key demands the owner's confirmation whatever is passed here.
 *
 * Adding `--ez lifeos.test.real_lock true` replaces only the camera: the app then asks for
 * fingerprint or screen lock and stores the pairing exactly like a scanned one, app lock included.
 */
object TestAids {
    const val VAULT = "test"
    private const val EXTRA_PAIRING_TEXT = "lifeos.test.pairing_text"
    private const val EXTRA_REAL_LOCK = "lifeos.test.real_lock"

    /** The pairing text handed over by the test, if any. Read once. */
    fun pairingText(intent: Intent?): String? {
        val text = intent?.getStringExtra(EXTRA_PAIRING_TEXT)
        intent?.removeExtra(EXTRA_PAIRING_TEXT)
        return text
    }

    /** Whether the test wants the real, confirmation-bound key (only the camera is replaced). */
    fun realLock(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_REAL_LOCK, false) == true

    /** A Keystore key without user confirmation, for test pairings only. */
    fun vault(kind: String): SecretVault? =
        if (kind == VAULT) KeystoreVault("lifeos.device.test.v1") {} else null
}
