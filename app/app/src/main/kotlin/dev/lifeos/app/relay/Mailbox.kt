package dev.lifeos.app.relay

import kotlinx.coroutines.flow.Flow

/** One entry of a mailbox folder: its key and its value (base64 text when it is a message). */
data class MailboxEntry(val key: String, val value: String?)

/** What a mailbox listener reports. */
sealed interface MailboxEvent {
    /** New entries. The receiver deletes each one it has taken; that is the acknowledgement. */
    data class Entries(val entries: List<MailboxEntry>) : MailboxEvent

    /** The mailbox is being reached (stream open, or a poll succeeded). */
    data object Up : MailboxEvent

    /** The mailbox cannot be reached at the moment; the listener keeps trying. */
    data object Down : MailboxEvent
}

/** The relay could not be used. Never carries addresses, tokens or content. */
class MailboxException(val reason: Reason, val status: Int = 0) : Exception(reason.name) {
    enum class Reason {
        /** No connection was made: the request certainly did not arrive. */
        NOT_SENT,

        /** The connection failed at some later point: the request may have arrived. */
        UNREACHABLE,

        /** The relay refused access to this place. */
        DENIED,

        /** Any other answer of the relay. */
        STATUS,
    }
}

/** What the relay client needs from the mailbox (the untrusted relay). */
interface Mailbox {
    suspend fun put(path: String, value: String)
    suspend fun get(path: String): String?
    suspend fun delete(path: String)
    suspend fun keys(path: String): List<String>
    fun listen(path: String): Flow<MailboxEvent>
}
