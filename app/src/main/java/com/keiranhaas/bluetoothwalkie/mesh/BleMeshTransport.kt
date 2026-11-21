package com.keiranhaas.bluetoothwalkie.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleMeshTransport(
    context: Context,
    private val onPacketReceived: (
        ByteArray
    ) -> Unit,
    private val onPeerCountChanged: (
        Int
    ) -> Unit,
    private val onStatus: (
        String
    ) -> Unit
) {

    private val appContext =
        context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(
            BluetoothManager::class.java
        )

    private val adapter:
        BluetoothAdapter?
        get() =
            bluetoothManager?.adapter

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO
        )

    private val reassembler =
        FragmentReassembler()

    private val peers =
        ConcurrentHashMap<
            String,
            PeerConnection
        >()

    private val connecting =
        ConcurrentHashMap.newKeySet<String>()

    private var gattServer:
        BluetoothGattServer? = null

    private var scanner:
        BluetoothLeScanner? = null

    private var advertiser:
        BluetoothLeAdvertiser? = null

    @Volatile
    private var started =
        false

    val isBluetoothAvailable: Boolean
        get() {
            val localAdapter =
                adapter
                    ?: return false

            if (!localAdapter.isEnabled) {
                return false
            }

            return localAdapter.bluetoothLeScanner != null &&
                localAdapter.bluetoothLeAdvertiser != null
        }

    fun start(): Boolean {

        if (started) {
            return true
        }

        val localAdapter =
            adapter

        if (
            localAdapter == null ||
            !localAdapter.isEnabled
        ) {
            onStatus(
                "Bluetooth is unavailable or disabled"
            )

            return false
        }

        scanner =
            localAdapter.bluetoothLeScanner

        advertiser =
            localAdapter.bluetoothLeAdvertiser

        if (
            scanner == null ||
            advertiser == null
        ) {
            onStatus(
                "This device does not support the required BLE roles"
            )

            return false
        }

        val server =
            bluetoothManager.openGattServer(
                appContext,
                serverCallback
            )

        if (server == null) {
            onStatus(
                "Unable to open Bluetooth GATT server"
            )

            return false
        }

        gattServer = server

        val service =
            BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

        val receiveCharacteristic =
            BluetoothGattCharacteristic(
                RX_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

        service.addCharacteristic(
            receiveCharacteristic
        )

        val serviceAdded =
            server.addService(
                service
            )

        if (!serviceAdded) {

            onStatus(
                "Unable to register MeshWalkie GATT service"
            )

            server.close()

            gattServer = null

            return false
        }

        started = true

        startScanning()

        onStatus(
            "Starting Bluetooth mesh"
        )

        return true
    }

    fun stop() {

        if (!started) {
            return
        }

        started = false

        try {
            scanner?.stopScan(
                scanCallback
            )
        } catch (_: Exception) {
        }

        try {
            advertiser?.stopAdvertising(
                advertiseCallback
            )
        } catch (_: Exception) {
        }

        peers
            .values
            .toList()
            .forEach {
                closePeer(it)
            }

        peers.clear()

        connecting.clear()

        try {
            gattServer?.clearServices()
            gattServer?.close()
        } catch (_: Exception) {
        }

        gattServer = null

        onPeerCountChanged(0)

        onStatus(
            "Bluetooth mesh stopped"
        )

        scope.cancel()
    }

    fun broadcast(
        packet: MeshPacket
    ) {

        if (!started) {
            return
        }

        val encoded =
            MeshPacketCodec.encode(
                packet
            )

        for (
            peer in
            peers.values
        ) {

            val maximumWrite =
                (peer.mtu - ATT_OVERHEAD_BYTES)
                    .coerceAtLeast(
                        MINIMUM_WRITE_SIZE
                    )

            val fragments =
                FragmentCodec.fragment(
                    fragmentId =
                        packet.packetId,
                    packet =
                        encoded,
                    maxWriteSize =
                        maximumWrite
                )

            for (
                fragment in
                fragments
            ) {
                peer.sendQueue
                    .trySend(
                        fragment
                    )
            }
        }
    }

    private fun startScanning() {

        val localScanner =
            scanner
                ?: return

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid(
                        SERVICE_UUID
                    )
                )
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build()

        try {
            localScanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )
        } catch (
            exception: Exception
        ) {
            onStatus(
                "BLE scan failed: ${exception.message}"
            )
        }
    }

    private fun startAdvertising() {

        val localAdvertiser =
            advertiser
                ?: return

        val settings =
            AdvertiseSettings.Builder()
                .setAdvertiseMode(
                    AdvertiseSettings.ADVERTISE_MODE_BALANCED
                )
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                .build()

        val data =
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(
                    ParcelUuid(
                        SERVICE_UUID
                    )
                )
                .build()

        try {
            localAdvertiser.startAdvertising(
                settings,
                data,
                advertiseCallback
            )
        } catch (
            exception: Exception
        ) {
            onStatus(
                "BLE advertising failed: ${exception.message}"
            )
        }
    }

    private fun connectToDevice(
        device: BluetoothDevice
    ) {

        if (!started) {
            return
        }

        val address =
            device.address

        if (
            peers.containsKey(address) ||
            connecting.contains(address)
        ) {
            return
        }

        if (
            peers.size >= MAX_PEERS
        ) {
            return
        }

        connecting.add(address)

        try {

            device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )

        } catch (
            exception: Exception
        ) {

            connecting.remove(
                address
            )

            onStatus(
                "Peer connection failed"
            )
        }
    }

    private fun createPeer(
        gatt: BluetoothGatt,
        characteristic:
            BluetoothGattCharacteristic
    ) {

        val address =
            gatt.device.address

        if (
            peers.containsKey(address)
        ) {
            return
        }

        val peer =
            PeerConnection(
                address = address,
                gatt = gatt,
                characteristic =
                    characteristic
            )

        val existing =
            peers.putIfAbsent(
                address,
                peer
            )

        if (existing != null) {
            return
        }

        peer.senderJob =
            scope.launch {

                for (
                    frame in
                    peer.sendQueue
                ) {

                    if (!started) {
                        break
                    }

                    var sent =
                        false

                    repeat(
                        WRITE_RETRY_COUNT
                    ) {

                        if (
                            writeCharacteristic(
                                peer,
                                frame
                            )
                        ) {
                            sent = true
                            return@repeat
                        }

                        delay(
                            WRITE_RETRY_DELAY_MS
                        )
                    }

                    if (!sent) {
                        // Voice favors continuing
                        // instead of blocking the
                        // entire queue indefinitely.
                    }

                    delay(
                        WRITE_SPACING_MS
                    )
                }
            }

        onPeerCountChanged(
            peers.size
        )

        onStatus(
            "Connected to ${peers.size} nearby peer(s)"
        )
    }

    private fun removePeer(
        address: String
    ) {

        val peer =
            peers.remove(address)
                ?: return

        closePeer(peer)

        onPeerCountChanged(
            peers.size
        )

        if (
            started &&
            peers.isEmpty()
        ) {
            onStatus(
                "Searching for nearby MeshWalkie devices"
            )
        }
    }

    private fun closePeer(
        peer: PeerConnection
    ) {

        peer.sendQueue.close()

        peer.senderJob?.cancel()

        try {
            peer.gatt.disconnect()
        } catch (_: Exception) {
        }

        try {
            peer.gatt.close()
        } catch (_: Exception) {
        }
    }

    private fun writeCharacteristic(
        peer: PeerConnection,
        bytes: ByteArray
    ): Boolean {

        return try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU
            ) {

                peer.gatt.writeCharacteristic(
                    peer.characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) ==
                    BluetoothStatusCodes.SUCCESS

            } else {

                @Suppress("DEPRECATION")
                peer.characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                @Suppress("DEPRECATION")
                peer.characteristic.value =
                    bytes

                @Suppress("DEPRECATION")
                peer.gatt.writeCharacteristic(
                    peer.characteristic
                )
            }

        } catch (_: Exception) {
            false
        }
    }

    private val advertiseCallback =
        object :
            AdvertiseCallback() {

            override fun onStartSuccess(
                settingsInEffect:
                    AdvertiseSettings?
            ) {
                onStatus(
                    "Mesh online — searching for peers"
                )
            }

            override fun onStartFailure(
                errorCode: Int
            ) {
                onStatus(
                    "BLE advertising error $errorCode"
                )
            }
        }

    private val scanCallback =
        object :
            ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                connectToDevice(
                    result.device
                )
            }

            override fun onBatchScanResults(
                results:
                    MutableList<ScanResult>
            ) {
                for (
                    result in
                    results
                ) {
                    connectToDevice(
                        result.device
                    )
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                onStatus(
                    "BLE scanning error $errorCode"
                )
            }
        }

    private val serverCallback =
        object :
            BluetoothGattServerCallback() {

            override fun onServiceAdded(
                status: Int,
                service:
                    BluetoothGattService
            ) {

                if (
                    status ==
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    startAdvertising()
                } else {
                    onStatus(
                        "GATT service registration failed"
                    )
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic:
                    BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {

                if (
                    characteristic.uuid !=
                    RX_CHARACTERISTIC_UUID
                ) {
                    return
                }

                if (
                    preparedWrite ||
                    offset != 0
                ) {

                    if (responseNeeded) {
                        try {
                            gattServer
                                ?.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    0,
                                    null
                                )
                        } catch (_: Exception) {
                        }
                    }

                    return
                }

                val completePacket =
                    reassembler.accept(
                        source =
                            device.address,
                        frame =
                            value
                    )

                if (
                    completePacket != null
                ) {
                    onPacketReceived(
                        completePacket
                    )
                }

                if (
                    responseNeeded
                ) {
                    try {
                        gattServer
                            ?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                0,
                                null
                            )
                    } catch (_: Exception) {
                    }
                }
            }
        }

    private val gattCallback =
        object :
            BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {

                val address =
                    gatt.device.address

                when (
                    newState
                ) {

                    BluetoothProfile.STATE_CONNECTED -> {

                        connecting.remove(
                            address
                        )

                        try {
                            gatt.discoverServices()
                        } catch (
                            exception: Exception
                        ) {
                            removePeer(
                                address
                            )

                            try {
                                gatt.close()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {

                        connecting.remove(
                            address
                        )

                        removePeer(
                            address
                        )

                        try {
                            gatt.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    return
                }

                val service =
                    gatt.getService(
                        SERVICE_UUID
                    )
                        ?: return

                val characteristic =
                    service.getCharacteristic(
                        RX_CHARACTERISTIC_UUID
                    )
                        ?: return

                createPeer(
                    gatt,
                    characteristic
                )

                try {
                    gatt.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    )
                } catch (_: Exception) {
                }

                try {
                    gatt.requestMtu(
                        REQUESTED_MTU
                    )
                } catch (_: Exception) {
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt.GATT_SUCCESS
                ) {
                    return
                }

                peers[
                    gatt.device.address
                ]?.mtu =
                    mtu.coerceAtLeast(
                        DEFAULT_MTU
                    )
            }
        }

    private class PeerConnection(
        val address: String,
        val gatt: BluetoothGatt,
        val characteristic:
            BluetoothGattCharacteristic
    ) {

        @Volatile
        var mtu: Int =
            DEFAULT_MTU

        val sendQueue =
            Channel<ByteArray>(
                capacity =
                    SEND_QUEUE_SIZE
            )

        var senderJob:
            Job? = null
    }

    companion object {

        val SERVICE_UUID: UUID =
            UUID.fromString(
                "a8c22100-8d5d-4eb9-a4de-9c6d23d81410"
            )

        val RX_CHARACTERISTIC_UUID:
            UUID =
            UUID.fromString(
                "a8c22101-8d5d-4eb9-a4de-9c6d23d81410"
            )

        private const val MAX_PEERS =
            6

        private const val REQUESTED_MTU =
            247

        private const val DEFAULT_MTU =
            23

        private const val ATT_OVERHEAD_BYTES =
            5

        private const val MINIMUM_WRITE_SIZE =
            18

        private const val SEND_QUEUE_SIZE =
            512

        private const val WRITE_SPACING_MS =
            3L

        private const val WRITE_RETRY_DELAY_MS =
            8L

        private const val WRITE_RETRY_COUNT =
            3
    }
}