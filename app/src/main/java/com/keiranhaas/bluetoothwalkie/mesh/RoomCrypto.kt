package com.keiranhaas.bluetoothwalkie.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray
)

class RoomCrypto private constructor(
    val channel: Int,
    private val key:
        SecretKeySpec,
    val roomTag: ByteArray
) {

    private val secureRandom =
        SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray
    ): EncryptedPayload {

        val nonce =
            ByteArray(
                NONCE_SIZE
            )

        secureRandom.nextBytes(
            nonce
        )

        val cipher =
            Cipher.getInstance(
                TRANSFORMATION
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(
                GCM_TAG_BITS,
                nonce
            )
        )

        cipher.updateAAD(
            aad
        )

        val ciphertext =
            cipher.doFinal(
                plaintext
            )

        return EncryptedPayload(
            nonce = nonce,
            ciphertext =
                ciphertext
        )
    }

    fun decrypt(
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray? {

        if (
            nonce.size !=
            NONCE_SIZE
        ) {
            return null
        }

        return try {

            val cipher =
                Cipher.getInstance(
                    TRANSFORMATION
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(
                    GCM_TAG_BITS,
                    nonce
                )
            )

            cipher.updateAAD(
                aad
            )

            cipher.doFinal(
                ciphertext
            )

        } catch (_: Exception) {
            null
        }
    }

    fun matchesRoomTag(
        other: ByteArray
    ): Boolean {
        return MessageDigest.isEqual(
            roomTag,
            other
        )
    }

    companion object {

        const val NONCE_SIZE =
            12

        const val ROOM_TAG_SIZE =
            8

        private const val PBKDF2_ITERATIONS =
            120_000

        private const val KEY_BITS =
            256

        private const val GCM_TAG_BITS =
            128

        private const val TRANSFORMATION =
            "AES/GCM/NoPadding"

        fun derive(
            roomCode: String,
            channel: Int
        ): RoomCrypto {

            val cleanedCode =
                roomCode.trim()

            require(
                cleanedCode.isNotBlank()
            )

            val saltPrefix =
                "MeshWalkie.RoomKey.v2"
                    .toByteArray(
                        Charsets.UTF_8
                    )

            val salt =
                ByteBuffer.allocate(
                    saltPrefix.size +
                        Int.SIZE_BYTES
                )
                    .put(
                        saltPrefix
                    )
                    .putInt(
                        channel
                    )
                    .array()

            val specification =
                PBEKeySpec(
                    cleanedCode.toCharArray(),
                    salt,
                    PBKDF2_ITERATIONS,
                    KEY_BITS
                )

            val factory =
                SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256"
                )

            val keyBytes =
                factory
                    .generateSecret(
                        specification
                    )
                    .encoded

            specification.clearPassword()

            val secretKey =
                SecretKeySpec(
                    keyBytes,
                    "AES"
                )

            val tagDigest =
                MessageDigest.getInstance(
                    "SHA-256"
                )

            tagDigest.update(
                "MeshWalkie.RoomTag.v2"
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )

            tagDigest.update(
                keyBytes
            )

            val roomTag =
                tagDigest
                    .digest()
                    .copyOf(
                        ROOM_TAG_SIZE
                    )

            return RoomCrypto(
                channel =
                    channel,
                key =
                    secretKey,
                roomTag =
                    roomTag
            )
        }
    }
}