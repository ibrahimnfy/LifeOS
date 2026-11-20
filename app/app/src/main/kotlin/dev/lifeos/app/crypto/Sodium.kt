package dev.lifeos.app.crypto

import com.goterl.lazysodium.LazySodium

/**
 * The only cryptographic operations the app uses, all documented libsodium functions
 * (docs/security.md, section 3). Nothing else may be added here without a design change.
 */
interface Sodium {
    /** `randombytes_buf`. */
    fun randomBytes(count: Int): ByteArray

    /** `crypto_kdf_derive_from_key`: a 32-byte sub-key of [masterKey]. */
    fun kdfDeriveFromKey(subkeyId: Long, context: ByteArray, masterKey: ByteArray): ByteArray

    /** `crypto_aead_xchacha20poly1305_ietf_encrypt`: ciphertext followed by the tag. */
    fun aeadEncrypt(message: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray

    /** `crypto_aead_xchacha20poly1305_ietf_decrypt`: the message, or null if authentication fails. */
    fun aeadDecrypt(ciphertext: ByteArray, associatedData: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?

    /** `crypto_generichash` (BLAKE2b) with a key, 32 bytes of output. */
    fun genericHash(message: ByteArray, key: ByteArray): ByteArray

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16
        const val HASH_BYTES = 32
        const val KDF_CONTEXT_BYTES = 8
    }
}

/**
 * [Sodium] through lazysodium. The app passes the Android binding, JVM unit tests the
 * desktop one; both expose the same native functions of the same libsodium.
 */
class LazySodiumBackend(private val sodium: LazySodium) : Sodium {

    override fun randomBytes(count: Int): ByteArray = sodium.randomBytesBuf(count)

    override fun kdfDeriveFromKey(subkeyId: Long, context: ByteArray, masterKey: ByteArray): ByteArray {
        require(context.size == Sodium.KDF_CONTEXT_BYTES && masterKey.size == Sodium.KEY_BYTES)
        val subkey = ByteArray(Sodium.KEY_BYTES)
        check(sodium.cryptoKdfDeriveFromKey(subkey, subkey.size, subkeyId, context, masterKey) == 0)
        return subkey
    }

    override fun aeadEncrypt(
        message: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray {
        require(nonce.size == Sodium.NONCE_BYTES && key.size == Sodium.KEY_BYTES)
        val out = ByteArray(message.size + Sodium.TAG_BYTES)
        val length = LongArray(1)
        check(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                out, length, message, message.size.toLong(),
                associatedData, associatedData.size.toLong(), null, nonce, key,
            ),
        )
        check(length[0] == out.size.toLong())
        return out
    }

    override fun aeadDecrypt(
        ciphertext: ByteArray,
        associatedData: ByteArray,
        nonce: ByteArray,
        key: ByteArray,
    ): ByteArray? {
        require(nonce.size == Sodium.NONCE_BYTES && key.size == Sodium.KEY_BYTES)
        if (ciphertext.size < Sodium.TAG_BYTES) return null
        val out = ByteArray(ciphertext.size - Sodium.TAG_BYTES)
        val length = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, length, null, ciphertext, ciphertext.size.toLong(),
            associatedData, associatedData.size.toLong(), nonce, key,
        )
        return if (ok && length[0] == out.size.toLong()) out else null
    }

    override fun genericHash(message: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(Sodium.HASH_BYTES)
        check(sodium.cryptoGenericHash(out, out.size, message, message.size.toLong(), key, key.size))
        return out
    }
}
