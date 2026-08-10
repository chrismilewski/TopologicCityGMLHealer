package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.GroundSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractFeature;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.citygml4j.core.model.generics.StringAttribute;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.reader.CityGMLReader;
import org.citygml4j.xml.reader.ChunkOptions;
import org.citygml4j.xml.writer.CityGMLChunkWriter;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurve;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.CurveProperty;
import org.xmlobjects.gml.model.geometry.primitives.LinearRing;
import org.xmlobjects.gml.model.geometry.primitives.LineString;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import org.citygml4j.core.visitor.ObjectWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Gemeinsame Hilfsfunktionen für CityGML-Verarbeitung.
 */
public final class CityGmlUtils {

    private static final Logger log = LoggerFactory.getLogger(CityGmlUtils.class);

    /** Epsilon fuer Punkt-Gleichheit (XYZ-Vergleich). */
    private static final double POINT_EQUALITY_EPS = 1e-6;

    /** STRUKTUR-Marker fuer Balkon-Flaechen, ausgeschlossen aus der lod3Solid-Huelle (siehe Doku.md Schritt 6). */
    public static final String STRUKTUR_BALCONY_DECK = "Balkondecke";
    public static final String STRUKTUR_BALCONY_RAILING = "Balkonbruestung";

    private CityGmlUtils() {
        // Utility-Klasse
    }

    /** Liest ein StringAttribute aus einem AbstractCityObject. */
    public static String getStringAttribute(AbstractCityObject cityObject, String name) {
        if (cityObject.getGenericAttributes() == null) {
            return null;
        }
        return cityObject.getGenericAttributes().stream()
                .map(AbstractGenericAttributeProperty::getObject)
                .filter(Objects::nonNull)
                .filter(attr -> attr instanceof StringAttribute)
                .map(attr -> (StringAttribute) attr)
                .filter(attr -> name.equals(attr.getName()))
                .map(StringAttribute::getValue)
                .findFirst()
                .orElse(null);
    }

    /** Fuegt ein StringAttribute zu einem AbstractCityObject hinzu. */
    public static void addStringAttribute(AbstractCityObject cityObject, String name, String value) {
        StringAttribute attr = new StringAttribute(name, value);
        AbstractGenericAttributeProperty prop = new AbstractGenericAttributeProperty(attr);
        cityObject.getGenericAttributes().add(prop);
    }

    /** Setzt ein StringAttribute; aktualisiert den Wert falls es bereits existiert, sonst neu. */
    public static void setStringAttribute(AbstractCityObject cityObject, String name, String value) {
        if (cityObject.getGenericAttributes() != null) {
            for (var prop : cityObject.getGenericAttributes()) {
                if (prop.getObject() instanceof StringAttribute sa && name.equals(sa.getName())) {
                    sa.setValue(value);
                    return;
                }
            }
        }
        addStringAttribute(cityObject, name, value);
    }

    /** Liest ein StringAttribute als Double; null bei fehlendem Attribut oder Parse-Fehler. */
    public static Double parseDoubleAttribute(AbstractCityObject cityObject, String name) {
        String str = getStringAttribute(cityObject, name);
        if (str == null) return null;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Setzt den gml:name eines CityGML-Objekts. */
    public static void setGmlName(AbstractCityObject cityObject, String name) {
        cityObject.getNames().add(new Code(name));
    }

    // ==================== Geometrie-Berechnungen ====================

    /** 2D-Flaeche eines Polygons (Shoelace-Formel), Z-Koordinate wird ignoriert. */
    public static double calculatePolygonArea2D(List<Point3D> points) {
        List<Point3D> pts = removeClosingPoint(points);
        double area = 0.0;
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            Point3D current = pts.get(i);
            Point3D next = pts.get((i + 1) % n);
            area += current.x * next.y - next.x * current.y;
        }
        return Math.abs(area) / 2.0;
    }

    /** Azimut der Wandnormalen (Grad, 0=Nord) fuer eine vertikale Wand entlang Kante A->B. */
    private static double calculateWallNormalAzimuth(Point3D a, Point3D b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double azimuth = Math.toDegrees(Math.atan2(-dy, dx));
        if (azimuth < 0) azimuth += 360.0;
        return azimuth;
    }

