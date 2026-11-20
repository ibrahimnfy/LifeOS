package dev.lifeos.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

class SavedCopy(val raw: JsonElement, val savedAtMillis: Long)

/**
 * The last successful answer per endpoint, so a screen can show a saved copy with its age
 * when the computer cannot be reached. One small JSON file per endpoint key in the app's
 * private files (never in a backup); everything is deleted on unpair. File contents of
 * downloads are never put here.
 */
class ScreenCache(private val folder: File) {

    fun put(key: String, raw: JsonElement, nowMillis: Long) {
        try {
            if (!folder.isDirectory && !folder.mkdirs()) return
            val text = buildJsonObject {
                put("at", JsonPrimitive(nowMillis))
                put("data", raw)
            }.toString()
            val target = file(key)
            val temporary = File(folder, target.name + ".new")
            FileOutputStream(temporary).use { it.write(text.toByteArray()) }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // A saved copy is a convenience; failing to write one is not an error for the screen.
        }
    }

    fun get(key: String): SavedCopy? = try {
        val data = Json.parseToJsonElement(file(key).readText()).jsonObject
        SavedCopy(data.getValue("data"), data.getValue("at").jsonPrimitive.long)
    } catch (_: Exception) {
        null
    }

    fun wipe() {
        folder.listFiles()?.forEach { it.delete() }
        folder.delete()
    }

    private fun file(key: String): File = File(folder, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
}
