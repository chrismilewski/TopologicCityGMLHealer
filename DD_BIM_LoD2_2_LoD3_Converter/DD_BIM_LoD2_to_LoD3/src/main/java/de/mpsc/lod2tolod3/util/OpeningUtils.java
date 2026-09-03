package de.mpsc.lod2tolod3.util;

import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.LinearRing;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.List;

/**
 * Platzierungs-Pruefungen und Erzeugung von Oeffnungen (Fenster/Tueren) in Wand-/Dachpolygonen.
 */
public final class OpeningUtils {

    private OpeningUtils() {
        // Utility-Klasse
    }

    /** Prueft, ob ein Oeffnungs-Rechteck (alle 4 Ecken) vollstaendig im Wandpolygon liegt. */
    public static boolean openingInsideWall2D(double uLeft, double uRight,
            double vBottom, double vTop, double[][] wallPoly2D) {
        return GeometryUtils.pointInPolygon2D(uLeft,  vBottom, wallPoly2D)
            && GeometryUtils.pointInPolygon2D(uRight, vBottom, wallPoly2D)
            && GeometryUtils.pointInPolygon2D(uRight, vTop,    wallPoly2D)
            && GeometryUtils.pointInPolygon2D(uLeft,  vTop,    wallPoly2D);
    }

    /** Sicherheitsabstand nur an der Oberkante einer Oeffnung (2cm) — ohne ihn kann eine
     * Oeffnungs-Oberkante exakt auf der Wand-Oberkante landen (z.B. oberstes Geschoss ohne
     * GeschossDeckeZ, Fenster reicht bis zur Traufe); Ray-Casting ist an Randpunkten mehrdeutig
     * und laesst den strikten Containment-Test dann faelschlich "innen" liefern, obwohl der
     * Innenring die Aussenkontur beruehrt (CityDoctor GE_P_INTERIOR_DISCONNECTED, siehe Doku.md).
     * Bewusst NUR an der Oberkante, nicht links/rechts/unten — eine breitere Anwendung wuerde
     * echte Giebel-Abschnitte (schraege Seitenkanten) mitbeeinflussen und ueber veraenderte
     * Fensterzahlen in die nachgelagerte Balkon-Platzierung kaskadieren. */
    public static final double OPENING_TOP_CLEARANCE = 0.02;

    /** Wie {@link #openingInsideWall2D}, mit zusaetzlichem Sicherheitsabstand {@link
     * #OPENING_TOP_CLEARANCE} nur an der Oberkante (vTop): der Testpunkt wird bewusst Richtung
     * Kontur (nach OBEN) verschoben, damit eine Oberkante, die der Wandkontur zu nah ist, den
     * Test auch tatsaechlich verfehlt — nicht nach unten, das wuerde nur pruefen, ob ein
     * geschrumpftes Fenster passt, ohne die Naehe der echten Oberkante zur Kontur zu erfassen. */
    public static boolean openingInsideWallTopClearance2D(double uLeft, double uRight,
            double vBottom, double vTop, double[][] wallPoly2D) {
        return openingInsideWall2D(uLeft, uRight, vBottom, vTop + OPENING_TOP_CLEARANCE, wallPoly2D);
    }

    /** Wie {@link #openingInsideWallTopClearance2D}, zusaetzlich mit demselben Sicherheitsabstand
     * an LINKER und RECHTER Kante (uLeft/uRight nach aussen verschoben) — faengt den Fall ab, dass
     * eine Fensterkante exakt auf einer WAND-INTERNEN Kerbe liegt (z.B. Anbau-Notch in einer nicht-
     * konvexen Wandkontur), wo der reine 4-Eckpunkt-Ray-Casting-Test der Wand-Unterkante zu
     * unsicher ist (GE_P_INTERIOR_DISCONNECTED, analog zum Traufe-Fall oben). Bewusst weiterhin
     * OHNE Unterkante — bodenbuendige Fenster (z.B. BA-Kellerfenster ab Gelaende) bleiben gueltig. */
    public static boolean openingInsideWallSideTopClearance2D(double uLeft, double uRight,
            double vBottom, double vTop, double[][] wallPoly2D) {
        return openingInsideWall2D(uLeft - OPENING_TOP_CLEARANCE, uRight + OPENING_TOP_CLEARANCE,
                vBottom, vTop + OPENING_TOP_CLEARANCE, wallPoly2D);
    }