    /** 2D-Kantenlaenge zwischen zwei Punkten (ignoriert Z). */
    public static double calculateEdgeLength2D(Point3D a, Point3D b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Formatiert einen double-Wert als String (max. 5 Nachkommastellen, ohne abschliessende Nullen). */
    public static String formatNum(double value) {
        String s = String.format(java.util.Locale.US, "%.5f", value);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }

    /** Rundet einen Z-Wert auf mm-Genauigkeit, gegen Floating-Point-Artefakte. */
    public static double roundZ(double z) {
        return Math.round(z * 1000.0) / 1000.0;
    }

    /** Extrahiert 3D-Punkte aus einem Polygon. */
    public static List<Point3D> toPoints(Polygon polygon) {
        if (polygon.getExterior() == null || polygon.getExterior().getObject() == null) {
            return Collections.emptyList();
        }
        LinearRing ring = (LinearRing) polygon.getExterior().getObject();
        if (ring.getControlPoints() == null || ring.getControlPoints().getPosList() == null) {
            return Collections.emptyList();
        }
        DirectPositionList posList = ring.getControlPoints().getPosList();
        List<Double> coords = posList.getValue();
        if (coords == null || coords.isEmpty()) {
            return Collections.emptyList();
        }

        List<Point3D> points = new ArrayList<>();
        for (int i = 0; i + 2 < coords.size(); i += 3) {
            points.add(new Point3D(coords.get(i), coords.get(i + 1), coords.get(i + 2)));
        }
        return points;
    }

    /** Toleranz fuer das Verschmelzen aufeinanderfolgender (nahezu) gleicher Punkte (1 mm). */
    public static final double POINT_MERGE_TOL = 0.001;

    private static double dist3D(Point3D a, Point3D b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Entfernt aufeinanderfolgende (zyklisch) Punkte, die naeher als {@code tol} beieinander
     * liegen. Erwartet/liefert einen OFFENEN Ring (ohne Schliessungspunkt).
     */
    public static List<Point3D> dedupConsecutive(List<Point3D> pts, double tol) {
        List<Point3D> open = removeClosingPoint(pts);
        if (open.size() < 2) return new ArrayList<>(open);
        List<Point3D> out = new ArrayList<>(open.size());
        for (Point3D p : open) {
            if (out.isEmpty() || dist3D(p, out.get(out.size() - 1)) >= tol) out.add(p);
        }
        // zyklischer Schluss: letzter ~ erster?
        while (out.size() >= 2 && dist3D(out.get(0), out.get(out.size() - 1)) < tol) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * Erstellt ein Polygon aus einer Liste von 3D-Punkten.
     * Aufeinanderfolgende Duplikate werden entfernt (Sicherheitsnetz gegen GE_R_CONSECUTIVE_POINTS_SAME).
     */
    public static Polygon createPolygon(List<Point3D> pts) {
        List<Point3D> open = dedupConsecutive(pts, POINT_MERGE_TOL);
        List<Point3D> closed = new ArrayList<>(open);
        if (!open.isEmpty()) {
            closed.add(open.get(0));
        }

        List<Double> coords = new ArrayList<>(closed.size() * 3);
        for (Point3D p : closed) {
            coords.add(p.x);
            coords.add(p.y);
            coords.add(p.z);
        }

        DirectPositionList posList = new DirectPositionList(coords);
        posList.setSrsDimension(3);

        LinearRing ring = new LinearRing();
        ring.getControlPoints().setPosList(posList);

        Polygon poly = new Polygon();
        poly.setExterior(new AbstractRingProperty(ring));
        return poly;
    }

    /**
     * Entfernt den schließenden Punkt eines Polygons (wenn erster == letzter).
     */
    public static List<Point3D> removeClosingPoint(List<Point3D> pts) {
        if (pts.size() < 2) {
            return pts;
        }
        Point3D first = pts.get(0);
        Point3D last = pts.get(pts.size() - 1);
        if (first.nearlyEquals(last)) {
            return pts.subList(0, pts.size() - 1);
        }
        return pts;
    }

    // ==================== Gebäude-Abfragen ====================

    /** Sammelt alle Boundary-Objekte eines bestimmten Typs. */
    public static <T> List<T> collectBoundariesByType(AbstractBuilding building, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (type.isInstance(boundary.getObject())) {
                result.add(type.cast(boundary.getObject()));
            }
        }
        return result;
    }

    /** Sammelt alle WallSurface-Objekte eines Gebaeudes oder BuildingParts. */
    public static List<WallSurface> collectWallSurfaces(AbstractBuilding building) {
        return collectBoundariesByType(building, WallSurface.class);
    }

    /** Verarbeitungsziele eines Buildings: BuildingParts (zuerst), dann das Building selbst. */
    public static List<AbstractBuilding> getBuildingTargets(Building building) {
        List<AbstractBuilding> targets = new ArrayList<>();
        if (building.getBuildingParts() != null) {
            for (var pp : building.getBuildingParts()) {
                if (pp.getObject() != null) targets.add(pp.getObject());
            }
        }
        if (!building.getBoundaries().isEmpty()) {
            targets.add(building);
        }
        return targets;
    }

    /** Sammelt alle lod3MultiSurface-Polygone der BoundarySurfaces eines bestimmten Typs. */
    public static <T extends AbstractThematicSurface> List<Polygon> collectLod3Polygons(
            AbstractBuilding building, Class<T> surfaceType) {
        List<Polygon> polygons = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (!surfaceType.isInstance(boundary.getObject())) continue;
            MultiSurfaceProperty msp = surfaceType.cast(boundary.getObject()).getMultiSurface(3);
            if (msp == null || msp.getObject() == null) continue;
            for (var member : msp.getObject().getSurfaceMember()) {
                if (member.getObject() instanceof Polygon poly) {
                    polygons.add(poly);
                }
            }
        }
        return polygons;
    }

    /** Sammelt alle GroundSurface-Polygone eines Gebaeudes oder BuildingParts. */
    public static List<Polygon> collectGroundPolygons(AbstractBuilding building) {
        return collectLod3Polygons(building, GroundSurface.class);
    }

    /** Sammelt alle RoofSurface-Polygone eines Gebaeudes oder BuildingParts. */
    public static List<Polygon> collectRoofPolygons(AbstractBuilding building) {
        return collectLod3Polygons(building, RoofSurface.class);
    }

    /** Traufe/First der RoofSurface-Polygone, aus der flaechengroessten Dachflaeche (Mischdach: geneigte bevorzugt). */
    public static double[] getRoofZRange(AbstractBuilding building) {
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double dominantMinZ = Double.MAX_VALUE;
        double dominantArea = -1;
        // Separate Verfolgung geneigter Dachflaechen fuer Mischdach-Erkennung
        double slopedDominantMinZ = Double.MAX_VALUE;
        double slopedDominantArea = -1;
        double slopedRawMinZ = Double.MAX_VALUE; // Fallback: globales MinZ aller geneigten Flaechen
        boolean hasSlopedRoof = false;
        boolean hasFlatRoof = false;
        for (var boundary : building.getBoundaries()) {
            if (boundary.getObject() instanceof RoofSurface rs) {
                // FACEAREA-Attribut lesen (gesetzt von Schritt 1 / Promoter)
                double area = -1;
                String faceAreaStr = getStringAttribute(rs, "FACEAREA");
                if (faceAreaStr != null) {
                    try { area = Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
                }
                MultiSurfaceProperty msp = rs.getLod3MultiSurface();
                if (msp == null || msp.getObject() == null) continue;
                for (var member : msp.getObject().getSurfaceMember()) {
                    if (member.getObject() instanceof Polygon poly) {
                        double localMinZ = Double.MAX_VALUE;
                        double localMaxZ = -Double.MAX_VALUE;
                        for (Point3D p : toPoints(poly)) {
                            minZ = Math.min(minZ, p.z);
                            maxZ = Math.max(maxZ, p.z);
                            localMinZ = Math.min(localMinZ, p.z);
                            localMaxZ = Math.max(localMaxZ, p.z);
                        }
                        if (area > dominantArea) {
                            dominantArea = area;
                            dominantMinZ = localMinZ;
                        }
                        // Geometrische Flacherkennung: alle Z-Werte nahezu gleich → Flachdach-Polygon
                        boolean polyIsFlat = (localMaxZ - localMinZ) < 0.05;
                        if (polyIsFlat) {
                            hasFlatRoof = true;
                        } else {
                            hasSlopedRoof = true;
                            slopedRawMinZ = Math.min(slopedRawMinZ, localMinZ);
                            if (area > slopedDominantArea) {
                                slopedDominantArea = area;
                                slopedDominantMinZ = localMinZ;
                            }
                        }
                    }
                }
            }
        }
        if (minZ == Double.MAX_VALUE) return null;
        // Mischdach: geneigte Flaeche bestimmt die Traufe (flache Teilflaechen ignorieren)
        double traufeZ;
        if (hasFlatRoof && hasSlopedRoof) {
            // Bevorzuge groesste geneigte Flaeche (FACEAREA), sonst globales MinZ der geneigten
            traufeZ = (slopedDominantArea > 0) ? slopedDominantMinZ : slopedRawMinZ;
        } else {
            traufeZ = (dominantArea > 0) ? dominantMinZ : minZ;
        }
        // Index 0: traufeZ (dominante Dachflaeche), Index 1: maxZ,
        // Index 2: globalMinZ (Min-Z aller RoofSurface-Polygone, fuer Slab-Begrenzung),
        // Index 3: slopedRawMinZ (Min-Z nur geneigter Flaechen; MAX_VALUE = kein Mischdach)
        return new double[]{traufeZ, maxZ, minZ, slopedRawMinZ};
    }

    /** Liest das erste Polygon aus einer WallSurface (LoD3). */
    public static Polygon getWallPolygon(WallSurface wall) {
        MultiSurfaceProperty msp = wall.getLod3MultiSurface();
        if (msp == null || msp.getObject() == null) return null;
        var members = msp.getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return null;
        return members.get(0).getObject() instanceof Polygon poly ? poly : null;
    }

    // ==================== Wand-Schnitt (Sutherland-Hodgman) ====================

    /** Schneidet ein Wand-Polygon horizontal bei zCut (Sutherland-Hodgman), liefert [untererTeil, obererTeil]. */
    public static Polygon[] cutWallPolygonAtZ(Polygon wallPoly, double zCut, double tolerance) {
        List<Point3D> pts = toPoints(wallPoly);
        List<Point3D> open = removeClosingPoint(pts);

        if (open.size() < 3) {
            return null; // Degeneriertes Polygon
        }

        // Z-Grenzen des Polygons ermitteln
        double minZ = open.stream().mapToDouble(p -> p.z).min().orElse(0);
        double maxZ = open.stream().mapToDouble(p -> p.z).max().orElse(0);

        // Schnitt nur sinnvoll wenn zCut innerhalb des Z-Bereichs liegt (mit Toleranz)
        if (zCut <= minZ + tolerance || zCut >= maxZ - tolerance) {
            return null;
        }

        // Klassifikations-Schwelle (1mm) - feiner als die Fitzelchen-Toleranz
        double eps = 0.001;
        double zRounded = roundZ(zCut);

        // Sutherland-Hodgman: Polygon an der horizontalen Ebene z=zCut teilen
        List<Point3D> lowerPoly = new ArrayList<>();
        List<Point3D> upperPoly = new ArrayList<>();

        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D p = open.get(i);
            Point3D q = open.get((i + 1) % n);

            boolean pBelow = p.z < zRounded - eps;
            boolean pAbove = p.z > zRounded + eps;

            boolean qBelow = q.z < zRounded - eps;
            boolean qAbove = q.z > zRounded + eps;

            // --- Aktuellen Punkt zuordnen ---
            if (pBelow) {
                lowerPoly.add(p);
            } else if (pAbove) {
                upperPoly.add(p);
            } else {
                // Punkt liegt auf der Schnittebene → gehoert zu beiden Haelften
                Point3D onPlane = new Point3D(p.x, p.y, zRounded);
                lowerPoly.add(onPlane);
                upperPoly.add(onPlane);
            }

            // --- Schnittpunkt berechnen wenn Kante die Ebene kreuzt ---
            if ((pBelow && qAbove) || (pAbove && qBelow)) {
                double t = (zRounded - p.z) / (q.z - p.z);
                Point3D intersection = new Point3D(
                        p.x + t * (q.x - p.x),
                        p.y + t * (q.y - p.y),
                        zRounded
                );
                lowerPoly.add(intersection);
                upperPoly.add(intersection);
            }
        }

        // Validierung: Beide Haelften muessen mindestens 3 Punkte haben
        if (lowerPoly.size() < 3 || upperPoly.size() < 3) {
            return null;
        }

        return new Polygon[]{createPolygon(lowerPoly), createPolygon(upperPoly)};
    }

    /** Prueft, ob der Ring in seiner dominanten Projektionsebene selbstschneidend ist (Bowtie/Pinch). */
    public static boolean ringSelfIntersects(List<Point3D> ring) {
        List<Point3D> p = removeClosingPoint(ring);
        int n = p.size();
        if (n < 4) return false;

        // Pinch: nicht benachbarte, zusammenfallende Vertices
        double tol2 = POINT_MERGE_TOL * POINT_MERGE_TOL;
        for (int i = 0; i < n; i++) {
            Point3D pi = p.get(i);
            for (int j = i + 1; j < n; j++) {
                if (j == i + 1 || (i == 0 && j == n - 1)) continue;
                Point3D pj = p.get(j);
                double dx = pi.x - pj.x, dy = pi.y - pj.y, dz = pi.z - pj.z;
                if (dx * dx + dy * dy + dz * dz < tol2) return true;
            }
        }

        // Auf dominante Ebene projizieren (Newell-Normale)
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < n; i++) {
            Point3D a = p.get(i), b = p.get((i + 1) % n);
            nx += (a.y - b.y) * (a.z + b.z);
            ny += (a.z - b.z) * (a.x + b.x);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        double[][] q = new double[n][2];
        for (int i = 0; i < n; i++) {
            Point3D v = p.get(i);
            if (az >= ax && az >= ay)      { q[i][0] = v.x; q[i][1] = v.y; } // Z verwerfen
            else if (ay >= ax && ay >= az) { q[i][0] = v.x; q[i][1] = v.z; } // Y verwerfen
            else                           { q[i][0] = v.y; q[i][1] = v.z; } // X verwerfen
        }

        // Nicht benachbarte Kanten auf echten Schnitt ODER kollineare Ueberlappung testen.
        for (int i = 0; i < n; i++) {
            double[] a = q[i], b = q[(i + 1) % n];
            for (int k = i + 1; k < n; k++) {
                if (Math.abs(i - k) <= 1 || (i == 0 && k == n - 1)) continue;
                double[] c = q[k], d = q[(k + 1) % n];
                if (segmentsProperlyIntersect(a, b, c, d)) return true;
                if (segmentsCollinearOverlap(a, b, c, d)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsProperlyIntersect(double[] a, double[] b, double[] c, double[] d) {
        double d1 = orient2D(c, d, a), d2 = orient2D(c, d, b);
        double d3 = orient2D(a, b, c), d4 = orient2D(a, b, d);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    /** True, wenn ab und cd kollinear sind und sich in mehr als einem Punkt ueberlappen. */
    private static boolean segmentsCollinearOverlap(double[] a, double[] b, double[] c, double[] d) {
        double eps = 1e-6;
        // Alle vier Punkte kollinear? (c und d auf Gerade ab)
        if (Math.abs(orient2D(a, b, c)) > eps || Math.abs(orient2D(a, b, d)) > eps) return false;
        // Auf die dominante Achse von ab projizieren und 1D-Ueberlappung pruefen
        double dx = b[0] - a[0], dy = b[1] - a[1];
        boolean useX = Math.abs(dx) >= Math.abs(dy);
        double ta = useX ? a[0] : a[1], tb = useX ? b[0] : b[1];
        double tc = useX ? c[0] : c[1], td = useX ? d[0] : d[1];
        double loAB = Math.min(ta, tb), hiAB = Math.max(ta, tb);
        double loCD = Math.min(tc, td), hiCD = Math.max(tc, td);
        double overlap = Math.min(hiAB, hiCD) - Math.max(loAB, loCD);
        return overlap > POINT_MERGE_TOL; // mehr als nur Punkt-Beruehrung
    }

    private static double orient2D(double[] p, double[] q, double[] r) {
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
    }

    /** Ergebnis eines horizontalen Wand-Schnitts: die zusammenhaengenden Stuecke unten und oben. */
    public record WallCut(List<List<Point3D>> lower, List<List<Point3D>> upper) {}

    /** Schneidet ein Wand-Polygon horizontal bei zCut in echte, getrennte Einzelstuecke (nicht self-touching). */
    public static WallCut splitWallByZ(List<Point3D> open, double zCut, double eps) {
        // Ring augmentieren: Schnittpunkte an kreuzenden Kanten einfuegen, On-Punkte auf zCut snappen
        List<Point3D> aug = new ArrayList<>();
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D p = open.get(i), q = open.get((i + 1) % n);
            aug.add(Math.abs(p.z - zCut) <= eps ? new Point3D(p.x, p.y, zCut) : p);
            boolean pBelow = p.z < zCut - eps, pAbove = p.z > zCut + eps;
            boolean qBelow = q.z < zCut - eps, qAbove = q.z > zCut + eps;
            if ((pBelow && qAbove) || (pAbove && qBelow)) {
                double t = (zCut - p.z) / (q.z - p.z);
                aug.add(new Point3D(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y), zCut));
            }
        }
        return new WallCut(extractZRuns(aug, zCut, eps, true), extractZRuns(aug, zCut, eps, false));
    }

    /** Extrahiert aus einem an zCut augmentierten Ring die zusammenhaengenden Stuecke einer Seite. */
    private static List<List<Point3D>> extractZRuns(List<Point3D> aug, double zCut, double eps, boolean lower) {
        int m = aug.size();
        List<List<Point3D>> pieces = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < m; i++) {
            if (!isInside(aug.get(i), zCut, eps, lower)) { start = i; break; }
        }
        if (start == -1) { // alles inSide → ganzer Ring ist ein Stueck
            if (m >= 3) pieces.add(new ArrayList<>(aug));
            return pieces;
        }
        List<Point3D> cur = null;
        for (int k = 1; k <= m; k++) {
            Point3D pt = aug.get((start + k) % m);
            if (isInside(pt, zCut, eps, lower)) {
                if (cur == null) cur = new ArrayList<>();
                cur.add(pt);
            } else if (cur != null) {
                if (isRealPiece(cur, zCut, eps)) pieces.add(cur);
                cur = null;
            }
        }
        if (cur != null && isRealPiece(cur, zCut, eps)) pieces.add(cur);
        return pieces;
    }

    private static boolean isInside(Point3D p, double zCut, double eps, boolean lower) {
        return lower ? p.z <= zCut + eps : p.z >= zCut - eps;
    }

    /** Ein gueltiges Stueck hat >= 3 Punkte und liegt nicht komplett auf der Schnittlinie. */
    private static boolean isRealPiece(List<Point3D> piece, double zCut, double eps) {
        if (piece.size() < 3) return false;
        for (Point3D p : piece) if (Math.abs(p.z - zCut) > eps) return true;
        return false;
    }

    /** Projiziert einen Grundriss auf eine neue Z-Hoehe (X/Y bleiben, Z wird ersetzt). */
    public static List<Point3D> projectToZ(List<Point3D> points, double newZ) {
        List<Point3D> projected = new ArrayList<>(points.size());
        for (Point3D p : points) {
            projected.add(new Point3D(p.x, p.y, newZ));
        }
        return projected;
    }

    /** Erstellt eine MultiSurfaceProperty mit srsName/srsDimension. */
    private static MultiSurfaceProperty createMultiSurfacePropertyWithSrs(Polygon polygon,
            String srsName, int srsDimension) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(srsName);
        ms.setSrsDimension(srsDimension);
        ms.getSurfaceMember().add(new SurfaceProperty(polygon));
        return new MultiSurfaceProperty(ms);
    }

    /** Standard-SRS fuer das Projekt. */
    public static final String SRS_NAME = "urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH";
    public static final int SRS_DIMENSION = 3;

    /** Erstellt eine MultiSurfaceProperty mit Standard-SRS. */
    public static MultiSurfaceProperty createMultiSurfacePropertyWithDefaultSrs(Polygon polygon) {
        return createMultiSurfacePropertyWithSrs(polygon, SRS_NAME, SRS_DIMENSION);
    }

    /** Erstellt eine MultiSurfaceProperty mit XLink-Referenz auf ein bestehendes Polygon. */
    public static MultiSurfaceProperty createXLinkMultiSurfaceProperty(String polygonGmlId) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(SRS_NAME);
        ms.setSrsDimension(SRS_DIMENSION);
        ms.getSurfaceMember().add(new SurfaceProperty("#" + polygonGmlId));
        return new MultiSurfaceProperty(ms);
    }

    // ==================== Wand-Attribute berechnen ====================

    /** Z_MIN und Z_MAX aus einer Liste von Wandpunkten. */
    public static double[] getZRange(List<Point3D> points) {
        double minZ = points.stream().mapToDouble(p -> p.z).min().orElse(0);
        double maxZ = points.stream().mapToDouble(p -> p.z).max().orElse(0);
        return new double[] { minZ, maxZ };
    }

    /** Unterkante eines Wand-Polygons (Kante auf zMin), ihre 2D-Laenge und der Z-Bereich. */
    public record BottomEdge(
            Point3D start,     // Startpunkt der Unterkante
            Point3D end,       // Endpunkt der Unterkante
            double wallLength, // 2D-Laenge der Unterkante [m]
            double zMin,       // unterster Z-Wert des Polygons
            double zMax        // hoechster Z-Wert des Polygons
    ) {}

    /**
     * Ermittelt die Unterkante eines Wand-Polygons: das Punktepaar auf zMin mit
     * dem groessten 2D-Abstand (= die volle Wandbreite am Fuss).
     *
     * <p>Das ist robuster als "die erste Kante bei zMin", wenn mehrere Punkte auf
     * zMin liegen — etwa nach Geschoss-Schnitten mit Zwischenpunkten auf der Sohle
     * oder bei L-/Stufen-Grundrissen. Die volle Spannweite liefert die korrekte
     * Wandlaenge und Richtung als Bezug fuer Tuer-/Fensterplatzierung.
     *
     * @param open offener Polygonring (ohne Schliessungspunkt)
     * @return BottomEdge, oder null wenn weniger als 2 Punkte auf zMin liegen
     */
    public static BottomEdge findBottomEdge(List<Point3D> open) {
        if (open == null || open.size() < 2) return null;
        double[] zRange = getZRange(open);
        double zMin = zRange[0];
        double zMax = zRange[1];
        double zTol = 0.01;

        List<Integer> bottomIndices = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            if (Math.abs(open.get(i).z - zMin) < zTol) bottomIndices.add(i);
        }
        if (bottomIndices.size() < 2) return null;

        // Paar mit maximalem 2D-Abstand waehlen (= volle Wandbreite, nicht das erste Teilstueck)
        int startIdx = bottomIndices.get(0);
        int endIdx = bottomIndices.get(1);
        double maxDist2D = 0;
        for (int i = 0; i < bottomIndices.size(); i++) {
            for (int j = i + 1; j < bottomIndices.size(); j++) {
                double d = calculateEdgeLength2D(
                        open.get(bottomIndices.get(i)), open.get(bottomIndices.get(j)));
                if (d > maxDist2D) {
                    maxDist2D = d;
                    startIdx = bottomIndices.get(i);
                    endIdx = bottomIndices.get(j);
                }
            }
        }
        return new BottomEdge(open.get(startIdx), open.get(endIdx), maxDist2D, zMin, zMax);
    }

