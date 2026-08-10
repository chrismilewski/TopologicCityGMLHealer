package de.mpsc.sql2gml.legacy.model;

/**
 * Represents a LinearRing from the SQLite database
 */
public class LinearRing {
    private long polygonId;
    private int ringIndex;
    private String posList;  // Space-separated coordinate string
    private boolean valid;
    private String log;

    public LinearRing(long polygonId, int ringIndex, String posList, boolean valid, String log) {
        this.polygonId = polygonId;
        this.ringIndex = ringIndex;
        this.posList = posList;
        this.valid = valid;
        this.log = log;
    }

    // Getters and Setters
    public long getPolygonId() {
        return polygonId;
    }

    public int getRingIndex() {
        return ringIndex;
    }

    public String getPosList() {
        return posList;
    }

    public boolean isValid() {
        return valid;
    }

    public String getLog() {
        return log;
    }

    /**
     * Returns the coordinates as a double array
     * Format: [x1, y1, z1, x2, y2, z2, ...]
     */
    public double[] getPosListAsArray() {
        if (posList == null || posList.trim().isEmpty()) {
            return new double[0];
        }
        
        String[] parts = posList.trim().split("\\s+");
        double[] coords = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            coords[i] = Double.parseDouble(parts[i]);
        }
        return coords;
    }

    @Override
    public String toString() {
        return "LinearRing{" +
                "polygonId=" + polygonId +
                ", ringIndex=" + ringIndex +
                ", coordCount=" + (posList != null ? posList.split("\\s+").length : 0) +
                '}';
    }
}
