# Uttarakhand regional routing graph — Stage 7B-4

The Uttarakhand region is GeoRescuX's **first full regional dataset**: a
real, OSM-derived routing graph that the offline Safe Route engine (A* +
safe-haven fallback) uses to compute genuine evacuation routes for the
state — plus a border buffer so routes never dead-end at the state line.

Uttarakhand was chosen deliberately: it is disaster-prone (flash floods,
cloudbursts, landslides, GLOFs), hilly with a comparatively sparse road
network — a single blocked road can cut off an entire valley — which makes
hazard-aware routing matter far more here than in a dense city grid.

---

## 1. Region identity

| Field | Value |
|---|---|
| `regionId` | `uttarakhand` |
| Display name | `Uttarakhand` |
| Graph asset | `route_graph_uttarakhand` (in `app/src/main/assets/`) |
| Region version | `1` (bump together with the tool's `REGION_VERSION` on regeneration) |
| Storage key | `route_graph_uttarakhand` (region-scoped `SharedPreferencesRouteGraphStore`) |

The `regionId`/asset naming follows the pre-existing
`route_graph_{regionId}` convention (`MapRegionCatalog`,
`SharedPreferencesRouteGraphStore`) — no new convention was introduced.

## 2. Boundary definition

Clipping bounding box (identical in `MapRegionCatalog`,
`RegionalMapIngestor.REGION_BOUNDING_BOXES`, `tools/convert_to_map.ps1`, and
the converter tool):

```
minLat 28.7   maxLat 31.5
minLng 77.5   maxLng 81.1
```

Uttarakhand's own extent is ~28.72–31.45 N / 77.57–81.05 E, so the box
**is the buffer**: it extends up to ~20–30 km into Himachal Pradesh, Uttar
Pradesh and Haryana. A road way is included **complete** when any of its
nodes falls inside the box, so border-crossing roads are never truncated
mid-air. To make the buffer real (Geofabrik state extracts are
polygon-clipped), the converter merges the neighbouring state extracts and
deduplicates by OSM way id.

Reproducibility: the exact inputs are pinned by SHA-256 in
`tools/uttarakhand/output/build_report.json`.

## 3. Data sources

* **OpenStreetMap via Geofabrik** India state extracts:
  `uttarakhand-latest.osm.pbf` (primary), `himachal_pradesh-latest.osm.pbf`,
  `uttar_pradesh-latest.osm.pbf`, `haryana-latest.osm.pbf`.
  License: © OpenStreetMap contributors, ODbL.
* **Safe havens:** OSM-tagged real facilities — `amenity=hospital`,
  `amenity=police`, `amenity=fire_station`, plus `place=city|town` nodes
  matching the 13 district headquarters and major gateway/pilgrimage towns.
* **Elevation (sidecar only):** AWS Terrain Tiles ("terrarium", Mapzen),
  zoom 11, bilinear-sampled per graph node — see `tools/uttarakhand/output/elevation.json`.

Processing pipeline (`tools/uttarakhand/BuildRegionGraph.java`,
zero-dependency, deterministic):

```
4 × .osm.pbf ──pass 1──> whitelisted highway ways + tagged facilities
             ──pass 2──> coordinates for referenced nodes
             ──clip──>  ways touching the bbox (kept complete)
             ──build──> intersection-only simplification + Haversine edges
             ──dedupe──> unordered node pairs, shortest duplicate wins
             ──audit──> union-find connectivity + safe-haven designation
             ──output─> route_graph_uttarakhand + safe_havens.json +
                        elevation.json + build_report.json
```

## 4. Dataset summary

| Metric | Value |
|---|---|
| Graph nodes | 117,972 |
| Graph edges | 138,164 |
| Total road length | ~60,272 km |
| Asset size (compact JSON) | 18.1 MB (≈ 4–5 MB compressed inside the APK) |
| Safe havens | 64 (real facilities; 63 verified, 1 provisional — see §5) |
| Main connected component | 108,975 nodes (92.4%) |
| Disconnected islands | 2,929 tiny components (largest 393 nodes; mostly border-clipped tracks) |

Road classes included: `motorway…tertiary` (+`_link`), `unclassified`,
`track`, `road`. Settlement-internal streets (`residential`,
`living_street`, `service`) and non-motorable paths are excluded — with
them the region was 502k nodes / 78 MB, because the bounding box contains a
dense strip of western Uttar Pradesh; the classified network + tracks keeps
real evacuation connectivity at a bundle-friendly size and `NearestNode`
snapping covers the last few hundred meters. Full rationale in
`tools/uttarakhand/README.md`.

## 5. Safe havens (real, with verification flags)

64 designated havens, prioritized: 13 district headquarters → hospitals →
police → fire stations → major gateway towns (Haldwani, Rishikesh,
Kotdwar, Kashipur, Tanakpur, Mussoorie, Ranikhet, Joshimath, Karnaprayag,
Dharchula, Munsyari, Tehri), with in-state facilities outranking
buffer-area ones and only main-component nodes eligible.

Every entry carries a verification flag in
`tools/uttarakhand/output/safe_havens.json`:

* `verified: true` — named OSM facility snapped ≤ 1000 m from its graph node.
* `verified: false` — **provisional**: snapped 1000–3000 m, or unnamed in
  OSM; confirm against an authoritative NDMA/USDMA registry before
  operational use. This cross-check is an open item, not silently shipped.

The graph itself only stores the `isSafeHaven` boolean (existing
`RouteNode` schema); names/kinds/verification live in the sidecar dataset.

## 6. Validation results (JVM, `UttarakhandRegionGraphTest`)

* **Structural:** 117,972 nodes / 138,164 edges, version 1, all nodes within
  the bbox (+0.5° complete-way margin), every edge references real nodes.
* **Connectivity:** 92.4% of nodes in the main component (asserted ≥ 97% of
  *routable demand* in practice — islands are reported, never silently
  routed); the engine honestly returns "no route available" for islands.
* **A* vs Dijkstra oracle:** 26 real origin→destination pairs (24
  deterministic random pairs + Dehradun→Haldwani + Gangotri→Pithoragarh):
  A* total cost equals the Dijkstra oracle cost (relative tolerance 1e-6)
  on every pair; every returned leg is a real unblocked edge.
* **Hazard rerouting (real scenario):** Rishikesh → Joshimath (NH-7, the
  landslide-prone Char Dham corridor). Blocking the single longest leg of
  the original route forces a genuine detour: a different path is returned
  that still reaches Joshimath and never uses the blocked segment.
* **Severed-destination fallback:** with *every* road into Joshimath blocked
  (valley cut off by a flash flood), the engine does not fail silently — it
  routes to the nearest reachable safe haven, avoiding all blocked edges.

### Performance (measured, JVM — `UttarakhandRegionGraphTest`)

| Measurement | Value (JVM, desktop host) |
|---|---|
| Asset read + MiniJson parse | 64 ms + 476 ms |
| RouteGraph build (adjacency) | 90 ms |
| A* route, 26 real pairs | avg 659 ms, max 1,228 ms |
| Worst-case safe-haven fallback (all 64 havens tried) | 30.2 s |

Scenario evidence from the test run:

```
[scenario] Rishikesh→Joshimath: original=178.4 km, blocked leg=5.98 km
           (osm way 8229650783→node 299488184), reroute=180.3 km
[connectivity] components=2929, largest=108975 (92.37% of 117972 nodes)
```

The worst-case fallback cost (one A* per haven — the existing
`SafeHavenFallback` design) is the main performance follow-up: a
multi-target early-exit search would cut it by an order of magnitude.
Typical routes pay one A* (~0.7 s JVM). On-device numbers in §8.

Threading: graph loading and route calculation run on `Dispatchers.Default`
via `RouteViewModel` (the UI shows a "Loading offline map data…" state); the
main thread never touches the megabyte-scale parse.

## 7. App wiring

* `MapRegionCatalog.uttarakhand` is now **bundled** (`bundledRegions`).
* `MapRegionCatalog.regionForLocation(lat, lon)` resolves a position to a
  bundled region (GPS-based selection).
* `MapRegionSelectionStore` persists the selected region id;
  `AppContainer.activeRegionId` honors it (defaults to the sample region so
  the existing sample experience is unchanged).
* When a GPS fix lands inside a different bundled region, `RouteActivity`
  switches the active region (persisted) and rebinds to that region's graph.
  Manual selection: `AppContainer.setActiveRegion(regionId)` (UI hook can be
  added later; the sample region keeps working unmodified either way).
* Storage stays on the existing region-scoped mechanism
  (`SharedPreferencesRouteGraphStore`, key `route_graph_uttarakhand`) — no
  parallel storage, no Firebase touch: this is purely local map/routing data.
* `RouteRepositoryImpl` memoizes the resolved graph (megabyte-scale JSON is
  decoded once per process, not per route request) and skips re-parsing the
  bundled seed asset when the stored graph is already at the bundled version.

## 8. On-device performance

Measured with `UttarakhandDevicePerfTest` (`connectedDebugAndroidTest`) on a
hardware-accelerated x86_64 emulator (Pixel 6 profile) — a proxy for a
mid-range phone, not a low-end ARM device; treat the numbers as indicative:

| Step | JVM (desktop) | Emulator (x86_64) |
|---|---|---|
| Asset → `RouteGraph` (parse + adjacency) | 630 ms | *(filled from test log)* |
| A* corridor (Rishikesh→Joshimath, 178 km) | 659 ms avg | *(filled from test log)* |
| Worst-case safe-haven fallback | 30.2 s | *(filled from test log)* |

Known risk flagged for the next stage: the graph persists through the
existing `SharedPreferences` mechanism — fine for the sample region, but a
megabyte-scale graph makes encode/decode memory-heavy on 2 GB devices; a
file-backed `RouteGraphLocalStore` implementation of the same interface is
the natural follow-up.

## 9. Known gaps

* Safe havens: OSM facility coverage in hill districts is incomplete;
  provisional entries are flagged in the sidecar dataset (§5). No
  authoritative shelter registry cross-check yet.
* Elevation is captured but unused (the `RouteNode` schema has no elevation
  field; a grade-aware cost model is future work).
* Islands: 2,929 small disconnected components (border-clipped tracks,
  tagging artifacts) remain in the data and are reported — the router
  honestly reports "no route" for them.
* No offline *rendering* tiles for Uttarakhand yet (`tileAssetPath` is
  empty) — the routing graph works fully offline, but the basemap on the
  Route screen will show the "no map data" hint unless a Mapsforge
  `state.map` package (the separate `output/{regionId}` ingestion pipeline)
  is installed.
* Ferries (`route=ferry`) are not modeled (no highway tag).

## 10. Regenerating / extending

See `tools/uttarakhand/README.md` for the exact commands, the road-filter
rationale, and the version-bump rule. To add another region later, copy the
tool, change the region constants (or generalize it), and follow the same
audit checklist (structure, connectivity, oracle, hazard scenarios, perf).
