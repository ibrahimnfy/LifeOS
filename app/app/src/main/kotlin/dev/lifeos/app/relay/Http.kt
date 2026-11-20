package dev.lifeos.app.relay

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

/** Status and body text of a finished HTTP call. */
class HttpResult(val status: Int, val body: String)

/** A failed HTTP call. [sent] is false when no connection was made at all. */
class HttpFailure(val sent: Boolean) : IOException(if (sent) "connection failed" else "no connection")

object Http {
    /**
     * Plain HTTPS with the system trust store. No logging: addresses carry the access token.
     * Automatic retries are off, so the relay client alone decides what is sent twice.
     */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Runs a call without blocking; the body is read on OkHttp's own thread. */
    suspend fun run(call: Call): HttpResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = fail(continuation, e)

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { HttpResult(it.code, it.body.string()) }
                        continuation.resume(result)
                    } catch (error: IOException) {
                        fail(continuation, error)
                    }
                }
            },
        )
    }

    private fun fail(continuation: CancellableContinuation<HttpResult>, error: IOException) {
        if (continuation.isActive) continuation.resumeWith(Result.failure(HttpFailure(sent = wasSent(error))))
    }

    /** Failures that happen before any byte of the request leaves. */
    fun wasSent(error: IOException): Boolean =
        !(error is UnknownHostException || error is ConnectException || error is NoRouteToHostException)
}
