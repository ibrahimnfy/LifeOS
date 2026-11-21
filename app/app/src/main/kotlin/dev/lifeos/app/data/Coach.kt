package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `GET /coach/settings`: how often the coach may notify, its quiet hours, questions on or off (#76). */
@Serializable
data class CoachSettings(
    val frequency: String = CoachSettingRules.ONCE,
    @SerialName("quiet_start") val quietStart: String = "22:00",
    @SerialName("quiet_end") val quietEnd: String = "08:00",
    val questions: Boolean = true,
)

/** The coach's settings as the app offers them; the limits themselves are enforced on the computer. */
object CoachSettingRules {
    const val OFF = "off"
    const val ONCE = "1"
    const val UP_TO_THREE = "3"

    /** Off, once a day (the daily note), up to three a day. */
    val FREQUENCIES = listOf(OFF, ONCE, UP_TO_THREE)

    /** The bridge also accepts the older names; the app always shows one of [FREQUENCIES]. */
    fun frequencyOf(value: String): String = when (value) {
        "low" -> ONCE
        "normal" -> UP_TO_THREE
        in FREQUENCIES -> value
        else -> ONCE
    }

    /** Quiet hours that pass midnight are normal; equal start and end mean none. */
    fun hasQuietHours(settings: CoachSettings): Boolean = settings.quietStart != settings.quietEnd

    /** The body of `PUT /coach/settings`: only the changed fields. */
    fun changes(before: CoachSettings, after: CoachSettings): JsonObject = buildJsonObject {
        if (before.frequency != after.frequency) put("frequency", after.frequency)
        if (before.quietStart != after.quietStart) put("quiet_start", after.quietStart)
        if (before.quietEnd != after.quietEnd) put("quiet_end", after.quietEnd)
        if (before.questions != after.questions) put("questions", JsonPrimitive(after.questions))
    }
}

/** `POST /coach/question/skip`: three skips in a row pause all questions for a while. */
@Serializable
data class CoachSkip(
    val skipped: Boolean = true,
    @SerialName("questions_paused_until") val pausedUntil: String? = null,
)

/** The longest answer the computer accepts (`MAX_ANSWER` in the bridge's coach code). */
const val COACH_ANSWER_MAX = 4000

suspend fun Api.coachSettings(): CoachSettings =
    BridgeJson.decodeFromJsonElement(CoachSettings.serializer(), call("GET", "/coach/settings"))

suspend fun Api.changeCoachSettings(changes: JsonObject): CoachSettings =
    BridgeJson.decodeFromJsonElement(CoachSettings.serializer(), call("PUT", "/coach/settings", body = changes, timeoutMillis = Api.WRITE_TIMEOUT_MILLIS))

/**
 * The owner's own words to the open question. The answer is logged first; the filing run that
 * follows takes up to a few minutes, so the call waits as long as a plan does.
 */
suspend fun Api.answerCoachQuestion(text: String) {
    call(
        "POST",
        "/coach/question/answer",
        body = buildJsonObject { put("text", text) },
        timeoutMillis = Api.PLAN_TIMEOUT_MILLIS,
    )
}

suspend fun Api.skipCoachQuestion(): CoachSkip =
    BridgeJson.decodeFromJsonElement(CoachSkip.serializer(), call("POST", "/coach/question/skip", timeoutMillis = Api.WRITE_TIMEOUT_MILLIS))
