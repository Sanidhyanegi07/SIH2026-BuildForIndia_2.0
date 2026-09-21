# GeoRescueX

<p align="center">
  <img src="Images/dashboard.jpg" alt="GeoRescueX dashboard" width="900" />
</p>

<p align="center">
  <strong>Disaster resilience, offline-first.</strong>
</p>

<p align="center">
  <img alt="Platform: Android" src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" />
  <img alt="Offline First" src="https://img.shields.io/badge/Design-Offline%20First-FF6B6B" />
  <img alt="SIH 2026" src="https://img.shields.io/badge/Track-SIH%202026-4ECDC4" />
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-FFD166" />
</p>

GeoRescueX is an offline-first emergency response and disaster-resilience Android application designed for challenging real-world disaster conditions where connectivity, power, and infrastructure may fail.

> The system that works even when everything else stops working.

Built for Smart India Hackathon 2026 — Problem Statement SIH26206, Disaster Management (Software Track) — GeoRescueX focuses on three critical phases of disaster response:

- Prevent
- Respond
- Recover

The core design principle is simple: emergency response must continue even when the network does not.

## Why this project matters

Disasters such as floods, earthquakes, landslides, fires, and severe weather can disrupt cellular networks, damage roads, and prevent timely access to emergency information. In these conditions, a conventional cloud-first app may fail exactly when it is needed most.

GeoRescueX addresses that gap by combining:

- offline-safe routing over locally bundled regional graphs
- GPS-based emergency intelligence
- BLE-based phone-to-phone disaster communication
- local-first SOS persistence and retry-based sync
- admin monitoring and incident visibility when connectivity returns

## Mission

GeoRescueX aims to keep life-saving capabilities operational even in low-connectivity or no-connectivity conditions by making critical systems local-first and resilient by default.

## System overview

GeoRescueX is designed around a resilient emergency stack:

- local map and route data
- GPS + hazard-aware routing
- emergency SOS and incident tracking
- multi-hop BLE resilience mesh
- deferred cloud synchronization when internet becomes available

### High-level architecture

```text
User / field responder
        │
        ▼
   App UI + ViewModel
        │
        ▼
   Domain logic / Emergency workflow
        │
   ┌────┴───────────────┬──────────────┐
   │                    │              │
   ▼                    ▼              ▼
Local storage      Offline routing      Firebase sync
(Room/SQLite)      (region graphs)     (Auth + cloud)
   │                    │              │
   └────────────────────┴──────────────┘
                  │
                  ▼
           Emergency response continues
```

## Core workflow

```text
SOS activation
  → GPS capture
  → offline incident persistence
  → hazard + route analysis
  → safe-route generation
  → safe haven / emergency destination
  → sync when connectivity returns
```

## Key features

### 1. Emergency SOS

- Hold-to-activate SOS flow
- local-first emergency persistence
- GPS tagging and background location acquisition
- deferred sync when connectivity is restored
- emergency contact and alert workflows

### 2. GPS and location intelligence

- device GPS used for rescue readiness and route decisions
- location features remain useful even when mobile network is unavailable
- location-aware incident capture and responder context

### 3. Offline mapping and routing

- OpenStreetMap-derived geographic data
- region-scoped offline route graphs
- safe-route generation with hazard and blocked-road awareness
- local route calculations without cloud dependency

### 4. Regional route graphs

GeoRescueX is intentionally region-based instead of using a single giant global graph. This keeps data manageable, easier to validate, and easier to extend to future disaster-prone regions.

### 5. BLE emergency mesh

A raw BLE subsystem enables device-to-device emergency communication when cellular infrastructure is unavailable.

Key capabilities include:

- foreground service lifecycle
- automatic Bluetooth recovery
- scanning duty cycling and throttling resilience
- symmetric GATT roles
- multi-hop packet relay with deduplication and TTL expiry
- cloud ingestion once an online gateway node is reached

### 6. Admin-aware monitoring

Administrative access is enforced via trusted backend mechanisms and Firebase custom claims instead of client-side trust assumptions.

### 7. Firebase sync and retry logic

Firebase is used for synchronization and monitoring after connectivity returns, not as the primary dependency for immediate rescue functionality.

## Region support

| Region | Region ID | Status |
|---|---|---|
| Connaught Place, New Delhi (demo) | `sample-region` | bundled since Stage 7B-1 |
| Uttarakhand (full state + border buffer) | `uttarakhand` | bundled since Stage 7B-4 |

The Uttarakhand dataset includes real OSM-derived routing data, validation reports, and safe-haven analysis. Full details are documented in:

