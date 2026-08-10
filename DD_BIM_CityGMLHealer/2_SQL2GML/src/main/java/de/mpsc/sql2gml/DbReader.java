package de.mpsc.sql2gml;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.mpsc.sql2gml.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;

/**
 * Liest die Building→BuildingPart→Surface→SurfaceGeometry→PosList-Hierarchie aus dem neuen
 * Healer-Schema (siehe Doku.md); jede Tabelle wird genau einmal gelesen, kein N+1-Query.
 */
public class DbReader {
    private static final Logger logger = LoggerFactory.getLogger(DbReader.class);
    private final String databasePath;
    private final Gson gson;
    private final Type mapType = new TypeToken<Map<String, Object>>(){}.getType();

    public DbReader(String databasePath) {
        this.databasePath = databasePath;
        this.gson = new Gson();
    }

    /** Reads all buildings with their full hierarchy. */
    public List<Building> readAllBuildings() throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            logger.info("Connected to database: {}", databasePath);

            Map<Long, Building> buildingsById = readBuildings(conn);
            Map<Long, BuildingPart> partsById = readBuildingParts(conn, buildingsById);
            Map<Long, Surface> surfacesById = readSurfaces(conn, partsById);
            Map<Long, SurfaceGeometry> geometriesById = readSurfaceGeometries(conn, surfacesById);
            readPosLists(conn, geometriesById);

            logger.info("Read {} buildings, {} parts, {} surfaces, {} geometries",
                    buildingsById.size(), partsById.size(), surfacesById.size(), geometriesById.size());
            return new ArrayList<>(buildingsById.values());
        }
    }

    private Map<Long, Building> readBuildings(Connection conn) throws SQLException {
        Map<Long, Building> result = new LinkedHashMap<>();
        String query = "SELECT Id, BuildingIdGml, FileId, Attributes, IsValid, [Log] FROM Buildings";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                Building building = new Building(
                        rs.getLong("Id"),
                        rs.getString("BuildingIdGml"),
                        rs.getLong("FileId"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log"));
                building.setAttributes(parseAttributes(rs.getString("Attributes")));
                result.put(building.getId(), building);
            }
        }
        return result;
    }

    private Map<Long, BuildingPart> readBuildingParts(
            Connection conn, Map<Long, Building> buildingsById) throws SQLException {
        Map<Long, BuildingPart> result = new LinkedHashMap<>();
        String query = "SELECT Id, PartIdGml, BuildingId, Attributes, IsValid, [Log] FROM BuildingParts";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                long buildingId = rs.getLong("BuildingId");
                Building building = buildingsById.get(buildingId);
                if (building == null) {
                    logger.warn("BuildingPart {} references unknown Building {}", rs.getLong("Id"), buildingId);
                    continue;
                }
                BuildingPart part = new BuildingPart(
                        rs.getLong("Id"),
                        buildingId,
                        rs.getString("PartIdGml"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log"));
                part.setAttributes(parseAttributes(rs.getString("Attributes")));
                building.addBuildingPart(part);
                result.put(part.getId(), part);
            }
        }
        return result;
    }

    private Map<Long, Surface> readSurfaces(
            Connection conn, Map<Long, BuildingPart> partsById) throws SQLException {
        Map<Long, Surface> result = new LinkedHashMap<>();
        String query = "SELECT Id, SurfaceIdGml, BuildingPartId, SurfaceTypeId, Attributes, IsValid, [Log] FROM Surfaces";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                long partId = rs.getLong("BuildingPartId");
                BuildingPart part = partsById.get(partId);
                if (part == null) {
                    logger.warn("Surface {} references unknown BuildingPart {}", rs.getLong("Id"), partId);
                    continue;
                }
                Surface surface = new Surface(
                        rs.getLong("Id"),
                        rs.getString("SurfaceIdGml"),
                        rs.getInt("SurfaceTypeId"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log"));
                surface.setAttributes(parseAttributes(rs.getString("Attributes")));
                part.addSurface(surface);
                result.put(surface.getId(), surface);
            }
        }
        return result;
    }

    private Map<Long, SurfaceGeometry> readSurfaceGeometries(
            Connection conn, Map<Long, Surface> surfacesById) throws SQLException {
        Map<Long, SurfaceGeometry> result = new LinkedHashMap<>();
        String query = "SELECT Id, SurfaceId, GeometryIdGml, GeometryTypeId, IsValid, [Log] FROM SurfaceGeometries";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                long surfaceId = rs.getLong("SurfaceId");
                Surface surface = surfacesById.get(surfaceId);
                if (surface == null) {
                    logger.warn("SurfaceGeometry {} references unknown Surface {}", rs.getLong("Id"), surfaceId);
                    continue;
                }
                SurfaceGeometry geometry = new SurfaceGeometry(
                        rs.getLong("Id"),
                        surfaceId,
                        rs.getString("GeometryIdGml"),
                        rs.getInt("GeometryTypeId"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log"));
                if (surface.getGeometry() != null) {
                    logger.warn("Surface {} has more than one SurfaceGeometry — keeping the first",
                            surface.getSurfaceIdGml());
                    continue;
                }
                surface.setGeometry(geometry);
                result.put(geometry.getId(), geometry);
            }
        }
        return result;
    }

    private void readPosLists(
            Connection conn, Map<Long, SurfaceGeometry> geometriesById) throws SQLException {
        String query = "SELECT SurfaceGeometryId, PosListIndex, PosList, IsValid, [Log] FROM PosLists "
                + "ORDER BY SurfaceGeometryId, PosListIndex";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                long geometryId = rs.getLong("SurfaceGeometryId");
                SurfaceGeometry geometry = geometriesById.get(geometryId);
                if (geometry == null) {
                    logger.warn("PosList references unknown SurfaceGeometry {}", geometryId);
                    continue;
                }
                geometry.addPosList(new PosList(
                        geometryId,
                        rs.getInt("PosListIndex"),
                        rs.getString("PosList"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")));
            }
        }
    }

    private Map<String, Object> parseAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.trim().isEmpty()) {
            return null;
        }
        return gson.fromJson(attributesJson, mapType);
    }

    /** Reads all CityGML files from the database (FileId -> Filename). */
    public Map<Long, String> getCityGmlFiles() throws SQLException {
        Map<Long, String> files = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            String query = "SELECT Id, Filename FROM CityGmlFiles ORDER BY Id";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    files.put(rs.getLong("Id"), rs.getString("Filename"));
                }
            }
        }
        logger.info("Found {} CityGML files in database", files.size());
        return files;
    }

    /** Checks if there is any valid healed geometry for a specific file. */
    public boolean hasModificationsForFile(long fileId) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            String query = """
                SELECT COUNT(1) as cnt FROM SurfaceGeometries sg
                JOIN Surfaces s ON sg.SurfaceId = s.Id
                JOIN BuildingParts bp ON s.BuildingPartId = bp.Id
                JOIN Buildings b ON bp.BuildingId = b.Id
                WHERE b.FileId = ? AND sg.IsValid = 1
                """;
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, fileId);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() && rs.getInt("cnt") > 0;
                }
            }
        }
    }
}
