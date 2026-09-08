package com.example.georescux

import android.app.Application
import android.content.Context
import com.example.georescux.data.auth.AuthRepositoryImpl
import com.example.georescux.data.contacts.ContactLocalStore
import com.example.georescux.data.contacts.ContactsRepositoryImpl
import com.example.georescux.data.contacts.SharedPreferencesContactStore
import com.example.georescux.data.location.LocationRepositoryImpl
import com.example.georescux.data.routing.RouteGraphLocalStore
import com.example.georescux.data.routing.RouteRepositoryImpl
import com.example.georescux.data.routing.SharedPreferencesRouteGraphStore
import com.example.georescux.data.maps.MapRegionCatalog
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
import com.example.georescux.domain.repository.AuthRepository
import com.example.georescux.domain.repository.ContactsRepository
import com.example.georescux.domain.repository.LocationRepository
import com.example.georescux.domain.repository.RouteRepository
import com.example.georescux.domain.repository.SosRepository
import com.example.georescux.domain.sos.CancelSosUseCase
import com.example.georescux.domain.sos.GetActiveSosUseCase
import com.example.georescux.domain.sos.StartSosUseCase
import com.example.georescux.domain.sos.StopSosUseCase
import com.example.georescux.domain.sos.UpdateSosLocationUseCase
import com.example.georescux.work.WorkManagerSyncScheduler
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

    // Offline evacuation routing (Stage 7B-2: region-scoped storage; the
    // active region is the bundled real OSM-derived sample region).
    private val routeGraphStore: RouteGraphLocalStore = SharedPreferencesRouteGraphStore(appContext)
    val routeRepository: RouteRepository = RouteRepositoryImpl(
        store = routeGraphStore,
        activeRegionId = MapRegionCatalog.sampleRegion.id,
    ) {
        RegionGraphLoader.fromAsset(appContext.assets, MapRegionCatalog.sampleRegion.graphAssetPath)
    }

    // BLE Mesh Relay & Durable Seen-Event Persistence
    val relayConnectionManager: com.example.georescux.data.relay.RelayConnectionManager by lazy {
        com.example.georescux.data.relay.RelayConnectionManager(
            com.example.georescux.data.relay.GoogleNearbyClientAdapter(appContext)
        )
    }
    val bleRelayTransport: com.example.georescux.data.relay.BleRelayTransport by lazy {
        com.example.georescux.data.relay.BleRelayTransport(relayConnectionManager)
    }
    private val seenEventStore: com.example.georescux.data.relay.SeenEventStore by lazy {
        com.example.georescux.data.relay.SQLiteSeenEventStore(appContext)
    }
    val relayEngine: com.example.georescux.domain.relay.RelayEngine by lazy {
        com.example.georescux.domain.relay.RelayEngine(
            selfOriginId = authRepository.currentUserId ?: "DEVICE_LOCAL",
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
    val reportHazardUseCase: com.example.georescux.domain.hazard.ReportHazardUseCase by lazy {
        com.example.georescux.domain.hazard.ReportHazardUseCase(
            relayEngine,
            authRepository.currentUserId ?: "DEVICE_LOCAL"
        )
    }

    // Wire SOS through BLE Mesh Relay
    private val sosRelayRepository: SosRepository by lazy {
        com.example.georescux.data.sos.SosRelayRepository(
            delegate = syncingSosRepository,
            relayEngine = relayEngine,
            selfOriginId = authRepository.currentUserId ?: "DEVICE_LOCAL",
        )
    }
    val sosRepository: SosRepository by lazy { sosRelayRepository }

    init {
        relayConnectionManager.attachRelayEngine(relayEngine)
        relayConnectionManager.setOnEventReceivedListener { envelope ->
            hazardEventRouteBridge.onEventAccepted(envelope.event)
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
    }
}
