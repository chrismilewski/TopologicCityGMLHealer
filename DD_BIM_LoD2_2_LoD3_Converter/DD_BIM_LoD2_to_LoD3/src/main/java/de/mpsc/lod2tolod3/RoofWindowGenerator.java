package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.BuildingQueryUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.GeometryUtils;
import de.mpsc.lod2tolod3.util.OpeningUtils;
import de.mpsc.lod2tolod3.util.Point3D;
import de.mpsc.lod2tolod3.util.SolidShellUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Schritt 5c: Dachfenster-Generator. Platziert Dachflaechenfenster (flach in der Dachschraege
 * liegend, keine Gauben) auf geneigten RoofSurface-Flaechen, analog zur Wandfenster-Logik in
 * {@link WindowGenerator}, aber mit einer schraegen statt senkrechten lokalen Ebene (siehe
 * Doku.md, Abschnitt "Schritt 5c: Dachfenster").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.RoofWindowGenerator input.gml jsonDir [output.gml]
 */
public class RoofWindowGenerator extends AbstractGenerator<RoofWindowGenerator.GenerationStats> {

    /** Maximaler Window-to-Wall Ratio je Dachflaeche (60 %, wie bei Waenden — keine
     * dachspezifische Vorgabe vorhanden). */
    private static final double MAX_WWR = 0.60;

    /** Toleranz fuer Flachdach-Erkennung, identisch zu {@code BuildingQueryUtils.getRoofZRange}. */
    private static final double FLAT_ROOF_TOLERANCE = 0.05;

    public static void main(String[] args) {
        RoofWindowGenerator gen = new RoofWindowGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_roofwindows"; }
    @Override protected String displayName()  { return "Dachfenster-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Dachfenster erzeugt: {}", stats.roofWindowsCreated);
        log.info("Dachflaechen mit Fenstern: {}", stats.roofsWithWindows);
        log.info("Dachflaechen uebersprungen: {}", stats.roofsSkipped);
        log.info("WWR-Warnungen: {}", stats.wwrWarnings);
        log.info(stats.toSummary());
    }

    // ==================== Gebaeude-Verarbeitung ====================

    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters.Roof roof = bp.params().getRoof();
        ModuleParameters.WindowParams wp = roof != null ? roof.window : null;
        if (wp == null || !wp.isValid()) {
            return; // Modul ohne Dachfenster-Parameter — kein Sondercode, still ueberspringen
        }

