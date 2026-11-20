package dev.lifeos.app.push

import dev.lifeos.app.pairing.SecretVault
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The push key (sub-key 3 of the pairing) and this phone's device id, kept so that a push can
 * be opened while the app is locked or closed (owner's choice #136 = A).
 *
 * The push key is sealed by a second Keystore key that needs no user confirmation ([vault]);
 * the device secret itself stays under the confirmation-bound key. The push key opens push
 * hints only (direction "push", kind "push hint"): requests and answers use other sub-keys,
 * so holding it reveals nothing but the kind of an event and its references.
 *
 * Also keeps the push counter: a hint is accepted only with a counter greater than every
 * earlier one, written to storage before the hint is acted on.
 */
class PushKeyStore(private val folder: File, private val vault: () -> SecretVault?) {
    private val keyFile = File(folder, "push.key")
    private val deviceFile = File(folder, "push.device")
    private val counterFile = File(folder, "push.counter")
    private val tokenFile = File(folder, "push.token-sent")

    /** Keep the push key of a pairing (after pairing, and on every unlock in case it was lost). */
    @Synchronized
    fun save(pushKey: ByteArray, deviceId: ByteArray): Boolean {
        val sealed = try {
            vault()?.encrypt(pushKey) ?: return false
        } catch (_: Exception) {
            return false
        }
        return try {
            write(keyFile, sealed)
            write(deviceFile, deviceId)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** The push key and the device id, or null when there is none (not paired, or lost). */
    @Synchronized
    fun load(): Pair<ByteArray, ByteArray>? = try {
        if (!keyFile.isFile || !deviceFile.isFile) {
            null
        } else {
            val key = vault()?.decrypt(keyFile.readBytes())
            if (key == null) null else key to deviceFile.readBytes()
        }
    } catch (_: Exception) {
        null
    }

    fun has(): Boolean = keyFile.isFile && deviceFile.isFile

    /** True (and stored) if [counter] is greater than every counter accepted before. */
    @Synchronized
    fun acceptCounter(counter: Long): Boolean {
        val last = try {
            counterFile.readText().trim().toLong()
        } catch (_: Exception) {
            -1L
        }
        if (counter <= last) return false
        return try {
            write(counterFile, counter.toString().toByteArray())
            true
        } catch (_: IOException) {
            false
        }
    }

    /** The push address this phone last handed to the computer (to send a changed one again). */
    fun sentToken(): String? = try {
        tokenFile.readText().ifEmpty { null }
    } catch (_: IOException) {
        null
    }

    fun markTokenSent(token: String?) {
        try {
            if (token == null) tokenFile.delete() else write(tokenFile, token.toByteArray())
        } catch (_: IOException) {
        }
    }

    /** Unpair: the key, the device id, the counter and the sent address go. */
    @Synchronized
    fun wipe() {
        listOf(keyFile, deviceFile, counterFile, tokenFile).forEach { it.delete() }
        try {
            vault()?.destroy()
        } catch (_: Exception) {
        }
    }

    private fun write(file: File, bytes: ByteArray) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("no folder")
        val temporary = File(folder, file.name + ".new")
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
