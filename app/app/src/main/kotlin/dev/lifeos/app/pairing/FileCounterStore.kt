package dev.lifeos.app.pairing

import dev.lifeos.app.relay.CounterStore
import kotlinx.serialization.json.Json
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

/**
 * The message counters in a small file. Every change is written to a temporary file, forced
 * to storage and moved into place *before* the caller sends or acts, so a crash or power loss
 * can lose a number but never reuse one or accept a repeat.
 */
class FileCounterStore private constructor(private val file: File, private var sent: Long, private var received: Long) :
    CounterStore {

    @Synchronized
    override fun nextSend(): Long {
        write(file, sent + 1, received)
        sent += 1
        return sent
    }

    @Synchronized
    override fun acceptReceived(counter: Long): Boolean {
        if (counter <= received) return false
        write(file, sent, counter)
        received = counter
        return true
    }

    companion object {
        /** Opens existing counters. Throws [IOException] if the file is missing or unreadable. */
        fun open(file: File): FileCounterStore = try {
            val data = Json.parseToJsonElement(file.readText()).jsonObject
            FileCounterStore(file, data.getValue("sent").jsonPrimitive.long, data.getValue("received").jsonPrimitive.long)
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("the counters cannot be read", error)
        }

        /** Creates the counters of a new pairing. */
        fun create(file: File, sent: Long, received: Long): FileCounterStore {
            write(file, sent, received)
            return FileCounterStore(file, sent, received)
        }

        private fun write(file: File, sent: Long, received: Long) {
            val text = buildJsonObject {
                put("sent", JsonPrimitive(sent))
                put("received", JsonPrimitive(received))
            }.toString()
            val temporary = File(file.parentFile, file.name + ".new")
            FileOutputStream(temporary).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
