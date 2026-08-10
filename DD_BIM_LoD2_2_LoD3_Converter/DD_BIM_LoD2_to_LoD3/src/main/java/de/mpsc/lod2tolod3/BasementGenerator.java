package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.DgmLoader;
import de.mpsc.lod2tolod3.util.DgmProvider;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.GroundSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Schritt 2: Keller-Generator. Erzeugt Kellerwaende, -boden, -decke und TIC fuer Gebaeude
 * mit Keller (siehe Doku.md, Abschnitt "Schritt 2").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.BasementGenerator input.gml jsonDir [output.gml] [dgm.asc]
 */
public class BasementGenerator extends AbstractGenerator<BasementGenerator.GenerationStats> {
    private static final String BASEMENT_MARKER = "LoD3_Basement";

    /** Toleranz fuer WindowPreference-Zuordnung Kellerwand ↔ Original-Wand (50cm). */
    private static final double WINDOW_PREFERENCE_TOLERANCE = 0.5;

    /** Optionaler DGM-Provider fuer detaillierte TIC-Hoehen. */
    private DgmProvider dgm;

    public static void main(String[] args) {
        BasementGenerator gen = new BasementGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_basement"; }
    @Override protected String displayName()  { return "Keller-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    /** Laedt das optionale DGM aus args[3]; ohne DGM wird eine flache TIC bei H_DGM erzeugt. */
    @Override
    protected void onConfigure(String[] args) {
        if (args.length < 4) {
            log.info("DGM:    nicht vorhanden (Flat-TIC)");
            return;
        }
        Path dgmPath = Paths.get(args[3]);
        if (!Files.exists(dgmPath)) {
            log.warn("DGM-Pfad nicht gefunden: {} — verwende Flat-TIC", dgmPath);
            return;
        }
        try {
            setDgm(DgmLoader.load(dgmPath));
            log.info("DGM:    {}", dgm.describe());
        } catch (IOException e) {
            log.warn("DGM laden fehlgeschlagen ({}): {} — verwende Flat-TIC", dgmPath, e.getMessage());
        }
    }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Keller hinzugefuegt: {}", stats.basementsAdded);
        log.info("GroundSurfaces ersetzt: {}", stats.groundSurfacesReplaced);
        log.info("TICs erzeugt: {}", stats.ticsCreated);
    }

    /** Setzt den optionalen DGM-Provider fuer detaillierte TIC-Hoehen (null = Flat-TIC). */
    public void setDgm(DgmProvider dgm) {
        this.dgm = dgm;
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /** Verarbeitet ein Gebaeude: delegiert an processAbstractBuilding fuer Building und BuildingParts. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;

        Double hDgm = CityGmlUtils.parseDoubleAttribute(building, "H_DGM");
        if (hDgm == null) return;

        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, bp.sst(), hDgm, bp.params(), stats);
        }
    }

    /** Erzeugt Kellerwaende, -boden, -decke und TIC fuer ein AbstractBuilding (siehe Doku.md Schritt 2). */
    private void processAbstractBuilding(AbstractBuilding target, String sst, double hDgm,
            ModuleParameters params, GenerationStats stats) {

        // Keller-Parameter
        if (!params.hasBasement() || params.getBasement() == null) return;
        var basement = params.getBasement();
        if (basement.height == null || Double.isNaN(basement.height) || basement.height <= 0) return;
        double basementHeight = basement.height;
        double basementCeHe = (basement.ceilingHeight != null && !Double.isNaN(basement.ceilingHeight))
                ? basement.ceilingHeight : 0;

        // Gesamthoehe Keller = BA.height + BA.CeHe
        double basementTotalHeight = basementHeight + basementCeHe;

        // heightGr = oberirdischer Anteil des Kellers (GF.heightAboveGround)
        double heightGr = params.getHeightGr();

        // Nur GroundSurface-Polygone nahe H_DGM gehoeren zum Kellerfussabdruck.
        final double ELEVATED_THRESHOLD = 0.50;
        List<Polygon> groundPolygons = new ArrayList<>();
        List<AbstractSpaceBoundaryProperty> gsToRemove = new ArrayList<>();
        for (var boundary : target.getBoundaries()) {
            if (!(boundary.getObject() instanceof GroundSurface gs)) continue;
            String struktur = CityGmlUtils.getStringAttribute(gs, "STRUKTUR");
            if ("Bodenplatte".equals(struktur)) continue; // bereits vom BasementGenerator erzeugt
            MultiSurfaceProperty msp = gs.getLod3MultiSurface();
            if (msp == null || msp.getObject() == null) continue;
            for (var member : msp.getObject().getSurfaceMember()) {
                if (!(member.getObject() instanceof Polygon poly)) continue;
                List<CityGmlUtils.Point3D> pts = CityGmlUtils.toPoints(poly);
                if (pts.isEmpty()) continue;
                double polyMinZ = pts.stream().mapToDouble(p -> p.z).min().orElse(hDgm);
                if (polyMinZ <= hDgm + ELEVATED_THRESHOLD) {
                    groundPolygons.add(poly);
                    if (!gsToRemove.contains(boundary)) gsToRemove.add(boundary);
                } else {
                    log.debug("  Erhöhte GroundSurface {} (minZ={}) bleibt erhalten (> H_DGM+{}m)",
                            gs.getId(), CityGmlUtils.formatNum(polyMinZ), ELEVATED_THRESHOLD);
                }
            }
        }
        if (groundPolygons.isEmpty()) {
            log.warn("Keine terrain-nahen GroundSurface-Polygone fuer sst={} (gml:id={})", sst, target.getId());
            return;
        }

        String targetId = target.getId() != null ? target.getId() : "unknown";

        // === Nur terrain-nahe Original-GroundSurfaces entfernen ===
        target.getBoundaries().removeAll(gsToRemove);
        stats.groundSurfacesReplaced += gsToRemove.size();

        // === Hoehen berechnen (gerundet auf mm-Genauigkeit) ===
        // Keller-Oberkante = H_DGM + heightGr (oberirdischer Anteil)
        double basementTopZ = CityGmlUtils.roundZ(hDgm + heightGr);
        // Kellerboden = Oberkante - Gesamthoehe
        double basementFloorZ = CityGmlUtils.roundZ(basementTopZ - basementTotalHeight);

        log.info("Verarbeite sst={} (gml:id={}): BA.height={}, BA.CeHe={}, heightGr={}, H_DGM={}, Top={}, Floor={}",
                sst, targetId, basementHeight, basementCeHe, heightGr, hDgm,
                CityGmlUtils.formatNum(basementTopZ), CityGmlUtils.formatNum(basementFloorZ));

        // === WindowPreference-Zuordnung vorbereiten ===
        // Bestehende Waende sammeln, um WindowPreference auf Kellerwaende zu uebertragen
        List<WallSurface> existingWalls = CityGmlUtils.collectWallSurfaces(target);

        int floorCount = 0;
        int wallCount = 0;

        // Kellerdecken werden pro Footprint mit erzeugt, aber erst NACH allen
        // Boeden/Waenden an die Boundaries gehaengt (stabile Element-Reihenfolge im GML).
        List<AbstractSpaceBoundaryProperty> ceilings = new ArrayList<>();

        for (Polygon groundPoly : groundPolygons) {
            // Nur entduplizieren, KEIN kollineares Mergen (desynchronisiert sonst geteilte Kanten zu GF-Waenden).
            List<Point3D> groundPoints =
                    CityGmlUtils.dedupConsecutive(CityGmlUtils.toPoints(groundPoly), CityGmlUtils.POINT_MERGE_TOL);
            if (groundPoints.size() < 3) continue;

            floorCount++;

            // ── Kellerboden als GroundSurface (physische Bodenplatte) ──
            List<Point3D> floorPoints = CityGmlUtils.projectToZ(groundPoints, basementFloorZ);
            Polygon floorPoly = CityGmlUtils.createPolygon(floorPoints);

            GroundSurface ground = new GroundSurface();
            String groundFaceId = targetId + "_BA_Ground_" + floorCount;
            ground.setId("Face_" + groundFaceId);
            CityGmlUtils.setGmlName(ground, "LOD3_Ground");
            ground.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(floorPoly));

            double floorArea = CityGmlUtils.calculatePolygonArea2D(floorPoints);
            CityGmlUtils.addHorizontalSurfaceAttributes(ground, groundFaceId,
                    basementFloorZ, hDgm, floorArea, "BA");
            CityGmlUtils.addStringAttribute(ground, "STRUKTUR", "Bodenplatte");
            CityGmlUtils.addStringAttribute(ground, "Lage", "belowGround");

            target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ground));

            // ── Kellerwaende (WallSurface pro Kante), Oberkante basementTopZ, Unterkante basementFloorZ ──
            List<Point3D> topProjected = CityGmlUtils.projectToZ(groundPoints, basementTopZ);
            List<Point3D> topNoClose = CityGmlUtils.removeClosingPoint(topProjected);
            for (int i = 0; i < topNoClose.size(); i++) {
                wallCount++;
                Point3D a = topNoClose.get(i);
                Point3D b = topNoClose.get((i + 1) % topNoClose.size());
                Point3D aDown = new Point3D(a.x, a.y, basementFloorZ);
                Point3D bDown = new Point3D(b.x, b.y, basementFloorZ);

                // Polygon: A → B → B' → A' (CCW von aussen)
                List<Point3D> wallPoints = List.of(a, b, bDown, aDown);
                Polygon wallPoly = CityGmlUtils.createPolygon(new ArrayList<>(wallPoints));

                WallSurface wall = new WallSurface();
                String wallFaceId = targetId + "_BA_Wall_" + wallCount;
                wall.setId("Face_" + wallFaceId);
                CityGmlUtils.setGmlName(wall, "LOD3_BasementWall");
                wall.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));

