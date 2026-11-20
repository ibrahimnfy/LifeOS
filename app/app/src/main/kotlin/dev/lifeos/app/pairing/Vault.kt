package dev.lifeos.app.pairing

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class VaultException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The owner must confirm with fingerprint or screen lock first. */
        NEEDS_AUTHENTICATION,

        /** The phone has no screen lock, so no protected key can exist. */
        NO_SCREEN_LOCK,

        /** The key is gone for good (for example the screen lock was removed): pair again. */
        LOST,
    }
}

/** Encrypts the device secret at rest. */
interface SecretVault {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
    fun destroy()
}

/**
 * AES-256-GCM with a key that never leaves the Android Keystore. This protects the device
 * secret at rest only; it is the platform's own construction, not part of the end-to-end layer.
 */
class KeystoreVault(
    private val alias: String,
    private val configure: KeyGenParameterSpec.Builder.() -> Unit,
) : SecretVault {

    override fun encrypt(plain: ByteArray): ByteArray = guarded {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray = guarded {
        if (sealed.size <= IV_BYTES) throw VaultException(VaultException.Reason.LOST)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun destroy() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw VaultException(VaultException.Reason.LOST)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply(configure)
            .build()
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
        } catch (error: GeneralSecurityException) {
            throw VaultException(VaultException.Reason.NO_SCREEN_LOCK, error)
        } catch (error: IllegalStateException) {
            throw VaultException(VaultException.Reason.NO_SCREEN_LOCK, error)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: VaultException) {
        throw error
    } catch (error: UserNotAuthenticatedException) {
        throw VaultException(VaultException.Reason.NEEDS_AUTHENTICATION, error)
    } catch (error: KeyPermanentlyInvalidatedException) {
        throw VaultException(VaultException.Reason.LOST, error)
    } catch (error: GeneralSecurityException) {
        throw VaultException(VaultException.Reason.LOST, error)
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

object Vaults {
    /**
     * The key that seals the push key only (owner's choice #136 = A): usable without the
     * owner's confirmation, so a push can be opened while the app is locked or closed. It never
     * seals the device secret.
     */
    fun push(): SecretVault = KeystoreVault("lifeos.push.v1") {}

    /** The vault kind of every real pairing, as recorded in [PairingRecord.vault]. */
    const val AUTH = "auth"

    /** How long after a confirmation the key stays usable. */
    const val AUTH_VALIDITY_SECONDS = 300

    /**
     * The key of a real pairing: usable only for five minutes after the owner confirmed with a
     * strong biometric or the screen lock. Creating it fails when the phone has no screen lock.
     */
    fun authBound(): SecretVault = KeystoreVault("lifeos.device.auth.v1") {
        setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setUserAuthenticationParameters(
                AUTH_VALIDITY_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
        }
    }
}