    /** String-Key fuer den 2D-Mittelpunkt der untersten Wandkante, zur Dedup-Erkennung geteilter BuildingPart-Waende. */
    public static String wallBottomMidKey(WallSurface wall) {
        Polygon poly = getWallPolygon(wall);
        if (poly == null) return null;
        List<Point3D> pts = toPoints(poly);
        List<Point3D> open = removeClosingPoint(pts);
        if (open.size() < 2) return null;
        double[] zRange = getZRange(open);
        double zBotMin = zRange[0];
        double zTol = 0.01;
        double sumX = 0, sumY = 0;
        int cnt = 0;
        for (Point3D p : open) {
            if (Math.abs(p.z - zBotMin) < zTol) { sumX += p.x; sumY += p.y; cnt++; }
        }
        if (cnt < 2) return null;
        long gx = Math.round((sumX / cnt) * 10);
        long gy = Math.round((sumY / cnt) * 10);
        long gz = Math.round(zBotMin * 10);
        return gx + "," + gy + "," + gz;
    }

    /** Flaeche eines beliebigen planaren 3D-Polygons (Newell's Method). */
    public static double calculateWallArea(List<Point3D> wallPoints) {
        List<Point3D> open = removeClosingPoint(wallPoints);
        if (open.size() < 3) return 0;

        double nx = 0, ny = 0, nz = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D curr = open.get(i);
            Point3D next = open.get((i + 1) % n);
            nx += (curr.y - next.y) * (curr.z + next.z);
            ny += (curr.z - next.z) * (curr.x + next.x);
            nz += (curr.x - next.x) * (curr.y + next.y);
        }

