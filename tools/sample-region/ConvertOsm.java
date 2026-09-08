import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GeoRescueX Stage 7B-1 development tool.
 *
 * Converts a small OpenStreetMap extract (Overpass JSON, Connaught Place,
 * New Delhi) into:
 *   1. graph.json   — a real OSM-derived road graph for the Stage 7A
 *                     RouteGraph / A* engine (RegionGraphLoader format).
 *   2. tiles.sqlite — an osmdroid offline tile archive rendered from the
 *                     same OSM data (schema: tiles(key INTEGER, provider
 *                     TEXT, tile BLOB), key = ((z<<z)+x)<<z + y).
 *
 * The sample region is a small real OSM-derived demonstration area —
 * it is NOT India-wide data.
 *
 * Data © OpenStreetMap contributors (ODbL).
 *
 * Run: java -cp json.jar:sqlite-jdbc.jar ConvertOsm.java <overpass.json> <graph.json> <tiles.sqlite>
 */
public class ConvertOsm {

    static final double MIN_LAT = 28.6290, MAX_LAT = 28.6365;
    static final double MIN_LNG = 77.2130, MAX_LNG = 77.2270;
    static final String REGION_ID = "sample-region";
    static final String REGION_NAME = "Connaught Place, New Delhi (sample)";
    static final String REGION_SOURCE = "OpenStreetMap via Overpass API";
    static final String REGION_ATTRIBUTION = "© OpenStreetMap contributors";
    static final int REGION_VERSION = 1;
    static final int MIN_ZOOM = 13, MAX_ZOOM = 17;
    static final int TILE_SIZE = 256;
    static final String TILE_PROVIDER = "sample-region-offline";
    static final double EARTH_RADIUS_M = 6371000.0;

    static final Set<String> HIGHWAY_WHITELIST = new HashSet<>(Arrays.asList(
            "motorway", "trunk", "primary", "secondary", "tertiary",
            "unclassified", "residential", "living_street", "service", "pedestrian"));

    // Real landmarks used to designate the safe havens of the sample region.
    static final double[][] SAFE_HAVEN_LANDMARKS = {
            {28.6328, 77.2196}, // Central Park, Connaught Place
            {28.6305, 77.2209}, // Shivaji Stadium area
    };

    static class OsmNode {
        double lat, lng;
        int wayCount;
        boolean safeHaven;
    }

    static final Map<String, OsmNode> allNodes = new LinkedHashMap<>();
    static final List<List<String>> ways = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String overpassPath = args[0];
        String graphOutPath = args[1];
        String tilesOutPath = args[2];

        parseOverpass(overpassPath);

        int nodesBefore = countGraphNodes();
        List<List<String>> simplified = simplifyWays();
        markSafeHavens(simplified);
        int nodesAfter = countGraphNodes(simplified);
        double totalLength = totalLengthMeters(simplified);

        writeGraphJson(graphOutPath, simplified);

        File tilesFile = new File(tilesOutPath);
        renderTiles(tilesFile.getPath(), simplified);

