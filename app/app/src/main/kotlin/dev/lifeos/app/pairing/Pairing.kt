package dev.lifeos.app.pairing

import dev.lifeos.app.crypto.Direction
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.Header
import dev.lifeos.app.crypto.Kind
import dev.lifeos.app.crypto.Rejected
import dev.lifeos.app.relay.Mailbox
import dev.lifeos.app.relay.MailboxException
import dev.lifeos.app.relay.MailboxKeys
import dev.lifeos.app.relay.RelayAuth
import dev.lifeos.app.relay.RelayAuthException
import dev.lifeos.app.relay.RelayTokens
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

/** A completed pairing, ready to be stored. */
class Paired(
    val offer: PairingOffer,
    val tokens: RelayTokens,
    /** The name the computer knows this phone by. */
    val name: String,
    val sendCounter: Long,
    val receivedCounter: Long,
)

class PairingFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The relay or its account service could not be reached. */
        UNREACHABLE,

        /** The relay did not take the hello: the code was already used or is not valid here. */
        REFUSED,

        /** Nobody confirmed on the computer within two minutes. */
        TIMEOUT,
    }
}

/**
 * The phone's side of pairing (docs/security.md, section 4), after the QR code was read:
 * anonymous relay account → sealed hello with that account's id → show the comparison code →
 * wait for the bridge's first message. Nothing is stored here; on any failure the relay
 * account is removed again and the caller forgets the secret.
 */
class Pairing(private val e2e: E2e, private val nowMillis: () -> Long) {

    suspend fun pair(
        offer: PairingOffer,
        label: String,
        auth: RelayAuth,
        mailboxFor: (RelayTokens) -> Mailbox,
        onCode: (String) -> Unit,
    ): Paired {
        val deadline = nowMillis() + PAIRING_MILLIS
        val tokens = try {
            auth.signUp()
        } catch (error: RelayAuthException) {
            throw PairingFailure(if (error.refused) PairingFailure.Reason.REFUSED else PairingFailure.Reason.UNREACHABLE)
        }
        try {
            val mailbox = mailboxFor(tokens)
            // The hello is sealed exactly once: the comparison code is computed over these
            // bytes, and a second, different hello would destroy the pairing on the computer.
            val hello = e2e.seal(
                offer.secret,
                Header(offer.deviceId, Direction.PHONE_TO_BRIDGE, HELLO_COUNTER, Kind.HELLO),
                nowMillis() / 1000,
                "hello",
                buildJsonObject {
                    put("uid", JsonPrimitive(tokens.uid))
                    put("name", JsonPrimitive(cleanLabel(label)))
                },
            )
            sendHello(mailbox, MailboxKeys.hello(offer.deviceHex), MailboxKeys.encode(hello), deadline)
            onCode(e2e.comparisonCode(offer.secret, hello))
            val (name, received) = awaitConfirmation(mailbox, offer, deadline)
            return Paired(offer, tokens, name, HELLO_COUNTER, received)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                try {
                    auth.deleteAccount(tokens.idToken)
                } catch (_: RelayAuthException) {
                }
            }
            throw error
        }
    }

    /** Write the same hello until it is there. A refusal after an unclear try means it is there. */
    private suspend fun sendHello(mailbox: Mailbox, path: String, value: String, deadline: Long) {
        var maybeWritten = false
        while (true) {
            try {
                mailbox.put(path, value)
                return
            } catch (error: MailboxException) {
                when (error.reason) {
                    MailboxException.Reason.DENIED ->
                        if (maybeWritten) return else throw PairingFailure(PairingFailure.Reason.REFUSED)
                    MailboxException.Reason.NOT_SENT -> Unit
                    else -> maybeWritten = true
                }
            }
            if (nowMillis() + RETRY_MILLIS >= deadline) throw PairingFailure(PairingFailure.Reason.UNREACHABLE)
            delay(RETRY_MILLIS)
        }
    }

    /**
     * The mailbox of this device cannot be read until the owner has confirmed on the computer,
     * so "denied" means "not yet". The first genuine message must be the bridge's
     * `{"paired": true}` with request id "pair".
     */
    private suspend fun awaitConfirmation(mailbox: Mailbox, offer: PairingOffer, deadline: Long): Pair<String, Long> {
        val folder = MailboxKeys.toPhone(offer.deviceHex)
        while (nowMillis() < deadline) {
            try {
                for (key in mailbox.keys(folder).sorted()) {
                    val blob = MailboxKeys.decode(mailbox.get("$folder/$key")) ?: continue
                    val message = try {
                        val header = e2e.parseHeader(blob)
                        if (!header.isFor(offer.deviceId) || header.direction != Direction.BRIDGE_TO_PHONE) continue
                        e2e.open(offer.secret, blob)
                    } catch (_: Rejected) {
                        continue
                    }
                    val payload = message.payload as? JsonObject ?: continue
                    val paired = (payload["paired"] as? JsonPrimitive)?.booleanOrNull == true
                    if (message.header.kind != Kind.END || message.requestId != "pair" || !paired) continue
                    if (message.header.counter < 1) continue
                    try {
                        mailbox.delete("$folder/$key")
                    } catch (_: MailboxException) {
                    }
                    val name = (payload["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    return name to message.header.counter
                }
            } catch (_: MailboxException) {
                // Not confirmed yet, or the relay is unreachable for a moment: keep waiting.
            }
            delay(POLL_MILLIS)
        }
        throw PairingFailure(PairingFailure.Reason.TIMEOUT)
    }

    companion object {
        const val PAIRING_MILLIS = 120_000L
        const val POLL_MILLIS = 1_500L
        const val RETRY_MILLIS = 2_000L
        const val HELLO_COUNTER = 1L
        const val MAX_LABEL = 40
        const val DEFAULT_LABEL = "Phone"

        /** As the bridge's `clean_name`: single spaces, at most 40 characters. */
        fun cleanLabel(label: String): String =
            label.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(MAX_LABEL).trim()
                .ifEmpty { DEFAULT_LABEL }
    }
}
