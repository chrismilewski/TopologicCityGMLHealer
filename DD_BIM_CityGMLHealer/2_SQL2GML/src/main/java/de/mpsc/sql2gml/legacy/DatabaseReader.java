package de.mpsc.sql2gml.legacy;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.mpsc.sql2gml.legacy.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;

/**
 * Reads CityGML data from SQLite database.
 * Reads hierarchical structure: Buildings -> BuildingParts -> Surfaces -> Polygons -> LinearRings.
 * Each level has IsValid and Log columns.
 */
public class DatabaseReader {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseReader.class);
    private final String databasePath;
    private final Gson gson;
    private final Type mapType = new TypeToken<Map<String, Object>>(){}.getType();

    public DatabaseReader(String databasePath) {
        this.databasePath = databasePath;
        this.gson = new Gson();
    }

    /**
     * Reads all buildings with their hierarchy (new structure with Buildings table)
     */
    public List<Building> readAllBuildings() throws SQLException {
        List<Building> buildings = new ArrayList<>();
        
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            logger.info("Connected to database: {}", databasePath);
            
            // Read Buildings
            String buildingsQuery = "SELECT Id, BuildingIdGml, FileId, Attributes, IsValid, [Log] FROM Buildings";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(buildingsQuery)) {
                
                while (rs.next()) {
                    Building building = new Building(
                        rs.getLong("Id"),
                        rs.getString("BuildingIdGml"),
                        rs.getLong("FileId"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")
                    );
                    
                    // Parse JSON attributes
                    String attributesJson = rs.getString("Attributes");
                    if (attributesJson != null && !attributesJson.trim().isEmpty()) {
                        building.setAttributes(gson.fromJson(attributesJson, mapType));
                    }
                    
                    buildings.add(building);
                }
            }
            
            logger.info("Read {} buildings", buildings.size());
            
            // Read BuildingParts for each Building
            for (Building building : buildings) {
                readBuildingPartsForBuilding(conn, building);
            }
            
            // Read Surfaces, Polygons, LinearRings
            for (Building building : buildings) {
                for (BuildingPart part : building.getBuildingParts()) {
                    readSurfacesForBuildingPart(conn, part);
                }
            }
            
            for (Building building : buildings) {
                for (BuildingPart part : building.getBuildingParts()) {
                    for (Surface surface : part.getSurfaces()) {
                        readPolygonsForSurface(conn, surface);
                    }
                }
            }
            
            for (Building building : buildings) {
                for (BuildingPart part : building.getBuildingParts()) {
                    for (Surface surface : part.getSurfaces()) {
                        for (Polygon polygon : surface.getPolygons()) {
                            readLinearRingsForPolygon(conn, polygon);
                        }
                    }
                }
            }
        }
        
        return buildings;
    }



    /**
     * Reads building parts for a specific building
     */
    private void readBuildingPartsForBuilding(Connection conn, Building building) throws SQLException {
        String query = "SELECT Id, PartIdGml, BuildingId, Attributes, IsValid, [Log] FROM BuildingParts WHERE BuildingId = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, building.getId());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BuildingPart part = new BuildingPart(
                        rs.getLong("Id"),
                        rs.getLong("BuildingId"),
                        rs.getString("PartIdGml"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")
                    );
                    
                    // Parse JSON attributes
                    String attributesJson = rs.getString("Attributes");
                    if (attributesJson != null && !attributesJson.trim().isEmpty()) {
                        part.setAttributes(gson.fromJson(attributesJson, mapType));
                    }
                    
                    building.addBuildingPart(part);
                }
            }
        }
    }

    /**
     * Reads surfaces for a specific building part
     */
    private void readSurfacesForBuildingPart(Connection conn, BuildingPart part) throws SQLException {
        String query = "SELECT Id, SurfaceIdGml, SurfaceTypeId, Attributes, IsValid, [Log] FROM Surfaces WHERE BuildingPartId = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, part.getId());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Surface surface = new Surface(
                        rs.getLong("Id"),
                        rs.getString("SurfaceIdGml"),
                        rs.getInt("SurfaceTypeId"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")
                    );
                    
                    // Parse JSON attributes
                    String attributesJson = rs.getString("Attributes");
                    if (attributesJson != null && !attributesJson.trim().isEmpty()) {
                        surface.setAttributes(gson.fromJson(attributesJson, mapType));
                    }
                    
                    part.addSurface(surface);
                }
            }
        }
        
        logger.debug("Building part {} has {} surfaces", part.getPartIdGml(), part.getSurfaces().size());
    }

    /**
     * Reads polygons for a specific surface.
     * Reads IsValid and Log directly from the Polygons table.
     */
    private void readPolygonsForSurface(Connection conn, Surface surface) throws SQLException {
        String query = "SELECT Id, PolygonIdGml, IsValid, [Log] FROM Polygons WHERE SurfaceId = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, surface.getId());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Polygon polygon = new Polygon(
                        rs.getLong("Id"),
                        surface.getId(),
                        rs.getString("PolygonIdGml"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")
                    );
                    surface.addPolygon(polygon);
                }
            }
        }
    }

    /**
     * Reads linear rings for a specific polygon from LinearRings table.
     */
    private void readLinearRingsForPolygon(Connection conn, Polygon polygon) throws SQLException {
        String query = "SELECT PolygonId, RingIndex, PosList, IsValid, [Log] FROM LinearRings WHERE PolygonId = ? ORDER BY RingIndex";
        
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, polygon.getId());
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    LinearRing ring = new LinearRing(
                        rs.getLong("PolygonId"),
                        rs.getInt("RingIndex"),
                        rs.getString("PosList"),
                        rs.getInt("IsValid") == 1,
                        rs.getString("Log")
                    );
                    polygon.addLinearRing(ring);
                }
            }
        }
    }





    /**
     * Reads all CityGML files from the database
     * @return Map of FileId -> Filename
     */
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



    /**
     * Checks if there are any valid (repaired) polygons for a specific file.
     * A file has modifications when at least one polygon has IsValid=1.
     */
    public boolean hasModificationsForFile(long fileId) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            String query = """
                SELECT COUNT(1) as cnt FROM Polygons p
                JOIN Surfaces s ON p.SurfaceId = s.Id
                JOIN BuildingParts bp ON s.BuildingPartId = bp.Id
                JOIN Buildings b ON bp.BuildingId = b.Id
                WHERE b.FileId = ? AND p.IsValid = 1
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