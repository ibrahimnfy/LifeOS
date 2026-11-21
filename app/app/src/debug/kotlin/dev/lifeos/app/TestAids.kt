package dev.lifeos.app

import android.content.Context
import android.content.Intent
import dev.lifeos.app.data.Transport
import dev.lifeos.app.pairing.KeystoreVault
import dev.lifeos.app.pairing.SecretVault
import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Test aids of DEBUG builds only. This file is in `src/debug/`; release builds compile the
 * empty twin in `src/release/` instead, so none of this exists there.
 *
 * They let an automated check pair and use the connection without the camera and without the
 * owner's fingerprint:
 *
 *     adb shell am start -n dev.lifeos.app/.MainActivity --es lifeos.test.pairing_text '<text>'
 *
 * A pairing made this way is stored under a Keystore key that needs no confirmation, and the
 * app lock is skipped for it. A pairing made the normal way (camera) is never affected: its
 * key demands the owner's confirmation whatever is passed here.
 *
 * Adding `--ez lifeos.test.real_lock true` replaces only the camera: the app then asks for
 * fingerprint or screen lock and stores the pairing exactly like a scanned one, app lock included.
 *
 * `--ez lifeos.test.sample_courses true` makes the Courses screens (and only them) show the
 * synthetic sample courses in `src/debug/assets/sample-courses.json` instead of asking the
 * computer, until the app is closed. Nothing of the owner's courses is touched in that mode.
 *
 * `--ez lifeos.test.sample_data true` goes further, for screenshots on an emulator without a
 * computer: on a phone that is NOT paired, the app behaves as if it were connected and every
 * screen shows the invented answers of `src/debug/assets/sample-data.json` (nothing is sent
 * anywhere, nothing is kept as a saved copy). A paired phone ignores it.
 */
object TestAids {
    const val VAULT = "test"
    private const val EXTRA_PAIRING_TEXT = "lifeos.test.pairing_text"
    private const val EXTRA_REAL_LOCK = "lifeos.test.real_lock"
    private const val EXTRA_SAMPLE_COURSES = "lifeos.test.sample_courses"
    private const val EXTRA_SAMPLE_DATA = "lifeos.test.sample_data"

    @Volatile
    private var sampleCourses = false

    @Volatile
    private var sampleWanted = false

    /** Every answer comes from the sample data: switched on by the core, and only on an unpaired phone. */
    @Volatile
    private var sampleData = false

    /** The test asked for the sample data (the core decides whether it may be used). */
    val wantsSampleData: Boolean get() = sampleWanted

    fun startSampleData() {
        sampleData = true
    }

    /** The pairing text handed over by the test, if any. Read once. */
    fun pairingText(intent: Intent?): String? {
        val text = intent?.getStringExtra(EXTRA_PAIRING_TEXT)
        intent?.removeExtra(EXTRA_PAIRING_TEXT)
        return text
    }

    /** Whether the test wants the real, confirmation-bound key (only the camera is replaced). */
    fun realLock(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA_REAL_LOCK, false) == true

    /** Switches that last until the app is closed. */
    fun readSwitches(intent: Intent?) {
        if (intent?.hasExtra(EXTRA_SAMPLE_COURSES) == true) sampleCourses = intent.getBooleanExtra(EXTRA_SAMPLE_COURSES, false)
        if (intent?.hasExtra(EXTRA_SAMPLE_DATA) == true) sampleWanted = intent.getBooleanExtra(EXTRA_SAMPLE_DATA, false)
    }

    /**
     * `--es lifeos.test.push <event>` (optionally `--es lifeos.test.push_ref <item>`) shows the
     * notification a push of that kind would show, without Firebase, to check its text, channel
     * and what a tap opens. Nothing is received or sent.
     */
    fun showSamplePush(context: Context, intent: Intent?) {
        val event = intent?.getStringExtra("lifeos.test.push") ?: return
        intent.removeExtra("lifeos.test.push")
        if (event !in dev.lifeos.app.push.PushHints.EVENTS) return
        val ref = intent.getStringExtra("lifeos.test.push_ref").orEmpty()
        dev.lifeos.app.push.PushNotifier.show(context, dev.lifeos.app.push.Hint(event, ref, title = "Sample notice".takeIf { event == "notice" }))
    }

    /** A Keystore key without user confirmation, for test pairings only. */
    fun vault(kind: String): SecretVault? =
        if (kind == VAULT) KeystoreVault("lifeos.device.test.v1") {} else null

    /** The channel to the computer, with the sample courses in front of it while they are switched on. */
    fun transport(context: Context, real: Transport): Transport = SampleCourses(context, real)

