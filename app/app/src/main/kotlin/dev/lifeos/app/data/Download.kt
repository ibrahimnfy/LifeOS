package dev.lifeos.app.data

import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.security.MessageDigest

/** Where a downloaded file is written. Nothing of it is kept by the app itself. */
interface FileSink {
    fun write(bytes: ByteArray, length: Int = bytes.size)

    /** The file is complete: make it visible. */
    fun commit()

    /** Something failed: remove what was written. */
    fun abort()
}

class DownloadFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** The direct way failed (reason deliberately not kept: the address is secret). */
        DIRECT,

        /** The computer has no copy it can send (missing, changed, or too large for the relay route). */
        NO_COPY,

        /** The sign-in at the university site has run out; it is renewed on the computer. */
        SIGN_IN,

        /** What arrived was not the whole file, or not the file that was announced. */
        DAMAGED,

        /** The computer could not be reached. */
        UNREACHABLE,

        /** Storage on the phone refused the file. */
        STORAGE,
    }
}

/**
 * Rules for the direct download address. It contains a secret of the owner's account, so it
 * is used once, kept only in a local variable, never logged, stored or shown, and it may
 * only lead to its own host over HTTPS.
 */
object DownloadRules {
    /** The host of an acceptable address (HTTPS, a plain host, no credentials), or null. */
    fun hostOf(address: String): String? = try {
        val uri = URI(address)
        if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.port in listOf(-1, 443)) {
            uri.host.lowercase()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    /** The address a redirect may be followed to: only the same host, only HTTPS. Otherwise null. */
    fun redirectTarget(from: String, location: String?): String? = try {
        val host = hostOf(from)
        val target = URI(from).resolve(location ?: "").toString()
        if (location.isNullOrBlank() || host == null || hostOf(target) != host) null else target
    } catch (_: Exception) {
        null
    }

    /** A name that is safe as a file name in Downloads. */
    fun safeName(name: String): String {
        val cleaned = name.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("")
            .trim().trimStart('.').take(120).trim()
        return cleaned.ifEmpty { "file" }
    }
}

/**
 * Puts the chunks of a relayed file together: strictly in sequence, straight into the sink,
 * and at the end the size and the SHA-256 must be the announced ones.
 */
class ChunkAssembler(private val sink: FileSink) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var next = 0
        private set
    var bytes = 0L
        private set

    fun add(index: Int, total: Int, data: ByteArray) {
        if (index != next || (total in 1..index)) throw DownloadFailure(DownloadFailure.Reason.DAMAGED)
        sink.write(data)
        digest.update(data)
        next++
        bytes += data.size
    }

    /** Whether everything that was announced has arrived unchanged. */
    fun matches(size: Long, chunks: Int, sha256: String): Boolean =
        size == bytes && chunks == next && digest.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
}

/** A file from the computer's own copy, through the sealed channel. */
object RelayDownload {
    /**
     * Receives [offer] into [sink]. After a dropped connection the transfer is continued once
     * at the chunk that is missing. The sink is committed only when size and hash match.
     */
    suspend fun receive(api: Api, offer: FileOffer, sink: FileSink, onProgress: (Long) -> Unit) {
        val assembler = ChunkAssembler(sink)
        var continued = false
        try {
            while (true) {
                try {
                    var done: AnswerEvent.Done? = null
                    api.fileChunks(offer, assembler.next).collect { event ->
                        when (event) {
                            is AnswerEvent.FileChunk -> {
                                assembler.add(event.index, event.total, event.bytes)
                                onProgress(assembler.bytes)
                            }
                            is AnswerEvent.Done -> done = event
                            else -> Unit
                        }
                    }
                    val response = done?.response ?: throw DownloadFailure(DownloadFailure.Reason.DAMAGED)
                    if (!response.ok) {
                        throw DownloadFailure(
                            if (response.status == 404 || response.status == 409 || response.status == 413) {
                                DownloadFailure.Reason.NO_COPY
                            } else {
                                DownloadFailure.Reason.UNREACHABLE
                            },
                        )
                    }
                    val file = response.body as? JsonObject ?: throw DownloadFailure(DownloadFailure.Reason.DAMAGED)
                    val size = (file["size"] as? JsonPrimitive)?.longOrNull
                    val chunks = (file["chunks"] as? JsonPrimitive)?.intOrNull
                    val hash = (file["sha256"] as? JsonPrimitive)?.content
                    if (size == null || chunks == null || hash == null || !assembler.matches(size, chunks, hash)) {
                        throw DownloadFailure(DownloadFailure.Reason.DAMAGED)
                    }
                    sink.commit()
                    return
                } catch (error: RelayException) {
                    if (continued || error.reason == RelayException.Reason.DENIED) throw DownloadFailure(DownloadFailure.Reason.UNREACHABLE)
                    continued = true
                } catch (error: ApiException) {
                    throw DownloadFailure(DownloadFailure.Reason.UNREACHABLE)
                }
            }
        } catch (error: CancellationException) {
            sink.abort()
            throw error
        } catch (error: DownloadFailure) {
            sink.abort()
            throw error
        } catch (error: Exception) {
            sink.abort()
            throw DownloadFailure(DownloadFailure.Reason.STORAGE)
        }
    }
}