        return 0.5 * Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    /** Azimut der Wandnormalen aus einem beliebigen Wand-Polygon (Unterkante, sonst laengste/erste Kante als Fallback). */
    private static double calculateWallNormalAzimuthFromPolygon(List<Point3D> wallPoints) {
        List<Point3D> open = removeClosingPoint(wallPoints);
        if (open.size() < 3) return 0;

        double minZ = open.stream().mapToDouble(p -> p.z).min().orElse(0);
        double tolerance = 0.01;

        for (int i = 0; i < open.size(); i++) {
            int next = (i + 1) % open.size();
            if (Math.abs(open.get(i).z - minZ) < tolerance && 
                Math.abs(open.get(next).z - minZ) < tolerance) {
                return calculateWallNormalAzimuth(open.get(i), open.get(next));
            }
        }

        // Fallback: Laengste horizontale Kante verwenden
        double bestLen = 0;
        int bestIdx = -1;
        for (int i = 0; i < open.size(); i++) {
            int next = (i + 1) % open.size();
            double dz = Math.abs(open.get(i).z - open.get(next).z);
            if (dz < tolerance) {
                double len = calculateEdgeLength2D(open.get(i), open.get(next));
                if (len > bestLen) {
                    bestLen = len;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx >= 0) {
            return calculateWallNormalAzimuth(open.get(bestIdx),
                    open.get((bestIdx + 1) % open.size()));
        }

        // Letzter Fallback: Erste Kante (selten, z.B. rein schiefe Waende)
        return calculateWallNormalAzimuth(open.get(0), open.get(1));
    }

    /** Fuegt alle Standard-Wand-Attribute (FACEAREA, NORMAL_AZI, Z_MIN/MAX, ...) zu einer WallSurface hinzu. */
    public static void addWallAttributes(WallSurface wall, List<Point3D> wallPoints,
            String faceId, Double hDgm, String geschoss, String lage, String struktur,
            String ursprungspolygonId) {

        double[] zRange = getZRange(wallPoints);
        double wallMinZ = zRange[0];
        double wallMaxZ = zRange[1];
        double area = calculateWallArea(wallPoints);
        double azimuth = calculateWallNormalAzimuthFromPolygon(wallPoints);
        
        addStringAttribute(wall, "BldgFaceID", faceId);
        addStringAttribute(wall, "Z_MAX_ASL", formatNum(wallMaxZ));
        addStringAttribute(wall, "Z_MIN_ASL", formatNum(wallMinZ));
        if (hDgm != null) {
            addStringAttribute(wall, "Z_Max", formatNum(wallMaxZ - hDgm));
            addStringAttribute(wall, "Z_Min", formatNum(wallMinZ - hDgm));
        }
        addStringAttribute(wall, "FACEAREA", formatNum(area));
        addStringAttribute(wall, "NORMAL_AZI", formatNum(azimuth));
        addStringAttribute(wall, "NORMAL_H", "0");
        addStringAttribute(wall, "STRUKTUR", struktur);
        addStringAttribute(wall, "Innenwand", "0");
        addStringAttribute(wall, "Geschoss", geschoss);
        if (lage != null) {
            addStringAttribute(wall, "Lage", lage);
        }
        if (ursprungspolygonId != null) {
            addStringAttribute(wall, "UrsprungspolygonID", ursprungspolygonId);
        }
    }

    /** Fuegt Standard-Attribute (FACEAREA, Z_MIN_ASL, ...) zu einer FloorSurface oder CeilingSurface hinzu. */
    public static void addHorizontalSurfaceAttributes(AbstractCityObject surface,
            String faceId, double z, Double hDgm, double area, String geschoss) {
        addStringAttribute(surface, "BldgFaceID", faceId);
        addStringAttribute(surface, "Z_MIN_ASL", formatNum(z));
        if (hDgm != null) {
            addStringAttribute(surface, "Z_Min", formatNum(z - hDgm));
        }
        addStringAttribute(surface, "FACEAREA", formatNum(area));
        addStringAttribute(surface, "Geschoss", geschoss);
    }

    /** Einfache 3D-Punkt-Klasse. */
    public static class Point3D {
        public final double x;
        public final double y;
        public final double z;

        public Point3D(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public boolean nearlyEquals(Point3D other) {
            if (other == null) return false;
            return Math.abs(x - other.x) < POINT_EQUALITY_EPS
                    && Math.abs(y - other.y) < POINT_EQUALITY_EPS
                    && Math.abs(z - other.z) < POINT_EQUALITY_EPS;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, z);
        }
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
            List<Point3D> points = toPoints(groundPoly);
            if (points.size() < 4) continue;  // mind. 3 Punkte + Schlusspunkt

            // Z-Werte per DGM oder konstantem hDgm setzen
            List<Double> coords = new ArrayList<>(points.size() * 3);
            for (Point3D p : points) {
                double z;
                if (dgm != null && dgm.contains(p.x, p.y)) {
                    double dgmZ = dgm.getHeight(p.x, p.y);
                    z = Double.isNaN(dgmZ) ? hDgm : roundZ(dgmZ);
                } else {
                    z = roundZ(hDgm);
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
            lineString.setSrsName(SRS_NAME);
            lineString.setSrsDimension(SRS_DIMENSION);

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

    /** True, wenn die Flaeche per STRUKTUR-Attribut als Balkon-Deck oder -Bruestung markiert ist. */
    private static boolean isBalconySurface(AbstractThematicSurface surface) {
        String struktur = getStringAttribute(surface, "STRUKTUR");
        return STRUKTUR_BALCONY_DECK.equals(struktur) || STRUKTUR_BALCONY_RAILING.equals(struktur);
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
            if (isBalconySurface(ats)) continue;

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

            // FillingSurfaces (Fenster/Tueren) in die Shell aufnehmen, sonst GE_S_NOT_CLOSED am Lochrand.
            if (surface instanceof WallSurface wall) {
                for (AbstractFillingSurfaceProperty fillProp : wall.getFillingSurfaces()) {
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

    // ==================== Junction-Conforming (T-Nähte) ====================

    private static double dist3(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Fuegt fehlende T-Naht-Vertices auf bestehende Kanten ein (formneutral, siehe Doku.md Schritt 7). */
    public static int conformJunctions(Building building, double tol) {
        List<LinearRing> rings = new ArrayList<>();
        for (AbstractBuilding t : getBuildingTargets(building)) collectShellExteriorRings(t, rings);
        if (rings.size() < 2) return 0;

        // alle Vertices der Huellen-Ringe als Einfuege-Kandidaten sammeln
        List<double[]> verts = new ArrayList<>();
        for (LinearRing r : rings) {
            List<Double> v = ringValues(r);
            if (v == null) continue;
            int m = v.size() / 3;
            for (int i = 0; i < m - 1; i++) {
                verts.add(new double[]{v.get(3 * i), v.get(3 * i + 1), v.get(3 * i + 2)});
            }
        }

        int inserted = 0;
        for (LinearRing r : rings) {
            List<Double> v = ringValues(r);
            if (v == null) continue;
            int m = v.size() / 3;
            if (m < 4) continue;
            List<double[]> pts = new ArrayList<>(m - 1);
            for (int i = 0; i < m - 1; i++) {
                pts.add(new double[]{v.get(3 * i), v.get(3 * i + 1), v.get(3 * i + 2)});
            }

            List<double[]> out = new ArrayList<>();
            int nn = pts.size();
            boolean changed = false;
            for (int i = 0; i < nn; i++) {
                double[] a = pts.get(i), b = pts.get((i + 1) % nn);
                out.add(a);
                // Kandidaten, die auf der Kante a-b liegen (Innen, nicht Endpunkt)
                List<double[]> ins = new ArrayList<>();
                double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
                double len2 = abx * abx + aby * aby + abz * abz;
                if (len2 < 1e-18) continue;
                for (double[] cand : verts) {
                    double tpar = ((cand[0] - a[0]) * abx + (cand[1] - a[1]) * aby + (cand[2] - a[2]) * abz) / len2;
                    if (tpar <= 1e-6 || tpar >= 1 - 1e-6) continue;
                    if (dist3(cand, a) < tol || dist3(cand, b) < tol) continue;
                    double cx = a[0] + tpar * abx, cy = a[1] + tpar * aby, cz = a[2] + tpar * abz;
                    double ex = cand[0] - cx, ey = cand[1] - cy, ez = cand[2] - cz;
                    if (ex * ex + ey * ey + ez * ez < tol * tol) {
                        ins.add(new double[]{tpar, cand[0], cand[1], cand[2]});
                    }
                }
                if (!ins.isEmpty()) {
                    ins.sort((x, y2) -> Double.compare(x[0], y2[0]));
                    double lastT = -1;
                    for (double[] c : ins) {
                        if (c[0] - lastT < 1e-6) continue; // doppeltes t
                        out.add(new double[]{c[1], c[2], c[3]});
                        lastT = c[0];
                        inserted++;
                        changed = true;
                    }
                }
            }

            if (changed) {
                List<Double> nv = new ArrayList<>((out.size() + 1) * 3);
                for (double[] c : out) { nv.add(c[0]); nv.add(c[1]); nv.add(c[2]); }
                nv.add(out.get(0)[0]); nv.add(out.get(0)[1]); nv.add(out.get(0)[2]);
                r.getControlPoints().getPosList().setValue(nv);
            }
        }
        return inserted;
    }

    private static List<Double> ringValues(LinearRing r) {
        if (r.getControlPoints() == null || r.getControlPoints().getPosList() == null) return null;
        return r.getControlPoints().getPosList().getValue();
    }

    /** Sammelt die Aussenring-LinearRings aller Huellen-Polygone (wie {@link #rebuildSolidShell}). */
    private static void collectShellExteriorRings(AbstractBuilding target, List<LinearRing> out) {
        for (var boundary : target.getBoundaries()) {
            var surface = boundary.getObject();
            if (!(surface instanceof AbstractThematicSurface ats)) continue;
            if (surface instanceof FloorSurface || surface instanceof CeilingSurface) continue;
            if (isBalconySurface(ats)) continue;
            addExteriorRings(ats.getMultiSurface(3), out);
            if (surface instanceof WallSurface wall) {
                for (AbstractFillingSurfaceProperty fp : wall.getFillingSurfaces()) {
                    var fill = fp.getObject();
                    if (fill != null) addExteriorRings(fill.getMultiSurface(3), out);
                }
            }
        }
    }

    private static void addExteriorRings(MultiSurfaceProperty msp, List<LinearRing> out) {
        if (msp == null || msp.getObject() == null) return;
        for (SurfaceProperty m : msp.getObject().getSurfaceMember()) {
            if (m.getObject() instanceof Polygon poly
                    && poly.getExterior() != null
                    && poly.getExterior().getObject() instanceof LinearRing r) {
                out.add(r);
            }
        }
    }

    // ==================== GML-Datei-Verarbeitung ====================

    /** Liest alle Features einer CityGML-Datei, wendet processor auf jedes Building an, schreibt das Ergebnis. */
    public static void processGmlFile(Path input, Path output,
            Consumer<Building> processor) throws Exception {

        CityGMLContext context = CityGMLContext.newInstance();
        var in = context.createCityGMLInputFactory()
                .withChunking(ChunkOptions.defaults());
        CityGMLOutputFactory out = context.createCityGMLOutputFactory(CityGMLVersion.v1_0);

        org.xmlobjects.gml.model.feature.BoundingShape originalBoundedBy = null;
        try (CityGMLReader headerReader = context.createCityGMLInputFactory()
                .createCityGMLReader(input.toFile())) {
            if (headerReader.hasNext()) {
                var firstFeature = headerReader.next();
                if (firstFeature instanceof org.citygml4j.core.model.core.CityModel cm) {
                    originalBoundedBy = cm.getBoundedBy();
                }
            }
        }

        try (CityGMLReader reader = in.createCityGMLReader(input.toFile());
             CityGMLChunkWriter writer = out.createCityGMLChunkWriter(output,
                     StandardCharsets.UTF_8.name())) {

            writer.withIndent("\t").withDefaultPrefixes();
            if (originalBoundedBy != null) {
                writer.getCityModelInfo().setBoundedBy(originalBoundedBy);
                log.info("BoundedBy-Envelope uebernommen");
            }

            while (reader.hasNext()) {
                AbstractFeature feature = reader.next();
                if (feature instanceof Building building) {
                    processor.accept(building);
                }
                writer.writeMember(feature);
            }
        }
    }

    /** Erstellt den Ausgabe-Pfad aus Eingabe-Pfad + Suffix, oder verwendet explicitOutput falls gesetzt. */
    public static Path resolveOutputPath(Path inputPath, String suffix, Path explicitOutput) {
        if (explicitOutput != null) return explicitOutput;
        String baseName = inputPath.getFileName().toString();
        if (baseName.toLowerCase().endsWith(".gml")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String outputName = baseName + suffix + ".gml";
        return inputPath.getParent() != null
                ? inputPath.getParent().resolve(outputName)
                : Paths.get(outputName);
    }

    // ==================== Weitere Geometrie-Hilfsmethoden ====================

    /** Erzeugt einen geschlossenen LinearRing aus einer Punktliste (z.B. fuer Fenster-/Tuerausschnitte). */
    public static LinearRing createLinearRing(List<Point3D> pts) {
        List<Point3D> open = dedupConsecutive(pts, POINT_MERGE_TOL);
        List<Double> coords = new ArrayList<>(open.size() * 3 + 3);
        for (Point3D p : open) {
            coords.add(p.x);
            coords.add(p.y);
            coords.add(p.z);
        }
        coords.add(open.get(0).x);
        coords.add(open.get(0).y);
        coords.add(open.get(0).z);

        DirectPositionList posList = new DirectPositionList(coords);
        posList.setSrsDimension(3);

        LinearRing ring = new LinearRing();
        ring.getControlPoints().setPosList(posList);
        return ring;
    }

    /** Ray-Casting: prueft ob ein Punkt (px, py) innerhalb eines 2D-Polygons liegt. */
    public static boolean pointInPolygon2D(double px, double py, double[][] poly) {
        int n = poly.length;
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            double yi = poly[i][1], yj = poly[j][1];
            double xi = poly[i][0], xj = poly[j][0];

            if ((yi > py) != (yj > py)) {
                double xIntersect = xi + (py - yi) / (yj - yi) * (xj - xi);
                if (px < xIntersect) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    // ==================== Oeffnungen (Fenster/Tueren) ====================

    /** Projiziert einen offenen Wand-Ring in die 2D-Wandebene (u=entlang Unterkante, v=z-zMin). */
    public static double[][] projectWallTo2D(List<Point3D> open, Point3D edgeStart,
            double dirX, double dirY, double zMin) {
        double[][] poly2D = new double[open.size()][2];
        for (int i = 0; i < open.size(); i++) {
            Point3D p = open.get(i);
            poly2D[i][0] = (p.x - edgeStart.x) * dirX + (p.y - edgeStart.y) * dirY;
            poly2D[i][1] = p.z - zMin;
        }
        return poly2D;
    }

    /** Prueft, ob ein Oeffnungs-Rechteck (alle 4 Ecken) vollstaendig im Wandpolygon liegt. */
    public static boolean openingInsideWall2D(double uLeft, double uRight,
            double vBottom, double vTop, double[][] wallPoly2D) {
        return pointInPolygon2D(uLeft,  vBottom, wallPoly2D)
            && pointInPolygon2D(uRight, vBottom, wallPoly2D)
            && pointInPolygon2D(uRight, vTop,    wallPoly2D)
            && pointInPolygon2D(uLeft,  vTop,    wallPoly2D);
    }

    /** Fuegt eine rechteckige Oeffnung in ein Wand-Polygon ein, liefert das FillingSurface-Polygon dazu. */
    public static Polygon addOpeningToWall(Polygon wallPoly, Point3D bl, Point3D br,
            Point3D tr, Point3D tl, boolean extCCW) {
        List<Point3D> innerRing = extCCW
                ? List.of(bl, tl, tr, br)   // exterior CCW → interior CW
                : List.of(bl, br, tr, tl);  // exterior CW  → interior CCW
        wallPoly.getInterior().add(new AbstractRingProperty(createLinearRing(innerRing)));

        List<Point3D> fillingPoints = extCCW
                ? List.of(bl, br, tr, tl)   // CCW (Standard)
                : List.of(bl, tl, tr, br);  // CW  (Sachsen LoD2)
        return createPolygon(fillingPoints);
    }

    /** Liest die Punkte eines beliebigen LinearRing (nicht nur des Aussenrings eines Polygons). */
    private static List<Point3D> pointsOfRing(LinearRing ring) {
        if (ring.getControlPoints() == null || ring.getControlPoints().getPosList() == null) {
            return Collections.emptyList();
        }
        List<Double> coords = ring.getControlPoints().getPosList().getValue();
        if (coords == null || coords.isEmpty()) return Collections.emptyList();
        List<Point3D> pts = new ArrayList<>();
        for (int i = 0; i + 2 < coords.size(); i += 3) {
            pts.add(new Point3D(coords.get(i), coords.get(i + 1), coords.get(i + 2)));
        }
        return pts;
    }

    /** True, wenn jeder Punkt aus {@code a} einen (nicht doppelt genutzten) Partner in {@code b} hat. */
    private static boolean pointSetsMatch(List<Point3D> a, List<Point3D> b, double tol) {
        if (a.size() != b.size()) return false;
        boolean[] used = new boolean[b.size()];
        double tol2 = tol * tol;
        for (Point3D pa : a) {
            boolean found = false;
            for (int j = 0; j < b.size(); j++) {
                if (used[j]) continue;
                Point3D pb = b.get(j);
                double dx = pa.x - pb.x, dy = pa.y - pb.y, dz = pa.z - pb.z;
                if (dx * dx + dy * dy + dz * dz < tol2) {
                    used[j] = true;
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** Entfernt den Innenring eines Wand-Polygons, dessen Punkte mit openingPoints uebereinstimmen. */
    public static boolean removeMatchingInteriorRing(Polygon wallPoly, List<Point3D> openingPoints) {
        List<Point3D> target = removeClosingPoint(dedupConsecutive(openingPoints, POINT_MERGE_TOL));
        var it = wallPoly.getInterior().iterator();
        while (it.hasNext()) {
            AbstractRingProperty ringProp = it.next();
            if (!(ringProp.getObject() instanceof LinearRing ring)) continue;
            List<Point3D> ringPts = removeClosingPoint(dedupConsecutive(pointsOfRing(ring), POINT_MERGE_TOL));
            if (pointSetsMatch(ringPts, target, POINT_MERGE_TOL)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** Min. 2D-Abstand des Punktes (px,py) zur Kante {@code (ax,ay)-(bx,by)}. */
    private static double distPointToSegmentXY(double px, double py,
            double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = (len2 < 1e-18) ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    /** Min. 2D-Abstand des Punktes (px,py) zur Aussenkante eines Polygonrings. */
    public static double distPointToRingXY(double px, double py, List<Point3D> ring) {
        List<Point3D> open = removeClosingPoint(ring);
        int n = open.size();
        if (n < 2) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Point3D a = open.get(i), b = open.get((i + 1) % n);
            best = Math.min(best, distPointToSegmentXY(px, py, a.x, a.y, b.x, b.y));
        }
        return best;
    }

}
