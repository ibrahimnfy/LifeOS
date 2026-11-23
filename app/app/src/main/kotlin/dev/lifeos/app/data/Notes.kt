package dev.lifeos.app.data

import java.util.Locale

/** A note of the list as the screen shows it: with the text of an edit that is still on its way. */
data class NoteRow(val note: NoteSummary, val text: String, val pending: OutboxItem?)

/** What the Notes screen shows while writes are still on their way. */
object NoteLists {
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

        /** An edit of it is on its way or may have arrived: its id is not known, so not now. */
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
        return if (edit.state == OutboxItem.State.WAITING && !edit.sending && edit.attempts == 0) Deletion.Delete(noteId, edit.id) else Deletion.NotNow
    }

    /** Whether the note screen offers "Delete" now: not while an edit of the note is on its way. */
    fun canDelete(noteId: String, outbox: List<OutboxItem>): Boolean = deletion(noteId, outbox) != Deletion.NotNow

    /**
     * Whether a capture can be edited now: a topic note never (it is edited on the computer),
     * a capture unless an earlier edit already left the phone and is not confirmed (the
     * computer gives the note a new id with every edit, so a second one would miss it).
     */
    fun canEdit(note: NoteSummary, outbox: List<OutboxItem>): Boolean {
        if (!note.editable) return false
        val pending = pendingEdit(note.id, outbox) ?: return true
        return pending.state == OutboxItem.State.WAITING && !pending.sending
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
