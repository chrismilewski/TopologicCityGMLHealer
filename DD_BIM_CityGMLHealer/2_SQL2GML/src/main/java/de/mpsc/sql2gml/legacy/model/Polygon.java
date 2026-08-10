package de.mpsc.sql2gml.legacy.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Polygon from the SQLite database.
 * 
 * Status is determined by the IsValid column:
 * - isValid=true (1)  → data is valid, update coordinates from DB's LinearRings
 * - isValid=false (0) → data could not be repaired, keep original CityGML, add Log as attribute
 * 
 * Log is always transferred regardless of isValid.
 */
public class Polygon {
    
    private long id;
    private long surfaceId;
    private String polygonIdGml;
    private List<LinearRing> linearRings;
    private boolean valid;
    private String log;

    public Polygon(long id, long surfaceId, String polygonIdGml, boolean valid, String log) {
        this.id = id;
        this.surfaceId = surfaceId;
        this.polygonIdGml = polygonIdGml;
        this.valid = valid;
        this.log = log;
        this.linearRings = new ArrayList<>();
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public long getSurfaceId() {
        return surfaceId;
    }

    public String getPolygonIdGml() {
        return polygonIdGml;
    }

    public List<LinearRing> getLinearRings() {
        return linearRings;
    }

    public void addLinearRing(LinearRing linearRing) {
        this.linearRings.add(linearRing);
    }
    
    public String getLog() {
        return log;
    }
    
    public boolean isValid() {
        return valid;
    }

    @Override
    public String toString() {
        return "Polygon{" +
                "id=" + id +
                ", polygonIdGml='" + polygonIdGml + '\'' +
                ", linearRings=" + linearRings.size() +
                ", valid=" + valid +
                '}';
    }
}
