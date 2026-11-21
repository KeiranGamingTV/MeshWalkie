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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class BleMeshTransport(
    context: Context,
    private val onPacketReceived:
        (
            sourceAddress: String,
            packet: ByteArray
        ) -> Unit,
    private val onPeerCountChanged:
        (Int) -> Unit,
    private val onStatus:
        (String) -> Unit
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
        ConcurrentHashMap
            .newKeySet<String>()

    private val knownDevices =
        ConcurrentHashMap<
            String,
            BluetoothDevice
        >()

    private val scanRssi =
        ConcurrentHashMap<
            String,
            Int
        >()

    private val reconnectJobs =
        ConcurrentHashMap<
            String,
            Job
        >()

    private val reconnectBackoff =
        ConcurrentHashMap<
            String,
            Int
        >()

    private val nextConnectAllowed =
        ConcurrentHashMap<
            String,
            Long
        >()

    private val pendingGatts =
        ConcurrentHashMap<
            String,
            BluetoothGatt
        >()

    private val connectStartedAt =
        ConcurrentHashMap<
            String,
            Long
        >()

    private var gattServer:
        BluetoothGattServer? =
        null

    private var scanner:
        BluetoothLeScanner? =
        null

    private var advertiser:
        BluetoothLeAdvertiser? =
        null

    private var rssiJob:
        Job? =
        null

    @Volatile
    private var started =
        false

    val isBluetoothAvailable:
        Boolean
        get() {

            val localAdapter =
                adapter
                    ?: return false

            if (
                !localAdapter.isEnabled
            ) {
                return false
            }

            return (
                localAdapter.bluetoothLeScanner
                    != null &&
                localAdapter.bluetoothLeAdvertiser
                    != null
                )
        }

    fun start(): Boolean {

        if (
            started
        ) {
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
            localAdapter
                .bluetoothLeScanner

        advertiser =
            localAdapter
                .bluetoothLeAdvertiser

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
            bluetoothManager
                .openGattServer(
                    appContext,
                    serverCallback
                )

        if (
            server == null
        ) {
            onStatus(
                "Unable to open Bluetooth GATT server"
            )

            return false
        }

        gattServer =
            server

        val service =
            BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService
                    .SERVICE_TYPE_PRIMARY
            )

        val receiveCharacteristic =
            BluetoothGattCharacteristic(
                RX_CHARACTERISTIC_UUID,

                BluetoothGattCharacteristic
                    .PROPERTY_WRITE or
                    BluetoothGattCharacteristic
                        .PROPERTY_WRITE_NO_RESPONSE,

                BluetoothGattCharacteristic
                    .PERMISSION_WRITE
            )

        service.addCharacteristic(
            receiveCharacteristic
        )

        if (
            !server.addService(
                service
            )
        ) {

            server.close()

            gattServer =
                null

            onStatus(
                "Unable to register MeshWalkie GATT service"
            )

            return false
        }

        started =
            true

        startScanning()
        startRssiMonitor()

        onStatus(
            "Starting Bluetooth mesh"
        )

        return true
    }

    fun stop() {

        if (
            !started
        ) {
            return
        }

        started =
            false

        rssiJob?.cancel()
        rssiJob = null

        reconnectJobs
            .values
            .forEach {
                it.cancel()
            }

        reconnectJobs.clear()
        reconnectBackoff.clear()
        nextConnectAllowed.clear()

        try {
            scanner?.stopScan(
                scanCallback
            )
        } catch (_: Exception) {
        }

        try {
            advertiser
                ?.stopAdvertising(
                    advertiseCallback
                )
        } catch (_: Exception) {
        }

        peers
            .values
            .toList()
            .forEach {
                peer ->

                MeshDiagnostics
                    .markPeerDisconnected(
                        peer.address
                    )

                closePeer(
                    peer,
                    disconnect =
                        true
                )
            }

        peers.clear()
        connecting.clear()
        pendingGatts.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }

        pendingGatts.clear()
        connectStartedAt.clear()
        knownDevices.clear()
        scanRssi.clear()

        try {
            gattServer
                ?.clearServices()

            gattServer
                ?.close()
        } catch (_: Exception) {
        }

        gattServer =
            null

        onPeerCountChanged(
            0
        )

        onStatus(
            "Bluetooth mesh stopped"
        )

        scope.cancel()
    }

     fun broadcast(
        packetId: Long,
        encodedPacket: ByteArray,
        excludeAddress: String? = null,
        highPriority: Boolean = false,
        excludeNodeId: Long? = null
    ) {

        if (
            !started
        ) {
            return
        }

        for (
            peer in
            peers.values
        ) {

            if (
                peer.address ==
                excludeAddress
            ) {
                continue
            }

            if (
                excludeNodeId != null &&
                peer.nodeId == excludeNodeId
            ) {
                continue
            }

            val maximumWrite =
                (
                    peer.mtu -
                        ATT_OVERHEAD_BYTES
                    )
                    .coerceAtLeast(
                        MINIMUM_WRITE_SIZE
                    )

            val fragments =
                FragmentCodec.fragment(
                    fragmentId =
                        packetId,
                    packet =
                        encodedPacket,
                    maxWriteSize =
                        maximumWrite
                )

            val dropped =
                peer.sendQueue.offer(
                    OutgoingPacket(
                        fragments =
                            fragments,
                        highPriority =
                            highPriority
                    )
                )

            MeshDiagnostics
                .recordCongestionDrops(
                    dropped
                )
        }
    }

    fun updatePeerIdentity(
        address: String,
        nodeId: Long,
        callsign: String
    ) {

        peers[address]
            ?.let {
                peer ->

                peer.nodeId =
                    nodeId

                peer.callsign =
                    callsign
            }

        MeshDiagnostics
            .setPeerIdentity(
                address =
                    address,
                nodeId =
                    nodeId,
                callsign =
                    callsign
            )
    }

    private fun startScanning() {

        val localScanner =
            scanner
                ?: return

        val filter =
            ScanFilter
                .Builder()
                .setServiceUuid(
                    ParcelUuid(
                        SERVICE_UUID
                    )
                )
                .build()

        val settings =
            ScanSettings
                .Builder()
                .setScanMode(
                    ScanSettings
                        .SCAN_MODE_LOW_LATENCY
                )
                .build()

        try {

            localScanner.startScan(
                listOf(
                    filter
                ),
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
            AdvertiseSettings
                .Builder()
                .setAdvertiseMode(
                    AdvertiseSettings
                        .ADVERTISE_MODE_BALANCED
                )
                .setConnectable(
                    true
                )
                .setTimeout(
                    0
                )
                .setTxPowerLevel(
                    AdvertiseSettings
                        .ADVERTISE_TX_POWER_HIGH
                )
                .build()

        val data =
            AdvertiseData
                .Builder()
                .setIncludeDeviceName(
                    false
                )
                .setIncludeTxPowerLevel(
                    false
                )
                .addServiceUuid(
                    ParcelUuid(
                        SERVICE_UUID
                    )
                )
                .build()

        try {

            localAdvertiser
                .startAdvertising(
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

    private fun startRssiMonitor() {

        rssiJob =
            scope.launch {

                while (
                    isActive &&
                    started
                ) {

                    for (
                        peer in
                        peers.values
                    ) {

                        try {
                            peer.gatt
                                .readRemoteRssi()
                        } catch (_: Exception) {
                        }

                        delay(
                            RSSI_REQUEST_SPACING_MS
                        )
                    }

                    checkLinkHealth()

                    delay(
                        RSSI_POLL_INTERVAL_MS
                    )
                }
            }
    }

    /**
     * Self-healing watchdog. Runs every few seconds.
     *  - A connected peer with no successful write or RSSI reply for
     *    LINK_DEAD_MS is torn down and reconnected.
     *  - A connection attempt that never reaches "ready" within
     *    CONNECT_TIMEOUT_MS is abandoned and retried.
     */
    private fun checkLinkHealth() {

        val now =
            System.currentTimeMillis()

        for (
            peer in
            peers.values.toList()
        ) {

            if (
                now - peer.lastLinkOkMs >
                LINK_DEAD_MS
            ) {
                resetLink(peer)
            }
        }

        for (
            (address, startedAt) in
            connectStartedAt.entries.toList()
        ) {

            if (
                peers.containsKey(address)
            ) {
                pendingGatts.remove(address)
                connectStartedAt.remove(address)
                continue
            }

            if (
                now - startedAt >
                CONNECT_TIMEOUT_MS
            ) {
                abandonPending(address)
            }
        }
    }

    private fun resetLink(
        peer: PeerConnection
    ) {

        MeshDiagnostics
            .recordLinkReset()

        removeConnectedPeer(
            gatt =
                peer.gatt,
            reconnect =
                true
        )
    }

    private fun abandonPending(
        address: String
    ) {

        val gatt =
            pendingGatts.remove(
                address
            )

        connectStartedAt.remove(
            address
        )

        connecting.remove(
            address
        )

        if (
            gatt != null
        ) {

            try {
                gatt.disconnect()
            } catch (_: Exception) {
            }

            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }

        knownDevices[
            address
        ]?.let {
            device ->

            scheduleReconnect(
                device
            )
        }
    }

    private fun restartScanning() {

        try {
            scanner?.stopScan(
                scanCallback
            )
        } catch (_: Exception) {
        }

        startScanning()
    }

    private fun connectToDevice(
        device: BluetoothDevice,
        recoveryAttempt: Boolean = false
    ) {

        if (
            !started
        ) {
            return
        }

        val address =
            device.address

        knownDevices[address] =
            device

        if (
            peers.containsKey(
                address
            ) ||
            connecting.contains(
                address
            )
        ) {
            return
        }

        if (
            peers.size >=
            MAX_PEERS
        ) {
            return
        }

        val allowedAt =
            nextConnectAllowed[
                address
            ] ?: 0L

        if (
            System.currentTimeMillis() <
            allowedAt
        ) {
            return
        }

        connecting.add(
            address
        )

        if (
            recoveryAttempt
        ) {
            MeshDiagnostics
                .markReconnectAttempt(
                    address
                )
        }

        try {

            val gatt =
                device.connectGatt(
                    appContext,
                    false,
                    gattCallback,
                    BluetoothDevice
                        .TRANSPORT_LE
                )

            if (
                gatt == null
            ) {

                connecting.remove(
                    address
                )

                scheduleReconnect(
                    device
                )

            } else {

                pendingGatts[
                    address
                ] =
                    gatt

                connectStartedAt[
                    address
                ] =
                    System.currentTimeMillis()
            }

        } catch (_: Exception) {

            connecting.remove(
                address
            )

            scheduleReconnect(
                device
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

        connecting.remove(
            address
        )

        pendingGatts.remove(
            address
        )

        connectStartedAt.remove(
            address
        )

        val current =
            peers[address]

        if (
            current != null
        ) {

            /*
             * Service discovery can repeat on the same
             * connection. Only close a genuinely
             * different duplicate connection.
             */
            if (
                current.gatt !== gatt
            ) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
            }

            return
        }

        val peer =
            PeerConnection(
                address =
                    address,
                gatt =
                    gatt,
                characteristic =
                    characteristic,
                rssi =
                    scanRssi[
                        address
                    ]
            )

        val existing =
            peers.putIfAbsent(
                address,
                peer
            )

        if (
            existing != null
        ) {
            return
        }

        reconnectBackoff[
            address
        ] = 0

        nextConnectAllowed.remove(
            address
        )

        reconnectJobs.remove(
            address
        )?.cancel()

        MeshDiagnostics
            .markPeerConnected(
                address =
                    address,
                mtu =
                    peer.mtu,
                rssi =
                    peer.rssi
            )

        peer.senderJob =
            scope.launch {
                runSender(
                    peer
                )
            }

        onPeerCountChanged(
            peers.size
        )

        onStatus(
            "Connected to ${peers.size} nearby peer(s)"
        )
    }

    private suspend fun runSender(
        peer: PeerConnection
    ) {

        while (
            started
        ) {

            val packet =
                peer.sendQueue.take()
                    ?: return

            /*
             * Stale voice is useless. Discard it
             * instead of delaying everything behind it.
             */
            if (
                !packet.highPriority &&
                System.currentTimeMillis() -
                    packet.enqueuedAtMs >
                MAX_VOICE_AGE_MS
            ) {

                MeshDiagnostics
                    .recordCongestionDrops(
                        1
                    )

                continue
            }

            var complete =
                true

            for (
                fragment in
                packet.fragments
            ) {

                if (
                    !sendFragment(
                        peer,
                        fragment
                    )
                ) {
                    complete =
                        false

                    break
                }
            }

            if (
                complete
            ) {

                peer.consecutiveWriteFailures =
                    0

            } else {

                peer.consecutiveWriteFailures++

                if (
                    peer.consecutiveWriteFailures >=
                    MAX_CONSECUTIVE_WRITE_FAILURES
                ) {

                    resetLink(
                        peer
                    )

                    return
                }

                delay(
                    WRITE_FAILURE_PAUSE_MS
                )
            }
        }
    }

    /**
     * Writes one fragment and waits for Android's
     * onCharacteristicWrite callback before the next,
     * so we never overrun the stack's write buffer.
     */
    private suspend fun sendFragment(
        peer: PeerConnection,
        bytes: ByteArray
    ): Boolean {

        for (
            attempt in
            0 until WRITE_RETRY_COUNT
        ) {

            peer.writeAck
                .tryReceive()

            if (
                !writeCharacteristic(
                    peer =
                        peer,
                    bytes =
                        bytes
                )
            ) {

                delay(
                    WRITE_RETRY_DELAY_MS
                )

                continue
            }

            if (
                !USE_WRITE_ACK
            ) {

                delay(
                    WRITE_SPACING_MS
                )

                return true
            }

            val status =
                withTimeoutOrNull(
                    WRITE_ACK_TIMEOUT_MS
                ) {
                    peer.writeAck
                        .receiveCatching()
                        .getOrNull()
                }

            if (
                status ==
                BluetoothGatt
                    .GATT_SUCCESS
            ) {

                peer.lastLinkOkMs =
                    System.currentTimeMillis()

                return true
            }

            /*
             * Timed out or failed. The write may still
             * be in flight, so do not resend it.
             */
            return false
        }

        return false
    }

    private fun removeConnectedPeer(
        gatt: BluetoothGatt,
        reconnect: Boolean
    ) {

        val address =
            gatt.device.address

        connecting.remove(
            address
        )

        pendingGatts.remove(
            address
        )

        connectStartedAt.remove(
            address
        )

        val peer =
            peers.remove(
                address
            )

        if (
            peer != null
        ) {
            closePeer(
                peer =
                    peer,
                disconnect =
                    false
            )
        } else {
            try {
                gatt.close()
            } catch (_: Exception) {
            }
        }

        MeshDiagnostics
            .markPeerDisconnected(
                address
            )

        onPeerCountChanged(
            peers.size
        )

        if (
            reconnect &&
            started
        ) {

            knownDevices[
                address
            ]?.let {
                device ->

                scheduleReconnect(
                    device
                )
            }
        }

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
        peer: PeerConnection,
        disconnect: Boolean
    ) {

        peer.sendQueue.close()
        peer.writeAck.close()

        peer.senderJob
            ?.cancel()

        if (
            disconnect
        ) {
            try {
                peer.gatt
                    .disconnect()
            } catch (_: Exception) {
            }
        }

        try {
            peer.gatt.close()
        } catch (_: Exception) {
        }
    }

    private fun scheduleReconnect(
        device: BluetoothDevice
    ) {

        if (
            !started
        ) {
            return
        }

        val address =
            device.address

        if (
            reconnectJobs[
                address
            ]?.isActive ==
            true
        ) {
            return
        }

        val attempt =
            (
                reconnectBackoff[
                    address
                ] ?: 0
                ) + 1

        reconnectBackoff[
            address
        ] =
            attempt

        val exponent =
            (attempt - 1)
                .coerceAtMost(
                    4
                )

        val delayMs =
            (
                1_000L *
                    (1 shl exponent)
                )
                .coerceAtMost(
                    MAX_RECONNECT_DELAY_MS
                )

        nextConnectAllowed[
            address
        ] =
            System.currentTimeMillis() +
                delayMs

        val job =
            scope.launch {

                delay(
                    delayMs
                )

                nextConnectAllowed
                    .remove(
                        address
                    )

                if (
                    started &&
                    !peers
                        .containsKey(
                            address
                        )
                ) {

                    connectToDevice(
                        device =
                            device,
                        recoveryAttempt =
                            true
                    )
                }

                reconnectJobs.remove(
                    address
                )
            }

        reconnectJobs[
            address
        ] =
            job
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

                peer.gatt
                    .writeCharacteristic(
                        peer.characteristic,
                        bytes,
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    ) ==
                    BluetoothStatusCodes
                        .SUCCESS

            } else {

                @Suppress(
                    "DEPRECATION"
                )
                peer.characteristic
                    .writeType =
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE

                @Suppress(
                    "DEPRECATION"
                )
                peer.characteristic
                    .value =
                    bytes

                @Suppress(
                    "DEPRECATION"
                )
                peer.gatt
                    .writeCharacteristic(
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

                val address =
                    result.device.address

                knownDevices[
                    address
                ] =
                    result.device

                scanRssi[
                    address
                ] =
                    result.rssi

                peers[address]
                    ?.let {
                        peer ->

                        peer.rssi =
                            result.rssi

                        MeshDiagnostics
                            .updatePeerLink(
                                address =
                                    address,
                                rssi =
                                    result.rssi
                            )
                    }

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
                    onScanResult(
                        ScanSettings
                            .CALLBACK_TYPE_ALL_MATCHES,
                        result
                    )
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {

                onStatus(
                    "BLE scanning error $errorCode — retrying"
                )

                scope.launch {

                    delay(
                        SCAN_RESTART_DELAY_MS
                    )

                    if (
                        started
                    ) {
                        restartScanning()
                    }
                }
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
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {

                    startAdvertising()

                } else {

                    onStatus(
                        "GATT service registration failed"
                    )
                }
            }

            override fun onMtuChanged(
                device: BluetoothDevice,
                mtu: Int
            ) {

                peers[
                    device.address
                ]?.let {
                    peer ->

                    peer.mtu =
                        mtu

                    MeshDiagnostics
                        .updatePeerLink(
                            address =
                                device.address,
                            mtu =
                                mtu
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

                    if (
                        responseNeeded
                    ) {

                        try {
                            gattServer
                                ?.sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt
                                        .GATT_FAILURE,
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
                    completePacket !=
                    null
                ) {

                    onPacketReceived(
                        device.address,
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
                                BluetoothGatt
                                    .GATT_SUCCESS,
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

                    BluetoothProfile
                        .STATE_CONNECTED -> {

                        if (
                            status !=
                            BluetoothGatt
                                .GATT_SUCCESS
                        ) {

                            try {
                                gatt.close()
                            } catch (_: Exception) {
                            }

                            abandonPending(
                                address
                            )

                            return
                        }

                        try {

                            if (
                                !gatt
                                    .discoverServices()
                            ) {

                                try {
                                    gatt.close()
                                } catch (_: Exception) {
                                }

                                abandonPending(
                                    address
                                )
                            }

                        } catch (_: Exception) {

                            try {
                                gatt.close()
                            } catch (_: Exception) {
                            }

                            abandonPending(
                                address
                            )
                        }
                    }

                    BluetoothProfile
                        .STATE_DISCONNECTED -> {

                        removeConnectedPeer(
                            gatt =
                                gatt,
                            reconnect =
                                true
                        )
                    }
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic:
                    BluetoothGattCharacteristic,
                status: Int
            ) {

                peers[
                    gatt.device.address
                ]?.writeAck
                    ?.trySend(
                        status
                    )
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {

                    removeConnectedPeer(
                        gatt,
                        reconnect =
                            true
                    )

                    return
                }

                val service =
                    gatt.getService(
                        SERVICE_UUID
                    )

                val characteristic =
                    service
                        ?.getCharacteristic(
                            RX_CHARACTERISTIC_UUID
                        )

                if (
                    characteristic ==
                    null
                ) {

                    removeConnectedPeer(
                        gatt,
                        reconnect =
                            true
                    )

                    return
                }

                createPeer(
                    gatt =
                        gatt,
                    characteristic =
                        characteristic
                )

                try {
                    gatt.requestConnectionPriority(
                        BluetoothGatt
                            .CONNECTION_PRIORITY_HIGH
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
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    return
                }

                val peer =
                    peers[
                        gatt.device.address
                    ]
                        ?: return

                peer.mtu =
                    mtu.coerceAtLeast(
                        DEFAULT_MTU
                    )

                MeshDiagnostics
                    .updatePeerLink(
                        address =
                            peer.address,
                        mtu =
                            peer.mtu
                    )
            }

            override fun onReadRemoteRssi(
                gatt: BluetoothGatt,
                rssi: Int,
                status: Int
            ) {

                if (
                    status !=
                    BluetoothGatt
                        .GATT_SUCCESS
                ) {
                    return
                }

                val peer =
                    peers[
                        gatt.device.address
                    ]
                        ?: return

                peer.rssi =
                    rssi

                peer.lastLinkOkMs =
                    System.currentTimeMillis()

                MeshDiagnostics
                    .updatePeerLink(
                        address =
                            peer.address,
                        rssi =
                            rssi
                    )
            }

            override fun onServiceChanged(
                gatt: BluetoothGatt
            ) {

                try {
                    gatt.discoverServices()
                } catch (_: Exception) {
                }
            }
        }

    private class PeerConnection(
        val address: String,
        val gatt: BluetoothGatt,
        val characteristic:
            BluetoothGattCharacteristic,
        rssi: Int?
    ) {

        @Volatile
        var mtu: Int =
            DEFAULT_MTU

        @Volatile
        var rssi: Int? =
            rssi

        @Volatile
        var nodeId: Long? =
            null

        @Volatile
        var callsign: String? =
            null

        @Volatile
        var lastLinkOkMs: Long =
            System.currentTimeMillis()

        @Volatile
        var consecutiveWriteFailures: Int =
            0

        val sendQueue =
            PeerSendQueue()

        val writeAck =
            Channel<Int>(
                Channel.CONFLATED
            )

        var senderJob:
            Job? =
            null
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

        /*
         * ATT write-command overhead is
         * three bytes.
         */
        private const val ATT_OVERHEAD_BYTES =
            3

        private const val MINIMUM_WRITE_SIZE =
            20

        /*
         * Pace writes by Android's onCharacteristicWrite
         * callback. If voice stutters or link resets climb
         * with phones close together, set this to false to
         * return to the old fixed-delay pacing.
         */
        private const val USE_WRITE_ACK =
            true

        private const val WRITE_ACK_TIMEOUT_MS =
            200L

        /* Voice older than this when it reaches the front of the queue is dropped. */
        private const val MAX_VOICE_AGE_MS =
            600L

        private const val MAX_CONSECUTIVE_WRITE_FAILURES =
            25

        /* No successful write or RSSI reply for this long means the link is dead. */
        private const val LINK_DEAD_MS =
            20_000L

        private const val CONNECT_TIMEOUT_MS =
            25_000L

        private const val SCAN_RESTART_DELAY_MS =
            6_000L

        private const val WRITE_SPACING_MS =
            3L

        private const val WRITE_RETRY_DELAY_MS =
            8L

        private const val WRITE_FAILURE_PAUSE_MS =
            15L

        private const val WRITE_RETRY_COUNT =
            3

        private const val RSSI_POLL_INTERVAL_MS =
            5_000L

        private const val RSSI_REQUEST_SPACING_MS =
            150L

        private const val MAX_RECONNECT_DELAY_MS =
            15_000L
    }
}