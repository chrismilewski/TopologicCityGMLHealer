package de.mpsc.sql2gml.model;

/**
 * Represents a PosList from the SQLite database (new healer schema).
 *
 * A PosList is a closed coordinate ring (first point == last point):
 *   - GeometryType Polygon: PosListIndex 0 = exterior ring, index > 0 = interior ring (hole)
 *   - GeometryType TriangulatedSurface: each PosList is one independent triangle (4 points)
 */
public class PosList {
    private long surfaceGeometryId;
    private int posListIndex;
    private String posList;  // Space-separated coordinate string
    private boolean valid;
    private String log;

    public PosList(long surfaceGeometryId, int posListIndex, String posList, boolean valid, String log) {
        this.surfaceGeometryId = surfaceGeometryId;
        this.posListIndex = posListIndex;
        this.posList = posList;
        this.valid = valid;
        this.log = log;
    }

    // Getters and Setters
    public long getSurfaceGeometryId() {
        return surfaceGeometryId;
    }

    public int getPosListIndex() {
        return posListIndex;
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
        return "PosList{" +
                "surfaceGeometryId=" + surfaceGeometryId +
                ", posListIndex=" + posListIndex +
                ", coordCount=" + (posList != null ? posList.split("\\s+").length : 0) +
                '}';
    }
}