    /** Erkennt den Fall, dass die Wandkontur selbst mitten in eine (sonst gueltige) Oeffnung
     * hineinragt — z.B. eine "M"-foermige Wand unter einem Satteldach mit Gaube (zwei First-
     * Spitzen, ein Tal dazwischen): eine Oeffnung, die das Tal ueberspannt, besteht den reinen
     * 4-Eckpunkt-Containment-Test (beide Ecken liegen unterhalb der jeweils benachbarten Spitze),
     * obwohl ihre Oberkante mitten durch das Tal der Wandkontur verlaeuft (GE_P_INTERSECTING_RINGS/
     * val3dity 201). Reine Eckpunkt-Tests koennen das grundsaetzlich nicht erkennen. Prueft daher
     * zweistufig: (1) liegt ein Wand-Eckpunkt selbst strikt innerhalb des Oeffnungs-Rechtecks
     * (mit Sicherheitsabstand), und (2) durchquert irgendeine WAND-Kante (echt, nicht nur
     * beruehrend) eine der 4 Rechteck-Kanten — noetig fuer den Fall, dass die Kontur zwischen
     * zwei Eckpunkten durchhaengt, ohne dass ein Eckpunkt selbst im Rechteck liegt. */
    public static boolean wallContourEntersOpening(double uLeft, double uRight,
            double vBottom, double vTop, double[][] wallPoly2D) {
        double lo = OPENING_TOP_CLEARANCE;
        double left = uLeft + lo, right = uRight - lo, bottom = vBottom + lo, top = vTop - lo;
        if (right <= left || top <= bottom) return false; // Oeffnung zu schmal fuer die Toleranz

        for (double[] p : wallPoly2D) {
            if (p[0] > left && p[0] < right && p[1] > bottom && p[1] < top) {
                return true;
            }
        }

        double[][] rect = {{left, bottom}, {right, bottom}, {right, top}, {left, top}};
        int n = wallPoly2D.length;
        for (int i = 0; i < n; i++) {
            double[] a = wallPoly2D[i], b = wallPoly2D[(i + 1) % n];
            for (int k = 0; k < 4; k++) {
                if (GeometryUtils.segmentsProperlyIntersect(a, b, rect[k], rect[(k + 1) % 4])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True, wenn das Kandidaten-Rechteck [uLeft,uRight]x[vBottom,vTop] einem der vorhandenen
     * Oeffnungs-Rechtecke {uMin,uMax,vMin,vMax} zu nahe kommt (beruehrt oder ueberlappt, mit
     * {@link #OPENING_TOP_CLEARANCE} Sicherheitsabstand in BEIDEN Achsen). Ein reiner u-Bereichs-
     * Vergleich reicht nicht: zwei Oeffnungen koennen exakt an derselben u-Kante aneinanderstossen,
     * aber unterschiedlich hoch sein (z.B. Tuer neben Fenster) — die Randkanten der beiden inneren
     * Ringe waeren dann teilweise deckungsgleich statt sauber getrennt (GE_P_INTERSECTING_RINGS). */
    public static boolean overlapsAnyOpeningRect(List<double[]> existingRects,
            double uLeft, double uRight, double vBottom, double vTop) {
        double cl = OPENING_TOP_CLEARANCE;
        for (double[] r : existingRects) {
            boolean uSeparated = uRight + cl <= r[0] || uLeft - cl >= r[1];
            boolean vSeparated = vTop + cl <= r[2] || vBottom - cl >= r[3];
            if (!uSeparated && !vSeparated) return true;
        }
        return false;
    }

    /** Fuegt eine rechteckige Oeffnung in ein Wand-Polygon ein, liefert das FillingSurface-Polygon dazu. */
    public static Polygon addOpeningToWall(Polygon wallPoly, Point3D bl, Point3D br,
            Point3D tr, Point3D tl, boolean extCCW) {
        List<Point3D> innerRing = extCCW
                ? List.of(bl, tl, tr, br)   // exterior CCW → interior CW
                : List.of(bl, br, tr, tl);  // exterior CW  → interior CCW
        wallPoly.getInterior().add(new AbstractRingProperty(GeometryUtils.createLinearRing(innerRing)));

        List<Point3D> fillingPoints = extCCW
                ? List.of(bl, br, tr, tl)   // CCW (Standard)
                : List.of(bl, tl, tr, br);  // CW  (Sachsen LoD2)
        return GeometryUtils.createPolygon(fillingPoints);
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
        List<Point3D> target = GeometryUtils.removeClosingPoint(
                GeometryUtils.dedupConsecutive(openingPoints, GeometryUtils.POINT_MERGE_TOL));
        var it = wallPoly.getInterior().iterator();
        while (it.hasNext()) {
            AbstractRingProperty ringProp = it.next();
            if (!(ringProp.getObject() instanceof LinearRing ring)) continue;
            List<Point3D> ringPts = GeometryUtils.removeClosingPoint(
                    GeometryUtils.dedupConsecutive(GeometryUtils.pointsOfRing(ring), GeometryUtils.POINT_MERGE_TOL));
            if (pointSetsMatch(ringPts, target, GeometryUtils.POINT_MERGE_TOL)) {
                it.remove();
                return true;
            }
        }
        return false;
    }
}