    private class SampleCourses(private val context: Context, private val real: Transport) : Transport {
        private val answers: JsonObject by lazy {
            Json.parseToJsonElement(context.assets.open("sample-courses.json").use { it.readBytes().decodeToString() }).jsonObject
        }
        private val file = "LifeOS sample file.\n".toByteArray() // 20 bytes, as the sample offer says

        private val data: JsonObject by lazy {
            Json.parseToJsonElement(context.assets.open("sample-data.json").use { it.readBytes().decodeToString() }).jsonObject
        }

        private fun mine(path: String) =
            (sampleCourses && (path.startsWith("/courses") || path.startsWith("/files/"))) || (sampleData && path.startsWith("/files/"))

        /** The sample data: GETs from the file (dates made relative to today), every write simply accepted. */
        private fun sample(method: String, path: String): RelayResponse {
            // A routing change answers the (unchanged) sample overview.
            if (method == "PUT" && path == "/models/routing") return RelayResponse(200, relative(data.getValue("GET /models/routing")))
            if (method != "GET") return RelayResponse(200, buildJsonObject { put("saved", JsonPrimitive(true)) })
            val key = "GET " + path.replace(Regex("^/courses/\\d+/"), "/courses/ID/").replace(Regex("/assignments/\\d+$"), "/assignments/ID").replace(Regex("^/notes/[^/]+$"), "/notes/ID")
            val body = data[key] ?: return RelayResponse(404, Json.parseToJsonElement("""{"error":"not_found"}"""))
            return RelayResponse(200, relative(body))
        }

        private fun relative(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject {
                for ((name, value) in element) {
                    when (name) {
                        "due_in_days" -> put("due", JsonPrimitive(System.currentTimeMillis() / 1000 + value.jsonPrimitive.int * 86_400L))
                        "opens_in_days" -> put("opens", JsonPrimitive(System.currentTimeMillis() / 1000 + value.jsonPrimitive.int * 86_400L))
                        "due_date_in_days" -> put("due", JsonPrimitive(java.time.LocalDate.now().plusDays(value.jsonPrimitive.int.toLong()).toString()))
                        else -> put(name, relative(value))
                    }
                }
            }
            is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.buildJsonArray { element.forEach { add(relative(it)) } }
            else -> element
        }

        // Chat and Sessions of the sample data: see SampleChat.
        private val chat by lazy { SampleChat(context) }

        override fun events(method: String, path: String, body: JsonElement?, idleTimeoutMillis: Long): Flow<AnswerEvent> =
            if (sampleData) chat.events(path, body) else real.events(method, path, body, idleTimeoutMillis)

        override suspend fun request(method: String, path: String, query: Map<String, String>, body: JsonElement?, timeoutMillis: Long): RelayResponse {
            if (sampleData) return chat.request(method, path, query, body) ?: sample(method, path)
            if (!mine(path)) return real.request(method, path, query, body, timeoutMillis)
            val key = "$method " + path.replace(Regex("/files/[^/]+/(address|offer)$"), "/files/KEY/$1")
            if (key == "POST /courses/refresh") return RelayResponse(202, answers.getValue("GET /courses/sync").jsonObject.getValue("body"))
            if (key.endsWith("/address")) {
                // An address that cannot be reached, so the app falls back to the relay route.
                return RelayResponse(
                    200,
                    buildJsonObject {
                        put("url", JsonPrimitive("https://sample.example.invalid/sample.pdf"))
                        put("name", JsonPrimitive("sample.pdf"))
                        put("size", JsonPrimitive(file.size))
                    },
                )
            }
            val entry = answers[key]?.jsonObject ?: answers[key.replace(Regex("^POST (/courses/)\\d+/seen$"), "POST $15/seen")]?.jsonObject
                ?: return RelayResponse(404, Json.parseToJsonElement("""{"error":"course_not_found"}"""))
            return RelayResponse(entry.getValue("status").jsonPrimitive.int, entry.getValue("body"))
        }

        override fun stream(method: String, path: String, query: Map<String, String>): Flow<AnswerEvent> {
            if (!mine(path)) return real.stream(method, path, query)
            return flow {
                val half = file.size / 2
                emit(AnswerEvent.FileChunk(0, 2, file.copyOfRange(0, half)))
                emit(AnswerEvent.FileChunk(1, 2, file.copyOfRange(half, file.size)))
                val hash = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
                emit(
                    AnswerEvent.Done(
                        RelayResponse(
                            200,
                            buildJsonObject {
                                put("size", JsonPrimitive(file.size))
                                put("chunks", JsonPrimitive(2))
                                put("sha256", JsonPrimitive(hash))
                            },
                        ),
                    ),
                )
            }
        }
    }
}
