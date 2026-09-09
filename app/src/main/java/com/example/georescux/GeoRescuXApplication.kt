package com.example.georescux

import android.app.Application
import android.content.Context
import android.os.Build
import com.example.georescux.data.auth.AuthRepositoryImpl
import com.example.georescux.data.contacts.ContactLocalStore
import com.example.georescux.data.contacts.ContactsRepositoryImpl
import com.example.georescux.data.contacts.SharedPreferencesContactStore
import com.example.georescux.data.location.LocationRepositoryImpl
import com.example.georescux.data.routing.RouteGraphLocalStore
import com.example.georescux.data.routing.RouteRepositoryImpl
import com.example.georescux.data.routing.SharedPreferencesRouteGraphStore
import com.example.georescux.data.maps.MapRegionCatalog
import com.example.georescux.data.maps.MapRegionSelectionStore
import com.example.georescux.data.maps.RegionGraphLoader
import com.example.georescux.data.sos.LocalSosStore
import com.example.georescux.data.sos.SharedPreferencesSosStore
import com.example.georescux.data.sos.SosRepositoryImpl
import com.example.georescux.data.sync.AndroidConnectivityMonitor
import com.example.georescux.data.sync.ContactsBackup
import com.example.georescux.data.sync.FirebaseContactsCloudDataSource
import com.example.georescux.data.sync.FirebaseSosCloudDataSource
import com.example.georescux.data.sync.SharedPreferencesSyncStateStore
import com.example.georescux.data.sync.SyncRetryCoordinator
import com.example.georescux.data.sync.SyncingContactsRepository
import com.example.georescux.data.sync.SyncStateStore
import com.example.georescux.data.sync.SyncingSosRepository
import com.example.georescux.data.sync.SosBackup
import com.example.georescux.domain.incident.IncidentType
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import com.example.georescux.domain.repository.LocationRepository
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.CancelSosUseCase
import com.example.georescux.domain.sos.SosEmergency
import com.example.georescux.domain.sos.SosLocationStatus
import com.example.georescux.domain.sos.GetActiveSosUseCase
import com.example.georescux.domain.sos.SosLocation
import com.example.georescux.domain.sos.StartSosUseCase
import com.example.georescux.domain.sos.StopSosUseCase
import com.example.georescux.domain.sos.UpdateSosLocationUseCase
import com.example.georescux.work.WorkManagerSyncScheduler
import com.example.georescux.data.auth.DeviceIdProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency-injection container. Everything the app shares is
 * created once here and handed to the screens that need it — simple
 * and explicit, with no dependency-injection framework.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // The single FirebaseAuth instance used by the whole app.
    // Created lazily: Android constructs the Application object BEFORE
    // FirebaseInitProvider runs, so FirebaseAuth must not be touched here.
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    // The single doorway to authentication features.
    val authRepository: AuthRepository by lazy { AuthRepositoryImpl(firebaseAuth) }

    // Role verification for secure admin routing
    val roleResolver: com.example.georescux.domain.auth.RoleResolver by lazy { 
        com.example.georescux.data.auth.FirebaseRoleResolver(firebaseAuth) 
    }

    /**
     * Stable per-device identity used as the BLE relay's originId.
     * Prefers the Firebase UID (unique per account) so cloud and mesh IDs agree.
     * Falls back to a persisted UUID so two offline devices never share the same ID
     * (if both were "DEVICE_LOCAL" Device B's RelayEngine would reject Device A's
     * SOS as RelayDecision.OWN_EVENT and silently drop the alert).
     */
    private val selfOriginId: String by lazy {
        authRepository.currentUserId ?: DeviceIdProvider.getOrCreate(appContext)
    }

    // Stage 7B-3: durable, per-account record of what still needs to reach
    // Firebase; survives process death and is never cloud data. ONE instance
    // is shared by every syncing repository.
    private val syncStateStore: SyncStateStore = SharedPreferencesSyncStateStore(appContext)

    // SOS (Phase 1: local-only storage, works offline; Firebase sync later).
    private val sosStore: LocalSosStore = SharedPreferencesSosStore(appContext)
    private val localSosRepository: SosRepository = SosRepositoryImpl(sosStore)

    // Stage 6: Firebase backup — local storage remains the source of truth,
    // emergency records are mirrored best-effort to sos_alerts/{uid}.
    private val syncingSosRepository: SyncingSosRepository = SyncingSosRepository(
        local = localSosRepository,
        authRepository = authRepository,
        cloudDataSource = FirebaseSosCloudDataSource(),
        syncStateStore = syncStateStore,
    )
    val sosBackup: SosBackup = syncingSosRepository

    // Offline evacuation routing (Stage 7B-4: region-scoped storage with a
    // user-selected active region — the bundled sample region plus the
    // bundled real OSM-derived Uttarakhand region). Switching regions
    // swaps the repository (and its seeded graph) without touching any
    // other region's stored graph.
    private val routeGraphStore: RouteGraphLocalStore = SharedPreferencesRouteGraphStore(appContext)
    private val mapRegionSelection = MapRegionSelectionStore(appContext)

    @Volatile
    var activeRegionId: String = mapRegionSelection.load() ?: MapRegionCatalog.sampleRegion.id
        private set

    private val routeRepositories = mutableMapOf<String, RouteRepository>()

    fun routeRepositoryFor(regionId: String): RouteRepository = synchronized(routeRepositories) {
        routeRepositories.getOrPut(regionId) {
            val region = MapRegionCatalog.byId(regionId)
                ?: throw IllegalArgumentException("Unknown map region: $regionId")
            RouteRepositoryImpl(
                store = routeGraphStore,
                activeRegionId = regionId,
                seedProvider = { RegionGraphLoader.fromAsset(appContext.assets, region.graphAssetPath) },
                bundledSeedVersion = region.version,
            )
        }
    }

    val routeRepository: RouteRepository get() = routeRepositoryFor(activeRegionId)

    /** Switches the active region (manual selection or GPS-based). */
    fun setActiveRegion(regionId: String): Boolean {
        MapRegionCatalog.byId(regionId) ?: return false
        mapRegionSelection.save(regionId)
        activeRegionId = regionId
        return true
    }

    // BLE Mesh Relay & Durable Seen-Event Persistence
    val relayConnectionManager: com.example.georescux.data.relay.RelayConnectionManager by lazy {
        val nearbyAdapter = com.example.georescux.data.relay.GoogleNearbyClientAdapter(appContext)
        val manager = com.example.georescux.data.relay.RelayConnectionManager(nearbyAdapter)
        // FIX Bug 4: async Nearby failures (advertising/discovery failed after the call returned)
        // call stopMesh so isRunning is reset and startMesh can retry on the next onResume.
        nearbyAdapter.onMeshFailure = { manager.stopMesh() }
        manager
    }
    val bleRelayTransport: com.example.georescux.data.relay.BleRelayTransport by lazy {
        com.example.georescux.data.relay.BleRelayTransport(relayConnectionManager)
    }
    private val seenEventStore: com.example.georescux.data.relay.SeenEventStore by lazy {
        com.example.georescux.data.relay.SQLiteSeenEventStore(appContext)
    }
    val relayEngine: com.example.georescux.domain.relay.RelayEngine by lazy {
        com.example.georescux.domain.relay.RelayEngine(
            selfOriginId = selfOriginId,
            transport = bleRelayTransport,
        )
    }
    val durableRelayEngine: com.example.georescux.data.relay.DurableRelayEngine by lazy {
        com.example.georescux.data.relay.DurableRelayEngine(relayEngine, seenEventStore)
    }

    // Hazard Corroboration & Route Bridge
    val hazardEventRouteBridge: com.example.georescux.data.routing.HazardEventRouteBridge by lazy {
        com.example.georescux.data.routing.HazardEventRouteBridge(routeRepository)
    }
    val hazardCloudDataSource: com.example.georescux.data.sync.FirebaseHazardCloudDataSource by lazy {
        com.example.georescux.data.sync.FirebaseHazardCloudDataSource(routeRepository)
    }
    val reportHazardUseCase: com.example.georescux.domain.hazard.ReportHazardUseCase by lazy {
        com.example.georescux.domain.hazard.ReportHazardUseCase(
            relayEngine,
            selfOriginId,
        )
    }

    // Wire SOS through BLE Mesh Relay
    private val sosRelayRepository: SosRepository by lazy {
        com.example.georescux.data.sos.SosRelayRepository(
            delegate = syncingSosRepository,
            relayEngine = relayEngine,
            selfOriginId = selfOriginId,
        )
    }

    // Raw BLE subsystem (custom GATT emergency service + hop-TTL mesh).
    // Distinct from the Google Nearby relay above: Nearby handles the
    // Android-ecosystem mesh, the BLE subsystem provides the diagnosable
    // GATT transport (docs/BLE_SUBSYSTEM.md). Both feed the SAME local
    // persistence and the SAME Firebase sync engine.
    val bleSosBridge: com.example.georescux.data.ble.BleSosBridge by lazy {
        com.example.georescux.data.ble.BleSosBridge(
            manager = bleManager,
            originDeviceId = selfOriginId,
        )
    }
    val bleManager: com.example.georescux.data.ble.GeoRescueBleManager by lazy {
        // Logcat sink first: every BLE event goes to "GeoRescueX-BLE".
        com.example.georescux.data.ble.GeoRescueBleLogSink.installOnce()
        val manager = com.example.georescux.data.ble.GeoRescueBleManager(
            appContext = appContext,
            selfDeviceId = selfOriginId,
            repository = com.example.georescux.data.ble.GeoRescueBleRepository(appContext),
        )
        manager.addPacketListener(object : com.example.georescux.data.ble.GeoRescueBleManager.PacketListener {
            override fun onPacketAccepted(
                packet: com.example.georescux.domain.ble.GeoRescueBlePacket,
                fromPeerId: String?,
            ) {
                // Ignore locally originated packets (already persisted by local SOS layer)
                if (packet.originDeviceId == selfOriginId) return
                val emergency = bleSosBridge.emergencyFromPacket(packet) ?: return
                // Persist into the SAME local SOS store the existing sync
                // engine reads — a device with Internet will upload the
                // received emergency to Firebase on its next sync run.
                sosStore.addToHistory(emergency)
                if (emergency.isActive) {
                    com.example.georescux.data.notification.SosAlertNotifier.notify(
                        context = appContext,
                        originId = packet.originDeviceId,
                        startedAtMs = packet.timestampMs,
                    )
                }
                // If signed in, mark pending so this device uploads the relayed emergency to Firebase
                authRepository.currentUserId?.let { uid ->
                    syncStateStore.setSosAlertPending(uid, emergency.id, pending = true)
                }
                syncRetryCoordinator.retryPendingNow()
            }
        })
        manager
    }
    private val sosBleBridgeRepository: SosRepository by lazy {
        com.example.georescux.data.sos.SosBleBridgeRepository(
            delegate = sosRelayRepository,
            bridge = bleSosBridge,
        )
    }
    val sosRepository: SosRepository by lazy { sosBleBridgeRepository }

    init {
        relayConnectionManager.attachRelayEngine(relayEngine)
        relayConnectionManager.setOnEventReceivedListener { envelope ->
            hazardEventRouteBridge.onEventAccepted(envelope.event)

            if (envelope.event.type == IncidentType.SOS) {
                val event = envelope.event
                val status = event.payload["status"] ?: "ACTIVE"
                val locationStatusName = event.payload["locationStatus"]
                val locStatus = locationStatusName?.let { runCatching { SosLocationStatus.valueOf(it) }.getOrNull() }
                val location = if (event.latitude != null && event.longitude != null) {
                    SosLocation(
                        latitude = event.latitude,
                        longitude = event.longitude,
                        accuracyMeters = 0f,
                        timestampMs = event.occurredAtMs,
                        provider = "mesh_relay"
                    )
                } else null

                val emergency = SosEmergency(
                    id = "MESH_${event.originId}_${event.eventId}",
                    startedAtMs = event.occurredAtMs,
                    stoppedAtMs = if (status == "COMPLETED") event.occurredAtMs + 1000L else null,
                    location = location,
                    locationStatus = locStatus ?: (if (location != null) SosLocationStatus.ACQUIRED else null)
                )
                sosStore.addToHistory(emergency)

                // Gap B fix: post a visible heads-up notification so the user on this device
                // is immediately alerted — without this, the alert was saved silently and only
                // visible if the user manually opened the Alerts screen.
                if (status != "COMPLETED") {
                    com.example.georescux.data.notification.SosAlertNotifier.notify(
                        context = appContext,
                        originId = event.originId,
                        startedAtMs = event.occurredAtMs,
                    )
                }
            }
        }
    }

    val startSosUseCase = StartSosUseCase(sosRepository)
    val cancelSosUseCase = CancelSosUseCase(sosRepository)
    val stopSosUseCase = StopSosUseCase(sosRepository)
    val getActiveSosUseCase = GetActiveSosUseCase(sosRepository)

    // Location (Phase 2: local fixes only, no background tracking).
    val locationRepository: LocationRepository = LocationRepositoryImpl(appContext)
    val updateSosLocationUseCase = UpdateSosLocationUseCase(sosRepository)

    // Emergency contacts (Stage 3: local-only, per-user, offline).
    private val contactStore: ContactLocalStore = SharedPreferencesContactStore(appContext)
    private val localContactsRepository: ContactsRepository = ContactsRepositoryImpl(contactStore, authRepository)

    // Stage 5B: Firebase backup — local storage remains the source of truth,
    // every local change is mirrored best-effort to contacts/{uid}.
    private val syncingContactsRepository: SyncingContactsRepository = SyncingContactsRepository(
        local = localContactsRepository,
        authRepository = authRepository,
        cloudDataSource = FirebaseContactsCloudDataSource(),
        syncStateStore = syncStateStore,
    )
    val contactsBackup: ContactsBackup = syncingContactsRepository
    val contactsRepository: ContactsRepository = syncingContactsRepository

    // Stage 7B-3 Step 4 + Stage 8: process-level retry triggers. Fires the
    // existing pending-backup retries on app start and on connectivity
    // regained; the WorkManager scheduler additionally queues a unique
    // network-constrained trigger so pending state survives process death
    // (docs/STAGE8_SYNC_RETRY_DESIGN.md). The coordinator remains the sole
    // executor; SyncStateStore remains the durable mechanism, and each
    // retry method itself checks the current session's pending state.
    val syncRetryCoordinator: SyncRetryCoordinator = SyncRetryCoordinator(
        contactsBackup = contactsBackup,
        sosBackup = sosBackup,
        connectivityMonitor = AndroidConnectivityMonitor(appContext),
        workScheduler = WorkManagerSyncScheduler(appContext),
    )
}

