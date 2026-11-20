package dev.lifeos.app.relay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Access data of this phone's relay account. Not an end-to-end key, but never logged. */
class RelayTokens(val idToken: String, val refreshToken: String, val uid: String, val expiresInSeconds: Long)

class RelayAuthException(val refused: Boolean) : Exception(if (refused) "refused" else "unreachable")

/** The relay's account service: an anonymous account per pairing. */
interface RelayAuth {
    suspend fun signUp(): RelayTokens
    suspend fun refresh(refreshToken: String): RelayTokens
    suspend fun deleteAccount(idToken: String)
}

/** Hands out a valid access token, renewing it shortly before it runs out. */
class TokenSource(
    private val auth: RelayAuth,
    initial: RelayTokens?,
    private var refreshToken: String,
    private val nowMillis: () -> Long,
    private val onNewRefreshToken: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var idToken: String? = initial?.idToken
    private var validUntil: Long = initial?.let { nowMillis() + (it.expiresInSeconds - MARGIN_SECONDS) * 1000 } ?: 0

    /** When the current token stops being used (milliseconds); streams reconnect before it. */
    val expiresAtMillis: Long get() = validUntil

    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val current = idToken
        if (current != null && !forceRefresh && nowMillis() < validUntil) return current
        val fresh = auth.refresh(refreshToken)
        if (fresh.refreshToken != refreshToken) {
            refreshToken = fresh.refreshToken
            onNewRefreshToken(fresh.refreshToken)
        }
        idToken = fresh.idToken
        validUntil = nowMillis() + (fresh.expiresInSeconds - MARGIN_SECONDS) * 1000
        fresh.idToken
    }

    private companion object {
        const val MARGIN_SECONDS = 300
    }
}
