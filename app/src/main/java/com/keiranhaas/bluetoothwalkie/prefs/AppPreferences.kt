package com.keiranhaas.bluetoothwalkie.prefs

import android.content.Context
import java.security.SecureRandom

class AppPreferences(
    context: Context
) {

    private val preferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    val nodeId: Long
        get() {
            if (preferences.contains(KEY_NODE_ID)) {
                return preferences.getLong(
                    KEY_NODE_ID,
                    1L
                )
            }

            var generated =
                SecureRandom().nextLong()

            if (generated == 0L) {
                generated = 1L
            }

            preferences
                .edit()
                .putLong(
                    KEY_NODE_ID,
                    generated
                )
                .apply()

            return generated
        }

    var callsign: String
        get() {
            val existing =
                preferences.getString(
                    KEY_CALLSIGN,
                    null
                )

            if (!existing.isNullOrBlank()) {
                return existing
            }

            val suffix =
                (nodeId and 0xFFFFL)
                    .toString(16)
                    .uppercase()
                    .padStart(4, '0')

            return "Radio-$suffix"
        }
        set(value) {
            val cleaned =
                value
                    .trim()
                    .take(MAX_CALLSIGN_LENGTH)
                    .ifBlank { "Radio" }

            preferences
                .edit()
                .putString(
                    KEY_CALLSIGN,
                    cleaned
                )
                .apply()
        }

    var channel: Int
        get() =
            preferences.getInt(
                KEY_CHANNEL,
                1
            ).coerceIn(
                MIN_CHANNEL,
                MAX_CHANNEL
            )

        set(value) {
            preferences
                .edit()
                .putInt(
                    KEY_CHANNEL,
                    value.coerceIn(
                        MIN_CHANNEL,
                        MAX_CHANNEL
                    )
                )
                .apply()
        }

    companion object {
        const val MIN_CHANNEL = 1
        const val MAX_CHANNEL = 16

        const val MAX_CALLSIGN_LENGTH = 20

        private const val PREFS_NAME =
            "mesh_walkie_preferences"

        private const val KEY_NODE_ID =
            "node_id"

        private const val KEY_CALLSIGN =
            "callsign"

        private const val KEY_CHANNEL =
            "channel"
    }
}