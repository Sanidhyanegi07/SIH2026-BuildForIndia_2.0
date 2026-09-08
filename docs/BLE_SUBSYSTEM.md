# BLE Subsystem (GeoRescuX) — Design, Diagnostics & Limitations

This document describes the dedicated raw-BLE subsystem (`ble`) added to
GeoRescuX, how it relates to the existing Google Nearby relay, how to run the
two-device test, and the honest platform limitations (Phase 18).

## 1. Architecture position

```
Internet -> Firebase -> GeoRescuX local persistence
                              ^
                              |  (existing sync engine)
BLE (GATT mesh, this document)  +  Google Nearby relay (existing)
                              ^
                              |
                 Nearby GeoRescuX devices
```

BLE is a **local emergency transport only**. It never replaces Firebase, and
it never becomes a second synchronization system:

- **Send:** SOS activation -> `SosBleBridgeRepository` (fire-and-forget,
  exception-contained) -> `BleSosBridge.publishSos` -> `GeoRescueBleManager.publishPacket`.
- **Receive:** BLE packet -> dedup/validate (mesh node) -> persisted into the
  SAME local SOS store -> the EXISTING `SyncingSosRepository` / WorkManager
  sync engine uploads it to Firebase when that device has Internet.
- **BLE failure can never cancel, delay or block SOS activation.** If the radio
  is off or no peer is reachable, the packet stays in the durable outbound
  queue and is flushed on the first READY peer (store-and-forward).

## 2. The GATT profile

| Item | UUID | Notes |
|---|---|---|
| Service | `f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b01` | Primary service |
| RX characteristic | `f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b02` | Clients WRITE packets here (received by device) |
| TX characteristic | `f5a6bd01-2c3e-4d5a-9b7f-1e3d5c7a9b03` | Server NOTIFIES packets here (transmitted) |
| CCCD | standard 0x2902 | On TX; enables notifications |