- [docs/UTTARAKHAND_REGION.md](docs/UTTARAKHAND_REGION.md)
- [docs/BLE_SUBSYSTEM.md](docs/BLE_SUBSYSTEM.md)
- [docs/STAGE8_SYNC_RETRY_DESIGN.md](docs/STAGE8_SYNC_RETRY_DESIGN.md)

## Architecture and design principles

GeoRescueX follows a layered design model built around resilience:

1. Offline first
2. Local reliability
3. Safety over distance
4. Secure by design
5. Regional scalability
6. Graceful failure
7. Separation of concerns

## Technology stack

- Android: Kotlin, Android SDK, Jetpack, WorkManager, Location APIs
- Mapping: OpenStreetMap, offline map tiles, local route graphs
- Routing: custom A* engine over region-scoped graphs
- Local storage: Room / SQLite
- Cloud: Firebase Authentication, Firestore, custom claims
- Connectivity layer: WorkManager + retry-aware synchronization

## Project status

This project is a working prototype under active development, with strong offline-first foundations already implemented.

Currently demonstrated and documented:

- offline SOS and GPS functionality
- local safe-route navigation with regional graph data
- offline OSM and map rendering
- BLE-based multi-hop emergency relay
- admin dashboard and incident monitoring
- Firebase sync and deferred retry workflows

## App preview

| Login | Register | Dashboard |
|---|---|---|
| ![Login](Images/login.jpg) | ![Register](Images/register.jpg) | ![Dashboard](Images/dashboard.jpg) |

| Safe Route (Uttarakhand) | Admin Panel |
|---|---|
| ![Safe Route](Images/safe-route.jpg) | ![Admin](Images/admin.jpg) |

## Core user experience

### Authentication

- Firebase-based sign-in and registration flow
- user-specific records and emergency scopes
- core emergency workflows remain available even offline

### Safe route

- offline-safe evacuation routing for Uttarakhand
- route planning across region graph data
- hazard-aware rerouting
- map-based start and destination selection

### Admin panel

- live incident and SOS dashboard
- hazard and road-block monitoring
- sync status visibility
- emergency record detail inspection

## Repository structure

```text
.
├── app/
│   ├── src/
│   ├── build.gradle.kts
│   └── google-services.json
├── docs/
│   ├── BLE_SUBSYSTEM.md
│   ├── STAGE8_SYNC_RETRY_DESIGN.md
│   └── UTTARAKHAND_REGION.md
├── Images/
├── output/
├── source/
├── tools/
│   ├── sample-region/
│   └── uttarakhand/
├── README.md
├── README-ADMIN-PROVISIONING.md
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew
├── gradlew.bat
├── database.rules.json
├── LICENSE
└── .gitignore
```

## Documentation

The repository includes deeper technical documentation for operators and contributors:

- [README-ADMIN-PROVISIONING.md](README-ADMIN-PROVISIONING.md) — admin provisioning and Firebase access setup
- [docs/UTTARAKHAND_REGION.md](docs/UTTARAKHAND_REGION.md) — regional routing graph and validation notes
- [docs/BLE_SUBSYSTEM.md](docs/BLE_SUBSYSTEM.md) — emergency BLE relay system design
- [docs/STAGE8_SYNC_RETRY_DESIGN.md](docs/STAGE8_SYNC_RETRY_DESIGN.md) — background sync design and failure handling

## Getting started

1. Clone the repository.
2. Open the project in Android Studio.
3. Ensure `app/google-services.json` is available for Firebase connectivity.
4. Connect physical Android devices for testing.
5. Run the app in debug mode using Android Studio or Gradle.

### Android / Gradle

```bash
./gradlew :app:testDebugUnitTest
```

### Build notes

- Android Studio AGP 8.13 / Kotlin 2.2
- `minSdk 24`
- the project includes a workaround for OneDrive-synced build directories via `GEORESCUX_BUILD_DIR`

## Testing the BLE mesh (conceptual validation flow)

Follow a physical-device test to validate the emergency relay chain:

1. Phone A triggers SOS while offline.
2. Phone B receives and relays the emergency packet.
3. Phone C continues the multi-hop path.
4. Phone D acts as the online gateway and syncs data to Firebase.
5. Admin panel confirms the incident is visible in the cloud.

This validates a realistic disaster scenario: offline relay + later cloud ingestion.

## Team

Built for Smart India Hackathon 2026 — Problem Statement SIH26206 (Disaster Management, Software Track) by:

- Sanidhya
- Karan
- Rudraksh
- Shobhit

## License

This project is licensed under the MIT License.

---

<p align="center">
  <strong>GeoRescueX</strong> — resilient emergency infrastructure when the world goes dark.
</p>
