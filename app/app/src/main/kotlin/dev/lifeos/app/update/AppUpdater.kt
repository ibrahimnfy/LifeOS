package dev.lifeos.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import dev.lifeos.app.BuildConfig
import dev.lifeos.app.data.Api
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.AppRelease
import dev.lifeos.app.data.BridgeJson
import dev.lifeos.app.data.DownloadFailure
import dev.lifeos.app.data.FileSink
import dev.lifeos.app.data.RelayDownload
import dev.lifeos.app.data.ReleaseOffer
import dev.lifeos.app.data.ReleaseRules
import dev.lifeos.app.data.UpdateProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Where a self-update stands. */
sealed interface UpdateState {
    data object None : UpdateState
    data class Ready(val release: AppRelease) : UpdateState

    /** A newer release exists, but this build is signed with another key (a debug build). */
    data class NotThisBuild(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val bytes: Long) : UpdateState

    /** Android must allow this app to install apps first (asked only now). */
    data class NeedsPermission(val release: AppRelease) : UpdateState

    /** With the installer; [confirm] opens its screen again when it could not show itself. */
    data class Installing(val release: AppRelease, val confirm: Intent? = null) : UpdateState
    data class Failed(val release: AppRelease, val problem: UpdateProblem) : UpdateState
}

/**
 * The "update ready" flow (#138): ask the computer for its newest release, fetch it through the
 * sealed file transfer, verify it (size, SHA-256, signing certificate equal to the announced one
 * and to this app's own), and hand it to Android's installer through a PackageInstaller
 * session. The file lives only in the app's cache folder while it is checked, and is deleted as
 * soon as the installer has it (and at every start, in case the app died in between).
 */
class AppUpdater(private val context: Context, private val api: Api) {
    private val state = MutableStateFlow<UpdateState>(UpdateState.None)
    val updates: StateFlow<UpdateState> = state
    private val folder = File(context.cacheDir, "update")

    /** Forget any file a previous run left behind. */
    fun cleanUp() {
        folder.listFiles()?.forEach { it.delete() }
    }

    /** Ask the computer for its newest release (on open and on an "update" push). */
    suspend fun check() {
        val current = state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        val release = try {
            BridgeJson.decodeFromJsonElement(AppRelease.serializer(), api.call("GET", "/app/release"))
        } catch (_: ApiException) {
            return // none registered (404), or not reachable: nothing to say
        } catch (_: Exception) {
            return
        }
        state.value = when {
            !ReleaseRules.isNewer(release, BuildConfig.VERSION_CODE.toLong()) -> UpdateState.None
            !ReleaseRules.canUpdateItself(ownCertificate(), release) -> UpdateState.NotThisBuild(release)
            else -> UpdateState.Ready(release)
        }
    }

    /** One tap: download, verify, install. */
    suspend fun install(release: AppRelease) {
        val current = state.value
        if (current is UpdateState.Downloading || current is UpdateState.Installing) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            state.value = UpdateState.NeedsPermission(release)
            return
        }
        state.value = UpdateState.Downloading(release, 0)
        val file = File(folder, "lifeos-${release.versionCode}.apk")
        try {
            val answer = api.call("POST", "/app/release/offer")
            val offer = try {
                BridgeJson.decodeFromJsonElement(ReleaseOffer.serializer(), answer)
            } catch (_: Exception) {
                throw ApiException(ApiException.Kind.HTTP, 502, "bad_answer")
            }
            withContext(Dispatchers.IO) {
                folder.mkdirs()
                RelayDownload.receive(api, offer.fileOffer(), FileOutputSink(file)) { state.value = UpdateState.Downloading(release, it) }
            }
            val own = ownCertificate()
            val apkCert = withContext(Dispatchers.IO) { apkCertificate(file) }
            if (!withContext(Dispatchers.IO) { ReleaseRules.verified(file, offer, apkCert, own) }) {
                state.value = UpdateState.Failed(release, ReleaseRules.problemOf(file, offer, apkCert, own))
                return
            }
            withContext(Dispatchers.IO) { handToInstaller(file) }
            state.value = UpdateState.Installing(release)
        } catch (error: DownloadFailure) {
            state.value = UpdateState.Failed(release, if (error.reason == DownloadFailure.Reason.DAMAGED) UpdateProblem.DAMAGED else UpdateProblem.UNREACHABLE)
        } catch (_: ApiException) {
            state.value = UpdateState.Failed(release, UpdateProblem.UNREACHABLE)
        } catch (_: Exception) {
            state.value = UpdateState.Failed(release, UpdateProblem.INSTALL)
        } finally {
            // The installer has its own copy (the session); the app keeps none.
            file.delete()
        }
    }

    fun dismiss() {
        state.value = UpdateState.None
    }

    /** The installer waits for the owner's confirmation on its own screen. */
    fun needsConfirmation(confirm: Intent) {
        val current = state.value as? UpdateState.Installing ?: return
        state.value = current.copy(confirm = confirm)
    }

    /** The installer's answer (see [InstallResultReceiver]). */
    fun installFinished(success: Boolean) {
        val current = state.value
        val release = (current as? UpdateState.Installing)?.release ?: return
        state.value = if (success) UpdateState.None else UpdateState.Failed(release, UpdateProblem.INSTALL)
    }

    private fun handToInstaller(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lifeos.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(result.intentSender)
        }
    }

    /**
     * The SHA-256 of this app's own signing certificate. Before Android 9 the signer of a
     * v2/v3-only file cannot be read, so such phones are never offered a self-update.
     */
    fun ownCertificate(): String? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        null
    } else {
        try {
            certificateOf(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        } catch (_: Exception) {
            null
        }
    }

    private fun apkCertificate(file: File): String? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        null
    } else {
        try {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let { certificateOf(it) }
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun certificateOf(info: PackageInfo): String? {
        val signing = info.signingInfo ?: return null
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        // The current signer: the only one, or the newest of a rotated history.
        val certificate = (if (signing.hasMultipleSigners()) signers.firstOrNull() else signers.lastOrNull()) ?: return null
        return ReleaseRules.certificateDigest(certificate.toByteArray())
    }
}

/** The downloaded release in the app's cache folder, until the installer has it. */
private class FileOutputSink(private val file: File) : FileSink {
    private val out = FileOutputStream(file)

    override fun write(bytes: ByteArray, length: Int) = out.write(bytes, 0, length)

    override fun commit() {
        out.flush()
        out.fd.sync()
        out.close()
    }

    override fun abort() {
        try {
            out.close()
        } catch (_: Exception) {
        }
        file.delete()
    }
}

/**
 * The installer's answers: when it needs the owner's confirmation, its screen is shown; the
 * end result is passed to the updater.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as dev.lifeos.app.LifeOsApplication
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                if (confirm != null) {
                    app.runningCore()?.updater?.needsConfirmation(confirm)
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        // Not allowed from the background: the card's button opens it.
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> app.runningCore()?.updater?.installFinished(true)
            else -> app.runningCore()?.updater?.installFinished(false)
        }
    }
}
