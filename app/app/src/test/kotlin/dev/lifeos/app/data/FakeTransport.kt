package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** A stand-in for the sealed channel: canned answers per "METHOD /path", and a record of every call. */
class FakeTransport : Transport {
    class Call(val method: String, val path: String, val query: Map<String, String>, val body: JsonElement?)

    val calls = mutableListOf<Call>()
    val answers = mutableMapOf<String, (Call) -> RelayResponse>()
    var streams: (Call) -> Flow<AnswerEvent> = { flow { } }

    fun answer(key: String, status: Int, body: String) {
        answers[key] = { RelayResponse(status, Json.parseToJsonElement(body)) }
    }

    fun fail(key: String, error: ApiException) {
        answers[key] = { throw error }
    }

    override suspend fun request(method: String, path: String, query: Map<String, String>, body: JsonElement?, timeoutMillis: Long): RelayResponse {
        val call = Call(method, path, query, body)
        calls += call
        return (answers["$method $path"] ?: { RelayResponse(404, Json.parseToJsonElement("""{"error":"not_found"}""")) })(call)
    }

    override fun stream(method: String, path: String, query: Map<String, String>): Flow<AnswerEvent> {
        val call = Call(method, path, query, null)
        calls += call
        return streams(call)
    }

    companion object {
        /** The answers of the bridge's course endpoints for its own synthetic test site. */
        val fixture = Json.parseToJsonElement(
            checkNotNull(FakeTransport::class.java.classLoader?.getResourceAsStream("courses-fixture.json")).use { it.readBytes().decodeToString() },
        ).jsonObject

        fun withCourses(): FakeTransport = FakeTransport().apply {
            for ((key, value) in fixture) {
                val entry = value as? kotlinx.serialization.json.JsonObject ?: continue
                val status = entry.getValue("status").toString().toInt()
                answers[key] = { RelayResponse(status, entry.getValue("body")) }
            }
        }
    }
}
