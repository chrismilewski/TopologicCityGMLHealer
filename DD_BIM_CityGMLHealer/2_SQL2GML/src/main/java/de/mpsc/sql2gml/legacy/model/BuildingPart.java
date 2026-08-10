package de.mpsc.sql2gml.legacy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a BuildingPart from the SQLite database
 */
public class BuildingPart {
    private long id;
    private long buildingId;  // FK to Building
    private String partIdGml;
    private Map<String, Object> attributes;
    private boolean valid;
    private String log;
    private List<Surface> surfaces;

    public BuildingPart(long id, long buildingId, String partIdGml, boolean valid, String log) {
        this.id = id;
        this.buildingId = buildingId;
        this.partIdGml = partIdGml;
        this.valid = valid;
        this.log = log;
        this.surfaces = new ArrayList<>();
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public String getPartIdGml() {
        return partIdGml;
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

    public List<Surface> getSurfaces() {
        return surfaces;
    }

    public void addSurface(Surface surface) {
        this.surfaces.add(surface);
    }

    @Override
    public String toString() {
        return "BuildingPart{" +
                "id=" + id +
                ", buildingId=" + buildingId +
                ", partIdGml='" + partIdGml + '\'' +
                ", surfaces=" + surfaces.size() +
                '}';
    }
}
