# GeoRescuX

## SIH2026-BuildForIndia_2.0

GeoRescuX — an offline-first disaster-response Android app for SOS, safe routing, and emergency coordination in low-connectivity environments.

The project is built around a resilient local-first architecture: device GPS, offline map data, region-scoped routing graphs, hazard-aware route evaluation, and delayed cloud synchronization when connectivity is available.

## Offline map regions

The map/routing layer is fully local (OpenStreetMap-derived graphs; Firebase is never used for maps or routing).

| Region | RegionId | Status |
|---|---|---|
| Connaught Place, New Delhi (demo) | `sample-region` | bundled since Stage 7B-1 |
| **Uttarakhand (full state + border buffer)** | `uttarakhand` | **bundled since Stage 7B-4** — 117,972 nodes / 138,164 edges / 60,272 km of real OSM roads, 64 real safe havens |

The Uttarakhand dataset, its processing pipeline, verification audit, validation results, and regeneration instructions are documented in **[docs/UTTARAKHAND_REGION.md](docs/UTTARAKHAND_REGION.md)** and **[tools/uttarakhand/README.md](tools/uttarakhand/README.md)**.

Region selection is persisted per device (`MapRegionSelectionStore`); a GPS fix inside a bundled region switches to it automatically (`MapRegionCatalog.regionForLocation`). The sample region remains functional without modification.

## Overview

During disasters such as floods, earthquakes, landslides, and other emergencies, conventional navigation and cloud-dependent emergency systems may become unreliable because of:

* Internet or mobile network failure
* Damaged or blocked roads
* Rapidly changing hazard conditions
* Limited access to updated information
* Difficulty identifying feasible safe locations
* Limited smartphone availability in remote communities

GeoRescuX addresses this with an offline-first architecture where critical emergency and routing functionality remains local, while Firebase is used for synchronization once connectivity returns.

## Core workflow

SOS -> GPS location -> connectivity checks -> local/updated data -> hazard and road analysis -> local routing engine -> safe route -> safe haven / emergency destination -> synchronization when connectivity returns.

## Key features

### 1. Emergency SOS

The SOS flow is designed to operate even when network connectivity is poor or absent. It supports local persistence of emergency information, GPS-based location association, and deferred synchronization.

### 2. GPS and location intelligence

The app uses device GPS to track the user, capture SOS locations, and drive routing decisions. GPS is distinct from internet connectivity and should continue to function even when the network is unavailable.

### 3. OpenStreetMap-based mapping

GeoRescuX uses OpenStreetMap-derived geographic data for roads, user location, hazards, blocked roads, emergency resources, and safe-route visualization.

### 4. Offline-first routing

Rather than selecting the shortest path blindly, the routing engine evaluates hazards, blocked roads, and region-specific conditions to determine a safe and feasible route. Offline maps and route graphs remain available locally.

### 5. Regional route graphs

GeoRescuX uses region-scoped routing graphs rather than a single global graph. This improves data organization, storage discipline, and regional scalability.

### 6. Firebase sync and WorkManager

Firebase acts as a synchronization layer, not the only source of truth for immediate response. Pending local changes are retried using WorkManager when connectivity returns.

### 7. Admin-aware security model

Administrative access is enforced with Firebase custom claims and trusted backend infrastructure rather than client-side flags.

### 8. BLE emergency mesh (raw GATT subsystem)

A dedicated raw-BLE subsystem (`data/ble` + `domain/ble`, see **[docs/BLE_SUBSYSTEM.md](docs/BLE_SUBSYSTEM.md)**) provides a diagnosable phone-to-phone emergency transport alongside the Google Nearby relay: a custom GATT service (RX/TX characteristics), UUID-filtered scanning, nameless advertising, structured emergency packets with unique `packetId`s (idempotent), durable deduplication, hop-based TTL (default 5), store-and-forward, and a two-device diagnostic screen with single-tag logging (`GeoRescuX-BLE`). BLE never replaces Firebase: received SOS packets are persisted into the same local store the existing sync engine uploads when Internet returns, and BLE failure can never block or cancel SOS activation.

## Architecture

GeoRescuX follows a layered model separating presentation, domain logic, and data access:

Presentation -> Map UI
Domain -> Routing engine, hazard logic, SOS flow
Data -> Local storage, regional graphs, Firebase sync

This keeps emergency functionality resilient even when the network is unavailable.

## Design principles

1. Offline first
2. Local reliability
3. Safety over distance
4. Secure by design
5. Regional scalability
6. Graceful failure
7. Separation of concerns

## Technology stack

Android: Kotlin, Android SDK, Jetpack, WorkManager, location services
Mapping: OpenStreetMap, local map data, offline routing graphs
Backend: Firebase Authentication, Realtime Database, custom claims, security rules

## Project status

The current implementation is a prototype under active development. The core offline routing and map infrastructure are in place, with the Uttarakhand region and validation tooling documented in the regional docs.

## Building

Android Studio (AGP 8.13 / Kotlin 2.2), `minSdk 24`.

Unit tests: `./gradlew :app:testDebugUnitTest`

## Why GeoRescuX?

The central question is simple: what happens when a disaster occurs and the infrastructure around people fails?

GeoRescuX is designed to provide continuity of emergency operations even during weak or absent connectivity by combining local data, offline maps, GPS, safe routing, and deferred cloud sync.

## Important limitations

Offline capability is not equivalent to storing the entire world on-device. The required regional map data, routing graph, and hazard information must already be present locally. Real-world deployment still requires trained responders, operational validation, and appropriate governance.

---

# GeoRescuX

### Offline-first. Safety-aware. Rescue-focused.

> "When connectivity fails, the rescue system shouldn't."

