import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * GeoRescuX development tool — builds the bundled Uttarakhand regional
 * routing graph (Stage 7B-4) from real OpenStreetMap data.
 *
 * Reads one or more Geofabrik .osm.pbf extracts (Uttarakhand plus its
 * neighbours Himachal Pradesh, Uttar Pradesh and Haryana), keeps every
 * evacuation-relevant road touching the region bounding box (which extends
 * beyond the state line, so routes do not dead-end at the border), and
 * writes:
 *
 *   1. route_graph_uttarakhand — RegionGraphLoader-format graph for the
 *      Stage 7A RouteGraph / A* engine (same RouteNode/RouteEdge JSON
 *      schema as the Stage 7B-1 sample region, compact encoding).
 *   2. safe_havens.json — the real safe-haven dataset (district
 *      headquarters, hospitals, police and fire stations) with a
 *      verified/provisional flag per entry.
 *   3. elevation.json (optional, --elevation) — per-node elevation in
 *      meters sampled from the AWS Terrain Tiles "terrarium" layer.
 *      The RouteNode schema has no elevation field, so this is captured
 *      as a sidecar for a future cost model, not used by the engine.
 *   4. build_report.json — provenance (input SHA-256s), counts, highway
 *      class breakdown and the graph connectivity report.
 *
 * Every node and edge in the output graph traces back to the input OSM
 * data; nothing is synthesized. Data (c) OpenStreetMap contributors (ODbL).
 *
 * Zero external dependencies (hand-rolled PBF/protobuf reader) so the
 * region can be regenerated with any JDK 17+, e.g.:
 *   java -Xmx6g BuildRegionGraph.java <in1.pbf> [in2.pbf ...] \
 *        --out-graph <graph.json> --out-dir <dir> [--elevation]
 */
public class BuildRegionGraph {

    // ---- Region definition (mirrors MapRegionCatalog.uttarakhand) ----

    static final String REGION_ID = "uttarakhand";
    static final String REGION_NAME = "Uttarakhand";
    static final int REGION_VERSION = 1;
    // Bounding box that clips the OSM data. Deliberately wider than the
    // state itself (state extent ~28.72-31.45 N, 77.57-81.05 E) so roads
    // crossing into Himachal Pradesh / Uttar Pradesh / Haryana stay
    // connected instead of dead-ending at the administrative border.
    static final double MIN_LAT = 28.7, MAX_LAT = 31.5;
    static final double MIN_LNG = 77.5, MAX_LNG = 81.1;
    static final String REGION_SOURCE =
            "OpenStreetMap via Geofabrik India state extracts (uttarakhand, himachal_pradesh, uttar_pradesh, haryana)";
    static final String REGION_ATTRIBUTION = "© OpenStreetMap contributors (ODbL)";

    // ---- Road filter ----

    /**
     * Evacuation-relevant road classes: the classified motorable network
     * (motorway..unclassified, incl. links) plus tracks — in Uttarakhand
     * many settlements are connected only by a track. Settlement-internal
     * streets (residential/living_street/service) and non-motorable paths
     * (footway/path/steps/cycleway) are excluded: this is a state-scale
     * evacuation router, and NearestNode snapping covers the last few
     * hundred meters to the classified network. The full detail pushed the
     * region to 502k nodes / 78 MB; this filter keeps connectivity while
     * staying bundle-sized.
     */
    static final Set<String> HIGHWAY_WHITELIST = new HashSet<>(Arrays.asList(
            "motorway", "motorway_link",
            "trunk", "trunk_link",
            "primary", "primary_link",
            "secondary", "secondary_link",
            "tertiary", "tertiary_link",
            "unclassified", "track", "road"));

    // ---- Safe-haven definition ----

    /** District headquarters of Uttarakhand's 13 districts (audited in the report). */
    static final String[] DISTRICT_HQ_NAMES = {
            "Almora", "Bageshwar", "Gopeshwar", "Champawat", "Dehradun", "Haridwar",
            "Nainital", "Pauri", "Rudraprayag", "New Tehri", "Pithoragarh",
            "Rudrapur", "Uttarkashi"
    };

    /** Major gateway / pilgrimage-belt towns used as additional havens (lower priority). */
    static final String[] MAJOR_TOWN_NAMES = {
            "Haldwani", "Rishikesh", "Kotdwar", "Kashipur", "Tanakpur", "Mussoorie",
            "Ranikhet", "Joshimath", "Karnaprayag", "Dharchula", "Munsyari", "Munsiari", "Tehri"
    };

    static final int HAVEN_PRIORITY_TOWN = 0;
    static final int HAVEN_PRIORITY_HOSPITAL = 1;
    static final int HAVEN_PRIORITY_POLICE = 2;
    static final int HAVEN_PRIORITY_FIRE = 3;
    static final int HAVEN_PRIORITY_MAJOR_TOWN = 4;

    /** Max snap distance facility -> graph node to be designated a safe haven. */
    static final double HAVEN_MAX_SNAP_METERS = 3000.0;
    /** Snap distance below which a named OSM facility counts as verified. */
    static final double HAVEN_VERIFIED_SNAP_METERS = 1000.0;
    /**
     * The state's own extent (tighter than the buffered region bbox). When
     * the haven cap is filled, facilities inside the state core win over
     * buffer-area facilities — otherwise dense plains hospitals crowd out
     * the hill-district facilities this app exists for. Floors chosen to
     * exclude the Meerut/Amroha plains belt while keeping Rudrapur,
     * Tanakpur and Kashipur (Udham Singh Nagar) inside.
     */
    static final double CORE_MIN_LAT = 28.95, CORE_MAX_LAT = 31.4;
    static final double CORE_MIN_LNG = 77.75, CORE_MAX_LNG = 81.0;
    /** Maximum designated safe havens (SafeHavenFallback runs one A* per
     *  haven when a destination is unreachable — keep the worst case sane). */
    static final int MAX_SAFE_HAVENS = 64;

    static final double EARTH_RADIUS_M = 6371000.0;

    // ---- Primitive long->int map (node id -> internal index) ----

    static final class LongIndex {
        long[] keys;
        int[] values;
        int mask;
        int size;

