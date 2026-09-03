package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.LinearRing;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Grundlegende Geometrie-Berechnungen und -Hilfsmethoden, von fast allen anderen
 * *Utils-Klassen dieses Pakets verwendet.
 */
public final class GeometryUtils {

    private GeometryUtils() {
        // Utility-Klasse
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

    /** Kehrt die Punktreihenfolge um, falls die Newell-Normale nicht in die gewuenschte
     * Z-Richtung zeigt (z.B. Kellerboden soll immer nach unten zeigen, siehe Doku.md). */
    public static List<Point3D> orientForNormalZ(List<Point3D> ring, boolean wantUpward) {
        double nz = 0;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            Point3D a = ring.get(i), b = ring.get((i + 1) % n);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        boolean pointsUpward = nz >= 0;
        if (pointsUpward == wantUpward) return ring;
        List<Point3D> rev = new ArrayList<>(ring);
        Collections.reverse(rev);
        return rev;
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

    /** Toleranz fuer den finalen Ring-Dedup in {@link #createPolygon}, bewusst groesser als
     * {@link #POINT_MERGE_TOL}: CityDoctor2's eigener GE_R_CONSECUTIVE_POINTS_SAME-Check nutzt
     * `minVertexDistance=0.00173` (siehe Nutzer-Konfiguration, Doku.md) — zwei durch getrennte
     * JTS-Berechnungen entstandene Kopien DESSELBEN Eckpunkts koennen bis zu 1mm auseinander
     * liegen (Rundungsdrift) und wurden von POINT_MERGE_TOL (1mm) nicht zuverlaessig erkannt, da
     * CityDoctor2's Schwelle strenger ist als unsere eigene. 2mm liegt sicher darueber. */
    public static final double RING_DEDUP_TOL = 0.002;

    /** Wie {@link #RING_DEDUP_TOL}, aber nur fuer clipSlabAtZ-Geschossdecken/-boeden
     * ({@link SlabClippingUtils#createPolygonWithHoles}, ausschliesslich dort verwendet): am
     * zyklischen Ringschluss einer Geschossdecke real bis zu 2,2mm beobachtete Rundungsdrift
     * (groesser als bei Wandstuecken) — bewusst NICHT fuer alle {@link #createPolygon}-Aufrufe
     * erhoeht, da eine pauschale Erhoehung an einer voellig unbeteiligten Wand (anderes Gebaeude)
     * eine neue, wenn auch grenzwertige Planaritaetsmeldung ausgeloest hat (siehe Doku.md). */
    static final double SLAB_RING_DEDUP_TOL = 0.003;

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
        return createPolygon(pts, RING_DEDUP_TOL);
    }

    static Polygon createPolygon(List<Point3D> pts, double dedupTol) {
        List<Point3D> open = dedupConsecutive(pts, dedupTol);
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

    // ==================== Selbstschnitt-Test ====================

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

    static boolean segmentsProperlyIntersect(double[] a, double[] b, double[] c, double[] d) {
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

    /** Liest die Wandflaeche aus dem Attribut FACEAREA, oder berechnet sie als Fallback aus dem
     * Polygon (z.B. bei neu geschnittenen Wandstuecken ohne eigenes FACEAREA-Attribut). */
    public static double resolveWallArea(WallSurface wall, List<Point3D> open) {
        String faceAreaStr = CityGmlUtils.getStringAttribute(wall, "FACEAREA");
        if (faceAreaStr != null) {
            try { return Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        return calculateWallArea(open);
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

        CityGmlUtils.addStringAttribute(wall, "BldgFaceID", faceId);
        CityGmlUtils.addStringAttribute(wall, "Z_MAX_ASL", formatNum(wallMaxZ));
        CityGmlUtils.addStringAttribute(wall, "Z_MIN_ASL", formatNum(wallMinZ));
        if (hDgm != null) {
            CityGmlUtils.addStringAttribute(wall, "Z_Max", formatNum(wallMaxZ - hDgm));
            CityGmlUtils.addStringAttribute(wall, "Z_Min", formatNum(wallMinZ - hDgm));
        }
        CityGmlUtils.addStringAttribute(wall, "FACEAREA", formatNum(area));
        CityGmlUtils.addStringAttribute(wall, "NORMAL_AZI", formatNum(azimuth));
        CityGmlUtils.addStringAttribute(wall, "NORMAL_H", "0");
        CityGmlUtils.addStringAttribute(wall, "STRUKTUR", struktur);
        CityGmlUtils.addStringAttribute(wall, "Innenwand", "0");
        CityGmlUtils.addStringAttribute(wall, "Geschoss", geschoss);
        if (lage != null) {
            CityGmlUtils.addStringAttribute(wall, "Lage", lage);
        }
        if (ursprungspolygonId != null) {
            CityGmlUtils.addStringAttribute(wall, "UrsprungspolygonID", ursprungspolygonId);
        }
    }

    /** Fuegt Standard-Attribute (FACEAREA, Z_MIN_ASL, ...) zu einer FloorSurface oder CeilingSurface hinzu. */
    public static void addHorizontalSurfaceAttributes(AbstractCityObject surface,
            String faceId, double z, Double hDgm, double area, String geschoss) {
        CityGmlUtils.addStringAttribute(surface, "BldgFaceID", faceId);
        CityGmlUtils.addStringAttribute(surface, "Z_MIN_ASL", formatNum(z));
        if (hDgm != null) {
            CityGmlUtils.addStringAttribute(surface, "Z_Min", formatNum(z - hDgm));
        }
        CityGmlUtils.addStringAttribute(surface, "FACEAREA", formatNum(area));
        CityGmlUtils.addStringAttribute(surface, "Geschoss", geschoss);
    }

    /** Projiziert einen Grundriss auf eine neue Z-Hoehe (X/Y bleiben, Z wird ersetzt). */
    public static List<Point3D> projectToZ(List<Point3D> points, double newZ) {
        List<Point3D> projected = new ArrayList<>(points.size());
        for (Point3D p : points) {
            projected.add(new Point3D(p.x, p.y, newZ));
        }
        return projected;
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

    // ==================== Ebenen-Projektion ====================

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

    /** Wie {@link #projectWallTo2D}, aber mit echtem 3D-"Aufwaerts"-Vektor (up, normiert) statt
     * der Wand-Annahme v=z-zMin — fuer geneigte Flaechen (z.B. Dachschraegen). */
    public static double[][] projectPlaneTo2D(List<Point3D> open, Point3D origin,
            double dirX, double dirY, double upX, double upY, double upZ) {
        double[][] poly2D = new double[open.size()][2];
        for (int i = 0; i < open.size(); i++) {
            Point3D p = open.get(i);
            double dx = p.x - origin.x, dy = p.y - origin.y, dz = p.z - origin.z;
            poly2D[i][0] = dx * dirX + dy * dirY;
            poly2D[i][1] = dx * upX + dy * upY + dz * upZ;
        }
        return poly2D;
    }

    /** Ermittelt den normierten "Aufwaerts"-Vektor (Traufe -> First) einer geneigten Flaeche:
     * Newell-Normale der Flaeche gekreuzt mit der (horizontalen) Traufrichtung, Vorzeichen so
     * gewaehlt, dass die Z-Komponente positiv ist (zeigt zum First). Liefert {@code null} bei
     * degenerierter/planloser Normale (z.B. entartetes Polygon). */
    public static double[] computeUpSlopeVector(List<Point3D> open, double dirX, double dirY) {
        double nx = 0, ny = 0, nz = 0;
        int n = open.size();
        for (int i = 0; i < n; i++) {
            Point3D a = open.get(i);
            Point3D b = open.get((i + 1) % n);
            nx += (a.y - b.y) * (a.z + b.z);
            ny += (a.z - b.z) * (a.x + b.x);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        double nLen = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nLen < 1e-9) return null;
        nx /= nLen; ny /= nLen; nz /= nLen;

        // up = normal x traufRichtung (dirX, dirY, 0)
        double ux = ny * 0 - nz * dirY;
        double uy = nz * dirX - nx * 0;
        double uz = nx * dirY - ny * dirX;
        double uLen = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (uLen < 1e-9) return null;
        ux /= uLen; uy /= uLen; uz /= uLen;
        if (uz < 0) { ux = -ux; uy = -uy; uz = -uz; }
        return new double[]{ux, uy, uz};
    }

    // ==================== Ring-Punkte ====================

    /** Liest die Punkte eines beliebigen LinearRing (nicht nur des Aussenrings eines Polygons). */
    static List<Point3D> pointsOfRing(LinearRing ring) {
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
}
