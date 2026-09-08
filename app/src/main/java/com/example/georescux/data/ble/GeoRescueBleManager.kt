package com.example.georescux.data.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.ParcelUuid
import com.example.georescux.domain.ble.GeoRescueBleConnectionStateMachine
import com.example.georescux.domain.ble.GeoRescueBleDiagnostics
import com.example.georescux.domain.ble.GeoRescueBleFramer
import com.example.georescux.domain.ble.GeoRescueBleMeshNode
import com.example.georescux.domain.ble.GeoRescueBlePacket
import com.example.georescux.domain.ble.GeoRescueBlePacketCodec
import com.example.georescux.domain.ble.GeoRescueBlePacketSink
import com.example.georescux.domain.ble.GeoRescueBlePacketType
import com.example.georescux.domain.ble.GeoRescueBleRelayDecision
import com.example.georescux.domain.ble.GeoRescueBleState
import java.util.Collections

/**
 * The BLE subsystem orchestrator — the single object the rest of the app
 * talks to. It composes the Android halves (scanner, advertiser, GATT
 * server, GATT connections, repository) around the pure mesh node
 * ([GeoRescueBleMeshNode]), and owns:
 *
 * - the packet pipeline (publish -> dedup -> validate -> persist -> relay
 *   with TTL decrement -> store-and-forward flush),
 * - the per-peer connection states in BOTH GATT roles,
 * - the diagnostics state consumed by the BLE diagnostics screen.
 *
 * Each device runs BOTH roles: GATT server (advertising; receives on RX,
 * notifies on TX) and GATT client (scanning + connecting; writes to the
 * peer's RX, receives the peer's TX notifications). A -> B therefore works
 * symmetrically in both directions. When both devices discover each other
 * simultaneously, the role-settling rule below keeps a single link:
 * a device never dials a peer that is already connected to its server.
 *
 * Threading: GATT and scan callbacks arrive on binder threads; all mutable
 * manager state is guarded by [lock]. Mesh forwarding happens inline with
 * the callback that delivered the frame, so relay latency stays minimal.
 */
