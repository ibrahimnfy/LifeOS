package dev.lifeos.app.relay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The mailbox in the owner's Realtime Database, over its REST interface:
 * `<database url>/<path>.json?auth=<token>`. New messages arrive on a streaming GET
 * (server-sent events); after repeated stream failures it polls instead.
 *
 * The mailbox is untrusted: it only ever holds what the end-to-end layer sealed.
 * Addresses are never logged, because they carry the access token.
 */
class RtdbMailbox(
    private val client: OkHttpClient,
    databaseUrl: String,
    private val tokens: TokenSource,
    private val nowMillis: () -> Long,
) : Mailbox {
    private val base: HttpUrl = databaseUrl.toHttpUrl()
    private val streamClient = client.newBuilder().readTimeout(STREAM_SILENCE_SECONDS, TimeUnit.SECONDS).build()

    private fun url(path: String, token: String, vararg query: Pair<String, String>): HttpUrl {
        val builder = base.newBuilder().addPathSegments(path.trim('/') + ".json").addQueryParameter("auth", token)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    /** One call; an expired token is renewed once. Returns the body text. */
    private suspend fun call(path: String, vararg query: Pair<String, String>, build: Request.Builder.() -> Unit): String {
        var renewed = false
        while (true) {
            val token = token(force = renewed)
            val request = Request.Builder().url(url(path, token, *query)).apply(build).build()
            val result = try {
                Http.run(client.newCall(request))
            } catch (error: HttpFailure) {
                throw MailboxException(if (error.sent) MailboxException.Reason.UNREACHABLE else MailboxException.Reason.NOT_SENT)
            }
            when {
                result.status in 200..299 -> return result.body
                result.status == 401 || result.status == 403 -> {
                    // A refusal by the rules is final; any other 401 means the token ran out.
                    if (renewed || "ermission denied" in result.body) {
                        throw MailboxException(MailboxException.Reason.DENIED, result.status)
                    }
                    renewed = true
                }
                else -> throw MailboxException(MailboxException.Reason.STATUS, result.status)
            }
        }
    }

    private suspend fun token(force: Boolean): String = try {
        tokens.token(force)
    } catch (error: RelayAuthException) {
        // Nothing was sent to the mailbox either way.
        throw MailboxException(if (error.refused) MailboxException.Reason.DENIED else MailboxException.Reason.NOT_SENT)
    }

    override suspend fun put(path: String, value: String) {
        val body = JsonPrimitive(value).toString().toRequestBody(JSON)
        call(path, "print" to "silent") { put(body) }
    }

    override suspend fun get(path: String): String? {
        val text = call(path) { get() }
        return try {
            (Json.parseToJsonElement(text) as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun delete(path: String) {
        call(path, "print" to "silent") { delete() }
    }

    override suspend fun keys(path: String): List<String> {
        val text = call(path, "shallow" to "true") { get() }
        return try {
            (Json.parseToJsonElement(text) as? JsonObject)?.keys?.sorted().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun listen(path: String): Flow<MailboxEvent> = flow {
        var failures = 0
        var pollingSince = 0L
        while (true) {
            if (failures < STREAM_FAILURES_BEFORE_POLLING) {
                val started = nowMillis()
                var opened = false
                try {
                    stream(path).collect { signal ->
                        when (signal) {
                            null -> {
                                opened = true
                                emit(MailboxEvent.Up)
                            }
                            is StreamSignal.Entries -> emit(MailboxEvent.Entries(signal.entries))
                            else -> Unit
                        }
                    }
                } catch (_: MailboxException) {
                } catch (_: IOException) {
                }
                // A stream that lived for a while was fine; one that died at once counts.
                failures = if (opened && nowMillis() - started > HEALTHY_STREAM_MILLIS) 0 else failures + 1
                if (!opened) emit(MailboxEvent.Down)
                if (failures >= STREAM_FAILURES_BEFORE_POLLING) pollingSince = nowMillis()
                delay(minOf(1000L shl failures.coerceAtMost(5), MAX_BACKOFF_MILLIS))
            } else {
                try {
                    val entries = keys(path).mapNotNull { key ->
                        try {
                            MailboxEntry(key, get("$path/$key"))
                        } catch (error: MailboxException) {
                            if (error.reason == MailboxException.Reason.DENIED) null else throw error
                        }
                    }
                    emit(MailboxEvent.Up)
                    if (entries.isNotEmpty()) emit(MailboxEvent.Entries(entries))
                } catch (_: MailboxException) {
                    emit(MailboxEvent.Down)
                }
                delay(POLL_MILLIS)
                // Try streaming again from time to time.
                if (nowMillis() - pollingSince > RETRY_STREAM_MILLIS) failures = 0
            }
        }
    }

    /**
     * One streaming connection. Emits null once it is open, then what each event means. It ends
     * when the server ends it, when access is withdrawn, or shortly before the token runs out;
     * a silent connection ends with the read timeout (keep-alives arrive every 30 seconds).
     */
    private fun stream(path: String): Flow<StreamSignal?> = callbackFlow {
        val token = token(force = false)
        val request = Request.Builder().url(url(path, token)).header("Accept", "text/event-stream").get().build()
        val call = streamClient.newCall(request) // redirects are followed by the client
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        val denied = "ermission denied" in response.body.string()
                        if (!denied) token(force = true)
                        throw MailboxException(MailboxException.Reason.DENIED, response.code)
                    }
                    if (response.code != 200) throw MailboxException(MailboxException.Reason.STATUS, response.code)
                    trySendBlocking(null)
                    val parser = SseParser()
                    val source = response.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.feed(line) ?: continue
                        val signal = RtdbStream.interpret(event)
                        if (signal is StreamSignal.Closed) {
                            if (signal.authRevoked) token(force = true)
                            break
                        }
                        trySendBlocking(signal)
                        if (nowMillis() >= tokens.expiresAtMillis) break
                    }
                }
                close()
            } catch (error: IOException) {
                close(error)
            } catch (error: MailboxException) {
                close(error)
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val STREAM_FAILURES_BEFORE_POLLING = 3
        const val STREAM_SILENCE_SECONDS = 75L
        const val HEALTHY_STREAM_MILLIS = 60_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val POLL_MILLIS = 2_000L
        const val RETRY_STREAM_MILLIS = 300_000L
    }
}
