package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

// ---------------------------------------------------------------- questions and approvals (docs/bridge.md)

/** One choice of a question. */
@Serializable
data class AskOption(val label: String = "", val description: String = "")

/** One question of the assistant, with its choices; [multiSelect]: several may be chosen. */
@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String = "",
    @SerialName("multi_select") val multiSelect: Boolean = false,
    val options: List<AskOption> = emptyList(),
)

/**
 * What a run waits for, as the bridge sends it: an `interaction` event in the answer, and the
 * entries of `GET /sessions/{id}/interactions`.
 */
@Serializable
data class InteractionData(
    val id: String = "",
    val kind: String = "",
    val tool: String = "",
    val command: String = "",
    val folder: String = "",
    val category: String = "",
    val questions: List<AskQuestion> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class PendingInteractions(val pending: List<InteractionData> = emptyList())

/**
 * A question or an approval the run on the computer waits for. Nothing is ever approved by
 * default: no answer before [deadline], a cancelled run and a lost answer all count as "no"
 * on the computer.
 */
data class Interaction(
    val id: String,
    val kind: Kind,
    /** Approval: exactly what will run, and where. */
    val command: String = "",
    val folder: String = "",
    /** Approval: why it needs a yes ([DELETION] is shown as a warning). */
    val category: String = "",
    val questions: List<AskQuestion> = emptyList(),
    /** Until when the computer waits, on the phone's own running clock (not the wall clock). */
    val deadline: Long,
    val status: Status = Status.WAITING,
    /** The answer could not be delivered; the owner can try again while the computer still waits. */
    val sendFailed: Boolean = false,
) {
    enum class Kind { APPROVAL, QUESTION }

    enum class Status {
        /** The computer waits for the owner. */
        WAITING,

        /** The owner's answer is on its way. */
        SENDING,
        ALLOWED,
        REFUSED,

        /** A question was answered. */
        ANSWERED,

        /** A question was declined. */
        SKIPPED,

        /** Not answered in time: the computer counted it as "no". */
        EXPIRED,

        /** The computer no longer waits for it (the run ended, or it was answered elsewhere). */
        GONE,
    }

    val isDeletion: Boolean get() = category == DELETION

    /** Whether the owner can still answer at [now] (the phone's running clock). */
    fun open(now: Long): Boolean = status == Status.WAITING && now < deadline

    fun remainingMillis(now: Long): Long = (deadline - now).coerceAtLeast(0)

    companion object {
        const val DELETION = "deletion"

        /** The computer's default when it names no times. */
        private const val DEFAULT_WAIT_MILLIS = 600_000L

        /**
         * [data] as received at [now]. [fresh]: it was just created (an event of the answer), so
         * the whole waiting time lies ahead, whatever the two clocks say; otherwise (read from
         * the list later) the time left is the computer's end time against the phone's clock
         * [wallMillis]. Null if it is nothing the app can show.
         */
        fun of(data: InteractionData, now: Long, wallMillis: Long, fresh: Boolean): Interaction? {
            if (!Chat.validId(data.id)) return null
            val created = moment(data.createdAt)
            val expires = moment(data.expiresAt)
            val wait = when {
                fresh && created != null && expires != null -> expires - created
                !fresh && expires != null -> expires - wallMillis
                else -> DEFAULT_WAIT_MILLIS
            }
            val deadline = now + wait.coerceIn(0, MAX_WAIT_MILLIS)
            return when (data.kind) {
                "approval" -> if (data.command.isBlank()) null else Interaction(data.id, Kind.APPROVAL, data.command, data.folder, data.category, deadline = deadline)
                "question" -> {
                    val questions = data.questions.filter { it.question.isNotBlank() }
                    if (questions.isEmpty()) null else Interaction(data.id, Kind.QUESTION, questions = questions, deadline = deadline)
                }
                else -> null
            }
        }

        /** The bridge allows at most an hour. */
        private const val MAX_WAIT_MILLIS = 3_600_000L

        private fun moment(iso: String?): Long? = try {
            iso?.let { Instant.parse(it).toEpochMilli() }
        } catch (_: Exception) {
            null
        }

        /** `{"approve": true|false}` */
        fun approvalBody(approve: Boolean): JsonObject = buildJsonObject { put("approve", JsonPrimitive(approve)) }

        /**
         * `{"answers": {"<question>": "<label or own text>" | [labels]}}`: one text for a question
         * with a single choice, a list where several may be chosen. Questions without an answer
         * are left out.
         */
        fun answersBody(questions: List<AskQuestion>, answers: Map<String, List<String>>): JsonObject = buildJsonObject {
            put(
                "answers",
                buildJsonObject {
                    for (question in questions) {
                        val given = answers[question.question].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                        if (given.isEmpty()) continue
                        val value: JsonElement = if (question.multiSelect) JsonArray(given.map(::JsonPrimitive)) else JsonPrimitive(given.first())
                        put(question.question, value)
                    }
                },
            )
        }

        /** `{"decline": true}`: the owner does not want to answer; the assistant goes on without. */
        val declineBody: JsonObject = buildJsonObject { put("decline", JsonPrimitive(true)) }

        /** Every question has an answer in [answers]. */
        fun complete(questions: List<AskQuestion>, answers: Map<String, List<String>>): Boolean =
            questions.all { question -> answers[question.question].orEmpty().any { it.isNotBlank() } }
    }
}
