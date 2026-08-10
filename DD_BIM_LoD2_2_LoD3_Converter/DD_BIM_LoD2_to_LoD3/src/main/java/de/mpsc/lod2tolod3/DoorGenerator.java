package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.*;

/**
 * Schritt 4: Tuer-Generator. Erzeugt Tueren an GF-Wandsegmenten anhand des DoorCount-Attributs
 * (siehe Doku.md, Abschnitt "Schritt 4").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.DoorGenerator input.gml jsonDir [output.gml]
 */
public class DoorGenerator extends AbstractGenerator<DoorGenerator.GenerationStats> {

    /** Tuersockelhoehe: 5 cm ueber Wandunterkante. */
    private static final double DOOR_SILL_HEIGHT = 0.05;

    /** Minimaler Abstand zwischen Tueren und zum Wandrand (10 cm). */
    private static final double MIN_SPACING = 0.10;

    /** Toleranz fuer „Tuermitte liegt auf gemeinsamer Footprint-Kante" (Anbau-Verdeckung, 10 cm). */
    private static final double FOOTPRINT_SHARE_TOL = 0.10;

    public static void main(String[] args) {
        DoorGenerator gen = new DoorGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_doors"; }
    @Override protected String displayName()  { return "Tuer-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Tueren erzeugt: {}", stats.doorsCreated);
        log.info("Waende modifiziert: {}", stats.wallsModified);
        log.info("Waende uebersprungen: {}", stats.wallsSkipped);
        log.info("Tueren hinter Anbau verworfen: {}", stats.doorsSkippedCovered);
        log.info("Tueren ausserhalb Wandkontur verworfen: {}", stats.doorsSkippedOutside);
        log.info("Waende uebersprungen (geteilt zwischen BuildingParts): {}", stats.wallsSkippedCoveredByPart);
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /** Verarbeitet ein Gebaeude: delegiert an processAbstractBuilding fuer Building und BuildingParts. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters params = bp.params();

        if (params.getGroundFloor() == null) return;
        ModuleParameters.DoorParams doorParams = params.getGroundFloor().door;
        if (doorParams == null || !doorParams.isValid()) return;

        List<AbstractBuilding> targets = CityGmlUtils.getBuildingTargets(building);

        // Footprints aller Targets fuer die Anbau-Verdeckungspruefung sammeln.
        List<List<Point3D>> footprints = new ArrayList<>();
        for (var t : targets) {
            for (Polygon gp : CityGmlUtils.collectGroundPolygons(t)) {
                List<Point3D> ring = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(gp));
                if (ring.size() >= 3) footprints.add(ring);
            }
        }

        // Verhindert doppelte Tueren auf einer von zwei BuildingParts geteilten Wand.
        Set<String> processedWallMids = new HashSet<>();

        for (var target : targets) {
            processAbstractBuilding(target, doorParams, footprints, processedWallMids, stats);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /** Sucht alle GF-WallSurfaces mit DoorCount-Attribut und fuegt Tueren ein. */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters.DoorParams doorParams, List<List<Point3D>> footprints,
            Set<String> processedWallMids, GenerationStats stats) {

        List<WallSurface> walls = CityGmlUtils.collectWallSurfaces(target);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            if (!"GF".equals(geschoss)) continue;

            String doorCountStr = CityGmlUtils.getStringAttribute(wall, "DoorCount");
            if (doorCountStr == null) continue;

            int doorCount;
            try {
                doorCount = Integer.parseInt(doorCountStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (doorCount < -1) continue; // ungueltig

            // Doppelte Wand zwischen BuildingParts: nur der erste Part platziert Tueren
            // (identische Geometrie an der gemeinsamen Grenze zweier Parts).
            String midKey = CityGmlUtils.wallBottomMidKey(wall);
            if (midKey != null && !processedWallMids.add(midKey)) {
                stats.wallsSkippedCoveredByPart++;
                continue;
            }

            // 0 = keine Tuer; -1 = Hintertuer (1 Tuer mit Hintertuer-Attribut)
            if (doorCount == 0) continue;

            if (doorCount == -1) {
                processWall(wall, 1, true, doorParams, footprints, stats);
            } else {
                processWall(wall, doorCount, false, doorParams, footprints, stats);
            }
        }
    }

    // ==================== Wand-Verarbeitung ====================

    /** Fuegt doorCount Tueren in eine GF-WallSurface ein (siehe Doku.md Schritt 4). */
    private void processWall(WallSurface wall, int doorCount, boolean isHintertuer,
            ModuleParameters.DoorParams doorParams, List<List<Point3D>> footprints,
            GenerationStats stats) {

        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) return;

        List<Point3D> allPoints = CityGmlUtils.toPoints(wallPoly);
        List<Point3D> open = CityGmlUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) return;

