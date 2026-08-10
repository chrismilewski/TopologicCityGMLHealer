package de.mpsc.sql2gml.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a SurfaceGeometry from the SQLite database (new healer schema).
 *
 * Each Surface has exactly ONE SurfaceGeometry. The GeometryTypeId decides how
 * the PosLists are interpreted (see {@link PosList}):
 *   - {@link #TYPE_POLYGON}: classic gml:Polygon (exterior ring + optional holes)
 *   - {@link #TYPE_TRIANGULATED_SURFACE}: gml:TriangulatedSurface, one Triangle per PosList
 */
public class SurfaceGeometry {
    public static final int TYPE_POLYGON = 0;
    public static final int TYPE_TRIANGULATED_SURFACE = 1;

    private long id;
    private long surfaceId;
    private String geometryIdGml;
    private int geometryTypeId;
    private boolean valid;
    private String log;
    private List<PosList> posLists;

    public SurfaceGeometry(long id, long surfaceId, String geometryIdGml, int geometryTypeId,
                           boolean valid, String log) {
        this.id = id;
        this.surfaceId = surfaceId;
        this.geometryIdGml = geometryIdGml;
        this.geometryTypeId = geometryTypeId;
        this.valid = valid;
        this.log = log;
        this.posLists = new ArrayList<>();
    }

    // Getters and Setters
    public long getId() {
        return id;
    }

    public long getSurfaceId() {
        return surfaceId;
    }

    public String getGeometryIdGml() {
        return geometryIdGml;
    }

    public int getGeometryTypeId() {
        return geometryTypeId;
    }

    public boolean isTriangulatedSurface() {
        return geometryTypeId == TYPE_TRIANGULATED_SURFACE;
    }

    public boolean isValid() {
        return valid;
    }

    public String getLog() {
        return log;
    }

    public List<PosList> getPosLists() {
        return posLists;
    }

    public void addPosList(PosList posList) {
        this.posLists.add(posList);
    }

    @Override
    public String toString() {
        return "SurfaceGeometry{" +
                "id=" + id +
                ", geometryIdGml='" + geometryIdGml + '\'' +
                ", geometryTypeId=" + geometryTypeId +
                ", posLists=" + posLists.size() +
                '}';
    }
}
