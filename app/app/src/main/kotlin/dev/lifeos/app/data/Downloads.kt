package dev.lifeos.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    /** [total] is 0 when the size is not known. */
    data class Running(val bytes: Long, val total: Long) : DownloadState
    data object Saved : DownloadState
    data class Failed(val reason: DownloadFailure.Reason) : DownloadState
}

/**
 * Downloads of course files to the phone's Downloads folder. The app keeps nothing: no copy,
 * no list of what was downloaded (the "saved" mark lives only until the app is closed), and
 * the direct address exists only inside [run] while it is used.
 */
class Downloads(private val context: Context, private val api: Api, private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stateFlow = MutableStateFlow<Map<String, DownloadState>>(emptyMap())

    val states: StateFlow<Map<String, DownloadState>> = stateFlow

    /** Before Android 10 an app cannot write into Downloads itself: the owner picks the place. */
    val needsPicker: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** [target] is the place the owner picked (only where [needsPicker]). */
    fun start(courseId: Long, file: CourseFile, target: Uri? = null) {
        if (jobs[file.key]?.isActive == true) return
        set(file.key, DownloadState.Running(0, file.size))
        jobs[file.key] = scope.launch(Dispatchers.IO) {
            try {
                run(courseId, file, target)
                set(file.key, DownloadState.Saved)
            } catch (error: DownloadFailure) {
                set(file.key, DownloadState.Failed(error.reason))
            } catch (error: CancellationException) {
                set(file.key, null)
                throw error
            } catch (_: Exception) {
                set(file.key, DownloadState.Failed(DownloadFailure.Reason.STORAGE))
            }
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    fun dismiss(key: String) = set(key, null)

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        stateFlow.value = emptyMap()
    }

    @Synchronized
    private fun set(key: String, state: DownloadState?) {
        stateFlow.value = if (state == null) stateFlow.value - key else stateFlow.value + (key to state)
    }

    private fun newSink(file: CourseFile, target: Uri?): FileSink = try {
        if (target != null) {
            PickedFileSink(context.contentResolver, target)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DownloadsFolderSink(context.contentResolver, DownloadRules.safeName(file.name), file.type)
        } else {
            throw DownloadFailure(DownloadFailure.Reason.STORAGE)
        }
    } catch (error: DownloadFailure) {
        throw error
    } catch (_: Exception) {
        throw DownloadFailure(DownloadFailure.Reason.STORAGE)
    }

    private suspend fun run(courseId: Long, file: CourseFile, target: Uri?) {
        val progress: (Long) -> Unit = { set(file.key, DownloadState.Running(it, file.size)) }
        if (direct(courseId, file, target, progress)) return
        currentCoroutineContext().ensureActive()
        // The direct way failed: ask for the computer's own copy through the sealed channel.
        val offer = try {
            api.fileOffer(courseId, file.key)
        } catch (error: ApiException) {
            if (error.kind != ApiException.Kind.HTTP) throw DownloadFailure(DownloadFailure.Reason.UNREACHABLE)
            throw DownloadFailure(if (signInRanOut()) DownloadFailure.Reason.SIGN_IN else DownloadFailure.Reason.NO_COPY)
        }
        progress(0)
        RelayDownload.receive(api, offer, newSink(file, target), progress)
    }

    /** True if the file was saved the direct way. The address never leaves this function. */
    private suspend fun direct(courseId: Long, file: CourseFile, target: Uri?, progress: (Long) -> Unit): Boolean {
        val address = try {
            api.fileAddress(courseId, file.key)
        } catch (error: ApiException) {
            if (error.kind != ApiException.Kind.HTTP) throw DownloadFailure(DownloadFailure.Reason.UNREACHABLE)
            return false
        }
        val sink = newSink(file, target)
        return try {
            DirectFetch.fetch(address.url, address.size, sink, currentCoroutineContext().job, progress)
            true
        } catch (_: DownloadFailure) {
            false
        }
    }

    private suspend fun signInRanOut(): Boolean = try {
        api.syncState().lastError == "token_expired"
    } catch (_: Exception) {
        false
    }
}

/** The direct download from the university site: HTTPS only, never to another host, nothing logged. */
object DirectFetch {
    private const val MAX_REDIRECTS = 3
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetches [address] into [sink] and commits it. On any failure the sink is aborted and a
     * [DownloadFailure] without detail is thrown: no message may carry the address.
     */
    fun fetch(address: String, expectedSize: Long, sink: FileSink, job: Job, onProgress: (Long) -> Unit) {
        var current = address
        try {
            repeat(MAX_REDIRECTS + 1) {
                if (DownloadRules.hostOf(current) == null) throw DownloadFailure(DownloadFailure.Reason.DIRECT)
                val call = client.newCall(Request.Builder().url(current).get().build())
                val handle = job.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { response ->
                        if (response.code in 300..399) {
                            current = DownloadRules.redirectTarget(current, response.header("Location"))
                                ?: throw DownloadFailure(DownloadFailure.Reason.DIRECT)
                            return@repeat
                        }
                        if (response.code != 200) throw DownloadFailure(DownloadFailure.Reason.DIRECT)
                        var written = 0L
                        val buffer = ByteArray(64 * 1024)
                        val input = response.body.byteStream()
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, count)
                            written += count
                            onProgress(written)
                        }
                        // An error page instead of the file has another size.
                        if (expectedSize > 0 && written != expectedSize) throw DownloadFailure(DownloadFailure.Reason.DIRECT)
                        sink.commit()
                        return
                    }
                } finally {
                    handle.dispose()
                }
            }
            throw DownloadFailure(DownloadFailure.Reason.DIRECT)
        } catch (_: Exception) {
            sink.abort()
            throw DownloadFailure(DownloadFailure.Reason.DIRECT)
        }
    }
}

/** A new entry in the system's Downloads collection; hidden from other apps until it is complete. */
@RequiresApi(Build.VERSION_CODES.Q)
private class DownloadsFolderSink(private val resolver: ContentResolver, name: String, type: String?) : FileSink {
    private val uri: Uri
    private val out: OutputStream

    init {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            if (!type.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, type)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        out = try {
            checkNotNull(resolver.openOutputStream(uri))
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun commit() {
        out.close()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    override fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
        try {
            resolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}

/** The document the owner chose with the system's "save as" screen (Android 8 and 9). */
private class PickedFileSink(private val resolver: ContentResolver, private val uri: Uri) : FileSink {
    private val out: OutputStream = checkNotNull(resolver.openOutputStream(uri, "wt"))

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun commit() = out.close()

    override fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
        }
    }
}
