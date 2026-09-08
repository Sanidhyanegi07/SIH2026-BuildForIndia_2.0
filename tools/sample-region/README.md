# GeoRescuX sample region (Stage 7B-1)

This directory contains the development tooling that produced the bundled
offline map data for the **sample region**:

* **Region:** Connaught Place, New Delhi, India (small demonstration area)
* **Bounds:** 28.6290, 77.2130 → 28.6365, 77.2270 (lat/lng)
* **Data source:** OpenStreetMap via the Overpass API
  (highway ways + member nodes)
* **Attribution:** Map data © OpenStreetMap contributors (ODbL)
* **Status:** small real OSM-derived demonstration region — NOT India-wide data

## Files

* `overpass-raw.json` — raw Overpass API result (input, kept for provenance)
* `ConvertOsm.java` — deterministic converter (graph + offline tiles)
* `libs/json.jar`, `libs/sqlite-jdbc.jar` — converter-only dev dependencies
  (downloaded from Maven Central; **not** app dependencies)

## Outputs (written into `app/src/main/assets/maps/sample-region/`)

* `graph.json` — real OSM-derived road graph for the Stage 7A
  `RegionGraphLoader` / A* engine (nodes with real coordinates, edges with
  real Haversine road distances, two safe havens at real landmarks:
  Central Park and Shivaji Stadium area)
* `tiles.sqlite` — osmdroid offline tile archive rendered from the same OSM
  data (zooms 13–17, schema `tiles(key INTEGER PRIMARY KEY, provider TEXT,
  tile BLOB)`, key = `((z << z) + x) << z + y`, provider
  `sample-region-offline`)

## Regenerating

```bash
# 1. Fetch OSM data (Overpass API)
curl -s -X POST -o overpass-raw.json \
  --data-urlencode 'data=[out:json][timeout:90];(way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|pedestrian)$"](28.6290,77.2130,28.6365,77.2270););(._;>;);out body;' \
  "https://overpass-api.de/api/interpreter"

# 2. Convert (any JDK 11+)
java -cp "libs/json.jar;libs/sqlite-jdbc.jar" ConvertOsm.java \
  overpass-raw.json ../../app/src/main/assets/maps/sample-region/graph.json \
  ../../app/src/main/assets/maps/sample-region/tiles.sqlite
```
