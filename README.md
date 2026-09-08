# SIH2026-BuildForIndia_2.0

GeoRescuX — an offline-first disaster-response Android app (NDMA/NDRF
framing): SOS flow, BLE mesh relay, emergency contacts and an offline Safe
Route engine (hazard-aware A* over region-scoped road graphs with a
safe-haven fallback).

## Offline map regions

The map/routing layer is fully local (OpenStreetMap-derived graphs; Firebase
is never used for maps or routing).

| Region | RegionId | Status |
|---|---|---|
| Connaught Place, New Delhi (demo) | `sample-region` | bundled since Stage 7B-1 |
| **Uttarakhand (full state + border buffer)** | `uttarakhand` | **bundled since Stage 7B-4** — 117,972 nodes / 138,164 edges / 60,272 km of real OSM roads, 64 real safe havens |

The Uttarakhand dataset, its processing pipeline, verification audit,
validation results (Dijkstra-oracle cross-check, real landslide-reroute
scenario, performance) and regeneration instructions are documented in
**[docs/UTTARAKHAND_REGION.md](docs/UTTARAKHAND_REGION.md)** and
**[tools/uttarakhand/README.md](tools/uttarakhand/README.md)**.

Region selection: the active region is persisted per device
(`MapRegionSelectionStore`); a GPS fix inside a bundled region switches to it
automatically (`MapRegionCatalog.regionForLocation`). The sample region keeps
working unmodified.

Building: Android Studio (AGP 8.13 / Kotlin 2.2), `minSdk 24`. Unit tests:
`./gradlew :app:testDebugUnitTest`.