        double doorWidth = doorParams.doorWidth;
        double doorHeight = doorParams.doorHeight;
        double hDistDoorWall = (doorParams.hDistDoorWall != null) ? doorParams.hDistDoorWall : 0.5;

        // --- Unterkante der Wand ermitteln (laengste Kante bei zMin) ---
        CityGmlUtils.BottomEdge edge = CityGmlUtils.findBottomEdge(open);
        if (edge == null) {
            log.warn("Keine Unterkante gefunden fuer Wand {}", wall.getId());
            stats.wallsSkipped++;
            return;
        }
        double zMin = edge.zMin();
        double wallHeight = edge.zMax() - edge.zMin();

        // Validierung: Tuerhoehe + Sockel muss in die Wand passen
        if (doorHeight + DOOR_SILL_HEIGHT > wallHeight) {
            log.warn("Tuer passt nicht in Wand {} (Tuerhoehe {} + Sockel > Wandhoehe {})",
                    wall.getId(), CityGmlUtils.formatNum(doorHeight),
                    CityGmlUtils.formatNum(wallHeight));
            stats.wallsSkipped++;
            return;
        }

        Point3D edgeStart = edge.start();
        Point3D edgeEnd = edge.end();
        double wallLength = edge.wallLength();

        // --- Tuer-Positionen berechnen ---
        double totalDoorWidth = doorCount * doorWidth;
        double requiredWidth = hDistDoorWall + totalDoorWidth;

        double[] doorLeftOffsets = new double[doorCount];

        if (requiredWidth <= wallLength + 0.01) {
            // Normalfall: HDistDoWa einhalten
            doorLeftOffsets[0] = hDistDoorWall;

            if (doorCount > 1) {
                double remainingSpace = wallLength - hDistDoorWall - doorWidth;
                int n = doorCount - 1;
                double spacing = (remainingSpace - n * doorWidth) / (n + 1);

                if (spacing < MIN_SPACING) {
                    log.warn("Zu wenig Abstand zwischen Tueren in Wand {} (spacing={}m)",
                            wall.getId(), CityGmlUtils.formatNum(spacing));
                    stats.wallsSkipped++;
                    return;
                }

                for (int i = 1; i < doorCount; i++) {
                    doorLeftOffsets[i] = hDistDoorWall + i * (doorWidth + spacing);
                }
            }
        } else {
            // Fallback: Tuer(en) zentrieren wenn HDistDoWa nicht passt
            double gapBetween = (doorCount > 1) ? MIN_SPACING : 0.0;
            double minNeeded = totalDoorWidth + (doorCount - 1) * gapBetween + 2 * MIN_SPACING;

            if (minNeeded > wallLength) {
                log.warn("Wand {} zu schmal fuer {} Tuer(en) (min {}m benoetigt, Wandlaenge {}m)",
                        wall.getId(), doorCount, CityGmlUtils.formatNum(minNeeded),
                        CityGmlUtils.formatNum(wallLength));
                stats.wallsSkipped++;
                return;
            }

            log.info("HDistDoWa ({}) passt nicht in Wand {} ({}m) - Tuer(en) werden zentriert",
                    CityGmlUtils.formatNum(hDistDoorWall), wall.getId(),
                    CityGmlUtils.formatNum(wallLength));
            double leftMargin = (wallLength - totalDoorWidth - (doorCount - 1) * gapBetween) / 2;
            doorLeftOffsets[0] = leftMargin;
            for (int i = 1; i < doorCount; i++) {
                doorLeftOffsets[i] = leftMargin + i * (doorWidth + gapBetween);
            }
        }

        // --- Richtungsvektoren der Unterkante ---
        double dx = edgeEnd.x - edgeStart.x;
        double dy = edgeEnd.y - edgeStart.y;
        double dirX = dx / wallLength;
        double dirY = dy / wallLength;

        // Orientierung des Aussenrings bestimmen
        boolean extCCW = CityGmlUtils.isExteriorRingCCW(open, edgeStart, dirX, dirY);

        double doorBottomZ = CityGmlUtils.roundZ(zMin + DOOR_SILL_HEIGHT);
        double doorTopZ = CityGmlUtils.roundZ(zMin + DOOR_SILL_HEIGHT + doorHeight);

