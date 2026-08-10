package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurface;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Schritt 5: Fenster-Generator. Platziert Fenster als innere Polygon-Ringe auf den
 * Aussenwand-Segmenten aller Geschosse (siehe Doku.md, Abschnitt "Schritt 5").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.WindowGenerator input.gml jsonDir [output.gml]
 */
public class WindowGenerator extends AbstractGenerator<WindowGenerator.GenerationStats> {

    /** Maximaler Window-to-Wall Ratio (60 %). */
    private static final double MAX_WWR = 0.60;

    public static void main(String[] args) {
        WindowGenerator gen = new WindowGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_windows"; }
    @Override protected String displayName()  { return "Fenster-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Fenster erzeugt: {}", stats.windowsCreated);
        log.info("Waende mit Fenstern: {}", stats.wallsWithWindows);
        log.info("Waende uebersprungen: {}", stats.wallsSkipped);
        log.info("Giebel-Fenster verworfen: {}", stats.gableWindowsDropped);
        log.info("WWR-Warnungen: {}", stats.wwrWarnings);
        log.info(stats.toSummary());
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /** Verarbeitet ein Gebaeude: delegiert an processAbstractBuilding fuer Building und BuildingParts. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters params = bp.params();

        // Verhindert doppelte Fenster auf einer von zwei BuildingParts geteilten Wand.
        Set<String> processedWallMids = new HashSet<>();

        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, params, stats, processedWallMids);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    // ==================== Gate-Check Chain ====================

    /** Ein Gate-Check: true = Wand besteht, false = uebersprungen (Grund landet in stats). */
    @FunctionalInterface
    private interface WallCheck {
        boolean test(WallSurface wall, String geschoss, GenerationStats stats);
    }

    /** Baut die Kette der Gate-Checks fuer processAbstractBuilding. */
    private List<WallCheck> buildGateChecks(Set<String> processedWallMids, ModuleParameters params) {
        List<WallCheck> checks = new ArrayList<>();

        // [1] Eligibles Geschoss (GF, UF_*, BA)
        checks.add((wall, geschoss, stats) -> {
            if (!isWindowEligibleGeschoss(geschoss)) return false;
            stats.addSkipForGeschoss(geschoss); // wird bei Erfolg korrigiert
            return true;
        });

        // [2] Doppelte Wand zwischen BuildingParts
        checks.add((wall, geschoss, stats) -> {
            String midKey = CityGmlUtils.wallBottomMidKey(wall);
            if (midKey != null && !processedWallMids.add(midKey)) {
                stats.skip(SkipReason.COVERED_BY_PART);
                return false;
            }
            return true;
        });

        // [3] WindowPreference muss gesetzt und != NONE sein;
        //     bei ABOVE_NEIGHBOR muss Z_Differenz > 0 (Wand ueberragt die Nachbarwand)
        checks.add((wall, geschoss, stats) -> {
            WindowPreference pref = WindowPreference.parse(
                    CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
            if (pref == WindowPreference.NONE) {
                stats.skip(SkipReason.NO_WINDOW_PREF);
                return false;
            }
            if (pref != WindowPreference.ABOVE_NEIGHBOR) return true;
            String zDiffStr = CityGmlUtils.getStringAttribute(wall, "Z_Differenz");
            if (zDiffStr == null) {
                stats.skip(SkipReason.NO_WINDOW_PREF); return false;
            }
            try {
                if (Double.parseDouble(zDiffStr) <= 0) {
                    stats.skip(SkipReason.NO_WINDOW_PREF); return false;
                }
            } catch (NumberFormatException e) {
                stats.skip(SkipReason.NO_WINDOW_PREF); return false;
            }
            return true;
        });

        // [4] BA: Z_Max muss existieren und > 0 sein
        checks.add((wall, geschoss, stats) -> {
            if (!"BA".equals(geschoss)) return true;
            String zMaxStr = CityGmlUtils.getStringAttribute(wall, "Z_Max");
            if (zMaxStr == null) { stats.skip(SkipReason.BA_NO_ZMAX); return false; }
            try {
                double zMax = Double.parseDouble(zMaxStr);
                if (zMax <= 0) { stats.skip(SkipReason.BA_NO_ZMAX); return false; }
            } catch (NumberFormatException e) {
                stats.skip(SkipReason.BA_NO_ZMAX);
                return false;
            }
            return true;
        });

        return checks;
    }

    /** Prueft alle WallSurfaces eines AbstractBuilding gegen die Gate-Checks und platziert Fenster. */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters params, GenerationStats stats,
            Set<String> processedWallMids) {

        List<WallSurface> walls = CityGmlUtils.collectWallSurfaces(target);
        List<WallCheck> gateChecks = buildGateChecks(processedWallMids, params);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");

            // Gate-Check-Chain: jeder Check muss true liefern
            boolean passed = true;
            for (WallCheck check : gateChecks) {
                if (!check.test(wall, geschoss, stats)) { passed = false; break; }
            }
            if (!passed) continue;

            // Fenster-Parameter fuer dieses Geschoss holen
            ModuleParameters.WindowParams wp = params.getWindowParamsForGeschoss(geschoss);
            if (wp == null || !wp.isValid()) {
                stats.skip(SkipReason.NO_PARAMS);
                continue;
            }

            processWall(wall, geschoss, wp, stats);
        }
    }

