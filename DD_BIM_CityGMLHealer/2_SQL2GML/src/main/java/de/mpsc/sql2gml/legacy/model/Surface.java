package de.mpsc.sql2gml.legacy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a Surface from the SQLite database
 */
public class Surface {
    private long id;
    private String surfaceIdGml;
    private int surfaceTypeId;  // 0=None, 1=Ground, 2=Wall, 3=Roof
    private Map<String, Object> attributes;  // JSON attributes
    private boolean valid;
    private String log;
    private List<Polygon> polygons;

    public Surface(long id, String surfaceIdGml, int surfaceTypeId, boolean valid, String log) {
        this.id = id;
        this.surfaceIdGml = surfaceIdGml;
        this.surfaceTypeId = surfaceTypeId;
        this.valid = valid;
        this.log = log;
        this.polygons = new ArrayList<>();
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

    public List<Polygon> getPolygons() {
        return polygons;
    }

    public void addPolygon(Polygon polygon) {
        this.polygons.add(polygon);
    }

    @Override
    public String toString() {
        return "Surface{" +
                "id=" + id +
                ", surfaceIdGml='" + surfaceIdGml + '\'' +
                ", polygons=" + polygons.size() +
                '}';
    }
}
