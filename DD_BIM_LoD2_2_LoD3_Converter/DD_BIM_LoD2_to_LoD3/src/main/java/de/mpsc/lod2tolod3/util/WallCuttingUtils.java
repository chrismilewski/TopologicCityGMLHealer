package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontale Wand-Schnitte: der klassische Sutherland-Hodgman-Einzelschnitt sowie der
 * robustere JTS-Bandschnitt an mehreren Hoehen gleichzeitig.
 */
public final class WallCuttingUtils {

    private static final Logger log = LoggerFactory.getLogger(WallCuttingUtils.class);

    private WallCuttingUtils() {
        // Utility-Klasse
    }

    // ==================== Wand-Schnitt (Sutherland-Hodgman) ====================

    /** Schneidet ein Wand-Polygon horizontal bei zCut (Sutherland-Hodgman), liefert [untererTeil, obererTeil]. */
    public static Polygon[] cutWallPolygonAtZ(Polygon wallPoly, double zCut, double tolerance) {
        List<Point3D> pts = GeometryUtils.toPoints(wallPoly);
        List<Point3D> open = GeometryUtils.removeClosingPoint(pts);

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
        double zRounded = GeometryUtils.roundZ(zCut);

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

        return new Polygon[]{GeometryUtils.createPolygon(lowerPoly), GeometryUtils.createPolygon(upperPoly)};
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
    static boolean isRealPiece(List<Point3D> piece, double zCut, double eps) {
        if (piece.size() < 3) return false;
        for (Point3D p : piece) if (Math.abs(p.z - zCut) > eps) return true;
        return false;
    }

    // ==================== JTS-Bandschnitt an mehreren Hoehen ====================

    /** Kleinere JTS-Bandschnitt-Ergebnisse sind Rauschsplitter (Rundungsschlieren an der
     * Schnittkante), keine echten Wandstuecke. */
    private static final double MIN_WALL_PIECE_AREA = 0.001;

    /** Schneidet ein Wand-Polygon an mehreren Z-Hoehen in echte Einzelstuecke — robust auch bei
     * konkaven Profilen (z.B. Wand mit Anbau-Kerbe, die bis zum Boden reicht: eine Schnittebene
     * kreuzt den Umriss dann mehr als zweimal), da JTS beliebige Polygon-Topologien korrekt in
     * (Multi-)Teilstuecke zerlegt statt bei mehrfach kreuzenden Ebenen den Schnitt zu verwerfen.
     * Arbeitet im wandeigenen (u,v)-Profil (u=Position entlang Unterkante, v=Hoehe ueber zMin,
     * siehe {@link GeometryUtils#projectWallTo2D}) und schneidet dort bandweise statt iterativ Ebene fuer
     * Ebene — jedes Hoehenband wird unabhaengig mit der Wandflaeche geschnitten, Ergebnis-Stuecke
     * werden 1:1 zurueck nach 3D projiziert. Null bei Entartung oder JTS-Fehler. */
    public static List<Polygon> cutWallAtMultipleZJTS(List<Point3D> open, List<Double> cutZValues) {
        List<Point3D> pts = GeometryUtils.removeClosingPoint(open);
        if (pts.size() < 3 || cutZValues.isEmpty()) return null;

        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(pts);
        if (edge == null) return null;
        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dirX = dx / edge.wallLength();
        double dirY = dy / edge.wallLength();
        Point3D edgeStart = edge.start();
        double zMin = edge.zMin();

        double[][] poly2D = GeometryUtils.projectWallTo2D(pts, edgeStart, dirX, dirY, zMin);

        try {
            org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
            org.locationtech.jts.geom.Coordinate[] coords =
                    new org.locationtech.jts.geom.Coordinate[poly2D.length + 1];
            double uMin = Double.POSITIVE_INFINITY, uMax = Double.NEGATIVE_INFINITY;
            double vMin = Double.POSITIVE_INFINITY, vMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < poly2D.length; i++) {
                coords[i] = new org.locationtech.jts.geom.Coordinate(poly2D[i][0], poly2D[i][1]);
                uMin = Math.min(uMin, poly2D[i][0]); uMax = Math.max(uMax, poly2D[i][0]);
                vMin = Math.min(vMin, poly2D[i][1]); vMax = Math.max(vMax, poly2D[i][1]);
            }
            coords[poly2D.length] = coords[0];
            boolean origCCW = signedArea2D(coords) > 0;
            org.locationtech.jts.geom.LinearRing shell = gf.createLinearRing(coords);
            org.locationtech.jts.geom.Polygon wallJts = gf.createPolygon(shell);
            if (!wallJts.isValid()) return null;

            List<Double> vCuts = new ArrayList<>();
            for (double cutZ : cutZValues) vCuts.add(cutZ - zMin);
            java.util.Collections.sort(vCuts);

            double margin = 1.0;
            double bandU0 = uMin - margin, bandU1 = uMax + margin;
            double bandVLo = vMin - margin;

            List<Polygon> result = new ArrayList<>();
            for (int b = 0; b <= vCuts.size(); b++) {
                double bandVHi = (b < vCuts.size()) ? vCuts.get(b) : vMax + margin;
                org.locationtech.jts.geom.Coordinate[] bandCoords = {
                        new org.locationtech.jts.geom.Coordinate(bandU0, bandVLo),
                        new org.locationtech.jts.geom.Coordinate(bandU1, bandVLo),
                        new org.locationtech.jts.geom.Coordinate(bandU1, bandVHi),
                        new org.locationtech.jts.geom.Coordinate(bandU0, bandVHi),
                        new org.locationtech.jts.geom.Coordinate(bandU0, bandVLo)
                };
                org.locationtech.jts.geom.Polygon bandRect =
                        gf.createPolygon(gf.createLinearRing(bandCoords));
                org.locationtech.jts.geom.Geometry piece = wallJts.intersection(bandRect);
                for (int i = 0; i < piece.getNumGeometries(); i++) {
                    if (!(piece.getGeometryN(i) instanceof org.locationtech.jts.geom.Polygon jtsPoly)) continue;
                    org.locationtech.jts.geom.Coordinate[] ring = jtsPoly.getExteriorRing().getCoordinates();
                    if (ring.length < 4) continue; // < 3 offene Punkte
                    double signedArea = signedArea2D(ring);
                    if (Math.abs(signedArea) / 2.0 < MIN_WALL_PIECE_AREA) continue; // JTS-Rauschsplitter
                    // JTS legt die Umlaufrichtung eines Intersection-Ergebnisses nicht fest — auf
                    // die Original-Orientierung der Wand zurueckdrehen, sonst zeigt die Normale
                    // ins Gebaeude (val3dity POLYGON_WRONG_ORIENTATION/NON_MANIFOLD_CASE).
                    boolean pieceCCW = signedArea > 0;
                    int start = ring.length - 1; // letzter Punkt == erster, Schliesspunkt weglassen
                    List<Point3D> seg3D = new ArrayList<>(start);
                    for (int k = 0; k < start; k++) {
                        int idx = (pieceCCW == origCCW) ? k : (start - k) % start;
                        double u = ring[idx].x, v = ring[idx].y;
                        // Punkt, der (bis auf JTS-Rundung) einem Original-Eckpunkt entspricht, auf
                        // dessen exakte Koordinate zurueckschnappen — nur echte, neu entstandene
                        // Schnittpunkte werden interpoliert. Sonst driften unveraenderte Kanten
                        // (die Nachbarwaende/Boeden/Decken exakt treffen muessen) im Sub-mm-Bereich
                        // auseinander → val3dity SHELL_NOT_CLOSED.
                        Point3D original = findOriginalPoint(poly2D, pts, u, v, GeometryUtils.POINT_MERGE_TOL);
                        if (original == null) {
                            // Echter neuer Schnittpunkt: direkt auf der betroffenen Original-3D-Kante
                            // interpolieren (wie der alte Einzelschnitt-Algorithmus) statt ueber den
                            // (u,v)-Rundweg — haengt so nur von den beiden Kanten-Endpunkten ab, nicht
                            // von wandspezifischen abgeleiteten Groessen (edgeStart/dir), und ist damit
                            // reproduzierbar, falls dieselbe physische Kante andernorts erneut geschnitten wird.
                            original = interpolateOnOriginalEdge(poly2D, pts, u, v, GeometryUtils.POINT_MERGE_TOL);
                        }
                        seg3D.add(original != null ? original : new Point3D(
                                edgeStart.x + u * dirX,
                                edgeStart.y + u * dirY,
                                GeometryUtils.roundZ(zMin + v)));
                    }
                    seg3D = GeometryUtils.dedupConsecutive(seg3D, GeometryUtils.POINT_MERGE_TOL);
                    if (seg3D.size() < 3) continue;
                    result.add(GeometryUtils.createPolygon(seg3D));
                }
                bandVLo = bandVHi;
            }
            return result.isEmpty() ? null : result;
        } catch (RuntimeException e) {
            log.warn("  cutWallAtMultipleZJTS: JTS-Schnitt fehlgeschlagen ({}), Wand unveraendert", e.toString());
            return null;
        }
    }

