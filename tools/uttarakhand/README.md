# Uttarakhand regional routing graph (Stage 7B-4)

Development tooling that produces the bundled offline routing graph for the
**Uttarakhand region** — the first full regional dataset in GeoRescuX.

* **RegionId:** `uttarakhand` (must match `MapRegionCatalog.uttarakhand`)
* **Bounds:** 28.7–31.5 N, 77.5–81.1 E (bounding box; deliberately wider than
  the state extent ~28.72–31.45 N / 77.57–81.05 E so border roads stay connected)
* **Data source:** OpenStreetMap via Geofabrik India state extracts:
  `uttarakhand-latest.osm.pbf` (primary) plus
  `himachal_pradesh-latest.osm.pbf`, `uttar_pradesh-latest.osm.pbf` and
  `haryana-latest.osm.pbf` for the cross-border buffer
* **Attribution:** Map data © OpenStreetMap contributors (ODbL)
* **Status:** real OSM-derived regional dataset — safe havens are real,
  OSM-tagged facilities (see the verification notes in
  `output/safe_havens.json`)

## Files

* `BuildRegionGraph.java` — deterministic, **zero-dependency** converter
  (hand-rolled OSM PBF/protobuf reader; needs only a JDK 17+, no libraries)
* `output/build_report.json` — provenance of the last build: input SHA-256s,
  counts, connectivity report, safe-haven town audit
* `output/safe_havens.json` — the real safe-haven dataset with a
  `verified`/provisional flag per entry
* `output/places.json` — the offline place-search index: 19k+ named OSM
  places (cities, towns, villages, hamlets) and facilities, each snapped to
  its routing-graph node. Bundled as `maps/uttarakhand-places.json`; the
  Safe Route screen autocompletes these names and resolves them to graph
  nodes fully offline.
* `output/elevation.json` — optional per-node elevation sidecar (meters,
  sampled from the AWS Terrain Tiles "terrarium" layer, zoom 11). The
  `RouteNode` schema has no elevation field, so this is captured for a
  future cost model only — the routing engine does not read it yet.

## What the converter does

1. **Pass 1** scans every input `.osm.pbf` (hand-rolled protobuf reader):
   collects all ways whose `highway` tag is in the whitelist, plus tagged
   facility objects (`amenity=hospital|police|fire_station`,
   `place=city|town` matching the district-HQ/major-town name lists).
2. **Pass 2** resolves coordinates for the nodes referenced by those ways.
3. A way is **kept when any of its nodes lies inside the region bounding
   box**; the complete way is kept, so roads crossing the state border are
   never truncated mid-air. Ways are deduplicated across the overlapping
   extracts by OSM way id.
4. Graph building mirrors the Stage 7B-1 sample tool: keep way endpoints and
   shared intersections, create edges between consecutive kept nodes with
   Haversine distances (identical formula to the A* engine), dedupe
   unordered node pairs (shortest duplicate wins), sort everything
   deterministically.
5. **Safe havens:** each facility is snapped to the nearest graph node of the
   largest connected component; the best `MAX_SAFE_HAVENS` (64) become
   `isSafeHaven = true` in `graph.json`, priority: district HQ towns >
   hospitals > police > fire stations > major towns; inside the state core
   beats the buffer periphery; shortest snap distance breaks ties. Every
   entry and its verification status lands in `output/safe_havens.json`.
6. **Connectivity report:** union-find over the final graph reports the
   number of connected components and island sizes (islands are *reported*,
   not silently dropped — the engine honestly answers "no route" for them).
7. Outputs `route_graph_uttarakhand` (compact `RegionGraphLoader` JSON) and
   the sidecars/report.

## Road filter (documented decision)

The whitelist keeps the **classified motorable network**
(`motorway…unclassified`, incl. links) **plus `track`** — in Uttarakhand many
settlements are connected only by a track — and excludes settlement-internal
streets (`residential`, `living_street`, `service`) and non-motorable paths
(`footway`, `path`, `steps`, `cycleway`).