class GeoRescueBleManager(
    appContext: Context,
    private val selfDeviceId: String,
    private val repository: GeoRescueBleRepository,
) {

    interface PacketListener {
        /** Called with every newly accepted packet (never duplicates). */
        fun onPacketAccepted(packet: GeoRescueBlePacket, fromPeerId: String?)
    }

    /** A device seen by the scanner (diagnostics screen list). */
    data class DiscoveredDevice(val address: String, val rssi: Int, val lastSeenMs: Long)

    /** Per-peer diagnostics view for the diagnostics screen. */
    data class PeerDiagnostics(
        val address: String,
        val role: String,
        val state: GeoRescueBleState,
        val serviceDiscovered: Boolean,
        val rxFound: Boolean,
        val txFound: Boolean,
        val notificationsEnabled: Boolean,
    )

    /** Full diagnostics snapshot for the diagnostics screen. */
    data class DiagnosticsState(
        val initialized: Boolean,
        val readiness: GeoRescueBleReadiness?,
        val scanning: Boolean,
        val advertising: Boolean,
        val nearbyCount: Int,
        val nearbyDevices: List<DiscoveredDevice>,
        val peers: List<PeerDiagnostics>,
        val lastPacketSent: GeoRescueBlePacket?,
        val lastPacketReceived: GeoRescueBlePacket?,
        val lastError: String?,
        val serverServiceRegistered: Boolean,
        val pendingOutbound: Int,
        val seenPackets: Int,
    )

    private class ClientPeer(val address: String) {
        val stateMachine = GeoRescueBleConnectionStateMachine(GeoRescueBleState.CONNECTING)
        var connection: GeoRescueBleConnection? = null
    }

    private class ServerPeer(val address: String) {
        val stateMachine = GeoRescueBleConnectionStateMachine(GeoRescueBleState.CONNECTED)
        var subscribed = false
    }

    private val appContext: Context = appContext.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val lock = Any()

    private val packetSink = object : GeoRescueBlePacketSink {
        override fun availablePeerIds(): List<String> = this@GeoRescueBleManager.availablePeerIds()

        override fun send(peerId: String, packetJson: String): Boolean =
            this@GeoRescueBleManager.sendToPeer(peerId, packetJson)
    }

    private val meshNode: GeoRescueBleMeshNode = GeoRescueBleMeshNode(
        selfDeviceId = selfDeviceId,
        sink = packetSink,
        seenStore = repository,
        outboundQueue = repository,
        onPacketAccepted = { packet -> handleAcceptedPacket(packet, fromPeerId = null) },
    ).apply { restoreState() }

    private val capabilities = GeoRescueBleCapabilities(appContext)

    private var advertiser: GeoRescueBleAdvertiser? = null
    private var scanner: GeoRescueBleScanner? = null
    private var gattService: GeoRescueBleService? = null
    private val serverFramers = HashMap<String, GeoRescueBleFramer>()
    private val clientPeers = HashMap<String, ClientPeer>()
    private val serverPeers = HashMap<String, ServerPeer>()
    private val discovered = LinkedHashMap<String, DiscoveredDevice>()
    private val packetListeners = Collections.synchronizedList(mutableListOf<PacketListener>())

    /** Whether discovery should trigger automatic connection attempts (relay + diagnostics default: on). */
    @Volatile
    var autoConnectEnabled: Boolean = true

    @Volatile
    var readiness: GeoRescueBleReadiness? = null
        private set

    var initialized: Boolean = false
        private set

    // --- Packet listener surface (AppContainer installs SOS/test handlers) ---

    fun addPacketListener(listener: PacketListener) {
        packetListeners.add(listener)
    }

    fun removePacketListener(listener: PacketListener) {
        packetListeners.remove(listener)
    }

    // --- Lifecycle (Phase 2 capability detection) ---

    /**
     * Initializes the subsystem: evaluates capabilities, constructs the
     * radio components and opens the GATT server. Safe to call repeatedly;
     * a permission denial or disabled adapter leaves the manager in a
     * well-defined non-ready state — it never throws, never crashes.
     */
    fun initialize(): Boolean {
        GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.BLE_INIT_START, "deviceId=$selfDeviceId")
        if (initialized) return true
        val ready = capabilities.evaluate()
        readiness = ready
        if (!ready.ready) {
            GeoRescueBleDiagnostics.error(
                GeoRescueBleDiagnostics.BLE_INIT_FAILED,
                "reason=${ready.failureReason}"
            )
            setLastError("BLE init failed: ${ready.failureReason}")
            return false
        }
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            GeoRescueBleDiagnostics.error(GeoRescueBleDiagnostics.BLUETOOTH_DISABLED)
            return false
        }
        val newAdvertiser = GeoRescueBleAdvertiser(btAdapter)
        val newScanner = GeoRescueBleScanner(btAdapter)
        if (newScanner.isSupported()) {
            GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.SCANNER_CREATED)
        }
        val service = GeoRescueBleService(appContext, bluetoothManager!!)
        val opened = service.open(object : GeoRescueBleService.GattServerListener {
            override fun onServerDeviceConnected(device: BluetoothDevice) {
                onServerPeerConnected(device)
            }

            override fun onServerDeviceDisconnected(device: BluetoothDevice) {
                onServerPeerDisconnected(device)
            }

            override fun onClientSubscribed(device: BluetoothDevice) {
                onServerClientSubscribed(device, subscribed = true)
            }

            override fun onClientUnsubscribed(device: BluetoothDevice) {
                onServerClientSubscribed(device, subscribed = false)
            }

            override fun onBytesReceived(device: BluetoothDevice, bytes: ByteArray) {
                ingestServerBytes(device, bytes)
            }

            override fun onServerOpenFailed(status: Int) {
                setLastError("GATT server open failed: status=$status")
            }
        })
        if (opened == null) {
            return false
        }
        synchronized(lock) {
            advertiser = newAdvertiser
            scanner = newScanner
            gattService = service
            serverServiceRegistered = true
            initialized = true
        }
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.BLE_INIT_SUCCESS,
            "advertiser=${newAdvertiser.isSupported()} scanner=${newScanner.isSupported()} gattServer=open"
        )
        return true
    }

    // --- Phase 3: advertising ---

    fun startAdvertising(): Boolean {
        val adv = synchronized(lock) { advertiser } ?: return false
        return adv.startAdvertising(object : GeoRescueBleAdvertiser.AdvertisingListener {
            override fun onAdvertisingStarted() {
                GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.ADVERTISING_STARTED, "node=$selfDeviceId")
            }

            override fun onAdvertisingFailed(errorCode: Int) {
                setLastError("advertising failed: errorCode=$errorCode")
            }
        })
    }

    fun stopAdvertising() {
        synchronized(lock) { advertiser }?.stopAdvertising()
    }

    // --- Phase 4: scanning ---

    fun startScanning(): Boolean {
        val scan = synchronized(lock) { scanner } ?: return false
        return scan.startScan(object : GeoRescueBleScanner.ScanListener {
            override fun onGeoRescueDeviceFound(device: BluetoothDevice, rssi: Int, advertisedServices: List<ParcelUuid>) {
                onGeoRescueDeviceDiscovered(device, rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                setLastError("scan failed: errorCode=$errorCode")
            }
        })
    }

    fun stopScanning() {
        synchronized(lock) { scanner }?.stopScan()
    }

    private fun onGeoRescueDeviceDiscovered(device: BluetoothDevice, rssi: Int) {
        synchronized(lock) {
            discovered[device.address] = DiscoveredDevice(device.address, rssi, System.currentTimeMillis())
            if (discovered.size > MAX_DISCOVERED_TRACKED) {
                val oldest = discovered.entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
        // Auto-connect policy (opportunistic relay): dial a discovered
        // GeoRescuX node unless a link already exists in either role.
        if (autoConnectEnabled) {
            connectTo(device.address)
        }
    }

    // --- Phase 5: connection management (client role) ---

    /**
     * Connects (as GATT client) to the GeoRescuX node with [address].
     * No-op when a live link already exists in either role; refuses when
     * the subsystem is not initialized. Never throws.
     */
    fun connectTo(address: String): Boolean {
        synchronized(lock) {
            if (!initialized) return false
            if (address == ownBluetoothAddressOrNull()) return false
            val clientEntry = clientPeers[address]
            if (clientEntry != null && isLive(clientEntry.stateMachine.state)) return true
            // Role-settling rule: a peer already connected to our GATT
            // server provides the link — do not create a second one.
            val serverEntry = serverPeers[address]
            if (serverEntry != null && isLive(serverEntry.stateMachine.state)) return true
            val btAdapter = adapter ?: return false
            val device = try {
                btAdapter.getRemoteDevice(address)
            } catch (_: IllegalArgumentException) {
                return false
            }
            val entry = ClientPeer(address)
            val connection = GeoRescueBleConnection(appContext, device, connectionListener)
            entry.connection = connection
            clientPeers[address] = entry
            val started = try {
                connection.connect()
            } catch (e: Exception) {
                GeoRescueBleDiagnostics.error(
                    GeoRescueBleDiagnostics.CONNECTION_FAILED,
                    "address=$address code=CONNECT_EXCEPTION detail=${e.message}"
                )
                false
            }
            if (!started) {
                // Synchronous failure: drop the peer entry so a later retry
                // is not blocked by a stale CONNECTING record.
                clientPeers.remove(address)
            }
            return started
        }
    }

    private fun isLive(state: GeoRescueBleState): Boolean =
        state == GeoRescueBleState.CONNECTING || state == GeoRescueBleState.CONNECTED ||
            state == GeoRescueBleState.DISCOVERING_SERVICES || state == GeoRescueBleState.READY ||
            state == GeoRescueBleState.TRANSFERRING

    private val connectionListener = object : GeoRescueBleConnection.ConnectionListener {

        override fun onConnectionReady(connection: GeoRescueBleConnection) {
            synchronized(lock) {
                clientPeers[connection.deviceAddress]?.stateMachine?.transitionTo(GeoRescueBleState.READY)
            }
            // Store-and-forward: flush everything pending for this peer.
            val delivered = meshNode.onPeerReady(connection.deviceAddress)
            if (delivered > 0) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.PACKET_RELAYED,
                    "role=CLIENT_FLUSH address=${connection.deviceAddress} delivered=$delivered"
                )
            }
        }

        override fun onDisconnected(connection: GeoRescueBleConnection) {
            synchronized(lock) {
                val entry = clientPeers[connection.deviceAddress]
                if (entry?.connection === connection) {
                    entry.stateMachine.transitionTo(GeoRescueBleState.DISCONNECTED)
                    entry.connection = null
                    clientPeers.remove(connection.deviceAddress)
                }
            }
        }

        override fun onConnectionFailed(connection: GeoRescueBleConnection, status: Int) {
            setLastError("connection failed: ${connection.deviceAddress} status=$status")
            synchronized(lock) {
                val entry = clientPeers[connection.deviceAddress]
                if (entry?.connection === connection) {
                    entry.stateMachine.transitionTo(GeoRescueBleState.ERROR)
                    entry.connection = null
                    clientPeers.remove(connection.deviceAddress)
                }
            }
        }

        override fun onBytesReceived(connection: GeoRescueBleConnection, bytes: ByteArray) {
            val json = String(bytes, Charsets.UTF_8)
            ingestFrame(json, fromPeerId = connection.deviceAddress)
        }
    }

    // --- GATT server events (server role) ---

    private fun onServerPeerConnected(device: BluetoothDevice) {
        synchronized(lock) {
            serverPeers.getOrPut(device.address) { ServerPeer(device.address) }
                .stateMachine.transitionTo(GeoRescueBleState.CONNECTED)
        }
    }

    private fun onServerPeerDisconnected(device: BluetoothDevice) {
        synchronized(lock) {
            serverPeers.remove(device.address)?.stateMachine?.transitionTo(GeoRescueBleState.DISCONNECTED)
            serverFramers.remove(device.address)
        }
    }

    private fun onServerClientSubscribed(device: BluetoothDevice, subscribed: Boolean) {
        val entry = synchronized(lock) {
            serverPeers.getOrPut(device.address) { ServerPeer(device.address) }.apply {
                this.subscribed = subscribed
                if (subscribed) stateMachine.transitionTo(GeoRescueBleState.READY)
            }
        }
        if (subscribed) {
            val delivered = meshNode.onPeerReady(device.address)
            if (delivered > 0) {
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.PACKET_RELAYED,
                    "role=SERVER_FLUSH address=${device.address} delivered=$delivered"
                )
            }
        }
    }

    private fun ingestServerBytes(device: BluetoothDevice, bytes: ByteArray) {
        val framer = synchronized(lock) {
            serverFramers.getOrPut(device.address) { GeoRescueBleFramer() }
        }
        val frame = framer.feed(bytes)
        if (frame != null) {
            ingestFrame(frame, fromPeerId = device.address)
        }
    }

    // --- Packet pipeline (Phases 6-8) ---

    /**
     * Publishes a locally-originated packet (SOS, test). The mesh node
     * validates, deduplicates, marks seen, and forwards to all READY peers
     * (queueing what no peer could take). Returns the relay decision.
     *
     * Works even before the radio is initialized: packets are then simply
     * queued durably (store-and-forward) and flushed on the first READY
     * peer — "no BLE device exists" never loses an emergency packet.
     */
    fun publishPacket(packet: GeoRescueBlePacket): GeoRescueBleRelayDecision {
        if (!initialized) {
            GeoRescueBleDiagnostics.warn(
                GeoRescueBleDiagnostics.PACKET_QUEUED,
                "radio-not-initialized packetId=${packet.packetId} (queued until a peer is reachable)"
            )
        }
        synchronized(lock) { lastSent = packet }
        val decision = meshNode.publish(packet)
        logDecision(decision, packet, fromPeerId = null)
        return decision
    }

    /** Sends the GEORESCUEX_BLE_TEST diagnostic packet (first acceptance criterion). */
    fun sendTestPacket(message: String): GeoRescueBleRelayDecision {
        val packet = GeoRescueBlePacket(
            packetId = GeoRescueBlePacket.newPacketId(),
            originDeviceId = selfDeviceId,
            originEmergencyId = null,
            timestampMs = System.currentTimeMillis(),
            ttl = GeoRescueBlePacket.DEFAULT_TTL_HOPS,
            type = GeoRescueBlePacketType.TEST,
            payload = mapOf("message" to message.take(GeoRescueBlePacket.MAX_PAYLOAD_VALUE_CHARS)),
        )
        return publishPacket(packet)
    }

    private fun handleAcceptedPacket(packet: GeoRescueBlePacket, fromPeerId: String?) {
        if (fromPeerId != null) {
            synchronized(lock) { lastReceived = packet }
        }
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.PACKET_VALID,
            "packetId=${packet.packetId} type=${packet.type.wireName} ttl=${packet.ttl} from=${fromPeerId ?: "local"}"
        )
        val listeners = synchronized(packetListeners) { packetListeners.toList() }
        listeners.forEach { listener ->
            try {
                listener.onPacketAccepted(packet, fromPeerId)
            } catch (e: Exception) {
                // A broken app-layer listener must never break the mesh.
                GeoRescueBleDiagnostics.warn(
                    GeoRescueBleDiagnostics.BLE_ERROR,
                    "code=LISTENER_FAILED detail=${e.message}"
                )
            }
        }
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.PACKET_PERSISTED,
            "packetId=${packet.packetId} (seen-store + app layer)"
        )
    }

    private fun ingestFrame(json: String, fromPeerId: String) {
        GeoRescueBleDiagnostics.info(
            GeoRescueBleDiagnostics.DATA_RECEIVED,
            "from=$fromPeerId chars=${json.length}"
        )
        val decision = meshNode.ingest(json, fromPeerId)
        val parsed = GeoRescueBlePacketCodec.deserialize(json)
        if (parsed != null) {
            synchronized(lock) { lastReceived = parsed }
        }
        logDecision(decision, parsed, fromPeerId)
    }

    private fun logDecision(
        decision: GeoRescueBleRelayDecision,
        packet: GeoRescueBlePacket?,
        fromPeerId: String?,
    ) {
        val packetId = packet?.packetId ?: "unknown"
        when (decision) {
            GeoRescueBleRelayDecision.ACCEPTED_FORWARDED ->
                GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.PACKET_RELAYED, "packetId=$packetId")
            GeoRescueBleRelayDecision.ACCEPTED_QUEUED ->
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.PACKET_QUEUED,
                    "packetId=$packetId (no peer available; store-and-forward)"
                )
            GeoRescueBleRelayDecision.ACCEPTED_LOCAL ->
                GeoRescueBleDiagnostics.info(
                    GeoRescueBleDiagnostics.PACKET_TTL_EXPIRED,
                    "packetId=$packetId (ttl<=0; processed locally, not relayed)"
                )
            GeoRescueBleRelayDecision.DUPLICATE ->
                GeoRescueBleDiagnostics.info(GeoRescueBleDiagnostics.PACKET_DUPLICATE, "packetId=$packetId ignored")
            GeoRescueBleRelayDecision.INVALID ->
                GeoRescueBleDiagnostics.warn(GeoRescueBleDiagnostics.PACKET_INVALID, "packetId=$packetId rejected")
            GeoRescueBleRelayDecision.OWN_PACKET ->
                GeoRescueBleDiagnostics.verbose(
                    GeoRescueBleDiagnostics.PACKET_VALID,
                    "packetId=$packetId own-packet echo ignored"
                )
        }
    }

    // --- GeoRescueBlePacketSink implementation (wired into the mesh node) ---

    internal fun availablePeerIds(): List<String> {
        synchronized(lock) {
            val ready = mutableListOf<String>()
            clientPeers.values.forEach { entry ->
                if (entry.stateMachine.state == GeoRescueBleState.READY) ready.add(entry.address)
            }
            serverPeers.values.forEach { entry ->
                if (entry.stateMachine.state == GeoRescueBleState.READY) ready.add(entry.address)
            }
            return ready
        }
    }

    internal fun sendToPeer(peerId: String, packetJson: String): Boolean {
        synchronized(lock) {
            val clientEntry = clientPeers[peerId]
            if (clientEntry != null && clientEntry.stateMachine.state == GeoRescueBleState.READY) {
                return try {
                    clientEntry.connection?.writeFrame(packetJson.toByteArray(Charsets.UTF_8)) ?: false
                } catch (e: Exception) {
                    GeoRescueBleDiagnostics.warn(
                        GeoRescueBleDiagnostics.WRITE_FAILED,
                        "role=CLIENT address=$peerId detail=${e.message}"
                    )
                    false
                }
            }
            val serverEntry = serverPeers[peerId]
            if (serverEntry != null && serverEntry.stateMachine.state == GeoRescueBleState.READY) {
                return notifyFrameToServerClient(serverEntry, packetJson.toByteArray(Charsets.UTF_8))
            }
            return false
        }
    }

    /**
     * Notifies a chunked frame to a subscribed SERVER-role client. The
     * receiving GeoRescuX client reassembles via its own framer.
     */
    private fun notifyFrameToServerClient(entry: ServerPeer, frameBytes: ByteArray): Boolean {
        val service = gattService ?: return false
        val chunks = GeoRescueBleFramer().chunksFor(frameBytes, SERVER_NOTIFY_CHUNK_BYTES)
        chunks.forEach { chunk ->
            val ok = service.notifyTo(entry.address, chunk)
            if (!ok) {
                GeoRescueBleDiagnostics.warn(
                    GeoRescueBleDiagnostics.WRITE_FAILED,
                    "role=SERVER_NOTIFY address=${entry.address}"
                )
                return false
            }
        }
        GeoRescueBleDiagnostics.verbose(
            GeoRescueBleDiagnostics.WRITE_SUCCESS,
            "role=SERVER_NOTIFY address=${entry.address} chunks=${chunks.size}"
        )
        return true
    }

    // --- Diagnostics state (consumed by the BLE diagnostics screen) ---

    fun setLastError(message: String) {
        synchronized(lock) { lastError = message }
        GeoRescueBleDiagnostics.error(GeoRescueBleDiagnostics.BLE_ERROR, message)
    }

    fun diagnostics(): DiagnosticsState {
        synchronized(lock) {
            val peerViews = mutableListOf<PeerDiagnostics>()
            clientPeers.values.forEach { entry ->
                val conn = entry.connection
                peerViews.add(
                    PeerDiagnostics(
                        address = entry.address,
                        role = "CLIENT",
                        state = entry.stateMachine.state,
                        serviceDiscovered = conn?.serviceDiscovered ?: false,
                        rxFound = conn?.rxFound ?: false,
                        txFound = conn?.txFound ?: false,
                        notificationsEnabled = conn?.notificationsEnabled ?: false,
                    )
                )
            }
            serverPeers.values.forEach { entry ->
                peerViews.add(
                    PeerDiagnostics(
                        address = entry.address,
                        role = "SERVER",
                        state = entry.stateMachine.state,
                        serviceDiscovered = serverServiceRegistered,
                        rxFound = serverServiceRegistered,
                        txFound = serverServiceRegistered,
                        notificationsEnabled = entry.subscribed,
                    )
                )
            }
            return DiagnosticsState(
                initialized = initialized,
                readiness = readiness,
                scanning = scanner?.isScanning ?: false,
                advertising = advertiser?.isAdvertising ?: false,
                nearbyCount = discovered.size,
                nearbyDevices = discovered.values.toList(),
                peers = peerViews,
                lastPacketSent = lastSent,
                lastPacketReceived = lastReceived,
                lastError = lastError,
                serverServiceRegistered = serverServiceRegistered,
                pendingOutbound = meshNode.pendingTotal(),
                seenPackets = meshNode.seenCount(),
            )
        }
    }

    /** Total packets currently waiting for delivery (store-and-forward). */
    fun pendingOutboundCount(): Int = meshNode.pendingTotal()

    fun seenPacketCount(): Int = meshNode.seenCount()

    /** Disconnects and releases a specific peer (client side). */
    fun disconnectPeer(address: String) {
        synchronized(lock) {
            clientPeers[address]?.connection?.disconnect()
        }
    }

    /** Graceful shutdown (Application.onTerminate / diagnostics "Reset"). */
    fun shutdown() {
        synchronized(lock) {
            stopScanning()
            stopAdvertising()
            clientPeers.values.forEach { it.connection?.disconnect() }
            clientPeers.clear()
            serverPeers.clear()
            serverFramers.clear()
            gattService?.close()
            gattService = null
            advertiser = null
            scanner = null
            initialized = false
        }
    }

    private fun ownBluetoothAddressOrNull(): String? {
        val btAdapter = adapter ?: return null
        return try {
            btAdapter.address
        } catch (_: SecurityException) {
            null
        }
    }

    private var lastError: String? = null
    private var lastSent: GeoRescueBlePacket? = null
    private var lastReceived: GeoRescueBlePacket? = null
    private var serverServiceRegistered = false

    private companion object {
        const val MAX_DISCOVERED_TRACKED = 20

        /**
         * Conservative notify chunk size: fits any client that negotiated
         * MTU >= 188 (our own client requests 247). Values larger than the
         * peer's MTU would be silently truncated by the stack, corrupting
         * the frame — chunking keeps every notify complete.
         */
        const val SERVER_NOTIFY_CHUNK_BYTES = 180
    }
}
