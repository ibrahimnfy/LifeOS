package dev.lifeos.app.data

import java.util.Locale

/** A note of the list as the screen shows it: with the text of an edit that is still on its way. */
data class NoteRow(val note: NoteSummary, val text: String, val pending: OutboxItem?)

/** What the Notes screen shows while writes are still on their way. */
object NoteLists {
    /** A topic note's id (a file of the memory's notes folder): it stays when the note is edited. */
    fun isTopic(noteId: String): Boolean = noteId.startsWith("n-")

    /** The longest text a note may get: a capture's, or a topic note's body (the bridge's limits). */
    fun maxText(noteId: String): Int = if (isTopic(noteId)) MAX_TOPIC_NOTE else MAX_NOTE

    const val MAX_NOTE = 4000
    const val MAX_TOPIC_NOTE = 20000

    /** The list's preview holds the start of a note ([PREVIEW] characters): shorter means all of it. */
    const val PREVIEW = 280

    /** The whole text of a note of the list when its preview is all of it, else null (ask the computer). */
    fun wholeText(note: NoteSummary): String? = note.preview.takeIf { it.length < PREVIEW }

    /** Notes captured on this phone that the computer does not have yet, newest first. */
    fun waiting(outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.kind == OutboxItem.Kind.NOTE && it.state == OutboxItem.State.WAITING }.reversed()

    /** The latest edit of a note that has not been confirmed yet (a refused one does not count). */
    fun pendingEdit(noteId: String, outbox: List<OutboxItem>): OutboxItem? =
        outbox.lastOrNull { it.kind == OutboxItem.Kind.NOTE_EDIT && it.noteId == noteId && it.state != OutboxItem.State.FAILED }

    /**
     * Notes the list does not show (#184): deleted a moment ago (still in their undo time,
     * [justDeleted]) or with a deletion on its way. A note made on this phone is named
     * "local-" and its phone id ([LocalItems.PREFIX]).
     */
    fun hidden(outbox: List<OutboxItem>, justDeleted: Set<String>): Set<String> =
        justDeleted + outbox.filter { it.kind == OutboxItem.Kind.NOTE_DELETE && it.state != OutboxItem.State.FAILED }.mapNotNull { it.noteId }

    /** Notes captured on this phone that wait, without the ones being deleted. */
    fun waiting(outbox: List<OutboxItem>, hidden: Set<String>): List<OutboxItem> =
        waiting(outbox).filterNot { LocalItems.PREFIX + it.id in hidden }

    fun rows(notes: List<NoteSummary>, outbox: List<OutboxItem>, hidden: Set<String> = emptySet()): List<NoteRow> =
        notes.filterNot { it.id in hidden }.map { note ->
            val pending = pendingEdit(note.id, outbox)
            NoteRow(note, pending?.text ?: note.preview, pending)
        }

    /** What deleting a note does to what waits on this phone (#184), see [deletion]. */
    sealed interface Deletion {
        /** A note made here that never left the phone: it is simply dropped from the outbox. */
        data class Discard(val itemId: String) : Deletion

        /**
         * Delete [noteId] on the computer. [dropEdit]: an edit of it that has not left the phone
         * is dropped first (the computer still has the note under [noteId]). For a note made here
         * that may have reached the computer already, [noteId] is its "local-" name, and the
         * deletion follows it once the computer confirms it ([Outbox.retargetNote]).
         */
        data class Delete(val noteId: String, val dropEdit: String? = null) : Deletion

        /** An edit of it is being sent this very moment: its outcome is seconds away, so not now. */
        data object NotNow : Deletion
    }

    /** [resolve]: the computer's id of a note made here (its "local-" name), once it is known. */
    fun deletion(noteId: String, outbox: List<OutboxItem>, resolve: (String) -> String? = { null }): Deletion {
        if (LocalItems.isWaiting(noteId)) {
            val own = outbox.firstOrNull { it.kind == OutboxItem.Kind.NOTE && LocalItems.PREFIX + it.id == noteId }
            if (own == null) {
                // It reached the computer meanwhile: delete it there by its id. Unknown: it was
                // dropped, and the deletion finds nothing to do.
                return Deletion.Delete(resolve(noteId) ?: noteId)
            }
            val untouched = own.state == OutboxItem.State.WAITING && !own.sending && own.attempts == 0
            return if (untouched || own.state == OutboxItem.State.FAILED) Deletion.Discard(own.id) else Deletion.Delete(noteId)
        }
        val edit = pendingEdit(noteId, outbox) ?: return Deletion.Delete(noteId)
        // An edit of it waits. While it is being sent nothing is certain for a few seconds.
        // Otherwise the edit is dropped and the note deleted under the id the phone knows: an
        // edit that never left, or was answered "later", did not change it; one whose answer
        // was lost ("check") may have given a capture a new id, and then the deletion finds
        // nothing and the note stays in the list with its new text, to be deleted from there.
        // Before #202 that uncertain edit hid "Delete" until the owner had dealt with its
        // notice: a note that could not be deleted, with no word of why.
        return if (edit.sending) Deletion.NotNow else Deletion.Delete(noteId, edit.id)
    }

    /** Whether a note can be deleted now: always, except in the seconds an edit of it is being sent. */
    fun canDelete(noteId: String, outbox: List<OutboxItem>): Boolean = deletion(noteId, outbox) != Deletion.NotNow

    /**
     * Whether a note can be edited now. Every note of the list can (#202): a capture, and a
     * topic note (its body). Not a filed capture (it is in no list; its words live in a topic
     * file), and not a capture whose earlier edit already left the phone and is not confirmed:
     * the computer gives a capture a new id with every edit, so a second one would miss it. A
     * topic note keeps its id, so only an edit that is being sent right now holds the next back.
     */
    fun canEdit(note: NoteSummary, outbox: List<OutboxItem>): Boolean {
        if (!note.editable) return false
        val pending = pendingEdit(note.id, outbox) ?: return true
        if (pending.sending) return false
        return isTopic(note.id) || pending.state == OutboxItem.State.WAITING
    }

    /** The computer's search rule, for the saved copy while the computer cannot be asked. */
    fun matches(note: NoteSummary, query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return true
        return "${note.title.orEmpty()}\n${note.preview}".lowercase(Locale.ROOT).contains(needle)
    }

    /** A row's two lines: the title (a topic note's own, or a capture's first line) and the rest. */
    fun headline(title: String?, text: String): Pair<String, String?> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!title.isNullOrBlank()) return title to lines.joinToString(" ").ifEmpty { null }
        return (lines.firstOrNull() ?: "") to lines.drop(1).joinToString(" ").ifEmpty { null }
    }
}
