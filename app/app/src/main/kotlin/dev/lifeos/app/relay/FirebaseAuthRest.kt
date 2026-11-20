package dev.lifeos.app.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The relay account over plain REST: an anonymous account of the owner's Firebase project.
 * It only decides which mailbox this phone may reach; it protects no content.
 */
class FirebaseAuthRest(private val client: OkHttpClient, private val apiKey: String) : RelayAuth {

    override suspend fun signUp(): RelayTokens {
        val data = post(
            "$IDENTITY/accounts:signUp",
            buildJsonObject { put("returnSecureToken", JsonPrimitive(true)) }.toString().toRequestBody(JSON),
        )
        return tokens(data, "idToken", "refreshToken", "localId", "expiresIn")
    }

    override suspend fun refresh(refreshToken: String): RelayTokens {
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).build()
        return tokens(post("$SECURE_TOKEN/token", form), "id_token", "refresh_token", "user_id", "expires_in")
    }

    override suspend fun deleteAccount(idToken: String) {
        post(
            "$IDENTITY/accounts:delete",
            buildJsonObject { put("idToken", JsonPrimitive(idToken)) }.toString().toRequestBody(JSON),
        )
    }

    private suspend fun post(address: String, body: RequestBody): JsonObject {
        val url = address.toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
        val result = try {
            Http.run(client.newCall(Request.Builder().url(url).post(body).build()))
        } catch (_: IOException) {
            throw RelayAuthException(refused = false)
        }
        if (result.status in 500..599 || result.status == 429) throw RelayAuthException(refused = false)
        if (result.status != 200) throw RelayAuthException(refused = true)
        return try {
            Json.parseToJsonElement(result.body).jsonObject
        } catch (_: Exception) {
            throw RelayAuthException(refused = false)
        }
    }

    private fun tokens(data: JsonObject, id: String, refresh: String, uid: String, expires: String): RelayTokens {
        fun text(name: String) = (data[name] as? JsonPrimitive)?.content ?: throw RelayAuthException(refused = false)
        return RelayTokens(text(id), text(refresh), text(uid), text(expires).toLongOrNull() ?: 3600)
    }

    private companion object {
        const val IDENTITY = "https://identitytoolkit.googleapis.com/v1"
        const val SECURE_TOKEN = "https://securetoken.googleapis.com/v1"
        val JSON = "application/json".toMediaType()
    }
}
