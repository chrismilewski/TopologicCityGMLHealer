package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurve;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.CurveProperty;
import org.xmlobjects.gml.model.geometry.primitives.LineString;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * TerrainIntersectionCurve-Erzeugung und der komplette Neuaufbau der lod3Solid-Shell aus den
 * BoundarySurfaces eines Gebaeudes/BuildingParts.
 */
public final class SolidShellUtils {

    private SolidShellUtils() {
        // Utility-Klasse
    }

    // ==================== TerrainIntersectionCurve ====================

    /** Erzeugt eine flache lod3TerrainIntersectionCurve bei Z=hDgm (ohne DGM). */
    public static MultiCurveProperty createTerrainIntersectionCurve(
            List<Polygon> groundPolygons, double hDgm) {
        return createTerrainIntersectionCurve(groundPolygons, hDgm, null);
    }

    /** Erzeugt eine lod3TerrainIntersectionCurve; mit DGM bilinear interpoliert, sonst Fallback auf hDgm. */
    public static MultiCurveProperty createTerrainIntersectionCurve(
            List<Polygon> groundPolygons, double hDgm, DgmProvider dgm) {

        if (groundPolygons == null || groundPolygons.isEmpty()) {
            return null;
        }

        MultiCurve multiCurve = new MultiCurve();

        for (Polygon groundPoly : groundPolygons) {
            List<Point3D> points = GeometryUtils.toPoints(groundPoly);
            if (points.size() < 4) continue;  // mind. 3 Punkte + Schlusspunkt

            // Z-Werte per DGM oder konstantem hDgm setzen
            List<Double> coords = new ArrayList<>(points.size() * 3);
            for (Point3D p : points) {
                double z;
                if (dgm != null && dgm.contains(p.x, p.y)) {
                    double dgmZ = dgm.getHeight(p.x, p.y);
                    z = Double.isNaN(dgmZ) ? hDgm : GeometryUtils.roundZ(dgmZ);
                } else {
                    z = GeometryUtils.roundZ(hDgm);
                }
                coords.add(p.x);
                coords.add(p.y);
                coords.add(z);
            }

            // Ring schliessen (sicherheitshalber)
            if (coords.size() >= 6) {
                double x0 = coords.get(0), y0 = coords.get(1), z0 = coords.get(2);
                int last = coords.size() - 3;
                double xn = coords.get(last), yn = coords.get(last + 1), zn = coords.get(last + 2);
                double dist = Math.sqrt(Math.pow(x0 - xn, 2) + Math.pow(y0 - yn, 2));
                if (dist > 1e-6) {
                    coords.add(x0);
                    coords.add(y0);
                    coords.add(z0);
                }
            }

            DirectPositionList posList = new DirectPositionList(coords);
            posList.setSrsDimension(3);

            LineString lineString = new LineString(posList);
            lineString.setSrsName(CityGmlUtils.SRS_NAME);
            lineString.setSrsDimension(CityGmlUtils.SRS_DIMENSION);

            multiCurve.getCurveMember().add(new CurveProperty(lineString));
        }

        if (multiCurve.getCurveMember().isEmpty()) {
            return null;
        }

        return new MultiCurveProperty(multiCurve);
    }

    // ==================== Solid-Shell-Rebuild ====================