    // ==================== Storey Strategy ====================

    /** Geschoss-spezifische Geometrie-Logik fuer die Fensterplatzierung (GF/UF vs. BA). */
    private interface StoreyWindowStrategy {
        /** Nutzbare Wandhoehe (Obergrenze fuer Fenster-Reihen). */
        double usableHeight(WallSurface wall, double wallHeight, double zMin);

        /** Gelaendehoehe (TIC), unterhalb derer BA-Reihen uebersprungen werden; NaN fuer GF/UF. */
        double terrainZ(WallSurface wall);

        /** Horizontal verfuegbare Wandabschnitte (GF: um Tueren herum; UF/BA: ganze Wand). */
        List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp);
    }

    /** Strategie fuer GF- und UF-Geschosse. */
    private final StoreyWindowStrategy strategyGfUf = new StoreyWindowStrategy() {
        @Override
        public double usableHeight(WallSurface wall, double wallHeight, double zMin) {
            String geschossDeckeStr = CityGmlUtils.getStringAttribute(wall, "GeschossDeckeZ");
            if (geschossDeckeStr != null) {
                double geschossDeckeZ = Double.parseDouble(geschossDeckeStr);
                return Math.min(wallHeight, geschossDeckeZ - zMin);
            }
            return wallHeight;
        }

        @Override
        public double terrainZ(WallSurface wall) {
            return Double.NaN; // kein Gelaende-Filter fuer GF/UF
        }

        @Override
        public List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp) {
            return extractFreeSections(wall, edgeStart, dirX, dirY, wallLength, wp);
        }
    };

    /** Strategie fuer BA-Geschoss (Keller, teilweise oberirdisch). */
    private static final StoreyWindowStrategy strategyBa = new StoreyWindowStrategy() {
        @Override
        public double usableHeight(WallSurface wall, double wallHeight, double zMin) {
            return wallHeight + 0.10; // +10 cm Toleranz fuer JSON-Rundungsfehler
        }

        @Override
        public double terrainZ(WallSurface wall) {
            String zMaxRelStr = CityGmlUtils.getStringAttribute(wall, "Z_Max");
            String zMaxAslStr = CityGmlUtils.getStringAttribute(wall, "Z_MAX_ASL");
            double zMaxRel = Double.parseDouble(zMaxRelStr);
            double zMaxAsl = Double.parseDouble(zMaxAslStr);
            return zMaxAsl - zMaxRel; // TIC-Niveau
        }

        @Override
        public List<double[]> sections(WallSurface wall, Point3D edgeStart,
                double dirX, double dirY, double wallLength,
                ModuleParameters.WindowParams wp) {
            return List.of(new double[]{0, wallLength}); // gesamte Wand
        }
    };

    /** Waehlt die passende Strategie fuer ein Geschoss. */
    private StoreyWindowStrategy strategyFor(String geschoss) {
        return "BA".equals(geschoss) ? strategyBa : strategyGfUf;
    }

    // ==================== WallContext ====================

    /** Buendelt die geometrischen Kontextdaten einer Wand fuer die Fensterplatzierung. */
    private record WallContext(
            WallSurface wall,
            String geschoss,
            ModuleParameters.WindowParams wp,
            List<Point3D> open,     // offener Polygon (kein schliessender Punkt)
            Point3D edgeStart,      // linker Startpunkt der Unterkante
            double dirX,            // normierter Richtungsvektor X der Unterkante
            double dirY,            // normierter Richtungsvektor Y der Unterkante
            double wallLength,      // Laenge der Unterkante [m]
            double zMin,            // unterster Z-Wert des Polygons
            double floorZ,          // absolute Z-Koordinate des Fussbodens (= zMin)
            double usableHeight,    // nutzbare Wandhoehe fuer Fenster [m]
            double terrainZ,        // Gelaendehoehe TIC; NaN fuer GF/UF
            List<double[]> sections // verfuegbare Wandabschnitte [sectionStart, sectionEnd]
    ) {}

    // ==================== Wand-Verarbeitung (Intermediate Records) ====================

    /** Horizontale Fenster-Offsets fuer alle Wandabschnitte einer Reihe. */
    private record HorizResult(
            List<double[]> sectionOffsets, // absolute Offsets von edgeStart pro Abschnitt
            int windowsPerRow              // Summe aller Fenster pro Reihe
    ) {}

    // ==================== Wand-Verarbeitung ====================

    /** Analysiert eine Wand und platziert Fenster (delegiert an Hilfsmethoden). */
    private void processWall(WallSurface wall, String geschoss,
            ModuleParameters.WindowParams wp, GenerationStats stats) {

        // 1. Polygon lesen
        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) { stats.skip(SkipReason.NO_POLY); return; }
        List<Point3D> allPoints = CityGmlUtils.toPoints(wallPoly);
        List<Point3D> open = CityGmlUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) { stats.skip(SkipReason.NO_POLY); return; }

        // 2. Unterkante ermitteln
        CityGmlUtils.BottomEdge edge = resolveBottomEdge(wall, open, stats);
        if (edge == null) return;
        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dirX = dx / edge.wallLength();
        double dirY = dy / edge.wallLength();

        // 3. Geschoss-Strategie + BA-Vorpruefung
        StoreyWindowStrategy strategy = strategyFor(geschoss);
        if ("BA".equals(geschoss)) {
            String zMaxAslStr = CityGmlUtils.getStringAttribute(wall, "Z_MAX_ASL");
            if (zMaxAslStr == null) { stats.skip(SkipReason.BA_NO_ZMAX_ASL); return; }
        }
        double usableHeight = strategy.usableHeight(wall, edge.zMax() - edge.zMin(), edge.zMin());
        double terrainZ = strategy.terrainZ(wall);

        // ABOVE_NEIGHBOR: Fenster nur oberhalb Z_Fenster_ASL.
        double effectiveFloorZ = edge.zMin();
        WindowPreference pref = WindowPreference.parse(
                CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
        if (pref == WindowPreference.ABOVE_NEIGHBOR) {
            String zFensterAslStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster_ASL");
            if (zFensterAslStr != null) {
                try {
                    double zFensterAsl = Double.parseDouble(zFensterAslStr);
                    if (zFensterAsl >= edge.zMax()) {
                        // Segment komplett durch Nachbarwand verdeckt
                        stats.skip(SkipReason.NO_WINDOW_PREF); return;
                    }
                    if (zFensterAsl > edge.zMin()) {
                        usableHeight -= (zFensterAsl - edge.zMin());
                        effectiveFloorZ = zFensterAsl;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 4. Groessen-Checks
        double vDist = ModuleParameters.WindowParams.safeValue(wp.vDistFloorWindow);
        if (usableHeight < vDist + wp.windowHeight) {
            stats.skip(SkipReason.TOO_LOW); return;
        }
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        if (edge.wallLength() < 2 * hDistMin + wp.windowWidth) {
            stats.skip(SkipReason.TOO_SHORT); return;
        }

        // 5. WallContext zusammenstellen
        List<double[]> sections = strategy.sections(wall, edge.start(), dirX, dirY, edge.wallLength(), wp);
        WallContext ctx = new WallContext(wall, geschoss, wp, open,
                edge.start(), dirX, dirY, edge.wallLength(),
                edge.zMin(), effectiveFloorZ, usableHeight, terrainZ, sections);

        // 6. Horizontale Offsets
        HorizResult horiz = computeHorizontalOffsets(ctx, stats);
        if (horiz == null) return;

        // 7. Wandflaeche (fuer WWR-Check und FACEAREA-Korrektur)
        double wallArea = resolveWallArea(ctx);

        // 8. Fensterreihen (mit WWR-Trim)
        List<double[]> rows = computeRowPositions(ctx, horiz, wallArea, stats);
        if (rows.isEmpty()) return;

        // 9. PiP-Check: Fenster muessen vollstaendig im Wandpolygon liegen
        List<double[]> validWindows = collectValidWindows(ctx, rows, horiz, stats);
        if (validWindows.isEmpty()) { stats.skip(SkipReason.PIP_FAIL); return; }

        // 10. Innere Ringe + WindowSurface erzeugen
        placeWindowSurfaces(ctx, wallPoly, validWindows, wallArea, rows.size(), stats);
    }

    // ==================== Wand-Verarbeitung Hilfsmethoden ====================

    /** Ermittelt die Unterkante der Wand, zaehlt bei Misserfolg in die Statistik. */
    private CityGmlUtils.BottomEdge resolveBottomEdge(WallSurface wall, List<Point3D> open,
            GenerationStats stats) {
        CityGmlUtils.BottomEdge edge = CityGmlUtils.findBottomEdge(open);
        if (edge == null) {
            log.warn("Weniger als 2 Punkte an Unterkante fuer Wand {}", wall.getId());
            stats.skip(SkipReason.NO_BOTTOM_EDGE);
        }
        return edge;
    }

    /** Berechnet horizontale Fenster-Offsets fuer alle Wandabschnitte; null wenn keins passt. */
    private static HorizResult computeHorizontalOffsets(WallContext ctx, GenerationStats stats) {
        List<double[]> sectionOffsets = new ArrayList<>();
        int windowsPerRow = 0;

        for (double[] sec : ctx.sections()) {
            double secStart = sec[0];
            double secLength = sec[1] - sec[0];
            int count = calculateWindowCount(secLength, ctx.wp());
            if (count < 1) continue;

            double[] relOffsets = calculateWindowOffsets(count, secLength, ctx.wp());
            double[] absOffsets = new double[relOffsets.length];
            for (int i = 0; i < relOffsets.length; i++) absOffsets[i] = secStart + relOffsets[i];
            sectionOffsets.add(absOffsets);
            windowsPerRow += relOffsets.length;
        }

        if (windowsPerRow < 1) {
            stats.skip(SkipReason.NO_FIT);
            return null;
        }
        return new HorizResult(sectionOffsets, windowsPerRow);
    }

    /** Liest die Wandflaeche aus FACEAREA oder berechnet sie als Fallback aus dem Polygon. */
    private static double resolveWallArea(WallContext ctx) {
        String faceAreaStr = CityGmlUtils.getStringAttribute(ctx.wall(), "FACEAREA");
        if (faceAreaStr != null) {
            try { return Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        return CityGmlUtils.calculateWallArea(ctx.open());
    }

    /** Berechnet die Z-Positionen aller Fensterreihen und trimmt sie auf den WWR-Grenzwert. */
    private List<double[]> computeRowPositions(WallContext ctx, HorizResult horiz,
            double wallArea, GenerationStats stats) {
        double vDist = ModuleParameters.WindowParams.safeValue(ctx.wp().vDistFloorWindow);
        double wallTopZ = ctx.floorZ() + ctx.usableHeight();

        List<double[]> rows = new ArrayList<>();
        // BA startet ab Gelaendeoberflaeche (TIC), GF/UF ab Fussboden.
        double rowStartZ = !Double.isNaN(ctx.terrainZ()) ? ctx.terrainZ() : ctx.floorZ();
        double rowBottomZ = CityGmlUtils.roundZ(rowStartZ + vDist);
        double rowTopZ = CityGmlUtils.roundZ(rowBottomZ + ctx.wp().windowHeight);

        while (rowTopZ <= wallTopZ + 0.001) {
            rows.add(new double[]{rowBottomZ, rowTopZ});
            rowBottomZ = CityGmlUtils.roundZ(rowTopZ + vDist);
            rowTopZ = CityGmlUtils.roundZ(rowBottomZ + ctx.wp().windowHeight);
        }

        if (rows.isEmpty()) {
            stats.skip(SkipReason.TOO_LOW);
            return rows;
        }

        // WWR-Check: ueberzaehlige Reihen von oben entfernen.
        if (wallArea > 0) {
            int numRows = rows.size();
            double winW = ctx.wp().windowWidth;
            double winH = ctx.wp().windowHeight;
            double windowArea = numRows * horiz.windowsPerRow() * winW * winH;
            double wwr = windowArea / wallArea;
            boolean capped = false;

            while (wwr > MAX_WWR && numRows > 1) {
                numRows--;
                wwr = numRows * horiz.windowsPerRow() * winW * winH / wallArea;
                capped = true;
            }

            if (wwr > MAX_WWR) {
                log.warn("WWR={} > {} an Wand {} (Geschoss={}, {} Fenster, Wandflaeche={})",
                        CityGmlUtils.formatNum(wwr), CityGmlUtils.formatNum(MAX_WWR),
                        ctx.wall().getId(), ctx.geschoss(),
                        numRows * horiz.windowsPerRow(), CityGmlUtils.formatNum(wallArea));
                capped = true;
            }
            if (capped) stats.wwrWarnings++;
            rows = rows.subList(0, numRows);
        }
        return rows;
    }

    /** Point-in-Polygon-Check je Fenster-Kandidat; Treffer ausserhalb (Giebel) werden verworfen. */
    private static List<double[]> collectValidWindows(WallContext ctx,
            List<double[]> rowZPositions, HorizResult horiz, GenerationStats stats) {
        double[][] wallPoly2D = CityGmlUtils.projectWallTo2D(
                ctx.open(), ctx.edgeStart(), ctx.dirX(), ctx.dirY(), ctx.zMin());

        List<double[]> validWindows = new ArrayList<>();
        for (double[] rowZ : rowZPositions) {
            double wBottomZ = rowZ[0];
            double wTopZ    = rowZ[1];
            double vBottom  = wBottomZ - ctx.zMin();
            double vTop     = wTopZ    - ctx.zMin();

            for (double[] offsets : horiz.sectionOffsets()) {
                for (double hOffset : offsets) {
                    if (CityGmlUtils.openingInsideWall2D(hOffset,
                            hOffset + ctx.wp().windowWidth, vBottom, vTop, wallPoly2D)) {
                        validWindows.add(new double[]{hOffset, wBottomZ, wTopZ});
                    } else {
                        stats.gableWindowsDropped++;
                    }
                }
            }
        }
        return validWindows;
    }

    /** Erzeugt innere Polygon-Ringe und WindowSurface-Objekte fuer alle validen Fenster. */
    private void placeWindowSurfaces(WallContext ctx, Polygon wallPoly,
            List<double[]> validWindows, double wallArea, int numRows, GenerationStats stats) {
        String wallFaceId = CityGmlUtils.getStringAttribute(ctx.wall(), "BldgFaceID");
        if (wallFaceId == null) {
            wallFaceId = ctx.wall().getId() != null ? ctx.wall().getId() : "unknown";
        }

        boolean extCCW = CityGmlUtils.isExteriorRingCCW(ctx.open(), ctx.edgeStart(), ctx.dirX(), ctx.dirY());

        int windowIdx = 0;
        for (double[] win : validWindows) {
            double hOffset  = win[0];
            double wBottomZ = win[1];
            double wTopZ    = win[2];
            windowIdx++;

            Point3D bl = new Point3D(ctx.edgeStart().x + hOffset                        * ctx.dirX(),
                                     ctx.edgeStart().y + hOffset                        * ctx.dirY(), wBottomZ);
            Point3D br = new Point3D(ctx.edgeStart().x + (hOffset + ctx.wp().windowWidth) * ctx.dirX(),
                                     ctx.edgeStart().y + (hOffset + ctx.wp().windowWidth) * ctx.dirY(), wBottomZ);
            Point3D tr = new Point3D(ctx.edgeStart().x + (hOffset + ctx.wp().windowWidth) * ctx.dirX(),
                                     ctx.edgeStart().y + (hOffset + ctx.wp().windowWidth) * ctx.dirY(), wTopZ);
            Point3D tl = new Point3D(ctx.edgeStart().x + hOffset                        * ctx.dirX(),
                                     ctx.edgeStart().y + hOffset                        * ctx.dirY(), wTopZ);

            Polygon winPoly = CityGmlUtils.addOpeningToWall(wallPoly, bl, br, tr, tl, extCCW);

            String windowId = wallFaceId + "_Win_" + windowIdx;
            WindowSurface windowSurface = new WindowSurface();
            windowSurface.setId("Face_" + windowId);
            CityGmlUtils.setGmlName(windowSurface, "LOD3_Window");
            windowSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(winPoly));
            CityGmlUtils.addStringAttribute(windowSurface, "BldgFaceID", windowId);
            CityGmlUtils.addStringAttribute(windowSurface, "FACEAREA",
                    CityGmlUtils.formatNum(ctx.wp().windowWidth * ctx.wp().windowHeight));
            CityGmlUtils.addStringAttribute(windowSurface, "Geschoss", ctx.geschoss());
            ctx.wall().getFillingSurfaces().add(new AbstractFillingSurfaceProperty(windowSurface));
            stats.windowsCreated++;
        }

        // FACEAREA der Wand aktualisieren
        double totalWindowArea = validWindows.size() * ctx.wp().windowWidth * ctx.wp().windowHeight;
        if (wallArea > 0) {
            CityGmlUtils.setStringAttribute(ctx.wall(), "FACEAREA",
                    CityGmlUtils.formatNum(Math.max(0, wallArea - totalWindowArea)));
        }

        stats.wallsWithWindows++;
        stats.addWindowsForGeschoss(ctx.geschoss(), validWindows.size());
        log.debug("Wand {}: Geschoss={}, L={}, H_nutzbar={}, Abschnitte={}, Reihen={}, Fenster={} (dropped={})",
                ctx.wall().getId(), ctx.geschoss(),
                CityGmlUtils.formatNum(ctx.wallLength()),
                CityGmlUtils.formatNum(ctx.usableHeight()),
                ctx.sections().size(), numRows, validWindows.size(), stats.gableWindowsDropped);
    }

    // ==================== GF Tuer-Aufspaltung ====================

    /** Freie Wandabschnitte einer GF-Wand ausserhalb vorhandener Tueren plus HDistDoWi-Puffer. */
    private List<double[]> extractFreeSections(WallSurface wall, Point3D edgeStart,
            double dirX, double dirY, double wallLength,
            ModuleParameters.WindowParams wp) {

        double hDistDoWi = ModuleParameters.WindowParams.safeValue(wp.hDistDoorWindow);

        // --- Tuer-Positionen sammeln ---
        List<double[]> doorRanges = new ArrayList<>(); // [leftOffset, rightOffset]

        for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
            AbstractFillingSurface fs = fsp.getObject();
            if (!(fs instanceof DoorSurface)) continue;

            // Tuer-Polygon auslesen
            Polygon doorPoly = null;
            if (fs.getLod3MultiSurface() != null
                    && fs.getLod3MultiSurface().getObject() != null) {
                var members = fs.getLod3MultiSurface().getObject()
                        .getSurfaceMember();
                if (!members.isEmpty()
                        && members.get(0).getObject() instanceof Polygon p) {
                    doorPoly = p;
                }
            }
            if (doorPoly == null) continue;

            List<Point3D> doorPts = CityGmlUtils.toPoints(doorPoly);
            List<Point3D> doorOpen = CityGmlUtils.removeClosingPoint(doorPts);

            // Horizontale Projektion aller Tuer-Punkte auf die Wandrichtung
            double minProj = Double.MAX_VALUE;
            double maxProj = -Double.MAX_VALUE;
            for (Point3D p : doorOpen) {
                double proj = (p.x - edgeStart.x) * dirX + (p.y - edgeStart.y) * dirY;
                minProj = Math.min(minProj, proj);
                maxProj = Math.max(maxProj, proj);
            }

            doorRanges.add(new double[]{minProj, maxProj});
        }

        // Keine Tueren → gesamte Wand als ein Abschnitt
        if (doorRanges.isEmpty()) {
            return List.of(new double[]{0, wallLength});
        }

        // Tuer-Ranges nach Position sortieren
        doorRanges.sort((a, b) -> Double.compare(a[0], b[0]));

        // --- Freie Abschnitte berechnen ---
        List<double[]> sections = new ArrayList<>();
        double cursor = 0;

        for (double[] door : doorRanges) {
            double doorLeft = door[0];
            double doorRight = door[1];

            // Abschnitt links der Tuer (mit Puffer)
            double secEnd = doorLeft - hDistDoWi;
            if (secEnd > cursor + 0.01) {
                sections.add(new double[]{cursor, secEnd});
            }

            // Cursor hinter Tuer + Puffer
            cursor = doorRight + hDistDoWi;
        }

        // Abschnitt rechts der letzten Tuer
        if (cursor < wallLength - 0.01) {
            sections.add(new double[]{cursor, wallLength});
        }

        return sections;
    }

    // ==================== Fenster-Berechnung ====================

    /** Maximale Fensteranzahl fuer einen Wandabschnitt (siehe Doku.md, "Fensteranzahl berechnen"). */
    static int calculateWindowCount(double availableLength, ModuleParameters.WindowParams wp) {
        double wiLen = wp.windowWidth;
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);
        double hDistWiWi = ModuleParameters.WindowParams.safeValue(wp.hDistWindowWindow);

        double numerator = availableLength - 2 * hDistMin + hDistWiWi;
        double denominator = wiLen + hDistWiWi;

        if (denominator <= 0) return 0;

        int n = (int) Math.floor(numerator / denominator);
        if (n < 1) return 0;

        // MaxWiPerRow begrenzen (falls gesetzt und > 0)
        if (wp.maxWindowsPerRow != null && wp.maxWindowsPerRow > 0) {
            n = Math.min(n, wp.maxWindowsPerRow);
        }

        return n;
    }

    /** Horizontale Fenster-Offsets, immer zentriert auf den Wandabschnitt (siehe Doku.md "Fensterpositionen"). */
    static double[] calculateWindowOffsets(int count, double sectionLength,
            ModuleParameters.WindowParams wp) {
        double wiLen = wp.windowWidth;
        double hDistWiWi = ModuleParameters.WindowParams.safeValue(wp.hDistWindowWindow);
        double hDistMin = ModuleParameters.WindowParams.safeValue(wp.hDistMinWallWindow);

        for (int n = count; n >= 1; n--) {
            double totalWidth = n * wiLen + Math.max(0, n - 1) * hDistWiWi;
            double startOffset = (sectionLength - totalWidth) / 2.0;
            if (startOffset >= hDistMin - 0.001) {
                double[] centered = new double[n];
                for (int k = 0; k < n; k++) {
                    centered[k] = startOffset + k * (wiLen + hDistWiWi);
                }
                return centered;
            }
        }

        // Nichts passt (sollte nach calculateWindowCount nicht vorkommen)
        return new double[0];
    }

    // ==================== Geschoss-/Parameter-Logik ====================

    /** Prueft ob ein Geschoss fuer Fenster in Frage kommt (ausgeschlossen: Dach, Boden, leer). */
    private static boolean isWindowEligibleGeschoss(String geschoss) {
        if (geschoss == null || geschoss.isBlank()) return false;
        if ("1000".equals(geschoss)) return false;
        if ("2000".equals(geschoss)) return false;
        return "GF".equals(geschoss)
                || geschoss.startsWith("UF_")
                || "BA".equals(geschoss);
    }

    // ==================== Statistiken ====================

    /** Skip-Gruende fuer uebersprungene Waende (Label = Kuerzel in der Log-Zusammenfassung). */
    public enum SkipReason {
        NO_WINDOW_PREF("WP0/null"),       // WindowPreference fehlt oder "0"
        COVERED_BY_PART("coveredByPart"), // Wand geometrisch von BuildingPart ueberdeckt
        BA_NO_ZMAX("BA_noZMax"),          // BA ohne Z_Max oder Z_Max <= 0
        NO_PARAMS("noParams"),            // Keine oder ungueltige WindowParams
        NO_POLY("noPoly"),                // Kein Polygon oder < 3 Punkte
        NO_BOTTOM_EDGE("noBottom"),       // Keine Unterkante gefunden
        TOO_SHORT("tooShort"),            // Wand zu kurz fuer Fenster
        TOO_LOW("tooLow"),                // Wand zu niedrig fuer Fenster
        NO_FIT("noFit"),                  // Kein Fenster passt (inkl. Section-Fit)
        BA_NO_ZMAX_ASL("BA_noZMaxAsl"),   // BA ohne Z_MAX_ASL
        PIP_FAIL("pipFail");              // Alle Fenster PiP-Fail (Giebel/Kontur)

        final String label;
        SkipReason(String label) { this.label = label; }
    }

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int windowsCreated = 0;
        public int wallsWithWindows = 0;
        public int wallsSkipped = 0;
        public int gableWindowsDropped = 0;
        public int wwrWarnings = 0;

        /** Detaillierte Skip-Gruende (ein Zaehler je {@link SkipReason}). */
        public final java.util.EnumMap<SkipReason, Integer> skips =
                new java.util.EnumMap<>(SkipReason.class);

        /** Zaehlt eine uebersprungene Wand samt Grund. */
        public void skip(SkipReason reason) {
            wallsSkipped++;
            skips.merge(reason, 1, Integer::sum);
        }

        // Per-Geschoss Fenster-Zaehler
        public final java.util.Map<String, int[]> perGeschoss = new java.util.TreeMap<>();

        /** Zaehlt Fenster fuer ein Geschoss. Dekrementiert den Skip-Zaehler. */
        public void addWindowsForGeschoss(String geschoss, int count) {
            int[] c = perGeschoss.computeIfAbsent(geschoss, k -> new int[]{0, 0});
            c[0] += count;
            c[1]--; // war vorab als Skip gezaehlt
        }

        /** Zaehlt eine uebersprungene Wand fuer ein Geschoss. */
        public void addSkipForGeschoss(String geschoss) {
            perGeschoss.computeIfAbsent(geschoss, k -> new int[]{0, 0})[1]++;
        }

        public String toSummary() {
            StringBuilder sb = new StringBuilder("Skip-Gruende: ");
            for (SkipReason r : SkipReason.values()) {
                if (sb.length() > "Skip-Gruende: ".length()) sb.append(", ");
                sb.append(r.label).append('=').append(skips.getOrDefault(r, 0));
            }
            return sb.toString();
        }

        public String toGeschossSummary() {
            StringBuilder sb = new StringBuilder("Per-Geschoss: ");
            for (var entry : perGeschoss.entrySet()) {
                int[] counts = entry.getValue();
                sb.append(entry.getKey())
                  .append("(win=").append(counts[0])
                  .append("/skip=").append(counts[1])
                  .append(") ");
            }
            return sb.toString().trim();
        }
    }
}