Rationale: the first build that included `residential`/`service` produced
502,000 nodes / 78 MB because the bounding box includes a dense strip of
western Uttar Pradesh. The classified network + tracks keeps genuine
evacuation connectivity (national/state highways, district roads, PMGSY
village roads, tracks) at 18 MB, and `NearestNode` snapping covers the last
few hundred meters from any point to the network. If a future sub-region
split (e.g. per-district graphs) lands, the finer classes can be re-added.

## Regenerating

```bash
# 1. Refresh the Geofabrik extracts into ../../source/ (or reuse the current ones)
curl -L -o ../../source/uttarakhand-latest.osm.pbf \
  https://download.geofabrik.de/asia/india/uttarakhand-latest.osm.pbf
# (repeat for himachal_pradesh, uttar_pradesh, haryana)

# 2. Build graph + safe havens + report (+ elevation sidecar)
cd tools/uttarakhand
java -Xmx6g BuildRegionGraph.java \
  ../../source/uttarakhand-latest.osm.pbf \
  ../../source/himachal_pradesh-latest.osm.pbf \
  ../../source/uttar_pradesh-latest.osm.pbf \
  ../../source/haryana-latest.osm.pbf \
  --out-graph ../../app/src/main/assets/route_graph_uttarakhand \
  --out-dir output --elevation

# 3. Run the app-side acceptance tests
cd ../..
./gradlew :app:testDebugUnitTest --tests "com.example.georescux.data.maps.UttarakhandRegionGraphTest"
```

Any JDK 17+ works for the converter (single-file source launch, no
dependencies). The build is deterministic: identical inputs produce an
identical graph. `--debug-towns` (any position) additionally prints every
`place=city|town` node found in pass 1 — useful when auditing the
safe-haven town matching.

### Offline basemap (state.map)

The routing graph is separate from the visual basemap. The bundled vector
basemap `app/src/main/assets/maps/uttarakhand-state.map` (Mapsforge format,
~26 MB) is generated with Osmosis + the mapsforge map-writer plugin:

```bash
# One-time: osmosis 0.49.2 from GitHub releases into tools/osmosis/,
# mapsforge-map-writer 0.20.0.jar from Maven Central into tools/osmosis/osmosis-0.49.2/lib/plugin/
cd tools/osmosis
JAVA_HOME=<jdk17+> ./osmosis-0.49.2/bin/osmosis.bat \
  --read-pbf file=../../source/uttarakhand-latest.osm.pbf \
  --mapfile-writer file=../uttarakhand/output/state.map \
  bbox=28.7,77.5,31.5,81.1 type=hd
# then copy output/state.map to app/src/main/assets/maps/uttarakhand-state.map
```

RouteActivity renders it via osmdroid's MapsForgeTileProvider (no network).

**When you regenerate, bump `REGION_VERSION`** in `BuildRegionGraph.java`
*and* `MapRegionCatalog.uttarakhand.version` together — the app upgrades a
stored graph only when the bundled seed's version is newer than the stored
one.

## Known gaps

* Safe havens are OSM-tagged facilities — OSM's hospital/relief-shelter
  coverage in the hill districts is incomplete. Entries are flagged
  `verified` (named facility, snapped ≤ 1000 m) vs provisional in
  `output/safe_havens.json`; cross-checking against an authoritative
  NDMA/USDMA shelter registry is an open item.
* District-HQ / major-town audit: see `matchedHavenTowns` /
  `missingHavenTowns` in `output/build_report.json`. A missing town means
  OSM has no `place=city|town` node with that exact name inside the bbox.
* Elevation is captured in `output/elevation.json` but unused by the engine
  (the `RouteNode` schema has no elevation field by design; a grade-aware
  cost model would be a schema + engine change).
* Small disconnected islands (typically short tracks clipped at the bbox or
  border) are kept and reported, not silently removed; the router honestly
  reports "no route available" for them.
