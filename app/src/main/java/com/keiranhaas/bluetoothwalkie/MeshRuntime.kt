package com.keiranhaas.bluetoothwalkie

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MeshUiState(
    val networkRunning: Boolean = false,
    val peerCount: Int = 0,
    val transmitting: Boolean = false,
    val receivingFrom: String? = null,
    val callsign: String = "",
    val channel: Int = 1,
    val roomCodeConfigured: Boolean = false,
    val status: String = "Mesh stopped",
    val bluetoothSupported: Boolean = true,
    val floorNoticeSerial: Long = 0
)

object MeshRuntime {

    private val mutableState =
        MutableStateFlow(
            MeshUiState()
        )

    val state: StateFlow<MeshUiState> =
        mutableState.asStateFlow()

    fun setRunning(
        running: Boolean,
        status: String
    ) {
        mutableState.update {
            it.copy(
                networkRunning = running,
                status = status
            )
        }
    }

    fun setPeerCount(
        count: Int
    ) {
        mutableState.update {
            it.copy(
                peerCount = count
            )
        }
    }

    fun setTransmitting(
        transmitting: Boolean
    ) {
        mutableState.update {
            it.copy(
                transmitting = transmitting
            )
        }
    }

    fun setReceivingFrom(
        callsign: String?
    ) {
        mutableState.update {
            it.copy(
                receivingFrom = callsign
            )
        }
    }

    fun setStatus(
        status: String
    ) {
        mutableState.update {
            it.copy(
                status = status
            )
        }
    }

    /** Sets the status text and signals the UI to give "denied" haptic feedback. */
    fun postFloorNotice(
        message: String
    ) {
        mutableState.update {
            it.copy(
                status = message,
                floorNoticeSerial =
                    it.floorNoticeSerial + 1
            )
        }
    }

    fun setBluetoothSupported(
        supported: Boolean
    ) {
        mutableState.update {
            it.copy(
                bluetoothSupported = supported
            )
        }
    }

    fun setConfig(
        callsign: String,
        channel: Int,
        roomCodeConfigured: Boolean
    ) {
        mutableState.update {
            it.copy(
                callsign = callsign,
                channel = channel,
                roomCodeConfigured =
                    roomCodeConfigured
            )
        }
    }

    fun setRoomCodeConfigured(
        configured: Boolean
    ) {
        mutableState.update {
            it.copy(
                roomCodeConfigured =
                    configured
            )
        }
    }
}