        System.out.println("nodes " + nodesBefore + " -> " + nodesAfter);
        System.out.println("edges " + (simplified.size()));
        System.out.println("total length " + Math.round(totalLength) + " m");
        System.out.println("safe havens " + safeHavenIds(simplified));
        System.out.println("tiles " + tilesFile.length() + " bytes");
    }

    // ---- Overpass parsing ----

    static void parseOverpass(String path) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(path)));
        JSONObject root = new JSONObject(text);
        JSONArray elements = root.getJSONArray("elements");
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.getJSONObject(i);
            String type = element.getString("type");
            if ("node".equals(type)) {
                String id = String.valueOf(element.getLong("id"));
                OsmNode node = new OsmNode();
                node.lat = element.getDouble("lat");
                node.lng = element.getDouble("lon");
                allNodes.put(id, node);
            } else if ("way".equals(type)) {
                JSONObject tags = element.optJSONObject("tags");
                String highway = tags == null ? null : tags.optString("highway", "");
                if (!HIGHWAY_WHITELIST.contains(highway)) continue;
                JSONArray nodeIds = element.getJSONArray("nodes");
                List<String> way = new ArrayList<>();
                for (int j = 0; j < nodeIds.length(); j++) {
                    way.add(String.valueOf(nodeIds.getLong(j)));
                }
                if (way.size() >= 2) ways.add(way);
            }
        }
        for (List<String> way : ways) {
            for (String nodeId : way) {
                allNodes.get(nodeId).wayCount++;
            }
        }
        System.out.println("ways " + ways.size() + ", osm nodes " + allNodes.size());
    }

    // ---- Graph simplification ----

    static List<List<String>> simplifyWays() {
        List<List<String>> simplified = new ArrayList<>();
        for (List<String> way : ways) {
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < way.size(); i++) {
                String nodeId = way.get(i);
                boolean isEnd = i == 0 || i == way.size() - 1;
                if (isEnd || allNodes.get(nodeId).wayCount >= 2) {
                    if (kept.isEmpty() || !kept.get(kept.size() - 1).equals(nodeId)) {
                        kept.add(nodeId);
                    }
                }
            }
            if (kept.size() >= 2) simplified.add(kept);
        }
        return simplified;
    }

    static void markSafeHavens(List<List<String>> simplified) {
        Set<String> designated = new HashSet<>();
        for (double[] landmark : SAFE_HAVEN_LANDMARKS) {
            String best = null;
            double bestDistance = Double.MAX_VALUE;
            for (List<String> way : simplified) {
                for (String nodeId : way) {
                    OsmNode node = allNodes.get(nodeId);
                    double distance = haversine(landmark[0], landmark[1], node.lat, node.lng);
                    if (distance < bestDistance && !designated.contains(nodeId)) {
                        bestDistance = distance;
                        best = nodeId;
                    }
                }
            }
            if (best != null) {
                allNodes.get(best).safeHaven = true;
                designated.add(best);
                System.out.println("safe haven " + best + " at " + Math.round(bestDistance) + " m from landmark");
            }
        }
    }

    static Set<String> graphNodeIds(List<List<String>> simplified) {
        Set<String> ids = new HashSet<>();
        simplified.forEach(ids::addAll);
        return ids;
    }

    static int countGraphNodes() {
        return graphNodeIds(simplifyWays()).size();
    }

    static int countGraphNodes(List<List<String>> simplified) {
        return graphNodeIds(simplified).size();
    }

    static double totalLengthMeters(List<List<String>> simplified) {
        double total = 0.0;
        for (List<String> way : simplified) {
            for (int i = 1; i < way.size(); i++) {
                OsmNode a = allNodes.get(way.get(i - 1));
                OsmNode b = allNodes.get(way.get(i));
                total += haversine(a.lat, a.lng, b.lat, b.lng);
            }
        }
        return total;
    }

    static Set<String> safeHavenIds(List<List<String>> simplified) {
        Set<String> ids = new HashSet<>();
        simplified.forEach(way -> way.forEach(nodeId -> {
            if (allNodes.get(nodeId).safeHaven) ids.add(nodeId);
        }));
        return ids;
    }

    // ---- graph.json ----

    static void writeGraphJson(String path, List<List<String>> simplified) throws Exception {
        // Deterministic node ids sorted; edges sorted by (from, to).
        Map<String, double[]> nodeCoords = new TreeMap<>();
        simplified.forEach(way -> way.forEach(nodeId -> {
            OsmNode node = allNodes.get(nodeId);
            nodeCoords.put(nodeId, new double[]{node.lat, node.lng});
        }));

        // Edges aggregated by unordered pair (shortest of duplicate ways).
        Map<String, Double> edgeByKey = new TreeMap<>();
        for (List<String> way : simplified) {
            for (int i = 1; i < way.size(); i++) {
                String a = way.get(i - 1), b = way.get(i);
                if (a.equals(b)) continue;
                String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                double distance = haversine(allNodes.get(a).lat, allNodes.get(a).lng, allNodes.get(b).lat, allNodes.get(b).lng);
                edgeByKey.merge(key, distance, Math::min);
            }
        }

        JSONObject region = new JSONObject();
        region.put("id", REGION_ID);
        region.put("name", REGION_NAME);
        region.put("version", REGION_VERSION);
        region.put("source", REGION_SOURCE);
        region.put("attribution", REGION_ATTRIBUTION);

        JSONArray nodes = new JSONArray();
        nodeCoords.forEach((nodeId, coords) -> {
            OsmNode node = allNodes.get(nodeId);
            nodes.put(new JSONObject()
                    .put("id", nodeId)
                    .put("latitude", coords[0])
                    .put("longitude", coords[1])
                    .put("isSafeHaven", node.safeHaven));
        });

        JSONArray edges = new JSONArray();
        edgeByKey.forEach((key, distance) -> {
            String[] parts = key.split("\\|", 2);
            edges.put(new JSONObject()
                    .put("fromNodeId", parts[0])
                    .put("toNodeId", parts[1])
                    .put("distanceMeters", distance));
        });

        JSONObject graph = new JSONObject()
                .put("region", region)
                .put("nodes", nodes)
                .put("edges", edges);
        Files.write(Paths.get(path), graph.toString(2).getBytes("UTF-8"));
    }

    // ---- Offline tiles ----

    static double lngToPixelX(double lng, int zoom) {
        return (lng + 180.0) / 360.0 * (1 << zoom) * TILE_SIZE;
    }

    static double latToPixelY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        double y = 1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI;
        return y / 2.0 * (1 << zoom) * TILE_SIZE;
    }

    static long tileKey(int zoom, int x, int y) {
        // osmdroid DatabaseFileArchive: key = ((z << z) + x) << z + y
        long key = ((((long) zoom) << zoom) + x) << zoom;
        return key + y;
    }

    static void renderTiles(String tilesOutPath, List<List<String>> simplified) throws Exception {
        File tilesFile = new File(tilesOutPath);
        if (tilesFile.exists()) tilesFile.delete();
        System.out.println("loading jdbc driver");
        // Direct factory: DriverManager's ServiceLoader scan is unreliable
        // in single-file source launch mode.
        Connection connection = org.sqlite.JDBC.createConnection(
                "jdbc:sqlite:" + tilesFile.getAbsolutePath(), new java.util.Properties());
        Statement statement = connection.createStatement();
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS tiles (key INTEGER PRIMARY KEY, provider TEXT, tile BLOB)");
        PreparedStatement insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO tiles (key, provider, tile) VALUES (?, ?, ?)");

        int tileCount = 0;
        for (int zoom = MIN_ZOOM; zoom <= MAX_ZOOM; zoom++) {
            double minX = lngToPixelX(MIN_LNG, zoom), maxX = lngToPixelX(MAX_LNG, zoom);
            double minY = latToPixelY(MAX_LAT, zoom), maxY = latToPixelY(MIN_LAT, zoom);
            int tileXMin = (int) Math.floor(minX / TILE_SIZE), tileXMax = (int) Math.floor(maxX / TILE_SIZE);
            int tileYMin = (int) Math.floor(minY / TILE_SIZE), tileYMax = (int) Math.floor(maxY / TILE_SIZE);
            for (int tileX = tileXMin; tileX <= tileXMax; tileX++) {
                for (int tileY = tileYMin; tileY <= tileYMax; tileY++) {
                    BufferedImage image = renderTile(zoom, tileX, tileY, simplified);
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", png);
                    long key = tileKey(zoom, tileX, tileY);
                    insert.setLong(1, key);
                    insert.setString(2, TILE_PROVIDER);
                    insert.setBytes(3, png.toByteArray());
                    insert.executeUpdate();
                    tileCount++;
                }
            }
        }
        insert.close();
        statement.close();
        connection.close();
        System.out.println("tiles written: " + tileCount);
    }

    static BufferedImage renderTile(int zoom, int tileX, int tileY, List<List<String>> simplified) {
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(0xF2EFE9));
        graphics.fillRect(0, 0, TILE_SIZE, TILE_SIZE);

        double originX = tileX * (double) TILE_SIZE;
        double originY = tileY * (double) TILE_SIZE;
        float strokeWidth = Math.max(1.5f, (zoom - 11) * 1.1f);

        List<Path2D> roadFills = new ArrayList<>();
        List<Path2D> roadCasings = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        for (List<String> way : simplified) {
            Path2D road = new Path2D.Double();
            boolean started = false;
            for (String nodeId : way) {
                OsmNode node = allNodes.get(nodeId);
                double x = lngToPixelX(node.lng, zoom) - originX;
                double y = latToPixelY(node.lat, zoom) - originY;
                if (!started) {
                    road.moveTo(x, y);
                    started = true;
                } else {
                    road.lineTo(x, y);
                }
            }
            if (started) {
                roadFills.add(road);
            }
        }
        // Casing pass, then fill pass (simple two-pass road rendering).
        graphics.setStroke(new BasicStroke(strokeWidth + 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(0xC9BFAF));
        roadFills.forEach(graphics::draw);
        graphics.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(Color.WHITE);
        roadFills.forEach(graphics::draw);

        if (zoom >= 15) {
            graphics.setColor(new Color(0x2E7D32));
            simplified.forEach(way -> {
                for (String nodeId : way) {
                    OsmNode node = allNodes.get(nodeId);
                    if (!node.safeHaven) continue;
                    double x = lngToPixelX(node.lng, zoom) - originX;
                    double y = latToPixelY(node.lat, zoom) - originY;
                    if (x >= -8 && x <= TILE_SIZE + 8 && y >= -8 && y <= TILE_SIZE + 8) {
                        graphics.setColor(new Color(0x2E7D32));
                        graphics.fillOval((int) x - 5, (int) y - 5, 10, 10);
                    }
                }
            });
        }

        graphics.dispose();
        return image;
    }

    static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
