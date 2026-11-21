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
    // ---- a skill install that waits for the owner (#97); see [SkillInstall]
    val source: String = "",
    val state: String = "",
    val error: String? = null,
    val name: String = "",
    val target: String = "",
    val description: String = "",
    val warnings: List<String> = emptyList(),
    val files: List<InstallFile> = emptyList(),
    @SerialName("total_size") val totalSize: Long = 0,
    // ---- an MCP server that waits for the owner's approval (#153)
    val server: McpServer? = null,
)

/** One file of a skill that waits in the computer's quarantine. */
@Serializable
data class InstallFile(val path: String = "", val size: Long = 0)

/**
 * A skill the assistant proposed for the session's folder (the bridge's "Skill installs"). The
 * computer downloads it into a quarantine where nothing loads or runs it; only the owner's
 * yes copies it to [target]. Everything in it is someone else's text: shown, never trusted.
 */
data class SkillInstall(
    val source: String,
    val state: State,
    val name: String = "",
    val target: String = "",
    val description: String = "",
    val warnings: List<String> = emptyList(),
    val files: List<InstallFile> = emptyList(),
    val totalSize: Long = 0,
    /** Why it cannot be installed ([State.FAILED], [State.REFUSED]). */
    val problem: Problem? = null,
) {
    enum class State {
        /** The computer is fetching it; its contents are not known yet. */
        DOWNLOADING,

        /** In quarantine, contents listed: it can be installed or declined. */
        READY,

        /** It could not be fetched or is not a usable skill: it can only be put away. */
        FAILED,

        /** The computer did not accept the proposal at all (not an HTTPS source). */
        REFUSED,
    }

    enum class Problem { NOT_HTTPS, TOO_LARGE, UNSAFE, NO_SKILL_FILE, TOO_MANY, NO_NAME, EXISTS, UNRECORDED, DOWNLOAD }

    companion object {
        fun of(data: InteractionData): SkillInstall {
            val state = when (data.state) {
                "ready" -> State.READY
                "failed" -> State.FAILED
                "refused" -> State.REFUSED
                else -> State.DOWNLOADING
            }
            return SkillInstall(
                source = data.source,
                state = state,
                name = data.name,
                target = data.target,
                description = data.description,
                warnings = data.warnings,
                files = data.files.filter { it.path.isNotBlank() },
                totalSize = data.totalSize,
                problem = if (state == State.FAILED || state == State.REFUSED) problem(data.error.orEmpty()) else null,
            )
        }

        /** The bridge's sentence (skill_installs.py) as what the card says; it has no word for it. */
        fun problem(error: String): Problem = when {
            "HTTPS" in error -> Problem.NOT_HTTPS
            "larger than" in error -> Problem.TOO_LARGE
            "unsafe entry" in error -> Problem.UNSAFE
            "no SKILL.md" in error -> Problem.NO_SKILL_FILE
            "too many files" in error -> Problem.TOO_MANY
            "no usable name" in error -> Problem.NO_NAME
            "already installed" in error -> Problem.EXISTS
            "audit log" in error -> Problem.UNRECORDED
            else -> Problem.DOWNLOAD
        }
    }
}

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
    /** Question: what the owner answered, per question (shown on the card afterwards). */
    val answers: Map<String, List<String>> = emptyMap(),
    /** Skill install: what would be installed, from where and where to. */
    val install: SkillInstall? = null,
    /** MCP server: exactly the definition that would be approved. */
    val server: McpServer? = null,
) {
    enum class Kind { APPROVAL, QUESTION, SKILL_INSTALL, MCP_SERVER }

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

        /** The owner said yes, but the computer did not do it (what was shown is no longer what is there). */
        NOT_DONE,

        /** A skill of this name was installed meanwhile: nothing was replaced. */
        EXISTS,

        /** The computer could not write its audit log, so it did nothing. */
        UNRECORDED,
    }

    val isDeletion: Boolean get() = category == DELETION

    /**
     * It waits for the owner whether or not a run is in progress (a skill install, the
     * approval of an MCP server): for up to a day, across runs, messages and restarts of the computer. Only an answer, its own end
     * time or the computer's list closes it; the end of a run or the next message does not.
     */
    val crossRun: Boolean get() = kind == Kind.SKILL_INSTALL || kind == Kind.MCP_SERVER

    /** A yes is possible: an install only once its contents are listed. */
    val canApprove: Boolean get() = install == null || install.state == SkillInstall.State.READY

    /** Still to be answered, or the answer is on its way. */
    val pending: Boolean get() = status == Status.WAITING || status == Status.SENDING

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
            val deadline = now + wait.coerceIn(0, if (data.kind in CROSS_RUN) MAX_CARD_MILLIS else MAX_WAIT_MILLIS)
            return when (data.kind) {
                SKILL_INSTALL ->
                    if (data.source.isBlank()) null else Interaction(data.id, Kind.SKILL_INSTALL, folder = data.folder, deadline = deadline, install = SkillInstall.of(data))
                MCP_SERVER ->
                    if (data.server?.usable != true) null else Interaction(data.id, Kind.MCP_SERVER, folder = data.folder, deadline = deadline, server = data.server)
                "approval" -> if (data.command.isBlank()) null else Interaction(data.id, Kind.APPROVAL, data.command, data.folder, data.category, deadline = deadline)
                "question" -> {
                    val questions = data.questions.filter { it.question.isNotBlank() }
                    if (questions.isEmpty()) null else Interaction(data.id, Kind.QUESTION, questions = questions, deadline = deadline)
                }
                else -> null
            }
        }

        /** The bridge lets a run wait at most an hour. */
        private const val MAX_WAIT_MILLIS = 3_600_000L

        /** A card that waits across runs is kept for a day on the computer. */
        private const val MAX_CARD_MILLIS = 25 * 3_600_000L
        const val SKILL_INSTALL = "skill_install"
        const val MCP_SERVER = "mcp_server"
        private val CROSS_RUN = setOf(SKILL_INSTALL, MCP_SERVER)

        /**
         * A proposal the computer refused at once (it is only in the answer's own list and
         * has no id there): a card that says so, with nothing to answer. [key] is the phone's.
         */
        fun refusedInstall(data: InteractionData, key: Long, now: Long): Interaction? {
            if (data.kind != SKILL_INSTALL || data.source.isBlank()) return null
            val install = SkillInstall.of(data.copy(state = "refused"))
            return Interaction("local-$key", Kind.SKILL_INSTALL, deadline = now, status = Status.REFUSED, install = install)
        }

        /**
         * What the computer's answer to a yes or no means. A yes it did not act on
         * (`"installed": false`, `"approved": false`) is [Status.NOT_DONE]; everything else is what was tapped.
         */
        fun settled(approve: Boolean, answer: JsonElement?): Status {
            if (!approve) return Status.REFUSED
            val body = answer as? JsonObject
            val undone = listOf("installed", "approved").any { (body?.get(it) as? JsonPrimitive)?.content == "false" }
            return if (undone) Status.NOT_DONE else Status.ALLOWED
        }

        /**
         * What a refused answer means, or null when the answer simply did not get through (it
         * can be given again). The computer takes a card that waits across runs out of its list
         * before it acts, so after these refusals there is nothing left to answer.
         */
        fun refusal(error: ApiException): Status? = when {
            error.kind != ApiException.Kind.HTTP -> null
            // "Not pending": answered, expired or cancelled meanwhile.
            error.status == 404 -> Status.GONE
            error.code == "skill_exists" -> Status.EXISTS
            error.code == "audit_unavailable" -> Status.UNRECORDED
            else -> null
        }

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
