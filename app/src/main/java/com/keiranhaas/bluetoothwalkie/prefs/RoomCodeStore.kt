package com.keiranhaas.bluetoothwalkie.prefs

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RoomCodeStore(
    context: Context
) {

    private val preferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun save(
        roomCode: String
    ) {
        val cleaned =
            roomCode.trim()

        if (cleaned.isBlank()) {
            clear()
            return
        }

        val key =
            getOrCreateKey()

        val cipher =
            Cipher.getInstance(
                TRANSFORMATION
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            key
        )

        val ciphertext =
            cipher.doFinal(
                cleaned.toByteArray(
                    Charsets.UTF_8
                )
            )

        val iv =
            cipher.iv

        val combined =
            ByteArray(
                1 +
                    iv.size +
                    ciphertext.size
            )

        combined[0] =
            iv.size.toByte()

        System.arraycopy(
            iv,
            0,
            combined,
            1,
            iv.size
        )

        System.arraycopy(
            ciphertext,
            0,
            combined,
            1 + iv.size,
            ciphertext.size
        )

        preferences
            .edit()
            .putString(
                KEY_ENCRYPTED_ROOM_CODE,
                Base64.encodeToString(
                    combined,
                    Base64.NO_WRAP
                )
            )
            .apply()
    }

    fun load(): String {

        val encoded =
            preferences.getString(
                KEY_ENCRYPTED_ROOM_CODE,
                null
            )
                ?: return ""

        return try {

            val combined =
                Base64.decode(
                    encoded,
                    Base64.NO_WRAP
                )

            if (
                combined.size < 14
            ) {
                return ""
            }

            val ivLength =
                combined[0]
                    .toInt() and 0xFF

            if (
                ivLength <= 0 ||
                1 + ivLength >=
                combined.size
            ) {
                return ""
            }

            val iv =
                combined.copyOfRange(
                    1,
                    1 + ivLength
                )

            val ciphertext =
                combined.copyOfRange(
                    1 + ivLength,
                    combined.size
                )

            val key =
                getOrCreateKey()

            val cipher =
                Cipher.getInstance(
                    TRANSFORMATION
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(
                    128,
                    iv
                )
            )

            cipher
                .doFinal(
                    ciphertext
                )
                .toString(
                    Charsets.UTF_8
                )

        } catch (_: Exception) {

            clear()

            ""
        }
    }

    fun hasRoomCode(): Boolean {
        return load().isNotBlank()
    }

    fun clear() {
        preferences
            .edit()
            .remove(
                KEY_ENCRYPTED_ROOM_CODE
            )
            .apply()
    }

    private fun getOrCreateKey():
        SecretKey {

        val keyStore =
            KeyStore.getInstance(
                ANDROID_KEYSTORE
            )

        keyStore.load(null)

        val existing =
            keyStore.getKey(
                KEY_ALIAS,
                null
            )

        if (
            existing is SecretKey
        ) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

        val specification =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()

        generator.init(
            specification
        )

        return generator
            .generateKey()
    }

    companion object {

        const val MIN_ROOM_CODE_LENGTH =
            8

        private const val PREFS_NAME =
            "mesh_walkie_secure_preferences"

        private const val KEY_ENCRYPTED_ROOM_CODE =
            "encrypted_room_code"

        private const val KEY_ALIAS =
            "MeshWalkieRoomStorageKey"

        private const val ANDROID_KEYSTORE =
            "AndroidKeyStore"

        private const val TRANSFORMATION =
            "AES/GCM/NoPadding"
    }
}