package de.mpsc.sql2gml.model;

import java.util.Map;

/**
 * Represents a Surface from the SQLite database (new healer schema).
 * Each Surface holds exactly ONE SurfaceGeometry (Polygon or TriangulatedSurface).
 */
public class Surface {
    private long id;
    private String surfaceIdGml;
    private int surfaceTypeId;
    private Map<String, Object> attributes;
    private boolean valid;
    private String log;
    private SurfaceGeometry geometry;

    public Surface(long id, String surfaceIdGml, int surfaceTypeId, boolean valid, String log) {
        this.id = id;
        this.surfaceIdGml = surfaceIdGml;
        this.surfaceTypeId = surfaceTypeId;
        this.valid = valid;
        this.log = log;
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public String getSurfaceIdGml() {
        return surfaceIdGml;
    }

    public int getSurfaceTypeId() {
        return surfaceTypeId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }

    public boolean isValid() {
        return valid;
    }

    public String getLog() {
        return log;
    }

    public SurfaceGeometry getGeometry() {
        return geometry;
    }

    public void setGeometry(SurfaceGeometry geometry) {
        this.geometry = geometry;
    }

    @Override
    public String toString() {
        return "Surface{" +
                "id=" + id +
                ", surfaceIdGml='" + surfaceIdGml + '\'' +
                ", surfaceTypeId=" + surfaceTypeId +
                ", geometry=" + (geometry != null ? geometry.getGeometryIdGml() : "none") +
                '}';
    }
}