/**
 * Application class. Registered in AndroidManifest.xml so Android creates
 * it before any activity. Owns the AppContainer for the whole process.
 */
class GeoRescuXApplication : Application() {
    // Lazily created so the base context is attached before it is used
    // (field initializers of Application run before attachBaseContext).
    val appContainer: AppContainer by lazy { AppContainer(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Stage 7B-3 Step 4: retry durable pending backups on app start and
        // again whenever usable connectivity is regained (signed-in users
        // only, failures contained — never delays or blocks startup, and
        // never replaces local data with cloud data; when nothing is
        // pending the run performs no cloud work).
        //
        // EVERYTHING that touches appContainer happens on the background
        // scope, never on the main thread: the first appContainer access
        // constructs the whole container — including FirebaseAuth, whose
        // first initialization loads large Firebase class graphs. If the
        // main thread wins that lazy race (e.g. by calling start() here
        // directly), the first activity render blocks behind that work and
        // the app sits on a white screen. Constructed on the worker, the
        // container is warm before MainActivity touches it.
        appScope.launch {
            val coordinator = appContainer.syncRetryCoordinator
            coordinator.start()
            coordinator.retryPendingNow()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Release the ConnectivityManager registration (instrumented runs
        // only — on devices the process-lifetime callback is intentional;
        // runs long after startup, so touching appContainer here is safe).
        appContainer.syncRetryCoordinator.stop()
        appContainer.relayConnectionManager.stopMesh()
        runCatching { appContainer.bleManager.shutdown() }
    }
}