                CityGmlUtils.addWallAttributes(wall, new ArrayList<>(wallPoints),
                        wallFaceId, hDgm, "BA", "belowGround", "Kellerwand", null);

                // WindowPreference vom zugehoerigen Original-Wall uebertragen
                String windowPref = findWindowPreferenceForEdge(existingWalls, a, b);
                if (windowPref != null) {
                    CityGmlUtils.addStringAttribute(wall, "WindowPreference", windowPref);
                }

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(wall));
            }

            // ── Kellerdecke (CeilingSurface), Z=basementTopZ, gml:id fuer XLink vom GF-Floor ──
            List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(groundPoints, basementTopZ);
            Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

            // gml:id auf dem Polygon setzen (fuer XLink-Referenz vom GF-Floor)
            String slabGmlId = "Slab_" + targetId + "_BA_" + floorCount;
            ceilingPoly.setId(slabGmlId);

            CeilingSurface ceiling = new CeilingSurface();
            String ceilingFaceId = targetId + "_BA_Ceiling_" + floorCount;
            ceiling.setId("Face_" + ceilingFaceId);
            CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
            ceiling.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

            double ceilingArea = CityGmlUtils.calculatePolygonArea2D(ceilingPoints);
            CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                    basementTopZ, hDgm, ceilingArea, "BA");

            ceilings.add(new AbstractSpaceBoundaryProperty(ceiling));
        }
        int ceilingCount = ceilings.size();
        target.getBoundaries().addAll(ceilings);

        // storeysBelowGround als natives CityGML-Element
        target.setStoreysBelowGround(1);

        // === TerrainIntersectionCurve (TIC): ohne DGM flacher Ring, mit DGM bilinear interpoliert ===
        MultiCurveProperty tic = CityGmlUtils.createTerrainIntersectionCurve(
                groundPolygons, hDgm, dgm);
        if (tic != null) {
            target.setLod3TerrainIntersectionCurve(tic);
            stats.ticsCreated++;
        }

        // === Ergebnis ===
        CityGmlUtils.addStringAttribute(target, BASEMENT_MARKER, "generated");
        stats.basementsAdded++;

        log.info("  => {} Kellerwaende, {} GroundSurfaces, {} Decken, {} alte GS entfernt, TIC={}",
                wallCount, floorCount, ceilingCount, gsToRemove.size(), tic != null);
    }

    // ==================== Hilfsmethoden ====================

    /**
     * Sucht die WindowPreference derjenigen Original-Wand, auf deren Grundkante die gegebene
     * Kellerwand-Kante (a→b) liegt (Kollinearitaets-, nicht Mittelpunkt-Check — erkennt auch
     * Teilsegmente einer zerschnittenen Original-Wand, siehe Doku.md Schritt 2).
     *
     * @param walls Bestehende WallSurfaces des Gebaeudes
     * @param a Startpunkt der Kellerwand-Kante
     * @param b Endpunkt der Kellerwand-Kante
     * @return WindowPreference-String oder null
     */
    private String findWindowPreferenceForEdge(List<WallSurface> walls, Point3D a, Point3D b) {
        double edgeMidX = (a.x + b.x) / 2.0;
        double edgeMidY = (a.y + b.y) / 2.0;

        String bestPref = null;
        double bestPerpDist = Double.MAX_VALUE;

        for (WallSurface wall : walls) {
            String pref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
            if (pref == null) continue;

            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;

            List<Point3D> pts = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(wallPoly));
            CityGmlUtils.BottomEdge edge = CityGmlUtils.findBottomEdge(pts);
            if (edge == null) continue;

            double wsx = edge.start().x, wsy = edge.start().y;
            double wex = edge.end().x, wey = edge.end().y;
            double wlen = edge.wallLength();
            if (wlen < 1e-6) continue;
            double dirX = (wex - wsx) / wlen, dirY = (wey - wsy) / wlen;

            double relX = edgeMidX - wsx, relY = edgeMidY - wsy;
            double u = relX * dirX + relY * dirY;                        // Projektion entlang der Wand
            double perpDist = Math.abs(relX * -dirY + relY * dirX);      // senkrechter Abstand zur Wandlinie

            if (perpDist > WINDOW_PREFERENCE_TOLERANCE) continue;
            if (u < -WINDOW_PREFERENCE_TOLERANCE || u > wlen + WINDOW_PREFERENCE_TOLERANCE) continue;

            if (perpDist < bestPerpDist) {
                bestPerpDist = perpDist;
                bestPref = pref;
            }
        }

        return bestPref;
    }

    // ==================== Statistiken ====================

    /** Statistiken der Generierung. */
    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int basementsAdded = 0;
        public int groundSurfacesReplaced = 0;
        public int ticsCreated = 0;
    }
}
