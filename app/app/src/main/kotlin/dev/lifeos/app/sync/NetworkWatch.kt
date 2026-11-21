package dev.lifeos.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Follows the phone's default network through Android's network callback (#165) and reports
 * whether it is validated: it has internet and the system checked that it really reaches it
 * (a captive portal or a network without internet does not count).
 */
object NetworkWatch {
    /** Whether the default network is validated right now. */
    fun validatedNow(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return try {
            manager.getNetworkCapabilities(manager.activeNetwork)?.let(::validated) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Report every change of the default network to [onChange] (on a system thread), for as
     * long as the process lives. False if the callback could not be registered.
     */
    fun register(context: Context, onChange: (Boolean) -> Unit): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                synchronized(this) { current = network }
                onChange(validated(capabilities))
            }

            override fun onLost(network: Network) {
                // When the default network is replaced, the old one may still be reported lost.
                val wasCurrent = synchronized(this) {
                    (current == null || current == network).also { if (it) current = null }
                }
                if (wasCurrent) onChange(false)
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            true
        } catch (_: RuntimeException) {
            // Too many callbacks or no permission: sending then waits for the usual checks.
            false
        }
    }

    private fun validated(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
