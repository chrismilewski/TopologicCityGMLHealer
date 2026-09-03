package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.construction.WallSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Erkennung von Wand-Deckungsbereichen durch ein anderes Bauteil desselben Gebaeudes (Anbau) —
 * Oeffnungen dort waeren physisch hinter dem Nachbarbauteil verborgen.
 */
public final class PartyWallCoverageUtils {

    private PartyWallCoverageUtils() {
        // Utility-Klasse
    }

    /** Toleranz fuer Bodenkanten-Deckung (senkrechter Abstand + Z_MIN-Uebereinstimmung), analog
     * StoreyGenerator.CUT_TOLERANCE fuer reale Oberflaechen-Mismatches im LoD2-Quelldatensatz. */
    public static final double WALL_COVERAGE_TOL = 0.05;

    /** Toleranz fuer Span-Ueberlappungen: mehr als reine Kantenberuehrung. */
    public static final double SPAN_OVERLAP_TOL = 0.02;

    /**
     * Deckungs-Bereiche entlang der eigenen Unterkante (u=0 bei edgeStart, u=wallLength am
     * Wandende), an denen eine ANDERE WallSurface desselben Gebaeudes (z.B. eines Anbaus) exakt
     * denselben Bodenkanten-Abschnitt auf gleicher Geschoss-Ebene beansprucht — Oeffnungen dort
     * waeren physisch hinter dem Nachbarbauteil verborgen (siehe Doku.md). Prueft ALLE Bodenpunkte
     * der anderen Wand (nicht nur Start/Ende), damit auch eine geknickte ANDERE Wand (deckt mehrere
     * Grundriss-Kanten in einem WallSurface ab) korrekt erfasst wird. Liefert sortierte, gemergte
     * [uStart, uEnd]-Paare (leer = keine Deckung).
     */
    public static List<double[]> computeCoveredSpans(WallSurface thisWall,
            List<WallSurface> allWallsOfBuilding, Point3D edgeStart, double dirX, double dirY,
            double wallLength, double thisZMin) {
        List<double[]> raw = new ArrayList<>();
        for (WallSurface other : allWallsOfBuilding) {
            if (other == thisWall) continue;
            Polygon poly = BuildingQueryUtils.getWallPolygon(other);
            if (poly == null) continue;
            List<Point3D> open = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(poly));
            if (open.size() < 2) continue;
            double otherZMin = GeometryUtils.getZRange(open)[0];
            if (Math.abs(otherZMin - thisZMin) > WALL_COVERAGE_TOL) continue; // anderes Geschoss

            List<Point3D> bottomPts = new ArrayList<>();
            for (Point3D p : open) {
                if (Math.abs(p.z - otherZMin) < 0.01) bottomPts.add(p);
            }
            if (bottomPts.size() < 2) continue;

            for (int i = 0; i < bottomPts.size() - 1; i++) {
                Point3D p0 = bottomPts.get(i), p1 = bottomPts.get(i + 1);
                double v0 = wallCoveragePerp(p0, edgeStart, dirX, dirY);
                double v1 = wallCoveragePerp(p1, edgeStart, dirX, dirY);
                if (Math.abs(v0) > WALL_COVERAGE_TOL || Math.abs(v1) > WALL_COVERAGE_TOL) continue;
                double u0 = wallCoverageProj(p0, edgeStart, dirX, dirY);
                double u1 = wallCoverageProj(p1, edgeStart, dirX, dirY);
                double uLo = Math.max(0, Math.min(u0, u1));
                double uHi = Math.min(wallLength, Math.max(u0, u1));
                if (uHi - uLo > SPAN_OVERLAP_TOL) raw.add(new double[]{uLo, uHi});
            }
        }
        return mergeCoveredSpans(raw);
    }

    private static double wallCoverageProj(Point3D p, Point3D edgeStart, double dirX, double dirY) {
        return (p.x - edgeStart.x) * dirX + (p.y - edgeStart.y) * dirY;
    }

    private static double wallCoveragePerp(Point3D p, Point3D edgeStart, double dirX, double dirY) {
        return (p.x - edgeStart.x) * (-dirY) + (p.y - edgeStart.y) * dirX;
    }

    private static List<double[]> mergeCoveredSpans(List<double[]> spans) {
        if (spans.isEmpty()) return spans;
        spans.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        double[] cur = spans.get(0).clone();
        for (int i = 1; i < spans.size(); i++) {
            double[] s = spans.get(i);
            if (s[0] <= cur[1] + WALL_COVERAGE_TOL) {
                cur[1] = Math.max(cur[1], s[1]);
            } else {
                out.add(cur);
                cur = s.clone();
            }
        }
        out.add(cur);
        return out;
    }

    /** True, wenn [lo, hi] mit irgendeiner Span mehr als eine reine Kantenberuehrung ueberlappt. */
    public static boolean overlapsAnySpan(List<double[]> spans, double lo, double hi, double tol) {
        for (double[] s : spans) {
            if (Math.min(hi, s[1]) - Math.max(lo, s[0]) > tol) return true;
        }
        return false;
    }

    /** True, wenn die (bereits gemergten) Spans die gesamte Wand [0, wallLength] abdecken. */
    public static boolean isFullyCovered(List<double[]> spans, double wallLength) {
        return spans.size() == 1 && spans.get(0)[0] <= WALL_COVERAGE_TOL
                && spans.get(0)[1] >= wallLength - WALL_COVERAGE_TOL;
    }
}