Every device runs BOTH roles: GATT server (advertising; receives on RX,
notifies on TX) and GATT client (scans + connects; writes to the peer's RX,
subscribes to the peer's TX). Advertising carries the service UUID only — no
device name, no service data, no manufacturer data — so discovery never leaks
personal information. Actual emergency data always flows over the GATT
connection, never in the advertisement.

## 3. Packet format & relay semantics

One newline-terminated JSON frame per packet (chunked across GATT writes /
notifications, reassembled by `GeoRescueBleFramer`):

```json
{
  "protocolVersion": 1,
  "packetId": "GRX-<uuid>",          // unique per emission; idempotency key
  "originDeviceId": "DEVICE_...",    // stable per-install identity
  "originEmergencyId": "SOS-...",    // link to the local emergency record
  "timestamp": 1690000000000,
  "ttl": 5,                          // hops remaining
  "type": "SOS" | "GEORESCUEX_BLE_TEST",
  "latitude": 30.7333,
  "longitude": 76.7794,
  "status": "ACTIVE",
  "payload": {}
}
```

- **Deduplication:** `packetId` is recorded in SQLite (`ble_seen_packets`)
  before processing; duplicates are dropped and survive process death.
- **TTL (hop limit):** default 5. Every forward decrements by exactly one; a
  packet whose decrement reaches 0 is processed locally and never forwarded
  (`PACKET_TTL_EXPIRED`). Packets older than 1 h are not propagated; seen-id
  rows expire after 24 h.
- **Store-and-forward:** packets no peer could take are queued in SQLite
  (`ble_outbound_queue`) and flushed to each peer the moment its connection
  becomes READY.

Relay chain: A -> B -> C -> (device with Internet) -> Firebase, each hop
requiring neither A nor C to have Internet.

## 4. Where the code lives

```
domain/ble/                       (pure Kotlin, JVM-testable)
  GeoRescueBlePacket(.Codec/.Validator)   wire format + validation
  GeoRescueBleFramer                      chunk/reassemble GATT frames
  GeoRescueBleMeshNode                    dedup + TTL + store-and-forward core
  GeoRescueBleState(.Machine)             explicit connection-flow states
  GeoRescueBlePermissions                 centralized permission policy
  GeoRescueBleDiagnostics                 single log tag + event vocabulary

data/ble/                         (Android glue)
  GeoRescueBleProfile                     UUID constants + GATT service build
  GeoRescueBleCapabilities                adapter/BLE/permission readiness
  GeoRescueBleAdvertiser                  Phase 3 (connectable adv, UUID only)
  GeoRescueBleScanner                     Phase 4 (UUID-filtered scan)
  GeoRescueBleService                     GATT server (RX/TX + CCCD)
  GeoRescueBleConnection                  GATT client flow per peer
  GeoRescueBleRepository                  SQLite seen-ids + outbound queue
  GeoRescueBleManager                     orchestrator + diagnostics state
  BleSosBridge / SosBleBridgeRepository   SOS <-> BLE integration
  GeoRescueBleLogSink                     android.util.Log under GeoRescueX-BLE

ui/ble/BleDiagnosticsActivity     two-device diagnostic screen
```

Every BLE operation logs under the single tag **`GeoRescueX-BLE`** with the
structured event vocabulary (BLE_INIT_*, PERMISSION_CHECK, SCAN_*,
ADVERTISING_*, CONNECTION_*, GATT_CONNECTED, SERVICE_DISCOVERY_*,
GEORESCUE_SERVICE_FOUND, RX/TX_CHARACTERISTIC_FOUND, NOTIFICATION_ENABLED,
WRITE_*, DATA_RECEIVED, PACKET_VALID/INVALID/DUPLICATE/PERSISTED/RELAYED/
QUEUED/TTL_EXPIRED, DISCONNECTED, BLE_ERROR) including error codes.

## 5. Two-device diagnostic mode

Home screen -> **BLE Diagnostics** tile. The screen shows the full checklist
(capabilities, per-permission state, scanner/advertiser readiness, nearby
GeoRescuX device count, per-peer connection state, GATT service / RX / TX /
notification status, last packet sent/received, last error, outbound queue
size, dedup cache size) plus a live event log. "Send Test Packet" emits the
`GEORESCUEX_BLE_TEST` packet.

### Manual physical-device test procedure (BLE needs 2 REAL devices)

Prepare both devices: install the same debug APK, enable Bluetooth and
location services, grant the requested permissions, keep screens on and the
app open (see §6), keep the devices within ~5 m.

**DEVICE A (advertiser):**
1. Open GeoRescuX -> BLE Diagnostics.
2. "Grant permissions / enable Bluetooth" until every row shows GRANTED and
   Bluetooth enabled: YES.
3. Tap **Advertise ON** -> expect `ADVERTISING_STARTED`.
4. Tap **Scan ON** -> expect `GEORESCUE_DEVICE_FOUND` with Device B's address.

**DEVICE B (sender):**
1. Open GeoRescuX -> BLE Diagnostics; grant permissions the same way.
2. Tap **Advertise ON** and **Scan ON**. Both devices should show the other
   in the nearby count and connect automatically (`CONNECTION_SUCCESS`,
   `NOTIFICATION_ENABLED`, peer state=READY).
3. Tap **Send Test Packet**.

**Expected result (first acceptance criterion):**
- Device B log: `PACKET_SENT`/`PACKET_RELAYED` (or `PACKET_QUEUED` then
  `PACKET_RELAYED` when READY), `WRITE_STARTED`, `WRITE_SUCCESS`.
- Device A log: `DATA_RECEIVED`, `PACKET_VALID`, `PACKET_PERSISTED` and the
  packet appears under "RECEIVED TEST PACKETS".

Only after this works, test SOS relay: activate SOS on B (A's app open) ->
A receives the SOS packet, it is stored in A's local SOS history
(Alerts screen) and a heads-up notification appears.

### Failure classification guide (use the diagnostics screen + logcat)

- No `GEORESCUE_DEVICE_FOUND` on either side -> **A** (discovery).
- Found but never `GATT_CONNECTED` -> **B** (connection).
- `GATT_CONNECTED` but no READY / no WRITE_SUCCESS -> **C** (data transfer).
- `PACKET_*` logs absent while WRITE_SUCCESS present -> **D** (app layer).
- Works foreground, fails when backgrounded -> **E**.
- Permission or capability rows not satisfied -> **F**.
- Everything above passes on both devices -> **G**.

## 6. Background behavior — documented limitations (honest platform facts)

The subsystem runs while the app is in the foreground / process alive with
activities resumed (Home auto-starts it). It deliberately does NOT use a
foreground service in this pass:

- **Screen off / device idle:** the OS suspends the app; the GATT links drop
  or stop being serviced. Packets stay queued and flush on the next app
  foreground.
- **Background app:** advertising/scanning and existing GATT connections die
  shortly after the process is cached (Android kills BLE work of cached apps;
  OEMs are more aggressive). No continuous background relay is promised.
- **Doze / App Standby:** all BLE work pauses during maintenance windows;
  no alarms are used to wake BLE (battery policy).
- **Manufacturer restrictions (Xiaomi/Samsung/Oppo etc.):** autostart limits
  may prevent any process revival; expect foreground-only behavior unless a
  user explicitly exempts the app.
- **Foreground service option (future):** a connected-device foreground
  service (`connectedDevice` type, plus Bluetooth permissions) would allow
  continuous advertising/GATT while SOS is ACTIVE. This is the correct
  follow-up enhancement and is intentionally not silently half-implemented
  here.
- **Scanning requires a screen** on some OEMs when using `NEVER_FOR_LOCATION`
  flows; keep the diagnostics screen open during tests.

## 7. Security posture

Packets carry the minimum emergency data (id, timestamp, location, status).
They never contain Firebase credentials, ID tokens, passwords or personal
information. The advertisement is nameless.

The existing project has an ECDSA event-signing mechanism
(`domain/security/EventSigner` + `EventAuthenticator`). **The BLE subsystem
does not yet integrate it** — BLE packets are currently unauthenticated and
UNENCRYPTED (any nearby device that speaks the profile can read them, and
packets could be forged). This is documented as a FUTURE security
enhancement (sign packet canonical form with the same EventSigner, add a
signature field + verify in the mesh node). No insecure custom cryptography
was invented.

## 8. Build & tests

```
./gradlew :app:assembleDebug        # APK
./gradlew :app:testDebugUnitTest    # JVM tests incl. BLE subsystem
```

BLE unit tests (pure JVM): `GeoRescueBlePacketTest`,
`GeoRescueBleMeshNodeTest`, `GeoRescueBleFramerTest`,
`GeoRescueBlePermissionsTest`, `GeoRescueBleConnectionStateMachineTest`.

**Physical BLE communication cannot be verified by unit tests or emulators** —
two real Android devices are required (§5).