        LongIndex(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected)) << 1;
            keys = new long[cap];
            values = new int[cap];
            mask = cap - 1;
        }

        int get(long key) {
            int i = mix(key) & mask;
            while (keys[i] != 0) {
                if (keys[i] == key) return values[i];
                i = (i + 1) & mask;
            }
            return key == 0 ? zeroValue : -1;
        }

        int zeroValue = -1;

        void put(long key, int value) {
            if (key == 0) { zeroValue = value; return; }
            if ((size + 1) * 3 >= keys.length * 2) grow();
            int i = mix(key) & mask;
            while (keys[i] != 0) {
                if (keys[i] == key) { values[i] = value; return; }
                i = (i + 1) & mask;
            }
            keys[i] = key;
            values[i] = value;
            size++;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldVals = values;
            int cap = keys.length << 1;
            keys = new long[cap];
            values = new int[cap];
            mask = cap - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] == 0) continue;
                int j = mix(oldKeys[i]) & mask;
                while (keys[j] != 0) j = (j + 1) & mask;
                keys[j] = oldKeys[i];
                values[j] = oldVals[i];
            }
        }

        static int mix(long k) {
            k = (k ^ (k >>> 33)) * 0xff51afd7ed558ccdL;
            k = (k ^ (k >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return (int) (k ^ (k >>> 33));
        }
    }

    /** Open-addressing set of longs (needed-node ids for pass 2). */
    static final class LongSet {
        long[] keys;
        int mask;
        int size;

        LongSet(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected)) << 1;
            keys = new long[cap];
            mask = cap - 1;
        }

        boolean contains(long key) {
            if (key == 0) return false; // OSM ids are positive
            int i = mix(key) & mask;
            while (keys[i] != 0) {
                if (keys[i] == key) return true;
                i = (i + 1) & mask;
            }
            return false;
        }

        void add(long key) {
            if (key == 0) return;
            if ((size + 1) * 3 >= keys.length * 2) grow();
            int i = mix(key) & mask;
            while (keys[i] != 0) {
                if (keys[i] == key) return;
                i = (i + 1) & mask;
            }
            keys[i] = key;
            size++;
        }

        private void grow() {
            long[] old = keys;
            int cap = keys.length << 1;
            keys = new long[cap];
            mask = cap - 1;
            size = 0;
            for (long k : old) if (k != 0) add(k);
        }

        static int mix(long k) { return LongIndex.mix(k); }
    }

    // ---- Intermediate data ----

    static final class Way {
        long id;
        String highway;
        long[] refs;
    }

    static final class Facility {
        int priority;
        String kind;      // district_hq | hospital | police | fire_station | town
        String name;
        String osmType;   // node | way
        long osmId;
        double lat = Double.NaN;
        double lon = Double.NaN;
        long[] wayRefs;   // for way facilities (centroid computed later)
    }

    // Graph nodes: internal index -> data.
    static LongIndex nodeIndex = new LongIndex(1 << 22);
    static double[] nodeLat = new double[1 << 20];
    static double[] nodeLon = new double[1 << 20];
    static long[] reverseIds = new long[1 << 20];
    static int nodeCount = 0;

    static String[] stringTable = new String[0];
    static int pbfGranularity = 100;
    static long pbfLatOffset = 0;
    static long pbfLonOffset = 0;

    static final Map<String, Integer> highwayWayCount = new TreeMap<>();
    static long osmNodeScanned;
    static long wayScanned;
    static final Map<Long, Way> acceptedWays = new LinkedHashMap<>(); // by OSM way id, first wins
    static final List<Facility> facilities = new ArrayList<>();
    static final Set<String> matchedHqNames = new HashSet<>();
    static LongSet pendingCoordIds = new LongSet(1 << 21);
    static double totalLengthKm;

    public static void main(String[] args) throws Exception {
        List<String> pbfPaths = new ArrayList<>();
        String outGraph = null;
        String outDir = ".";
        boolean elevation = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--out-graph": outGraph = args[++i]; break;
                case "--out-dir": outDir = args[++i]; break;
                case "--elevation": elevation = true; break;
                case "--debug-towns": pendingDebugTowns = true; break;
                default: pbfPaths.add(a);
            }
        }
        if (pbfPaths.isEmpty() || outGraph == null) {
            System.err.println("usage: java BuildRegionGraph.java <in1.osm.pbf> [in2...] "
                    + "--out-graph <graph.json> [--out-dir <dir>] [--elevation]");
            System.exit(2);
        }

        long t0 = System.currentTimeMillis();

        // Pass 1: whitelisted highway ways + tagged facility objects
        // (facility node coordinates are captured directly here).
        for (String pbf : pbfPaths) {
            System.out.println("[pass1] " + pbf);
            scanPbf(pbf, true);
        }
        System.out.println("pass1: " + osmNodeScanned + " node entries, " + wayScanned + " ways, "
                + acceptedWays.size() + " whitelisted ways, " + facilities.size() + " facilities");

        // Coordinates needed in pass 2: refs of accepted ways + facility ways.
        pendingCoordIds = new LongSet(acceptedWays.size() * 8 + 1024);
        List<Facility> wayFacilities = new ArrayList<>();
        for (Facility f : facilities) {
            if (f.wayRefs != null) {
                wayFacilities.add(f);
                for (long ref : f.wayRefs) pendingCoordIds.add(ref);
            }
        }
        for (Way w : acceptedWays.values()) {
            for (long ref : w.refs) pendingCoordIds.add(ref);
        }
        System.out.println("pass2 will resolve " + pendingCoordIds.size + " node ids");

        // Pass 2: resolve coordinates for the needed node ids only.
        for (String pbf : pbfPaths) {
            System.out.println("[pass2] " + pbf);
            scanPbf(pbf, false);
        }
        System.out.println("pass2: resolved " + nodeCount + " node coordinates");

        // A way is kept when at least one member node lies inside the region
        // bbox; the whole way is kept (border-crossing roads stay complete).
        List<Way> regionWays = new ArrayList<>();
        for (Way w : acceptedWays.values()) {
            if (wayTouchesBbox(w)) regionWays.add(w);
        }
        regionWays.sort((a, b) -> Long.compare(a.id, b.id));
        System.out.println("region ways (touching bbox): " + regionWays.size());

        // Facilities must themselves lie inside the bbox.
        List<Facility> regionFacilities = new ArrayList<>();
        for (Facility f : facilities) {
            if (f.wayRefs != null) {
                int kept = 0;
                double sLat = 0, sLon = 0;
                for (long ref : f.wayRefs) {
                    int idx = nodeIndex.get(ref);
                    if (idx < 0) continue;
                    double la = nodeLat[idx], lo = nodeLon[idx];
                    if (inBbox(la, lo)) { kept++; sLat += la; sLon += lo; }
                }
                if (kept > 0) {
                    f.lat = sLat / kept;
                    f.lon = sLon / kept;
                    regionFacilities.add(f);
                }
            } else if (!Double.isNaN(f.lat) && inBbox(f.lat, f.lon)) {
                regionFacilities.add(f);
            }
        }
        System.out.println("region facilities: " + regionFacilities.size());

        buildGraph(regionWays, regionFacilities, outGraph, outDir, pbfPaths, elevation);
        System.out.println("done in " + (System.currentTimeMillis() - t0) / 1000.0 + " s");
    }

    // ---- Graph construction (mirrors the Stage 7B-1 ConvertOsm pipeline) ----

    static void buildGraph(List<Way> regionWays, List<Facility> regionFacilities,
                           String outGraph, String outDir, List<String> pbfPaths,
                           boolean elevation) throws Exception {

        // 1. Distinct ways through each node (intersection test).
        int[] wayCountArr = new int[nodeCount];
        for (Way w : regionWays) {
            long prev = Long.MIN_VALUE;
            for (long ref : w.refs) {
                if (ref == prev) continue; // closed way: first==last counts once
                prev = ref;
                int idx = nodeIndex.get(ref);
                if (idx >= 0) wayCountArr[idx]++;
            }
        }

        // 2. Simplify: keep way endpoints and shared intersections only.
        List<int[]> keptWays = new ArrayList<>();
        for (Way w : regionWays) {
            int[] kept = new int[Math.min(w.refs.length, 4096)];
            int n = 0;
            for (int i = 0; i < w.refs.length; i++) {
                int idx = nodeIndex.get(w.refs[i]);
                if (idx < 0) continue; // node never resolved (clipped extract edge)
                boolean isEnd = i == 0 || i == w.refs.length - 1;
                if (isEnd || wayCountArr[idx] >= 2) {
                    if (n == 0 || kept[n - 1] != idx) kept[n++] = idx;
                }
            }
            if (n >= 2) keptWays.add(Arrays.copyOf(kept, n));
        }

        // 3. Edges between consecutive kept nodes; dedupe unordered pairs,
        //    shortest duplicate wins (same rule as the sample-region tool).
        //    Keyed by sorted node-id strings for determinism.
        Map<String, double[]> edgeMap = new TreeMap<>(); // "a|b" -> {distance, internalA, internalB}
        for (int[] kept : keptWays) {
            for (int i = 1; i < kept.length; i++) {
                int a = kept[i - 1], b = kept[i];
                if (a == b) continue;
                double distance = haversine(nodeLat[a], nodeLon[a], nodeLat[b], nodeLon[b]);
                String key = pairKey(a, b);
                double[] existing = edgeMap.get(key);
                if (existing == null || distance < existing[0]) {
                    edgeMap.put(key, new double[]{distance, a, b});
                }
            }
        }

        // 4. Deterministic output node order: sorted OSM id string (like the
        //    sample tool). Output index -> internal index.
        Map<String, Integer> idToInternal = new TreeMap<>();
        for (int[] kept : keptWays) {
            for (int internal : kept) idToInternal.put(Long.toString(internalId(internal)), internal);
        }
        String[] ids = idToInternal.keySet().toArray(new String[0]);
        int[] internalOfOut = new int[ids.length];
        Map<Integer, Integer> outOfInternal = new HashMap<>();
        for (int p = 0; p < ids.length; p++) {
            internalOfOut[p] = idToInternal.get(ids[p]);
            outOfInternal.put(internalOfOut[p], p);
        }

        // 5. Connectivity (union-find over output indices).
        int[] parent = new int[ids.length];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (double[] e : edgeMap.values()) {
            union(parent, outOfInternal.get((int) e[1]), outOfInternal.get((int) e[2]));
            totalLengthKm += e[0] / 1000.0;
        }
        Map<Integer, Integer> componentSizes = new TreeMap<>();
        for (int i = 0; i < ids.length; i++) {
            int root = find(parent, i);
            componentSizes.merge(root, 1, Integer::sum);
        }
        int largestComponent = 0;
        for (Map.Entry<Integer, Integer> e : componentSizes.entrySet()) {
            if (e.getValue() > largestComponent) {
                largestComponent = e.getValue();
                mainComponentRoot = e.getKey();
            }
        }
        List<Integer> islandSizes = new ArrayList<>(componentSizes.values());
        islandSizes.sort((a, b) -> Integer.compare(b, a));
        while (islandSizes.size() > 10) islandSizes.remove(islandSizes.size() - 1);

        // 6. Safe havens: snap real facilities to the nearest main-component node.
        Map<Integer, String[]> havenByNode = designateSafeHavens(
                regionFacilities, idToInternal, internalOfOut, parent);

        // 7. graph.json (compact RegionGraphLoader format).
        StringBuilder json = new StringBuilder(1 << 26);
        json.append("{\"region\":{\"id\":\"").append(REGION_ID)
                .append("\",\"name\":\"").append(REGION_NAME)
                .append("\",\"version\":").append(REGION_VERSION)
                .append(",\"source\":\"").append(REGION_SOURCE)
                .append("\",\"attribution\":\"").append(REGION_ATTRIBUTION)
                .append("\"},\"nodes\":[");
        for (int p = 0; p < ids.length; p++) {
            int internal = internalOfOut[p];
            if (p > 0) json.append(',');
            json.append("{\"id\":\"").append(ids[p])
                    .append("\",\"latitude\":").append(fmtCoord(nodeLat[internal]))
                    .append(",\"longitude\":").append(fmtCoord(nodeLon[internal]));
            if (havenByNode.containsKey(internal)) json.append(",\"isSafeHaven\":true");
            json.append('}');
        }
        json.append("],\"edges\":[");
        boolean first = true;
        for (double[] e : edgeMap.values()) {
            if (!first) json.append(',');
            first = false;
            json.append("{\"fromNodeId\":\"").append(internalId((int) e[1]))
                    .append("\",\"toNodeId\":\"").append(internalId((int) e[2]))
                    .append("\",\"distanceMeters\":").append(fmt1(e[0]))
                    .append('}');
        }
        json.append("]}");
        Files.write(Paths.get(outGraph), json.toString().getBytes("UTF-8"));

        // 8. Sidecars + report.
        writeSafeHavens(outDir, havenByNode);
        if (elevation) writeElevation(outDir, ids, internalOfOut);
        writeReport(outDir, pbfPaths, ids.length, edgeMap.size(), componentSizes.size(),
                largestComponent, islandSizes, outGraph);
    }

    static int mainComponentRoot = -1;

    static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /**
     * Designates havens: internal node index -> {name, kind, verified,
     * snapMeters, osmType, osmId}. One node hosts at most one haven (best
     * candidate wins); total capped at MAX_SAFE_HAVENS; only nodes of the
     * largest connected component are used, so the SafeHavenFallback never
     * targets an unreachable island.
     */
    static Map<Integer, String[]> designateSafeHavens(List<Facility> regionFacilities,
                                                       Map<String, Integer> idToInternal,
                                                       int[] internalOfOut, int[] parent) {
        // Internal-index -> output index for the component test.
        Map<Integer, Integer> outOfInternal = new HashMap<>();
        for (int p = 0; p < internalOfOut.length; p++) outOfInternal.put(internalOfOut[p], p);

        // Snap each facility to the nearest kept graph node.
        List<Object[]> snapped = new ArrayList<>(); // {priority, snapM, osmIdString, Facility, internalIdx}
        for (Facility f : regionFacilities) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (Map.Entry<String, Integer> e : idToInternal.entrySet()) {
                int idx = e.getValue();
                double d = haversine(f.lat, f.lon, nodeLat[idx], nodeLon[idx]);
                if (d < bestD) { bestD = d; best = idx; }
            }
            if (best >= 0 && bestD <= HAVEN_MAX_SNAP_METERS) {
                snapped.add(new Object[]{f.priority, bestD, Long.toString(f.osmId), f, best});
            }
        }
        snapped.sort((a, b) -> {
            int c = Integer.compare((int) a[0], (int) b[0]);
            if (c != 0) return c;
            // Facilities inside the state core outrank buffer-area ones.
            Facility fa = (Facility) a[3], fb = (Facility) b[3];
            c = Boolean.compare(!inCore(fa), !inCore(fb));
            if (c != 0) return c;
            c = Double.compare((double) a[1], (double) b[1]);
            if (c != 0) return c;
            return ((String) a[2]).compareTo((String) b[2]);
        });

        Map<Integer, String[]> havenByNode = new LinkedHashMap<>();
        int verifiedCount = 0;
        for (Object[] s : snapped) {
            if (havenByNode.size() >= MAX_SAFE_HAVENS) break;
            int internalIdx = (int) s[4];
            if (havenByNode.containsKey(internalIdx)) continue;
            Integer outIdx = outOfInternal.get(internalIdx);
            if (outIdx == null || find(parent, outIdx) != mainComponentRoot) continue; // island
            Facility f = (Facility) s[3];
            double snapM = (double) s[1];
            boolean verified = f.name != null && !f.name.isEmpty() && snapM <= HAVEN_VERIFIED_SNAP_METERS;
            if (verified) verifiedCount++;
            havenByNode.put(internalIdx, new String[]{
                    f.name == null ? "" : f.name,
                    f.kind,
                    Boolean.toString(verified),
                    String.format(Locale.ROOT, "%.0f", snapM),
                    f.osmType,
                    Long.toString(f.osmId),
            });
        }
        System.out.println("safe havens: " + havenByNode.size() + " designated ("
                + verifiedCount + " verified, " + (havenByNode.size() - verifiedCount) + " provisional)");
        return havenByNode;
    }

    static void writeSafeHavens(String outDir, Map<Integer, String[]> havenByNode) throws Exception {
        StringBuilder json = new StringBuilder(1 << 16);
        json.append("{\"region\":\"").append(REGION_ID).append("\",\"version\":").append(REGION_VERSION)
                .append(",\"note\":\"Snapped real OSM facilities; graph node = evacuation endpoint. "
                        + "verified=true means a named OSM facility snapped within ")
                .append((int) HAVEN_VERIFIED_SNAP_METERS)
                .append(" m; other entries are provisional and should be confirmed against an "
                        + "authoritative NDMA/state registry before operational use.\",\"havens\":[");
        boolean first = true;
        for (Map.Entry<Integer, String[]> e : havenByNode.entrySet()) {
            String[] h = e.getValue();
            if (!first) json.append(',');
            first = false;
            json.append("{\"nodeId\":\"").append(internalId(e.getKey()))
                    .append("\",\"name\":\"").append(jsonEscape(h[0]))
                    .append("\",\"kind\":\"").append(h[1])
                    .append("\",\"verified\":").append(h[2])
                    .append(",\"snapDistanceMeters\":").append(h[3])
                    .append(",\"osmType\":\"").append(h[4])
                    .append("\",\"osmId\":\"").append(h[5])
                    .append("\",\"latitude\":").append(fmtCoord(nodeLat[e.getKey()]))
                    .append(",\"longitude\":").append(fmtCoord(nodeLon[e.getKey()]))
                    .append('}');
        }
        json.append("]}");
        Files.write(Paths.get(outDir, "safe_havens.json"), json.toString().getBytes("UTF-8"));
        System.out.println("safe havens sidecar written: " + havenByNode.size()
                + " (haven town audit: " + matchedHqNames.size() + "/"
                + (DISTRICT_HQ_NAMES.length + MAJOR_TOWN_NAMES.length) + " matched)");
    }

    // ---- Elevation sidecar (optional) ----

    static void writeElevation(String outDir, String[] ids, int[] internalOfOut) throws Exception {
        final int zoom = 11;
        final String terrarium = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/" + zoom + "/";
        int xMin = (int) Math.floor((MIN_LNG + 180.0) / 360.0 * (1 << zoom));
        int xMax = (int) Math.floor((MAX_LNG + 180.0) / 360.0 * (1 << zoom));
        int yMin = latToTileY(MAX_LAT, zoom);
        int yMax = latToTileY(MIN_LAT, zoom);
        System.out.println("elevation tiles z" + zoom + ": x " + xMin + "-" + xMax + ", y " + yMin + "-" + yMax);

        // Bucket graph nodes per tile, then fetch tiles concurrently.
        Map<Long, List<Integer>> buckets = new HashMap<>(); // tileKey -> output indices
        for (int p = 0; p < ids.length; p++) {
            double lng = nodeLon[internalOfOut[p]], lat = nodeLat[internalOfOut[p]];
            long key = tileKey((long) Math.floor((lng + 180.0) / 360.0 * (1 << zoom)),
                    latToTileY(lat, zoom));
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<long[]> tileCoords = new ArrayList<>();
        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                if (buckets.containsKey(tileKey(x, y))) tileCoords.add(new long[]{x, y});
            }
        }
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.ConcurrentMap<Long, java.util.concurrent.CompletableFuture<BufferedImage>> tiles =
                new java.util.concurrent.ConcurrentHashMap<>();
        for (long[] t : tileCoords) {
            tiles.put(tileKey(t[0], t[1]), java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchTile(terrarium + t[0] + "/" + t[1] + ".png");
                } catch (Exception e) {
                    return null;
                }
            }, pool));
        }

        StringBuilder json = new StringBuilder(1 << 20);
        json.append("{\"source\":\"AWS Terrain Tiles (Mapzen terrarium PNG, zoom ").append(zoom)
                .append("; SRTM-derived), bilinear sampled\",\"unit\":\"meters\",\"elevations\":{");
        boolean first = true;
        int fetched = 0, failed = 0, missing = 0;
        for (long[] t : tileCoords) {
            List<Integer> bucket = buckets.get(tileKey(t[0], t[1]));
            BufferedImage tile;
            try {
                tile = tiles.get(tileKey(t[0], t[1])).join();
            } catch (Exception e) {
                tile = null;
            }
            if (tile == null) {
                failed++;
                missing += bucket.size();
                continue;
            }
            fetched++;
            for (Integer p : bucket) {
                int internal = internalOfOut[p];
                double ele = sampleTerrarium(tile, zoom, (int) t[0], (int) t[1], nodeLon[internal], nodeLat[internal]);
                if (!first) json.append(',');
                first = false;
                json.append('"').append(ids[p]).append("\":").append(fmt1(ele));
            }
        }
        pool.shutdown();
        json.append("}}");
        Files.write(Paths.get(outDir, "elevation.json"), json.toString().getBytes("UTF-8"));
        System.out.println("elevation sidecar: " + fetched + " tiles fetched, " + failed + " failed, "
                + missing + " nodes without elevation");
    }

    static long tileKey(long x, long y) { return (x << 32) | (y & 0xffffffffL); }

    static int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        double y = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * (1 << zoom);
        return (int) Math.floor(y);
    }

    static BufferedImage fetchTile(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "GeoRescuX-region-builder (dev tool; ODbL attribution)");
        int status = conn.getResponseCode();
        if (status != 200) throw new IOException("HTTP " + status + " for " + url);
        try (InputStream in = conn.getInputStream()) {
            return ImageIO.read(in);
        }
    }

    /** Terrarium encoding: elevation = (R*256 + G + B/256) - 32768. Bilinear sampled. */
    static double sampleTerrarium(BufferedImage tile, int zoom, int tileX, int tileY,
                                  double lng, double lat) {
        double worldPx = (1 << zoom) * 256.0;
        double xF = (lng + 180.0) / 360.0 * worldPx - tileX * 256.0;
        double merc = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
        double yF = (1 - merc / Math.PI) / 2 * worldPx - tileY * 256.0;
        int x0 = Math.max(0, Math.min((int) Math.floor(xF), tile.getWidth() - 1));
        int y0 = Math.max(0, Math.min((int) Math.floor(yF), tile.getHeight() - 1));
        int x1 = Math.min(x0 + 1, tile.getWidth() - 1);
        int y1 = Math.min(y0 + 1, tile.getHeight() - 1);
        double fx = Math.max(0, Math.min(xF - x0, 1.0));
        double fy = Math.max(0, Math.min(yF - y0, 1.0));
        double e00 = terrariumValue(tile.getRGB(x0, y0)), e10 = terrariumValue(tile.getRGB(x1, y0));
        double e01 = terrariumValue(tile.getRGB(x0, y1)), e11 = terrariumValue(tile.getRGB(x1, y1));
        return (e00 * (1 - fx) + e10 * fx) * (1 - fy) + (e01 * (1 - fx) + e11 * fx) * fy;
    }

    static double terrariumValue(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 256 + g + b / 256.0) - 32768.0;
    }

    // ---- PBF scanning ----

    /** pass1: collect ways + facilities; pass2: resolve pendingCoordIds. */
    static void scanPbf(String path, boolean pass1) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            readPbf(in, pass1);
        }
    }

    static void readPbf(InputStream in, boolean pass1) throws Exception {
        byte[] headerLenBuf = new byte[4];
        while (true) {
            int read = readFully(in, headerLenBuf);
            if (read == 0) break;
            if (read < 4) throw new IOException("truncated blob header");
            int headerLen = ((headerLenBuf[0] & 0xFF) << 24) | ((headerLenBuf[1] & 0xFF) << 16)
                    | ((headerLenBuf[2] & 0xFF) << 8) | (headerLenBuf[3] & 0xFF);
            if (headerLen <= 0 || headerLen > 64_000) throw new IOException("bad blob header length " + headerLen);
            byte[] header = new byte[headerLen];
            readFullyOrThrow(in, header);
            int[] pos = {0};
            String type = null;
            long datasize = -1;
            while (pos[0] < header.length) {
                long key = readVarint(header, pos);
                int field = (int) (key >>> 3);
                int wire = (int) (key & 7);
                if (field == 1 && wire == 2) {
                    type = new String(readBytes(header, pos), "UTF-8");
                } else if (field == 3 && wire == 0) {
                    datasize = readVarint(header, pos);
                } else {
                    skipField(header, pos, wire);
                }
            }
            if (type == null || datasize < 0 || datasize > 64_000_000) {
                throw new IOException("bad blob header (type=" + type + ", datasize=" + datasize + ")");
            }
            byte[] blobRaw = new byte[(int) datasize];
            readFullyOrThrow(in, blobRaw);

            if ("OSMHeader".equals(type)) {
                parseHeaderBlock(inflateBlob(blobRaw, true));
                continue;
            }
            if (!"OSMData".equals(type)) continue; // unknown block type — skip
            parsePrimitiveBlock(inflateBlob(blobRaw, false), pass1);
        }
    }

    /** Extracts a PrimitiveBlock/OSMHeader payload: raw (field 1) or zlib (field 3). */
    static byte[] inflateBlob(byte[] blobRaw, boolean headerBlock) throws IOException, DataFormatException {
        int[] pos = {0};
        byte[] raw = null;
        byte[] zlib = null;
        while (pos[0] < blobRaw.length) {
            long key = readVarint(blobRaw, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (wire == 2) {
                byte[] b = readBytes(blobRaw, pos);
                if (field == 1) raw = b;
                else if (field == 3) zlib = b;
                else if (field == 4) throw new IOException("lzma blobs not supported (Geofabrik ships zlib)");
                else if (field == 7) throw new IOException("zstd blobs not supported (Geofabrik ships zlib)");
            } else if (wire == 0) {
                readVarint(blobRaw, pos);
            } else {
                skipField(blobRaw, pos, wire);
            }
        }
        if (raw != null) return raw;
        if (zlib == null) throw new IOException("blob without raw or zlib data");
        Inflater inflater = new Inflater();
        inflater.setInput(zlib);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1 << 16, zlib.length * 4));
        byte[] out = new byte[1 << 20];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(out);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                } else {
                    bos.write(out, 0, n);
                }
            }
        } finally {
            inflater.end();
        }
        return bos.toByteArray();
    }

    static void parseHeaderBlock(byte[] data) throws IOException {
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int wire = (int) (key & 7);
            int field = (int) (key >>> 3);
            if (field == 4 && wire == 2) { // required_features
                String feature = new String(readBytes(data, pos), "UTF-8");
                if (!"OsmSchema-V0.6".equals(feature) && !"DenseNodes".equals(feature)
                        && !"HistoricalInformation".equals(feature)) {
                    throw new IOException("unsupported required feature: " + feature);
                }
            } else {
                skipField(data, pos, wire);
            }
        }
    }

    static void parsePrimitiveBlock(byte[] data, boolean pass1) throws IOException {
        // Two phases: granularity/lat_offset/lon_offset must be known before
        // any group is parsed (they are per-block).
        byte[][] strings = new byte[0][];
        List<byte[]> groups = new ArrayList<>(2);
        int[] pos = {0};
        while (pos[0] < data.length) {
            long key = readVarint(data, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (field == 1 && wire == 2) {
                strings = parseStringTable(readBytes(data, pos));
            } else if (field == 2 && wire == 2) {
                groups.add(readBytes(data, pos));
            } else if (field == 17 && wire == 0) {
                pbfGranularity = (int) readVarint(data, pos);
            } else if (field == 19 && wire == 0) {
                pbfLatOffset = readVarint(data, pos);
            } else if (field == 20 && wire == 0) {
                pbfLonOffset = readVarint(data, pos);
            } else {
                skipField(data, pos, wire);
            }
        }
        stringTable = new String[strings.length];
        for (int i = 0; i < strings.length; i++) stringTable[i] = new String(strings[i], "UTF-8");
        for (byte[] group : groups) parseGroup(group, pass1);
    }

    static byte[][] parseStringTable(byte[] st) throws IOException {
        List<byte[]> list = new ArrayList<>(1024);
        int[] pos = {0};
        while (pos[0] < st.length) {
            long key = readVarint(st, pos);
            int wire = (int) (key & 7);
            if ((key >>> 3) == 1 && wire == 2) {
                list.add(readBytes(st, pos));
            } else {
                skipField(st, pos, wire);
            }
        }
        return list.toArray(new byte[0][]);
    }

    static void parseGroup(byte[] group, boolean pass1) throws IOException {
        int[] pos = {0};
        while (pos[0] < group.length) {
            long key = readVarint(group, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (field == 2 && wire == 2) {
                parseDenseNodes(readBytes(group, pos), pass1);
            } else if (field == 3 && wire == 2) {
                parseWay(readBytes(group, pos), pass1);
            } else if (field == 1 && wire == 2) {
                parsePlainNode(readBytes(group, pos), pass1);
            } else {
                skipField(group, pos, wire); // relations, changesets
            }
        }
    }

    /** Growable long accumulator — packed repeated fields may arrive in several wire chunks. */
    static final class LongAcc {
        long[] a = new long[1024];
        int n;
        boolean seen;

        void append(long[] chunk) {
            seen = true;
            if (n + chunk.length > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + chunk.length));
            System.arraycopy(chunk, 0, a, n, chunk.length);
            n += chunk.length;
        }

        long[] toArray() { return Arrays.copyOf(a, n); }
    }

    static final class IntAcc {
        int[] a = new int[1024];
        int n;
        boolean seen;

        void append(int[] chunk) {
            seen = true;
            if (n + chunk.length > a.length) a = Arrays.copyOf(a, Math.max(a.length * 2, n + chunk.length));
            System.arraycopy(chunk, 0, a, n, chunk.length);
            n += chunk.length;
        }

        int[] toArray() { return Arrays.copyOf(a, n); }
    }

    /** DenseNodes: delta-coded ids/coords + interleaved tag string indexes. */
    static void parseDenseNodes(byte[] dense, boolean pass1) throws IOException {
        LongAcc idsAcc = new LongAcc(), latsAcc = new LongAcc(), lonsAcc = new LongAcc();
        IntAcc keyValsAcc = new IntAcc();
        int[] pos = {0};
        while (pos[0] < dense.length) {
            long key = readVarint(dense, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            // osmformat.proto DenseNodes: id=1, denseinfo=5, lat=8, lon=9,
            // keys_vals=10 (all packed sint64/int32 except denseinfo).
            if (field == 1 && wire == 2) idsAcc.append(readPackedSint64(dense, pos));
            else if (field == 8 && wire == 2) latsAcc.append(readPackedSint64(dense, pos));
            else if (field == 9 && wire == 2) lonsAcc.append(readPackedSint64(dense, pos));
            else if (field == 10 && wire == 2) keyValsAcc.append(readPackedInt32(dense, pos));
            else skipField(dense, pos, wire);
        }
        if (!idsAcc.seen) return;
        long[] ids = idsAcc.toArray();
        long[] lats = latsAcc.toArray();
        long[] lons = lonsAcc.toArray();
        int[] keyVals = keyValsAcc.toArray();
        long id = 0, lat = 0, lon = 0;
        int kvPos = 0;
        for (int i = 0; i < ids.length; i++) {
            id += ids[i];
            if (i < lats.length) lat += lats[i];
            if (i < lons.length) lon += lons[i];
            double latDeg = 1e-9 * (pbfLatOffset + (double) pbfGranularity * lat);
            double lonDeg = 1e-9 * (pbfLonOffset + (double) pbfGranularity * lon);
            osmNodeScanned++;

            if (pass1) {
                String name = null, amenity = null, place = null;
                if (kvPos < keyVals.length) {
                    while (kvPos < keyVals.length && keyVals[kvPos] != 0) {
                        String k = stringTable[keyVals[kvPos++]];
                        String v = kvPos < keyVals.length ? stringTable[keyVals[kvPos++]] : "";
                        if ("name".equals(k)) name = v;
                        else if ("amenity".equals(k)) amenity = v;
                        else if ("place".equals(k)) place = v;
                    }
                    kvPos++; // 0 terminator
                }
                handleTaggedNode(id, latDeg, lonDeg, name, amenity, place);
            } else if (pendingCoordIds.contains(id)) {
                storeCoord(id, latDeg, lonDeg);
            }
        }
    }

    /** Plain (non-dense) nodes — rare in Geofabrik extracts, handled for completeness. */
    static void parsePlainNode(byte[] node, boolean pass1) throws IOException {
        long id = 0;
        double latDeg = 0, lonDeg = 0;
        int[] pos = {0};
        while (pos[0] < node.length) {
            long key = readVarint(node, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            // osmformat.proto Node: id=1, keys=2, vals=3, info=4, lat=8, lon=9.
            if (field == 1 && wire == 0) id = zigzag(readVarint(node, pos));
            else if (field == 8 && wire == 0) latDeg = 1e-9 * (pbfLatOffset + (double) pbfGranularity * zigzag(readVarint(node, pos)));
            else if (field == 9 && wire == 0) lonDeg = 1e-9 * (pbfLonOffset + (double) pbfGranularity * zigzag(readVarint(node, pos)));
            else if (wire == 2) readBytes(node, pos); // keys/vals/info — tags not needed for plain nodes
            else skipField(node, pos, wire);
        }
        osmNodeScanned++;
        if (pendingCoordIds.contains(id)) storeCoord(id, latDeg, lonDeg);
    }

    static boolean pendingDebugTowns;

    static void handleTaggedNode(long id, double latDeg, double lonDeg,
                                 String name, String amenity, String place) {
        if (pendingDebugTowns && place != null && name != null && inBbox(latDeg, lonDeg)) {
            System.out.println("[place] " + place + " \"" + name + "\" @ " + latDeg + "," + lonDeg + " id=" + id);
        }
        if (amenity != null && (amenity.equals("hospital") || amenity.equals("police") || amenity.equals("fire_station"))) {
            Facility f = new Facility();
            f.kind = amenity;
            f.priority = amenity.equals("hospital") ? HAVEN_PRIORITY_HOSPITAL
                    : amenity.equals("police") ? HAVEN_PRIORITY_POLICE : HAVEN_PRIORITY_FIRE;
            f.name = name;
            f.osmType = "node";
            f.osmId = id;
            f.lat = latDeg;
            f.lon = lonDeg;
            facilities.add(f);
        }
        if (place != null && (place.equals("city") || place.equals("town")) && name != null) {
            Integer priority = havenTownPriority(name);
            if (priority != null) {
                matchedHqNames.add(foldName(name));
                Facility f = new Facility();
                f.kind = priority == HAVEN_PRIORITY_TOWN ? "district_hq" : "town";
                f.priority = priority;
                f.name = name;
                f.osmType = "node";
                f.osmId = id;
                f.lat = latDeg;
                f.lon = lonDeg;
                facilities.add(f);
            }
        }
    }

    static void parseWay(byte[] way, boolean pass1) throws IOException {
        long id = 0;
        IntAcc keysAcc = new IntAcc(), valsAcc = new IntAcc();
        LongAcc refsAcc = new LongAcc();
        int[] pos = {0};
        while (pos[0] < way.length) {
            long key = readVarint(way, pos);
            int field = (int) (key >>> 3);
            int wire = (int) (key & 7);
            if (field == 1 && wire == 0) id = readVarint(way, pos);
            else if (field == 2 && wire == 2) keysAcc.append(readPackedInt32(way, pos));
            else if (field == 3 && wire == 2) valsAcc.append(readPackedInt32(way, pos));
            else if (field == 8 && wire == 2) refsAcc.append(readPackedSint64(way, pos));
            else skipField(way, pos, wire);
        }
        wayScanned++;
        if (!keysAcc.seen || !valsAcc.seen || refsAcc.n < 2) return;
        int[] keys = keysAcc.toArray();
        int[] vals = valsAcc.toArray();
        long[] refs = new long[refsAcc.n];
        long acc = 0;
        for (int i = 0; i < refsAcc.n; i++) {
            acc += refsAcc.a[i];
            refs[i] = acc;
        }

        String name = null, highway = null, amenity = null;
        int n = Math.min(keys.length, vals.length);
        for (int i = 0; i < n; i++) {
            String k = stringTable[keys[i]];
            String v = stringTable[vals[i]];
            if ("name".equals(k)) name = v;
            else if ("highway".equals(k)) highway = v;
            else if ("amenity".equals(k)) amenity = v;
        }

        if (highway != null) {
            highwayWayCount.merge(highway, 1, Integer::sum);
            if (HIGHWAY_WHITELIST.contains(highway) && !acceptedWays.containsKey(id)) {
                Way w = new Way();
                w.id = id;
                w.highway = highway;
                w.refs = refs;
                acceptedWays.put(id, w);
            }
        }
        if (amenity != null && (amenity.equals("hospital") || amenity.equals("police") || amenity.equals("fire_station"))) {
            Facility f = new Facility();
            f.kind = amenity;
            f.priority = amenity.equals("hospital") ? HAVEN_PRIORITY_HOSPITAL
                    : amenity.equals("police") ? HAVEN_PRIORITY_POLICE : HAVEN_PRIORITY_FIRE;
            f.name = name;
            f.osmType = "way";
            f.osmId = id;
            f.wayRefs = refs;
            facilities.add(f);
        }
    }

    // ---- Pass-2 coordinate store ----

    static void storeCoord(long id, double latDeg, double lonDeg) {
        if (nodeIndex.get(id) >= 0) return; // first wins (identical OSM data across extracts)
        if (nodeCount == nodeLat.length) {
            nodeLat = Arrays.copyOf(nodeLat, nodeCount * 2);
            nodeLon = Arrays.copyOf(nodeLon, nodeCount * 2);
            reverseIds = Arrays.copyOf(reverseIds, nodeCount * 2);
        }
        nodeIndex.put(id, nodeCount);
        nodeLat[nodeCount] = latDeg;
        nodeLon[nodeCount] = lonDeg;
        reverseIds[nodeCount] = id;
        nodeCount++;
    }

    static long internalId(int internalIdx) { return reverseIds[internalIdx]; }

    // ---- Protobuf low-level ----

    static long readVarint(byte[] b, int[] pos) throws IOException {
        int shift = 0;
        long result = 0;
        while (true) {
            if (pos[0] >= b.length) throw new IOException("truncated varint");
            byte by = b[pos[0]++];
            result |= (long) (by & 0x7F) << shift;
            if ((by & 0x80) == 0) return result;
            shift += 7;
            if (shift > 63) throw new IOException("varint too long");
        }
    }

    static long zigzag(long n) { return (n >>> 1) ^ -(n & 1); }

    static byte[] readBytes(byte[] b, int[] pos) throws IOException {
        long len = readVarint(b, pos);
        if (len < 0 || pos[0] + len > b.length) throw new IOException("length-delimited field overruns buffer");
        byte[] out = new byte[(int) len];
        System.arraycopy(b, pos[0], out, 0, (int) len);
        pos[0] += len;
        return out;
    }

    /** Packed repeated sint64 (zigzag), NOT delta-accumulated. */
    static long[] readPackedSint64(byte[] b, int[] pos) throws IOException {
        byte[] raw = readBytes(b, pos);
        int[] p = {0};
        long[] out = new long[raw.length]; // worst case: 1-byte varints
        int n = 0;
        while (p[0] < raw.length) out[n++] = zigzag(readVarint(raw, p));
        return Arrays.copyOf(out, n);
    }

    static int[] readPackedInt32(byte[] b, int[] pos) throws IOException {
        byte[] raw = readBytes(b, pos);
        int[] p = {0};
        int[] out = new int[raw.length];
        int n = 0;
        while (p[0] < raw.length) out[n++] = (int) readVarint(raw, p);
        return Arrays.copyOf(out, n);
    }

    static void skipField(byte[] b, int[] pos, int wire) throws IOException {
        switch (wire) {
            case 0: readVarint(b, pos); break;
            case 1: pos[0] += 8; break;
            case 2: { long len = readVarint(b, pos); pos[0] += len; break; }
            case 5: pos[0] += 4; break;
            default: throw new IOException("unsupported wire type " + wire);
        }
        if (pos[0] > b.length) throw new IOException("skipped field overruns buffer");
    }

    static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    static void readFullyOrThrow(InputStream in, byte[] buf) throws IOException {
        if (readFully(in, buf) < buf.length) throw new IOException("unexpected end of file");
    }

    // ---- Geometry & formatting ----

    /** Same formula as AStarPathfinder.haversineMeters (6371 km, asin form). */
    static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    static boolean inBbox(double lat, double lng) {
        return lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG;
    }

    static boolean inCore(double lat, double lng) {
        return lat >= CORE_MIN_LAT && lat <= CORE_MAX_LAT && lng >= CORE_MIN_LNG && lng <= CORE_MAX_LNG;
    }

    static boolean inCore(Facility f) {
        return !Double.isNaN(f.lat) && inCore(f.lat, f.lon);
    }

    static boolean wayTouchesBbox(Way w) {
        for (long ref : w.refs) {
            int idx = nodeIndex.get(ref);
            if (idx >= 0 && inBbox(nodeLat[idx], nodeLon[idx])) return true;
        }
        return false;
    }

    static String pairKey(int a, int b) {
        String sa = Long.toString(internalId(a)), sb = Long.toString(internalId(b));
        return sa.compareTo(sb) < 0 ? sa + "|" + sb : sb + "|" + sa;
    }

    static String fmtCoord(double v) {
        String s = String.format(Locale.ROOT, "%.7f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Priority for a named place node: null when it is not a designated
     * haven town. Names are Unicode-folded — OSM uses transliteration
     * diacritics ("Haridwār", "Bāgeshwar", "Joshīmath") for many
     * Uttarakhand towns.
     */
    static String foldName(String s) {
        String n = java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }

    static Integer havenTownPriority(String name) {
        String n = foldName(name);
        for (String hq : DISTRICT_HQ_NAMES) {
            if (n.equals(foldName(hq))) return HAVEN_PRIORITY_TOWN;
        }
        for (String town : MAJOR_TOWN_NAMES) {
            if (n.equals(foldName(town))) return HAVEN_PRIORITY_MAJOR_TOWN;
        }
        return null;
    }

    // ---- Report ----

    static void writeReport(String outDir, List<String> pbfPaths, int outNodes, int outEdges,
                            int components, int largestComponent, List<Integer> islandSizes,
                            String outGraphPath) throws Exception {
        StringBuilder r = new StringBuilder(1 << 16);
        r.append("{\n  \"regionId\": \"").append(REGION_ID).append("\",\n")
                .append("  \"regionVersion\": ").append(REGION_VERSION).append(",\n")
                .append("  \"bbox\": {\"minLat\": ").append(MIN_LAT).append(", \"maxLat\": ").append(MAX_LAT)
                .append(", \"minLng\": ").append(MIN_LNG).append(", \"maxLng\": ").append(MAX_LNG).append("},\n")
                .append("  \"inputs\": [\n");
        for (String p : pbfPaths) {
            File f = new File(p);
            r.append("    {\"file\": \"").append(f.getName()).append("\", \"bytes\": ").append(f.length())
                    .append(", \"sha256\": \"").append(sha256(f)).append("\"},\n");
        }
        r.setLength(r.length() - 2);
        r.append("\n  ],\n");
        r.append("  \"osmNodesScanned\": ").append(osmNodeScanned).append(",\n")
                .append("  \"osmWaysScanned\": ").append(wayScanned).append(",\n")
                .append("  \"graphNodes\": ").append(outNodes).append(",\n")
                .append("  \"graphEdges\": ").append(outEdges).append(",\n")
                .append("  \"graphLengthKm\": ").append(fmt1(totalLengthKm)).append(",\n")
                .append("  \"connectedComponents\": ").append(components).append(",\n")
                .append("  \"largestComponentNodes\": ").append(largestComponent).append(",\n")
                .append("  \"largestIslandSizes\": ").append(islandSizes).append(",\n")
                .append("  \"matchedDistrictHq\": ").append(matchedHqNames.size())
                .append("/").append(DISTRICT_HQ_NAMES.length + MAJOR_TOWN_NAMES.length).append(",\n");
        List<String> matched = new ArrayList<>(matchedHqNames);
        matched.sort(String::compareTo);
        List<String> missingTowns = new ArrayList<>();
        for (String expected : DISTRICT_HQ_NAMES) {
            if (!matchedHqNames.contains(foldName(expected))) missingTowns.add(expected);
        }
        for (String expected : MAJOR_TOWN_NAMES) {
            if (!matchedHqNames.contains(foldName(expected))) missingTowns.add(expected);
        }
        r.append("  \"matchedHavenTowns\": [");
        for (int i = 0; i < matched.size(); i++) {
            if (i > 0) r.append(',');
            r.append('"').append(matched.get(i)).append('"');
        }
        r.append("],\n  \"missingHavenTowns\": [");
        for (int i = 0; i < missingTowns.size(); i++) {
            if (i > 0) r.append(',');
            r.append('"').append(missingTowns.get(i)).append('"');
        }
        r.append("],\n")
                .append("  \"highwayWayCounts\": {");
        boolean first = true;
        for (Map.Entry<String, Integer> e : highwayWayCount.entrySet()) {
            if (!first) r.append(',');
            first = false;
            r.append('"').append(e.getKey()).append("\": ").append(e.getValue());
        }
        r.append("},\n  \"output\": \"").append(new File(outGraphPath).getAbsolutePath()).append("\"\n}\n");
        Files.write(Paths.get(outDir, "build_report.json"), r.toString().getBytes("UTF-8"));
        System.out.println("report written to " + new File(outDir, "build_report.json").getPath());
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
