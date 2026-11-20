package dev.lifeos.app.pairing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What is known about the pairing without the secret. */
class PairingRecord(
    val deviceId: ByteArray,
    /** The name the computer knows this phone by. */
    val name: String,
    val relay: RelayInfo,
    val uid: String,
    val refreshToken: String,
    val pairedAtMillis: Long,
    /** Which Keystore key protects the secret; see [Vaults]. */
    val vault: String,
) {
    val deviceHex: String get() = deviceId.joinToString("") { "%02x".format(it) }
}

/**
 * The pairing in the app's private files (never in a backup: see the manifest and
 * `data_extraction_rules.xml`):
 *
 *     pairing/secret.bin      the device secret, encrypted with a Keystore key
 *     pairing/counters.json   the two message counters
 *     pairing/state.json      device id, relay address and relay account; written last
 *     pairing/contact         when the computer last answered
 *
 * A pairing exists only if all of the first three are there.
 */
class PairingStore(private val folder: File) {
    private val secretFile = File(folder, "secret.bin")
    private val countersFile = File(folder, "counters.json")
    private val stateFile = File(folder, "state.json")
    private val contactFile = File(folder, "contact")

    fun load(): PairingRecord? {
        if (!stateFile.isFile || !secretFile.isFile || !countersFile.isFile) return null
        return try {
            val data = Json.parseToJsonElement(stateFile.readText()).jsonObject
            val relay = data.getValue("relay").jsonObject
            PairingRecord(
                deviceId = data.text("device").chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                name = data.text("name"),
                relay = RelayInfo(relay.text("k"), relay.text("u"), relay.text("a")),
                uid = data.text("uid"),
                refreshToken = data.text("refresh"),
                pairedAtMillis = data.getValue("paired_at").jsonPrimitive.long,
                vault = data.text("vault"),
            )
        } catch (_: Exception) {
            null
        }
    }

    @Throws(IOException::class)
    fun save(record: PairingRecord, sealedSecret: ByteArray, sent: Long, received: Long) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("the pairing folder cannot be created")
        write(secretFile, sealedSecret)
        FileCounterStore.create(countersFile, sent, received)
        writeState(record)
    }

    fun sealedSecret(): ByteArray? = try {
        secretFile.readBytes()
    } catch (_: IOException) {
        null
    }

    @Throws(IOException::class)
    fun counters(): FileCounterStore = FileCounterStore.open(countersFile)

    /** The relay hands out a new refresh token from time to time. */
    fun updateRefreshToken(token: String) {
        val record = load() ?: return
        try {
            writeState(PairingRecord(record.deviceId, record.name, record.relay, record.uid, token, record.pairedAtMillis, record.vault))
        } catch (_: IOException) {
        }
    }

    fun lastContactMillis(): Long? = try {
        contactFile.readText().trim().toLongOrNull()
    } catch (_: IOException) {
        null
    }

    fun recordContact(nowMillis: Long) {
        try {
            write(contactFile, nowMillis.toString().toByteArray())
        } catch (_: IOException) {
        }
    }

    /** "Unpair this phone": nothing of the pairing stays. The Keystore key is deleted by the caller. */
    fun wipe() {
        folder.listFiles()?.forEach { file ->
            try {
                if (file.isFile) FileOutputStream(file).use { it.write(ByteArray(file.length().toInt().coerceIn(0, 65536))) }
            } catch (_: IOException) {
            }
            file.delete()
        }
        folder.delete()
    }

    private fun writeState(record: PairingRecord) {
        val text = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("device", JsonPrimitive(record.deviceHex))
            put("name", JsonPrimitive(record.name))
            put(
                "relay",
                buildJsonObject {
                    put("k", JsonPrimitive(record.relay.kind))
                    put("u", JsonPrimitive(record.relay.databaseUrl))
                    put("a", JsonPrimitive(record.relay.apiKey))
                },
            )
            put("uid", JsonPrimitive(record.uid))
            put("refresh", JsonPrimitive(record.refreshToken))
            put("paired_at", JsonPrimitive(record.pairedAtMillis))
            put("vault", JsonPrimitive(record.vault))
        }.toString()
        write(stateFile, text.toByteArray())
    }

    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
