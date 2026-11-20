package dev.lifeos.app

import android.content.Context
import android.content.Intent
import dev.lifeos.app.data.Transport
import dev.lifeos.app.pairing.SecretVault

/** Release builds have no test aids. The debug twin is in `src/debug/`. */
@Suppress("UNUSED_PARAMETER")
object TestAids {
    const val VAULT = ""

    fun pairingText(intent: Intent?): String? = null

    fun realLock(intent: Intent?): Boolean = false

    fun readSwitches(intent: Intent?) = Unit

    fun vault(kind: String): SecretVault? = null

    fun transport(context: Context, real: Transport): Transport = real
}