        // Flaeche der Wand vor Modifikation
        double wallAreaBefore = CityGmlUtils.calculateWallArea(open);

        // --- WallFaceID fuer Tuer-IDs ---
        String wallFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
        if (wallFaceId == null) {
            wallFaceId = wall.getId() != null ? wall.getId() : "unknown";
        }

        // --- 2D-Wandkontur fuer den Oeffnungs-Check (Tuer muss vollstaendig in der Wandflaeche liegen) ---
        double[][] wallPoly2D = CityGmlUtils.projectWallTo2D(open, edgeStart, dirX, dirY, zMin);

        // --- Tuer-Geometrien berechnen und DoorSurfaces erzeugen ---
        int doorsPlaced = 0;

        for (int d = 0; d < doorCount; d++) {
            double offset = doorLeftOffsets[d];

            // Anbau-Verdeckung: liegt die Tuermitte auf der gemeinsamen Kante zweier Footprints
            // (eigenes Gebaeude + Anbau), wuerde die Tuer hinter dem Anbau liegen → ueberspringen.
            double doorCx = edgeStart.x + (offset + doorWidth / 2.0) * dirX;
            double doorCy = edgeStart.y + (offset + doorWidth / 2.0) * dirY;
            int onBoundary = 0;
            for (List<Point3D> fp : footprints) {
                if (CityGmlUtils.distPointToRingXY(doorCx, doorCy, fp) < FOOTPRINT_SHARE_TOL) onBoundary++;
            }
            if (onBoundary >= 2) {
                stats.doorsSkippedCovered++;
                continue;
            }

            // Kontur-Check: alle 4 Tuer-Ecken muessen im Wandpolygon liegen
            if (!CityGmlUtils.openingInsideWall2D(offset, offset + doorWidth,
                    doorBottomZ - zMin, doorTopZ - zMin, wallPoly2D)) {
                stats.doorsSkippedOutside++;
                continue;
            }

            // Tuer-Eckpunkte: BL, BR, TR, TL (von aussen gesehen)
            Point3D bl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorBottomZ);
            Point3D br = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorBottomZ);
            Point3D tr = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorTopZ);
            Point3D tl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorTopZ);

            // Innenring in die Wand einfuegen + DoorSurface-Polygon konsistent orientiert erzeugen
            String doorId = wallFaceId + "_Door_" + (d + 1);
            Polygon doorPoly = CityGmlUtils.addOpeningToWall(wallPoly, bl, br, tr, tl, extCCW);

            DoorSurface doorSurface = new DoorSurface();
            doorSurface.setId("Face_" + doorId);
            CityGmlUtils.setGmlName(doorSurface, "LOD3_Door");
            doorSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(doorPoly));

            // Attribute
            double doorArea = doorWidth * doorHeight;
            CityGmlUtils.addStringAttribute(doorSurface, "BldgFaceID", doorId);
            CityGmlUtils.addStringAttribute(doorSurface, "FACEAREA",
                    CityGmlUtils.formatNum(doorArea));
            CityGmlUtils.addStringAttribute(doorSurface, "Geschoss", "GF");

            if (isHintertuer) {
                CityGmlUtils.addStringAttribute(doorSurface, "Hintertuer", "true");
            }

            // Als FillingSurface an der WallSurface verankern
            wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(doorSurface));
            stats.doorsCreated++;
            doorsPlaced++;
        }

        // Alle Tueren verworfen (Anbau-Verdeckung/Kontur)? → Wand unveraendert lassen
        if (doorsPlaced == 0) {
            return;
        }

        // FACEAREA der Wand aktualisieren (Wandflaeche minus tatsaechlich erzeugte Tueroeffnungen)
        double totalDoorArea = doorsPlaced * doorWidth * doorHeight;
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                CityGmlUtils.formatNum(Math.max(0, wallAreaBefore - totalDoorArea)));

        stats.wallsModified++;
    }

    // ==================== Statistiken ====================

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int doorsCreated = 0;
        public int wallsModified = 0;
        public int wallsSkipped = 0;
        public int doorsSkippedCovered = 0; // Tueren hinter Anbau verworfen
        public int doorsSkippedOutside = 0; // Tueren ausserhalb der Wandkontur verworfen
        public int wallsSkippedCoveredByPart = 0; // Wand geometrisch von anderem BuildingPart geteilt
    }
}
