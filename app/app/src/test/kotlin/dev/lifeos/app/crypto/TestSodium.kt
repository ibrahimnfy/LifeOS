package dev.lifeos.app.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/** libsodium for JVM unit tests: the desktop binding behind the same interface the app uses. */
val testSodium: Sodium by lazy { LazySodiumBackend(LazySodiumJava(SodiumJava())) }

fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
