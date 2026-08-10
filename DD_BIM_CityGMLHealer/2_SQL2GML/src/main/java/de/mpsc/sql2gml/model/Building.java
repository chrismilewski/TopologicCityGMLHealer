package de.mpsc.sql2gml.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a Building from the SQLite database (new healer schema).
 */
public class Building {
    private long id;
    private String buildingIdGml;
    private long fileId;
    private Map<String, Object> attributes;
    private boolean valid;
    private String log;
    private List<BuildingPart> buildingParts;

    public Building(long id, String buildingIdGml, long fileId, boolean valid, String log) {
        this.id = id;
        this.buildingIdGml = buildingIdGml;
        this.fileId = fileId;
        this.valid = valid;
        this.log = log;
        this.buildingParts = new ArrayList<>();
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public String getBuildingIdGml() {
        return buildingIdGml;
    }

    public long getFileId() {
        return fileId;
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

    public List<BuildingPart> getBuildingParts() {
        return buildingParts;
    }

    public void addBuildingPart(BuildingPart part) {
        this.buildingParts.add(part);
    }

    @Override
    public String toString() {
        return "Building{" +
                "id=" + id +
                ", buildingIdGml='" + buildingIdGml + '\'' +
                ", fileId=" + fileId +
                ", buildingParts=" + buildingParts.size() +
                '}';
    }
}