    /** Findet den Original-3D-Punkt zu einem (u,v)-Koordinatenpaar, falls einer innerhalb der
     * Toleranz existiert (unveraenderte Original-Ecke) — sonst null (echter neuer Schnittpunkt). */
    private static Point3D findOriginalPoint(double[][] poly2D, List<Point3D> pts,
            double u, double v, double tol) {
        for (int i = 0; i < poly2D.length; i++) {
            double du = poly2D[i][0] - u, dv = poly2D[i][1] - v;
            if (du * du + dv * dv <= tol * tol) return pts.get(i);
        }
        return null;
    }

    /** Interpoliert einen (u,v)-Punkt direkt auf der Original-3D-Kante, die ihn erzeugt hat (statt
     * ueber den (u,v)-Rundweg edgeStart+u*dir) — haengt nur von den beiden Kanten-Endpunkten ab.
     * Null, wenn keine Original-Kante bei diesem (u,v) kreuzt (sollte fuer echte Schnittpunkte
     * nicht vorkommen; robuster Fallback im Aufrufer). */
    private static Point3D interpolateOnOriginalEdge(double[][] poly2D, List<Point3D> pts,
            double u, double v, double tol) {
        int n = poly2D.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double pu = poly2D[i][0], pv = poly2D[i][1];
            double qu = poly2D[j][0], qv = poly2D[j][1];
            if (Math.abs(qv - pv) < 1e-9) continue; // horizontale Kante kreuzt keine Hoehenebene
            double t = (v - pv) / (qv - pv);
            if (t < -1e-6 || t > 1 + 1e-6) continue;
            double uAtT = pu + t * (qu - pu);
            if (Math.abs(uAtT - u) > tol) continue;
            Point3D p = pts.get(i), q = pts.get(j);
            return new Point3D(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y), p.z + t * (q.z - p.z));
        }
        return null;
    }

    /** Vorzeichenbehaftete 2D-Flaeche (Shoelace) eines geschlossenen JTS-Rings; positiv = CCW. */
    static double signedArea2D(org.locationtech.jts.geom.Coordinate[] ring) {
        double area2 = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            area2 += ring[i].x * ring[i + 1].y - ring[i + 1].x * ring[i].y;
        }
        return area2;
    }
}
