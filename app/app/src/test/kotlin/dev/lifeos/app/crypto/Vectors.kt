package dev.lifeos.app.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/** The vectors written by the bridge's `tests/make_e2e_vectors.py`. Synthetic keys only. */
object Vectors {
    class Sealed(
        val name: String,
        val direction: Int,
        val counter: Long,
        val kind: Int,
        val timestamp: Long,
        val requestId: String,
        val payload: JsonElement,
        val nonce: ByteArray,
        val header: ByteArray,
        val blob: ByteArray,
    )

    private val root: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("e2e-vectors.json")).use { it.readBytes().decodeToString() },
    ).jsonObject

    val secret: ByteArray = root.text("secret").hexToBytes()
    val deviceId: ByteArray = root.text("device_id").hexToBytes()
    val subkeys: Map<Int, String> = root.getValue("subkeys").jsonObject.entries.associate { it.key.toInt() to it.value.jsonPrimitive.content }
    val comparisonCode: String = root.text("comparison_code")
    val pairingText: String = root.text("pairing_text")
    val relay: JsonObject = root.getValue("relay").jsonObject
    val sealed: List<Sealed> = root.getValue("sealed").jsonArray.map { it.jsonObject }.map {
        Sealed(
            it.text("name"),
            it.getValue("direction").jsonPrimitive.int,
            it.getValue("counter").jsonPrimitive.long,
            it.getValue("kind").jsonPrimitive.int,
            it.getValue("ts").jsonPrimitive.long,
            it.text("rid"),
            it.getValue("payload"),
            it.text("nonce").hexToBytes(),
            it.text("header").hexToBytes(),
            Base64.getDecoder().decode(it.text("blob")),
        )
    }

    fun named(name: String): Sealed = sealed.first { it.name == name }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
