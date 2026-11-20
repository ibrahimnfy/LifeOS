package dev.lifeos.app

import android.content.Intent
import dev.lifeos.app.pairing.SecretVault

/** Release builds have no test aids. The debug twin is in `src/debug/`. */
object TestAids {
    const val VAULT = ""

    @Suppress("UNUSED_PARAMETER")
    fun pairingText(intent: Intent?): String? = null

    @Suppress("UNUSED_PARAMETER")
    fun realLock(intent: Intent?): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun vault(kind: String): SecretVault? = null
}