        for (var target : BuildingQueryUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, wp, stats);
            SolidShellUtils.rebuildSolidShell(target);
        }
    }

    /** Prueft alle RoofSurfaces eines AbstractBuilding und platziert Dachfenster auf geneigten. */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        List<RoofSurface> roofs = BuildingQueryUtils.collectBoundariesByType(target, RoofSurface.class);
        for (RoofSurface roof : roofs) {
            processRoof(roof, wp, stats);
        }
    }

    // ==================== Dachflaechen-Verarbeitung ====================

    private void processRoof(RoofSurface roof, ModuleParameters.WindowParams wp,
            GenerationStats stats) {

        // 1. Polygon lesen
        Polygon roofPoly = BuildingQueryUtils.getRoofPolygon(roof);
        if (roofPoly == null) { stats.skip(SkipReason.NO_POLY); return; }
        List<Point3D> allPoints = GeometryUtils.toPoints(roofPoly);
        List<Point3D> open = GeometryUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) { stats.skip(SkipReason.NO_POLY); return; }

        // 2. Flachdach-Erkennung: keine Dachfenster auf flachen Teilflaechen
        double[] zRange = GeometryUtils.getZRange(open);
        if (zRange[1] - zRange[0] < FLAT_ROOF_TOLERANCE) {
            stats.skip(SkipReason.FLAT_ROOF); return;
        }

        // 3. Traufkante (unterste Kante, wie bei Waenden) — komplexe/unregelmaessige
        // Verschneidungsflaechen ohne 2 Punkte auf zMin (z.B. Kehlflaechen) werden hier
        // automatisch uebersprungen, kein Sondercode noetig.
        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(open);
        if (edge == null) { stats.skip(SkipReason.NO_BOTTOM_EDGE); return; }
        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dirX = dx / edge.wallLength();
        double dirY = dy / edge.wallLength();

        // 4. Aufwaerts-Vektor (Traufe -> First) entlang der Dachschraege
        double[] up = GeometryUtils.computeUpSlopeVector(open, dirX, dirY);
        if (up == null) { stats.skip(SkipReason.NO_BOTTOM_EDGE); return; }
        double upX = up[0], upY = up[1], upZ = up[2];

        // 5. Verfuegbare Schraeglaenge (Traufe -> First) dieser Flaeche
        double maxV = 0;
        for (Point3D p : open) {
            double dpx = p.x - edge.start().x, dpy = p.y - edge.start().y, dpz = p.z - edge.start().z;
            double v = dpx * upX + dpy * upY + dpz * upZ;
            if (v > maxV) maxV = v;
        }

        // 6. Groessen-Checks (analog WindowGenerator)
        double vDist = ModuleParameters.WindowParams.safeValue(wp.vDistFloorWindow);
        double vBottom = vDist;
        double vTop = vDist + wp.windowHeight;
        if (vTop > maxV + 0.001) { stats.skip(SkipReason.TOO_LOW); return; }

        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        if (edge.wallLength() < 2 * hDistMin + wp.windowWidth) {
            stats.skip(SkipReason.TOO_SHORT); return;
        }

        // 7. Horizontale Platzierung entlang der Traufe (identische Arithmetik wie bei Waenden)
        int count = WindowGenerator.calculateWindowCount(edge.wallLength(), wp);
        if (count < 1) { stats.skip(SkipReason.NO_FIT); return; }
        double[] offsets = WindowGenerator.calculateWindowOffsets(count, edge.wallLength(), wp);
        if (offsets.length < 1) { stats.skip(SkipReason.NO_FIT); return; }

        // 8. Validierung: Fenster muss vollstaendig in der (ggf. zum First hin schmaler
        // werdenden) Dachflaeche liegen
        double[][] roofPoly2D = GeometryUtils.projectPlaneTo2D(open, edge.start(), dirX, dirY, upX, upY, upZ);
        List<double[]> validWindows = new ArrayList<>();
        for (double hOffset : offsets) {
            double uLeft = hOffset, uRight = hOffset + wp.windowWidth;
            // Kontur-Check mit Seiten-/Oberkanten-Sicherheitsabstand (wie bei Wandfenstern/-tueren,
            // siehe Doku.md "Fenster-Seitenkante-auf-Anbau-Kerbe-Fix") PLUS Durchquerungs-Check: bei
            // einer Dachflaeche, die per Kerbe um eine Gaube herumfuehrt (nicht-konvexe Kontur, "M"-
            // Form analog zu einer Wand unter einem Satteldach mit Gaube), reicht der reine 4-Eck-
            // punkt-Containment-Test nicht — ein Fenster kann komplett innerhalb der Bounding-Box
            // liegen und trotzdem die Kerbe ueberspannen, was die Dachflaeche mit der Gaubenwand/
            // -dach selbst ueberschneidet (GE_S_SELF_INTERSECTION, siehe Doku.md).
            if (OpeningUtils.openingInsideWallSideTopClearance2D(uLeft, uRight, vBottom, vTop, roofPoly2D)
                    && !OpeningUtils.wallContourEntersOpening(uLeft, uRight, vBottom, vTop, roofPoly2D)) {
                validWindows.add(new double[]{uLeft, uRight});
            } else {
                stats.gableRoofWindowsDropped++;
            }
        }
        if (validWindows.isEmpty()) { stats.skip(SkipReason.PIP_FAIL); return; }

        // 9. Erzeugen
        placeRoofWindowSurfaces(roof, roofPoly, open, edge.start(), dirX, dirY, upX, upY, upZ,
                validWindows, vBottom, vTop, wp, stats);
    }

    /** Erzeugt innere Polygon-Ringe und WindowSurface-Objekte fuer alle validen Dachfenster. */
    private void placeRoofWindowSurfaces(RoofSurface roof, Polygon roofPoly, List<Point3D> open,
            Point3D origin, double dirX, double dirY, double upX, double upY, double upZ,
            List<double[]> validWindows, double vBottom, double vTop,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        String roofFaceId = CityGmlUtils.getStringAttribute(roof, "BldgFaceID");
        if (roofFaceId == null) {
            roofFaceId = roof.getId() != null ? roof.getId() : "unknown";
        }

        boolean extCCW = SolidShellUtils.isRingCCWOnPlane(open, origin, dirX, dirY, upX, upY, upZ);

        int windowIdx = 0;
        for (double[] win : validWindows) {
            double uLeft = win[0], uRight = win[1];
            windowIdx++;

            Point3D bl = planePoint(origin, dirX, dirY, upX, upY, upZ, uLeft, vBottom);
            Point3D br = planePoint(origin, dirX, dirY, upX, upY, upZ, uRight, vBottom);
            Point3D tr = planePoint(origin, dirX, dirY, upX, upY, upZ, uRight, vTop);
            Point3D tl = planePoint(origin, dirX, dirY, upX, upY, upZ, uLeft, vTop);

            Polygon winPoly = OpeningUtils.addOpeningToWall(roofPoly, bl, br, tr, tl, extCCW);

            String windowId = roofFaceId + "_RoofWin_" + windowIdx;
            WindowSurface windowSurface = new WindowSurface();
            windowSurface.setId("Face_" + windowId);
            CityGmlUtils.setGmlName(windowSurface, "LOD3_RoofWindow");
            windowSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(winPoly));
            CityGmlUtils.addStringAttribute(windowSurface, "BldgFaceID", windowId);
            CityGmlUtils.addStringAttribute(windowSurface, "FACEAREA",
                    GeometryUtils.formatNum(wp.windowWidth * wp.windowHeight));
            CityGmlUtils.addStringAttribute(windowSurface, "Geschoss", "RO");
            roof.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(windowSurface));
            stats.roofWindowsCreated++;
        }

        // FACEAREA der Dachflaeche aktualisieren + WWR-Warnung
        double roofArea = GeometryUtils.calculateWallArea(open);
        String faceAreaStr = CityGmlUtils.getStringAttribute(roof, "FACEAREA");
        if (faceAreaStr != null) {
            try { roofArea = Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        double totalWindowArea = validWindows.size() * wp.windowWidth * wp.windowHeight;
        if (roofArea > 0) {
            CityGmlUtils.setStringAttribute(roof, "FACEAREA",
                    GeometryUtils.formatNum(Math.max(0, roofArea - totalWindowArea)));
            double wwr = totalWindowArea / roofArea;
            if (wwr > MAX_WWR) {
                log.warn("Dachflaeche {}: WWR {} ueberschreitet {} ({} Fenster)",
                        roof.getId(), GeometryUtils.formatNum(wwr), MAX_WWR, validWindows.size());
                stats.wwrWarnings++;
            }
        }

        stats.roofsWithWindows++;
        log.debug("Dachflaeche {}: Fenster={} (dropped={})",
                roof.getId(), validWindows.size(), stats.gableRoofWindowsDropped);
    }

    private static Point3D planePoint(Point3D origin, double dirX, double dirY,
            double upX, double upY, double upZ, double u, double v) {
        return new Point3D(
                origin.x + u * dirX + v * upX,
                origin.y + u * dirY + v * upY,
                origin.z + v * upZ);
    }

    // ==================== Statistiken ====================

    /** Skip-Gruende fuer uebersprungene Dachflaechen (Label = Kuerzel in der Log-Zusammenfassung). */
    public enum SkipReason {
        NO_POLY("noPoly"),               // Kein Polygon oder < 3 Punkte
        FLAT_ROOF("flatRoof"),           // Flachdach-Teilflaeche, keine Dachfenster
        NO_BOTTOM_EDGE("noBottom"),      // Keine Traufkante oder keine gueltige Normale
        TOO_LOW("tooLow"),               // Flaeche zu kurz entlang der Schraege (Traufe->First)
        TOO_SHORT("tooShort"),           // Traufkante zu kurz fuer ein Fenster
        NO_FIT("noFit"),                 // Kein Fenster passt entlang der Traufe
        PIP_FAIL("pipFail");             // Alle Kandidaten liegen ausserhalb der Dachkontur

        final String label;
        SkipReason(String label) { this.label = label; }
    }

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int roofWindowsCreated = 0;
        public int roofsWithWindows = 0;
        public int roofsSkipped = 0;
        public int gableRoofWindowsDropped = 0;
        public int wwrWarnings = 0;

        public final java.util.EnumMap<SkipReason, Integer> skips =
                new java.util.EnumMap<>(SkipReason.class);

        public void skip(SkipReason reason) {
            roofsSkipped++;
            skips.merge(reason, 1, Integer::sum);
        }

        public String toSummary() {
            StringBuilder sb = new StringBuilder("Skip-Gruende: ");
            boolean first = true;
            for (SkipReason r : SkipReason.values()) {
                if (!first) sb.append(", ");
                sb.append(r.label).append('=').append(skips.getOrDefault(r, 0));
                first = false;
            }
            return sb.toString();
        }
    }
}