    /** True, wenn der Aussenring eines Wand-Polygons im lokalen 2D-KS (Wandunterkante, Z) CCW laeuft. */
    public static boolean isExteriorRingCCW(List<Point3D> open, Point3D edgeStart,
            double dirX, double dirY) {
        double area2 = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D a = open.get(i);
            Point3D b = open.get((i + 1) % n);
            double ua = (a.x - edgeStart.x) * dirX + (a.y - edgeStart.y) * dirY;
            double ub = (b.x - edgeStart.x) * dirX + (b.y - edgeStart.y) * dirY;
            area2 += (ua * b.z - ub * a.z);
        }
        return area2 > 0;
    }

    /** Wie {@link #isExteriorRingCCW}, aber mit echtem 3D-"Aufwaerts"-Vektor (up) statt der
     * Wand-Annahme v=Z — fuer geneigte Flaechen (z.B. Dachschraegen), deren zweite lokale Achse
     * nicht senkrecht ist. */
    public static boolean isRingCCWOnPlane(List<Point3D> open, Point3D origin,
            double dirX, double dirY, double upX, double upY, double upZ) {
        double area2 = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D a = open.get(i);
            Point3D b = open.get((i + 1) % n);
            double ax = a.x - origin.x, ay = a.y - origin.y, az = a.z - origin.z;
            double bx = b.x - origin.x, by = b.y - origin.y, bz = b.z - origin.z;
            double ua = ax * dirX + ay * dirY, va = ax * upX + ay * upY + az * upZ;
            double ub = bx * dirX + by * dirY, vb = bx * upX + by * upY + bz * upZ;
            area2 += (ua * vb - ub * va);
        }
        return area2 > 0;
    }

    /** Baut die lod3Solid-Shell eines Gebaeudes/BuildingParts komplett aus seinen BoundarySurfaces neu auf. */
    public static int rebuildSolidShell(AbstractBuilding target) {
        SolidProperty solidProp = target.getSolid(3);
        if (solidProp == null || solidProp.getObject() == null) return 0;

        if (!(solidProp.getObject() instanceof
                org.xmlobjects.gml.model.geometry.primitives.Solid solid)) return 0;
        if (solid.getExterior() == null || solid.getExterior().getObject() == null) return 0;

        var shell = solid.getExterior().getObject();

        // Alle inline-Polygon gml:ids aus BoundarySurfaces sammeln
        List<String> polygonIds = new ArrayList<>();
        int autoIdCounter = 0;

        for (var boundary : target.getBoundaries()) {
            var surface = boundary.getObject();
            if (!(surface instanceof AbstractThematicSurface ats)) continue;

            // Floor/Ceiling gehoeren nicht zur aeusseren Solid-Hull (sonst GE_S_NON_MANIFOLD_EDGE).
            if (surface instanceof FloorSurface || surface instanceof CeilingSurface) continue;

            MultiSurfaceProperty msp = ats.getMultiSurface(3);
            if (msp == null || msp.getObject() == null) continue;

            for (SurfaceProperty member : msp.getObject().getSurfaceMember()) {
                // Nur inline-Polygone zaehlen, keine XLink-Referenzen.
                if (member.getObject() instanceof Polygon poly) {
                    if (poly.getId() == null || poly.getId().isBlank()) {
                        String surfaceId = ats.getId();
                        String baseId;
                        if (surfaceId != null && surfaceId.startsWith("Face_")) {
                            baseId = "Poly_" + surfaceId.substring(5);
                        } else if (surfaceId != null) {
                            baseId = "Poly_" + surfaceId;
                        } else {
                            baseId = "Poly_auto_" + (++autoIdCounter);
                        }
                        poly.setId(baseId);
                    }
                    polygonIds.add(poly.getId());
                }
            }

            // FillingSurfaces (Fenster/Tueren) in die Shell aufnehmen, sonst GE_S_NOT_CLOSED am
            // Lochrand — gilt fuer jede Flaeche mit Oeffnungen (Waende: Fenster/Tueren; Daecher:
            // Dachfenster), nicht nur Waende.
            List<AbstractFillingSurfaceProperty> fillingSurfaces =
                    surface instanceof WallSurface wall ? wall.getFillingSurfaces()
                    : surface instanceof RoofSurface roof ? roof.getFillingSurfaces()
                    : null;
            if (fillingSurfaces != null) {
                for (AbstractFillingSurfaceProperty fillProp : fillingSurfaces) {
                    var fill = fillProp.getObject();
                    if (fill == null) continue;
                    MultiSurfaceProperty fmsp = fill.getMultiSurface(3);
                    if (fmsp == null || fmsp.getObject() == null) continue;
                    for (SurfaceProperty fmember : fmsp.getObject().getSurfaceMember()) {
                        if (fmember.getObject() instanceof Polygon fpoly) {
                            if (fpoly.getId() == null || fpoly.getId().isBlank()) {
                                // ID aus der FillingSurface-ID ableiten (global eindeutig).
                                String fillId = fill.getId();
                                if (fillId != null && !fillId.isBlank()) {
                                    String base = fillId.startsWith("Face_")
                                            ? fillId.substring(5) : fillId;
                                    fpoly.setId("Poly_" + base);
                                } else {
                                    fpoly.setId("Poly_Fill_" + ats.getId()
                                            + "_" + (++autoIdCounter));
                                }
                            }
                            polygonIds.add(fpoly.getId());
                        }
                    }
                }
            }
        }

        // Shell-surfaceMembers komplett ersetzen
        shell.getSurfaceMembers().clear();
        for (String polyId : polygonIds) {
            shell.getSurfaceMembers().add(new SurfaceProperty("#" + polyId));
        }

        return polygonIds.size();
    }
}
