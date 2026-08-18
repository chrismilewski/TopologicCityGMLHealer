package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.*;

/**
 * Schritt 3: Geschoss-Unterteilung. Teilt Waende in geschossweise Segmente (GF, UF_1, UF_2, ...)
 * und erzeugt Floor-/CeilingSurface pro Geschoss (siehe Doku.md, Abschnitt "Schritt 3").
 */
public class StoreyGenerator extends AbstractGenerator<StoreyGenerator.GenerationStats> {

    /** Mindesthoehe fuer Schnitt-Ergebnisse (5cm) – verhindert degenerierte Geometrien. */
    private static final double CUT_TOLERANCE = 0.05;

    /** Mindesthoehe fuer Wand-Segmente nach Schnitt (50cm), verhindert sichtbare Duennstreifen. */
    private static final double MIN_WALL_SEGMENT_HEIGHT = 0.50;

    /** Toleranz fuer Flachdach-Erkennung (30cm) — wenn First-Traufe < Wert → Flachdach. */
    private static final double FLAT_ROOF_TOLERANCE = 0.30;

    /** XY-Toleranz fuer Kantenzuordnung von Wandsegmenten zu Grundriss-Polygonen (50cm). */
    private static final double XY_EDGE_TOLERANCE = 0.50;

    /** Mindest-Geschosshoehe (1.2m) – verhindert unrealistisch kurze Geschosse (Fitzelchen). */
    private static final double MIN_STOREY_HEIGHT = 1.20;

    /** Max. Geschosshoehe bei Flachdach-Fitzelchen-Merge (4.0m), sonst eigenes kurzes Geschoss. */
    private static final double MAX_STOREY_HEIGHT_FLACHDACH = 4.0;

    public static void main(String[] args) {
        StoreyGenerator gen = new StoreyGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_storeys"; }
    @Override protected String displayName()  { return "Geschoss-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Geschosse erstellt: {}", stats.storeysCreated);
        log.info("Wandsegmente erstellt: {}", stats.wallSegmentsCreated);
        log.info("Waende geschnitten: {}", stats.wallsCut);
        log.info("Boeden erstellt: {}", stats.floorsCreated);
        log.info("Decken erstellt: {}", stats.ceilingsCreated);
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

        // Solid-Shell immer neu aufbauen, auch bei vorzeitigem Abbruch.
        for (var target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, bp.sst(), hDgm, bp.params(), stats);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /** Berechnet Geschossgrenzen fuer ein AbstractBuilding und schneidet dessen Waende entsprechend. */
    private void processAbstractBuilding(AbstractBuilding target, String sst, double hDgm,
            ModuleParameters params, GenerationStats stats) {

        double[] roofZRange = CityGmlUtils.getRoofZRange(target);
        if (roofZRange == null) {
            log.debug("Keine RoofSurface fuer Gebaeude/Part {}", target.getId());
            return;
        }
        double traufeZ = roofZRange[0];
        double firstZ = roofZRange[1];
        // rawMinRoofZ: globales Minimum-Z aller RoofSurface-Polygone
        // (inkl. moegliche Boden-Niveau-Artefakte und flache Teilflaechen)
        double rawMinRoofZ = roofZRange[2];
        // slopedRawMinRoofZ: Min-Z nur geneigter Dachflaechen (MAX_VALUE = kein Mischdach)
        final double slopedRawMinRoofZ = roofZRange.length > 3 ? roofZRange[3] : rawMinRoofZ;

        // --- Hoehen-Parameter ---
        double heightGr = params.getHeightGr();
        double gfHeight = params.getGroundFloor() != null ? params.getGroundFloor().getTotalHeight() : 0;
        double ufHeight = params.getUpperFloor() != null ? params.getUpperFloor().getTotalHeight() : 0;

        // egFloorZ = Oberkante Sockel/Fundament; nur Keller-Gebaeude schneiden Waende hier.
        double egFloorZ = CityGmlUtils.roundZ(hDgm + heightGr);

        if (gfHeight <= 0) {
            log.warn("GF.height fehlt/ungueltig fuer sst={}, ueberspringe Geschossteilung", sst);
            return;
        }

        if (traufeZ <= egFloorZ + CUT_TOLERANCE) {
            log.warn("Traufe ({}) <= EG-Floor ({}) fuer sst={}, ueberspringe",
                    CityGmlUtils.formatNum(traufeZ), CityGmlUtils.formatNum(egFloorZ), sst);
            return;
        }

        // --- Flachdach-Erkennung (vor Geschossberechnung fuer Fitzelchen-Logik) ---
        boolean isFlachdach = (firstZ - traufeZ) < FLAT_ROOF_TOLERANCE;

        // --- Geschossgrenzen dynamisch berechnen ---
        List<StoreyInfo> storeys = calculateStoreys(
                egFloorZ, gfHeight, ufHeight, traufeZ, sst, isFlachdach);
        if (storeys.isEmpty()) return;

        String targetId = target.getId() != null ? target.getId() : "unknown";

        log.debug("Verarbeite sst={} (gml:id={}): {} Geschosse, EG-Floor={}, Traufe={}",
                sst, targetId, storeys.size(),
                CityGmlUtils.formatNum(egFloorZ), CityGmlUtils.formatNum(traufeZ));

        // --- Waende schneiden: egFloorZ (nur Keller) + Geschoss-Ceilings ---
        List<Double> cutZValues = new ArrayList<>();
        if (params.hasBasement()) {
            cutZValues.add(egFloorZ);
        }
        for (int i = 0; i < storeys.size() - 1; i++) {
            cutZValues.add(storeys.get(i).ceilingZ);
        }

        List<AbstractSpaceBoundaryProperty> toRemove = new ArrayList<>();
        List<AbstractSpaceBoundaryProperty> toAdd = new ArrayList<>();
        int wallsCut = 0;
        int segmentsCreated = 0;

        // Zaehler fuer laufende Nummern pro Geschoss-Tag (fuer Wand-Benennung)
        Map<String, Integer> wallCountPerStorey = new HashMap<>();

        for (var boundary : target.getBoundaries()) {
            if (!(boundary.getObject() instanceof WallSurface wall)) continue;

            // BA-Surfaces ueberspringen (vom BasementGenerator erzeugt)
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            if (geschoss != null) continue;

            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;

            List<Point3D> wallPoints = CityGmlUtils.toPoints(wallPoly);
            if (wallPoints.size() < 3) continue;

            double[] zRange = CityGmlUtils.getZRange(wallPoints);
            double wallMinZ = zRange[0];
            double wallMaxZ = zRange[1];

            // Schnitt-Z-Werte filtern: nur Grenzen innerhalb des Wand-Z-Bereichs
            List<Double> applicableCuts = new ArrayList<>();
            boolean hasEgFloorCut = false;
            for (double cutZ : cutZValues) {
                if (cutZ > wallMinZ + CUT_TOLERANCE && cutZ < wallMaxZ - CUT_TOLERANCE) {
                    applicableCuts.add(cutZ);
                    // Merken ob egFloorZ als Schnitt verwendet wird
                    if (Math.abs(cutZ - egFloorZ) < 0.001) {
                        hasEgFloorCut = true;
                    }
                }
            }

            // Duennstreifen-Vermeidung: letzten Schnitt entfernen wenn er ein zu duennes Segment erzeugt.
            if (!applicableCuts.isEmpty()) {
                double lastCut = applicableCuts.get(applicableCuts.size() - 1);
                if (wallMaxZ - lastCut < MIN_WALL_SEGMENT_HEIGHT
                        && Math.abs(lastCut - egFloorZ) > 0.001) {
                    applicableCuts.remove(applicableCuts.size() - 1);
                }
            }

            // Traufen-Schnitt nur bei Flachdach (Schraegdach-Giebelwaende bleiben unangetastet).
            boolean hasTraufeCut = false;
            if (isFlachdach && wallMaxZ > traufeZ + CUT_TOLERANCE && traufeZ > wallMinZ + CUT_TOLERANCE) {
                applicableCuts.add(traufeZ);
                hasTraufeCut = true;
            }

            // Keine Schnitte noetig? → Geschoss-Tag zuweisen, Wand beibehalten
            if (applicableCuts.isEmpty()) {
                // Wand komplett unterhalb egFloorZ? → verwerfen (Keller-Bereich)
                if (wallMaxZ <= egFloorZ + CUT_TOLERANCE) {
                    toRemove.add(boundary);
                    continue;
                }
                // Wand komplett oberhalb Traufe (nur Flachdach)? → verwerfen
                if (isFlachdach && wallMinZ >= traufeZ - CUT_TOLERANCE) {
                    toRemove.add(boundary);
                    continue;
                }
                // Unbeschnittene Wand haengt unter egFloorZ → hart kappen (siehe trimWallBelowEgFloor).
                if (params.hasBasement() && wallMinZ < egFloorZ - CUT_TOLERANCE) {
                    wallPoly = trimWallBelowEgFloor(wallPoly, egFloorZ, wall.getId());
                    wall.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));
                    wallPoints = CityGmlUtils.toPoints(wallPoly);
                    zRange = CityGmlUtils.getZRange(wallPoints);
                    wallMinZ = zRange[0];
                    wallMaxZ = zRange[1];
                }
                StoreyInfo storey = findStoreyForZ(storeys, (wallMinZ + wallMaxZ) / 2.0);
                if (storey != null) {
                    assignGeschossToExistingWall(wall, storey);
                    segmentsCreated++;
                }
                continue;
            }

            // Original-Eigenschaften sichern (fuer Uebernahme auf Segmente)
            String originalWallId = wall.getId();
            String originalFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
            String innenwand = CityGmlUtils.getStringAttribute(wall, "Innenwand");
            String dachTyp = CityGmlUtils.getStringAttribute(wall, "DachTyp_LOD3");
            String dachName = CityGmlUtils.getStringAttribute(wall, "DachName_LOD3");
            String doorCount = CityGmlUtils.getStringAttribute(wall, "DoorCount");
            String windowPref = CityGmlUtils.getStringAttribute(wall, "WindowPreference");
            boolean isAboveNeighbor = WindowPreference.parse(windowPref) == WindowPreference.ABOVE_NEIGHBOR;
            // ABOVE_NEIGHBOR: absoluten Z_Fenster_ASL = Z_MIN_ASL + Z_Fenster vorberechnen
            String zDifferenzStr = CityGmlUtils.getStringAttribute(wall, "Z_Differenz");
            String zFensterAslStr = null;
            if (isAboveNeighbor) {
                String zFensterStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster");
                String origZMinAsl = CityGmlUtils.getStringAttribute(wall, "Z_MIN_ASL");
                if (zFensterStr != null && origZMinAsl != null) {
                    try {
                        double zFensterAsl = Double.parseDouble(origZMinAsl)
                                           + Double.parseDouble(zFensterStr);
                        zFensterAslStr = CityGmlUtils.formatNum(zFensterAsl);
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (innenwand == null) innenwand = "0";

            // Iteratives Schneiden von unten nach oben
            List<Polygon> segments = cutWallAtMultipleZ(wallPoly, applicableCuts);
            if (segments == null || segments.isEmpty()) {
                // Schnitt fehlgeschlagen → nur Tag setzen, aber wie oben auf egFloorZ trimmen.
                if (params.hasBasement() && wallMinZ < egFloorZ - CUT_TOLERANCE) {
                    wallPoly = trimWallBelowEgFloor(wallPoly, egFloorZ, wall.getId());
                    wall.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(wallPoly));
                    double[] trimmedZRange = CityGmlUtils.getZRange(CityGmlUtils.toPoints(wallPoly));
                    wallMinZ = trimmedZRange[0];
                    wallMaxZ = trimmedZRange[1];
                }
                StoreyInfo storey = findStoreyForZ(storeys, (wallMinZ + wallMaxZ) / 2.0);
                if (storey != null) {
                    assignGeschossToExistingWall(wall, storey);
                    segmentsCreated++;
                }
                continue;
            }

            // Original-Wand zum Entfernen markieren
            toRemove.add(boundary);
            wallsCut++;

            // Segmente unterhalb egFloorZ bzw. oberhalb traufeZ verwerfen.
            double keptMaxTop = Double.NEGATIVE_INFINITY;
            for (Polygon seg : segments) {
                double[] z = CityGmlUtils.getZRange(CityGmlUtils.toPoints(seg));
                double midZ = (z[0] + z[1]) / 2.0;
                if (hasEgFloorCut && midZ < egFloorZ - CUT_TOLERANCE) continue;
                if (hasTraufeCut && midZ > traufeZ + CUT_TOLERANCE) continue;
                if (z[1] > keptMaxTop) keptMaxTop = z[1];
            }

            // Fuer jedes Segment: neues WallSurface mit Geschoss-Attributen
            for (int i = 0; i < segments.size(); i++) {
                // Segment entduplizieren (1mm) → gegen CONSECUTIVE_POINTS_SAME (kein kollineares
                // Mergen, sonst brechen geteilte Kanten auf). Polygon neu aufbauen.
                List<Point3D> segPoints =
                        CityGmlUtils.dedupConsecutive(CityGmlUtils.toPoints(segments.get(i)), CityGmlUtils.POINT_MERGE_TOL);
                if (segPoints.size() < 3) continue; // nach Dedup degeneriert
                double[] segZ = CityGmlUtils.getZRange(segPoints);
                double segMidZ = (segZ[0] + segZ[1]) / 2.0;

                // Rand-Segmente Z-basiert verwerfen
                if (hasEgFloorCut && segMidZ < egFloorZ - CUT_TOLERANCE) continue;
                if (hasTraufeCut && segMidZ > traufeZ + CUT_TOLERANCE) continue;

                // Auch ein "erfolgreich" geschnittenes Segment kann unter egFloorZ haengen bleiben
                // (uebersprungener Einzelschnitt in cutWallSinglePieceGuarded) — notfalls nachtrimmen.
                if (params.hasBasement() && segZ[0] < egFloorZ - CUT_TOLERANCE) {
                    Polygon trimmed = trimWallBelowEgFloor(
                            CityGmlUtils.createPolygon(segPoints), egFloorZ, originalWallId);
                    segPoints = CityGmlUtils.dedupConsecutive(
                            CityGmlUtils.toPoints(trimmed), CityGmlUtils.POINT_MERGE_TOL);
                    if (segPoints.size() < 3) continue;
                    segZ = CityGmlUtils.getZRange(segPoints);
                    segMidZ = (segZ[0] + segZ[1]) / 2.0;
                }

                Polygon segPoly = CityGmlUtils.createPolygon(segPoints);

                StoreyInfo storey = findStoreyForZ(storeys, segMidZ);
                if (storey == null) storey = storeys.get(storeys.size() - 1);

                boolean isTopSegment = (segZ[1] >= keptMaxTop - CUT_TOLERANCE);

                // Laufende Nummer pro Geschoss
                int runNum = wallCountPerStorey.merge(storey.geschoss, 1, Integer::sum);

                // Neues WallSurface-Segment: {OrigPolyId}_{StoreyTag}_{RunNum}
                WallSurface segWall = new WallSurface();
                String baseFaceId = originalFaceId != null ? originalFaceId : targetId;
                String segFaceId = baseFaceId + "_" + storey.geschoss + "_" + runNum;
                segWall.setId("Face_" + segFaceId);
                CityGmlUtils.setGmlName(segWall, "LOD3_Wall");
                segWall.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(segPoly));

                // Alle Standard-Wand-Attribute berechnen und setzen
                String struktur = "1".equals(innenwand) ? "Innenwand" : "Aussenwand";
                CityGmlUtils.addWallAttributes(segWall, segPoints,
                        segFaceId, hDgm, storey.geschoss, null, struktur,
                        originalWallId);

                // Geschossdecke-Z schreiben (fuer WindowGenerator: Obergrenze fuer Fensterplatzierung)
                CityGmlUtils.addStringAttribute(segWall, "GeschossDeckeZ",
                        CityGmlUtils.formatNum(storey.ceilingZ));

                // Innenwand-Wert vom Original uebernehmen (addWallAttributes setzt immer "0")
                if ("1".equals(innenwand)) {
                    CityGmlUtils.setStringAttribute(segWall, "Innenwand", "1");
                }

                // Zusatz-Attribute vom Original uebernehmen
                // DoorCount nur am GF-Geschoss (Tueren nur im Erdgeschoss)
                if (doorCount != null && "GF".equals(storey.geschoss)) {
                    CityGmlUtils.addStringAttribute(segWall, "DoorCount", doorCount);
                }
                if (windowPref != null) {
                    CityGmlUtils.addStringAttribute(segWall, "WindowPreference", windowPref);
                }
                // ABOVE_NEIGHBOR: Z_Differenz und absoluten Z_Fenster_ASL fuer den WindowGenerator mitgeben
                if (isAboveNeighbor) {
                    if (zDifferenzStr != null) {
                        CityGmlUtils.addStringAttribute(segWall, "Z_Differenz", zDifferenzStr);
                    }
                    if (zFensterAslStr != null) {
                        CityGmlUtils.addStringAttribute(segWall, "Z_Fenster_ASL", zFensterAslStr);
                    }
                }

                // DachTyp/DachName nur am obersten Geschoss
                if (isTopSegment && dachTyp != null) {
                    CityGmlUtils.addStringAttribute(segWall, "DachTyp_LOD3", dachTyp);
                }
                if (isTopSegment && dachName != null) {
                    CityGmlUtils.addStringAttribute(segWall, "DachName_LOD3", dachName);
                }

                toAdd.add(new AbstractSpaceBoundaryProperty(segWall));
                segmentsCreated++;
            }
        }

        target.getBoundaries().removeAll(toRemove);
        target.getBoundaries().addAll(toAdd);

        // --- Original-GroundSurface markieren (fuer spaetere Verwendung) ---
        // Nur GroundSurfaces markieren, die NICHT vom BasementGenerator erzeugt wurden
        // (BA-Bodenplatten haben STRUKTUR="Bodenplatte")
        for (var boundary : target.getBoundaries()) {
            if (boundary.getObject() instanceof org.citygml4j.core.model.construction.GroundSurface gs) {
                String struktur = CityGmlUtils.getStringAttribute(gs, "STRUKTUR");
                if (!"Bodenplatte".equals(struktur)) {
                    CityGmlUtils.addStringAttribute(gs, "Original_GroundSurface", "preserved");
                }
            }
        }

        // --- Floor/Ceiling pro Geschoss erzeugen (Ceiling inline, Floor des naechsten Geschosses per XLink) ---

        // Mischdach-Boden-Artefakt nahe EG-Fussboden: Slab-Begrenzung auf slopedRawMinZ umstellen.
        if (slopedRawMinRoofZ < Double.MAX_VALUE / 2
                && slopedRawMinRoofZ > rawMinRoofZ + CUT_TOLERANCE
                && rawMinRoofZ < egFloorZ + 2.0) {
            log.debug("  Mischdach-Artefakt {}: rawMinRoofZ={} nahe EG={}, verwende slopedRawMinZ={}",
                    targetId, CityGmlUtils.formatNum(rawMinRoofZ),
                    CityGmlUtils.formatNum(egFloorZ), CityGmlUtils.formatNum(slopedRawMinRoofZ));
            rawMinRoofZ = slopedRawMinRoofZ;
        }
        double slabsTraufeZ = rawMinRoofZ;

        if (slabsTraufeZ <= egFloorZ + CUT_TOLERANCE) {
            // Terrain-Niveau-Artefakt: keine Decken/Boeden erzeugen
            log.debug("Part/Building {} rawMinRoofZ={} <= egFloorZ={}, ueberspringe Decken/Boeden",
                    targetId, CityGmlUtils.formatNum(slabsTraufeZ), CityGmlUtils.formatNum(egFloorZ));
            stats.storeysCreated += storeys.size();
            stats.wallsCut += wallsCut;
            stats.wallSegmentsCreated += segmentsCreated;
            return;
        }

        // Slab-Geschosse, begrenzt durch slabsTraufeZ (Normalfall: identisch zu storeys).
        final boolean slabsAreLimited = (slabsTraufeZ < traufeZ - CUT_TOLERANCE);
        final List<StoreyInfo> slabStoreys;
        if (slabsAreLimited) {
            boolean isFlachdachSlabs = (firstZ - slabsTraufeZ) < FLAT_ROOF_TOLERANCE;
            slabStoreys = calculateStoreys(egFloorZ, gfHeight, ufHeight, slabsTraufeZ, sst, isFlachdachSlabs);
            log.debug("  Slab-Begrenzung {}: slabsTraufeZ={} < traufeZ={}, {} statt {} Slab-Geschosse",
                    targetId, CityGmlUtils.formatNum(slabsTraufeZ), CityGmlUtils.formatNum(traufeZ),
                    slabStoreys.size(), storeys.size());
        } else {
            slabStoreys = storeys;
        }
        if (slabStoreys.isEmpty()) {
            stats.storeysCreated += storeys.size();
            stats.wallsCut += wallsCut;
            stats.wallSegmentsCreated += segmentsCreated;
            return;
        }

        List<Polygon> groundPolygons = CityGmlUtils.collectGroundPolygons(target);
        int floorsAdded = 0;
        int ceilingsAdded = 0;

        // BA-Ceiling-IDs vom BasementGenerator (der GF-Floor referenziert sie per XLink).
        Map<Integer, String> baCeilingSlabIds = new HashMap<>();
        int baPolyIdx = 0;
        for (var boundary : target.getBoundaries()) {
            if (boundary.getObject() instanceof CeilingSurface cs) {
                String csGeschoss = CityGmlUtils.getStringAttribute(cs, "Geschoss");
                if ("BA".equals(csGeschoss)) {
                    baPolyIdx++;
                    // Slab-ID nach Konvention: Slab_{targetId}_BA_{nr}
                    String expectedSlabId = "Slab_" + targetId + "_BA_" + baPolyIdx;
                    baCeilingSlabIds.put(baPolyIdx, expectedSlabId);
                }
            }
        }
        // Mischdach-Erkennung: bei Flachdach+Schraegdach-Mix braucht nur die geneigte Flaeche eine CeilingSurface.
        List<Polygon> roofPolygons = CityGmlUtils.collectRoofPolygons(target);
        List<Polygon> slopedRoofPolygons = new ArrayList<>();
        int flatRoofCount = 0;

        for (Polygon roofPoly : roofPolygons) {
            List<Point3D> pts = CityGmlUtils.toPoints(roofPoly);
            if (pts.isEmpty()) continue;
            double maxZ = pts.stream().mapToDouble(p -> p.z).max().orElse(0);
            if ((maxZ - traufeZ) < 0.30) {
                flatRoofCount++;
            } else {
                slopedRoofPolygons.add(roofPoly);
            }
        }

        boolean isMixedRoof = flatRoofCount > 0 && !slopedRoofPolygons.isEmpty();
        if (isMixedRoof) {
            log.debug("  Mischdach erkannt fuer {}: {} flache + {} geneigte Dachflaechen",
                    targetId, flatRoofCount, slopedRoofPolygons.size());
        }

        // Per-Kante Hoehengrenze (Wand- und Dach-basiert) verhindert schwebende Slabs ueber
        // kuerzeren Anbauten, die sich das Grundpolygon mit dem Hauptbau teilen (kein eigenes
        // BuildingPart) — siehe Doku.md, Abschnitt "Anbau-Kerben-Entfernung".
        List<WallSurface> cutWalls = CityGmlUtils.collectWallSurfaces(target);
        List<double[]> edgeLimitsList = new ArrayList<>();
        for (Polygon gp : groundPolygons) {
            List<Point3D> gpts = CityGmlUtils.toPoints(gp);
            if (gpts.size() >= 3) {
                edgeLimitsList.add(computeEdgeLimits(gpts, cutWalls, roofPolygons, slabsTraufeZ));
            }
        }
        // Gestoppte Polygone: einmal komplett gestoppt (kein aktiver Rand-Rest) bleibt gestoppt
        Set<Integer> stoppedPolygons = new HashSet<>();

        // Map: polyIdx → Slab-gml:id des vorherigen Ceiling (fuer XLink im naechsten Floor)
        // Initialisierung: BA-Ceiling-IDs falls Keller vorhanden
        Map<Integer, String> previousCeilingSlabIds = new HashMap<>(baCeilingSlabIds);

        for (StoreyInfo storey : slabStoreys) {
            // Slab-IDs die in DIESEM Geschoss als Ceiling erzeugt werden
            Map<Integer, String> currentCeilingSlabIds = new HashMap<>();

            int polyIdx = 0;
            for (Polygon groundPoly : groundPolygons) {
                List<Point3D> groundPoints = CityGmlUtils.toPoints(groundPoly);
                if (groundPoints.size() < 3) continue;
                polyIdx++;

                // Per-Kante Kerben-Entfernung: kein Slab (mehr) ueber Anbau-Anteilen, deren
                // eigene Wand-/Dachhoehe unter diesem Geschossboden liegt, auch wenn andere
                // Kanten desselben (geteilten) Grundpolygons noch aktiv sind. Boden und Decke
                // EINES Geschosses liegen auf unterschiedlicher Z-Hoehe und werden daher separat
                // ausgewertet — sonst wuerde die (niedrigere) Boden-Kontur faelschlich auch fuer
                // die (hoehere) Decke verwendet.
                if (stoppedPolygons.contains(polyIdx)) continue;
                double[] edgeLimits = polyIdx <= edgeLimitsList.size()
                        ? edgeLimitsList.get(polyIdx - 1) : null;

                List<Point3D> activeAtFloor = groundPoints;
                if (edgeLimits != null) {
                    activeAtFloor = computeActiveSubPolygon(groundPoints, edgeLimits,
                            storey.floorZ, CUT_TOLERANCE);
                    if (activeAtFloor == null) {
                        stoppedPolygons.add(polyIdx);
                        log.debug("  PerEdgeTopZ {}: Poly {} (idx={}) floorZ={} → komplett gestoppt",
                                targetId, storey.geschoss, polyIdx,
                                CityGmlUtils.formatNum(storey.floorZ));
                        continue;
                    }
                }

                double areaFloor = CityGmlUtils.calculatePolygonArea2D(activeAtFloor);

                // --- FloorSurface ---
                FloorSurface floor = new FloorSurface();
                String floorFaceId = targetId + "_" + storey.geschoss + "_Floor_" + polyIdx;
                floor.setId("Face_" + floorFaceId);
                CityGmlUtils.setGmlName(floor, "LOD3_Floor");

                // XLink-Referenz: Wenn ein vorheriges Ceiling-Slab existiert → referenzieren
                String prevSlabId = previousCeilingSlabIds.get(polyIdx);
                if (prevSlabId != null) {
                    // XLink: Referenziert das Ceiling-Polygon des vorherigen Geschosses
                    floor.setLod3MultiSurface(
                            CityGmlUtils.createXLinkMultiSurfaceProperty(prevSlabId));
                } else {
                    // Inline: Kein vorheriges Ceiling → eigene Geometrie
                    List<Point3D> floorPoints = CityGmlUtils.projectToZ(activeAtFloor, storey.floorZ);
                    Polygon floorPoly = CityGmlUtils.createPolygon(floorPoints);
                    floor.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(floorPoly));
                }

                CityGmlUtils.addHorizontalSurfaceAttributes(floor, floorFaceId,
                        storey.floorZ, hDgm, areaFloor, storey.geschoss);
                CityGmlUtils.addStringAttribute(floor, "STRUKTUR", "Geschossboden");

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(floor));
                floorsAdded++;

                // --- CeilingSurface --- (Flachdach/Slab-Begrenzung: RoofSurface bildet bereits die Decke)
                if (storey.isTopStorey && (isFlachdach || slabsAreLimited)) {
                    continue;
                }

                // Mischdach: Ceiling wird separat aus geneigten Dachflaechen erzeugt (siehe unten).
                if (storey.isTopStorey && isMixedRoof && !slabsAreLimited) {
                    continue;
                }

                // Bei Schraegdach (Satteldach etc.): Giebel in oberstes Geschoss mergen
                // → keine Decke bei Traufe, es sei denn das Geschoss darunter ist ein
                //   vollstaendiges Stockwerk (Hoehe >= erwartet). Dann bleibt die Decke
                //   und der Giebel bildet ein eigenes Stockwerk.
                if (storey.isTopStorey && !isFlachdach && !isMixedRoof) {
                    double topStoreyHeight = storey.ceilingZ - storey.floorZ;
                    double expectedHeight = storey.geschoss.equals("GF") ? gfHeight : ufHeight;
                    if (topStoreyHeight < expectedHeight - CUT_TOLERANCE) {
                        log.debug("  Giebel-Merge: {} hat {}m < erwartet {}m → keine Decke bei Traufe",
                                storey.geschoss, CityGmlUtils.formatNum(topStoreyHeight),
                                CityGmlUtils.formatNum(expectedHeight));
                        continue;
                    }
                    log.debug("  Giebel-Stockwerk: {} hat {}m >= erwartet {}m → Decke bei Traufe bleibt",
                            storey.geschoss, CityGmlUtils.formatNum(topStoreyHeight),
                            CityGmlUtils.formatNum(expectedHeight));
                }

                // Decken-Kontur separat bei storey.ceilingZ auswerten (kann staerker
                // beschnitten sein als der Boden desselben Geschosses, siehe oben).
                List<Point3D> activeAtCeiling = groundPoints;
                if (edgeLimits != null) {
                    activeAtCeiling = computeActiveSubPolygon(groundPoints, edgeLimits,
                            storey.ceilingZ, CUT_TOLERANCE);
                    if (activeAtCeiling == null) {
                        stoppedPolygons.add(polyIdx);
                        log.debug("  PerEdgeTopZ {}: Poly {} (idx={}) ceilingZ={} → Decke gestoppt",
                                targetId, storey.geschoss, polyIdx,
                                CityGmlUtils.formatNum(storey.ceilingZ));
                        continue;
                    }
                }
                double areaCeiling = CityGmlUtils.calculatePolygonArea2D(activeAtCeiling);

                // Ceiling-Polygon inline erzeugen mit gml:id fuer XLink-Referenz
                List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(activeAtCeiling, storey.ceilingZ);
                Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

                // gml:id auf dem Polygon setzen (fuer XLink-Referenz vom naechsten Floor)
                String slabGmlId = "Slab_" + targetId + "_" + storey.geschoss + "_" + polyIdx;
                ceilingPoly.setId(slabGmlId);

                CeilingSurface ceiling = new CeilingSurface();
                String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_" + polyIdx;
                ceiling.setId("Face_" + ceilingFaceId);
                CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                ceiling.setLod3MultiSurface(
                        CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                        storey.ceilingZ, hDgm, areaCeiling, storey.geschoss);
                CityGmlUtils.addStringAttribute(ceiling, "STRUKTUR", "Geschossdecke");

                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
                ceilingsAdded++;

                // Slab-ID merken fuer den Floor des naechsten Geschosses
                currentCeilingSlabIds.put(polyIdx, slabGmlId);
            }

            // --- Mischdach: CeilingSurface aus den auf ceilingZ projizierten geneigten Dachflaechen ---
            if (storey.isTopStorey && isMixedRoof && !slabsAreLimited) {
                int roofIdx = 0;
                for (Polygon slopedPoly : slopedRoofPolygons) {
                    roofIdx++;
                    List<Point3D> roofPts = CityGmlUtils.toPoints(slopedPoly);
                    if (roofPts.size() < 3) continue;

                    List<Point3D> ceilingPoints = CityGmlUtils.projectToZ(roofPts, storey.ceilingZ);
                    double roofArea = CityGmlUtils.calculatePolygonArea2D(ceilingPoints);

                    Polygon ceilingPoly = CityGmlUtils.createPolygon(ceilingPoints);

                    CeilingSurface ceiling = new CeilingSurface();
                    String ceilingFaceId = targetId + "_" + storey.geschoss + "_Ceiling_R" + roofIdx;
                    ceiling.setId("Face_" + ceilingFaceId);
                    CityGmlUtils.setGmlName(ceiling, "LOD3_Ceiling");
                    ceiling.setLod3MultiSurface(
                            CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(ceilingPoly));

                    CityGmlUtils.addHorizontalSurfaceAttributes(ceiling, ceilingFaceId,
                            storey.ceilingZ, hDgm, roofArea, storey.geschoss);
                    CityGmlUtils.addStringAttribute(ceiling, "STRUKTUR", "Geschossdecke");

                    target.getBoundaries().add(new AbstractSpaceBoundaryProperty(ceiling));
                    ceilingsAdded++;
                }
            }

            // Ceiling-Slab-IDs dieses Geschosses werden zum "previous" fuer das naechste
            previousCeilingSlabIds = currentCeilingSlabIds;
        }

        // --- Metadaten aktualisieren ---
        CityGmlUtils.setStringAttribute(target, "storeysGenerated",
                String.valueOf(storeys.size()));

        // storeysAboveGround aktualisieren (dynamisch berechnet)
        target.setStoreysAboveGround(storeys.size());

        stats.storeysCreated += storeys.size();
        stats.wallsCut += wallsCut;
        stats.wallSegmentsCreated += segmentsCreated;
        stats.floorsCreated += floorsAdded;
        stats.ceilingsCreated += ceilingsAdded;

        log.debug("  => sst={}: {} Geschosse, {} Wand-Segmente, {} Boeden, {} Decken",
                sst, storeys.size(), segmentsCreated, floorsAdded, ceilingsAdded);
    }

    // ==================== Geschossberechnung ====================

    /** Berechnet die Geschossgrenzen dynamisch von GF aufwaerts bis zur Traufe (siehe Doku.md Schritt 3). */
    private List<StoreyInfo> calculateStoreys(double egFloorZ, double gfHeight,
            double ufHeight, double traufeZ, String sst, boolean isFlachdach) {

        List<StoreyInfo> storeys = new ArrayList<>();
        traufeZ = CityGmlUtils.roundZ(traufeZ);

        // --- GF ---
        double gfCeilingZ = CityGmlUtils.roundZ(egFloorZ + gfHeight);

        // Sicherheitscheck: GF-Ceiling darf nicht ueber Traufe liegen
        if (gfCeilingZ >= traufeZ - CUT_TOLERANCE) {
            log.debug("GF-Ceiling ({}) >= Traufe ({}) fuer sst={}, nur GF erzeugt",
                    CityGmlUtils.formatNum(gfCeilingZ), CityGmlUtils.formatNum(traufeZ), sst);
            storeys.add(new StoreyInfo("GF", egFloorZ, traufeZ, true));
            return storeys;
        }

        // Kein UF moeglich (ufHeight fehlt oder 0)?
        if (ufHeight <= 0) {
            storeys.add(new StoreyInfo("GF", egFloorZ, traufeZ, true));
            return storeys;
        }

        storeys.add(new StoreyInfo("GF", egFloorZ, gfCeilingZ, false));

        // --- Upper Floors (dynamisch berechnet) ---
        double currentFloorZ = gfCeilingZ;
        int ufNum = 0;

        while (true) {
            double remainingHeight = traufeZ - currentFloorZ;

            // Fitzelchen-Pruefung: Resthoehe zu gering fuer ein neues Geschoss?
            if (remainingHeight < MIN_STOREY_HEIGHT) {
                if (!storeys.isEmpty()) {
                    StoreyInfo prev = storeys.get(storeys.size() - 1);
                    double mergedHeight = traufeZ - prev.floorZ;

                    if (isFlachdach && mergedHeight > MAX_STOREY_HEIGHT_FLACHDACH) {
                        // Flachdach-Sonderregel: nicht mergen, Fitzelchen als eigenes Geschoss.
                        ufNum++;
                        String geschoss = "UF_" + ufNum;
                        storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                        log.debug("Flachdach: Fitzelchen-Geschoss {} mit {}m erzeugt (Merge haette {}m > {}m ergeben)",
                                geschoss, CityGmlUtils.formatNum(remainingHeight),
                                CityGmlUtils.formatNum(mergedHeight),
                                CityGmlUtils.formatNum(MAX_STOREY_HEIGHT_FLACHDACH));
                    } else {
                        // Standard: vorheriges Geschoss bis Traufe erweitern
                        storeys.set(storeys.size() - 1,
                                new StoreyInfo(prev.geschoss, prev.floorZ, traufeZ, true));
                        log.debug("UF uebersprungen: Resthoehe {}m < {}m, {} bis Traufe erweitert",
                                CityGmlUtils.formatNum(remainingHeight),
                                CityGmlUtils.formatNum(MIN_STOREY_HEIGHT), prev.geschoss);
                    }
                }
                break;
            }

            ufNum++;
            String geschoss = "UF_" + ufNum;
            double ceilingZ = CityGmlUtils.roundZ(currentFloorZ + ufHeight);

            // Ceiling erreicht oder uebersteigt Traufe → letztes UF
            if (ceilingZ >= traufeZ - CUT_TOLERANCE) {
                storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                break;
            }

            // Wuerde nach diesem UF ein Fitzelchen entstehen?
            double remainingAfter = traufeZ - ceilingZ;
            if (remainingAfter < MIN_STOREY_HEIGHT) {
                double extendedHeight = traufeZ - currentFloorZ;
                if (isFlachdach && extendedHeight > MAX_STOREY_HEIGHT_FLACHDACH) {
                    // Flachdach: Normal anlegen, naechste Iteration erzeugt Fitzelchen
                    storeys.add(new StoreyInfo(geschoss, currentFloorZ, ceilingZ, false));
                    currentFloorZ = ceilingZ;
                } else {
                    // Dieses UF bis Traufe erweitern
                    storeys.add(new StoreyInfo(geschoss, currentFloorZ, traufeZ, true));
                    break;
                }
            } else {
                // Normales UF
                storeys.add(new StoreyInfo(geschoss, currentFloorZ, ceilingZ, false));
                currentFloorZ = ceilingZ;
            }
        }

        // Warnung bei ungewoehnlich hohem letztem Geschoss
        if (storeys.size() > 1 && ufHeight > 0) {
            StoreyInfo last = storeys.get(storeys.size() - 1);
            double lastHeight = last.ceilingZ - last.floorZ;
            if (lastHeight > 1.5 * ufHeight && !last.geschoss.equals("GF")) {
                log.debug("Letztes Geschoss {} = {}m (erwartet ~{}m) fuer sst={} (Traufe-Auffuellung)",
                        last.geschoss, CityGmlUtils.formatNum(lastHeight),
                        CityGmlUtils.formatNum(ufHeight), sst);
            }
        }

        return storeys;
    }

    // ==================== Wand-Schnitt ====================

    /** Trimmt eine Wand, die unter egFloorZ haengt, auf einen Einzelschnitt (siehe Doku.md Schritt 3). */
    private Polygon trimWallBelowEgFloor(Polygon wallPoly, double egFloorZ, String wallId) {
        Polygon[] cut = CityGmlUtils.cutWallPolygonAtZ(wallPoly, egFloorZ, CUT_TOLERANCE);
        if (cut == null || cut[1] == null) {
            log.warn("Wand {} reicht unter egFloorZ ({}), Einzelschnitt dort aber nicht moeglich "
                    + "— Kontur bleibt unveraendert (moegliche Ueberlappung mit Kellerwand).",
                    wallId, CityGmlUtils.formatNum(egFloorZ));
            return wallPoly;
        }
        List<Point3D> upperPts = CityGmlUtils.toPoints(cut[1]);
        if (CityGmlUtils.ringSelfIntersects(upperPts)) {
            log.warn("Wand {} reicht unter egFloorZ ({}), Einzelschnitt dort erzeugt aber ein "
                    + "selbstschneidendes Stueck — Kontur bleibt unveraendert.",
                    wallId, CityGmlUtils.formatNum(egFloorZ));
            return wallPoly;
        }
        return cut[1];
    }

    /** Schneidet ein Wand-Polygon an mehreren Z-Hoehen in Geschoss-Segmente (siehe Doku.md Schritt 3). */
    private List<Polygon> cutWallAtMultipleZ(Polygon wallPoly, List<Double> cutZValues) {
        if (cutZValues.isEmpty()) return null;

        List<Polygon> multi = cutWallMultiPiece(wallPoly, cutZValues);
        if (multi != null && isFaithfulSplit(wallPoly, multi)) {
            return multi;
        }
        return cutWallSinglePieceGuarded(wallPoly, cutZValues);
    }

    /** True, wenn die Split-Stuecke die Original-Wand flaechentreu kacheln und keines selbstschneidend ist. */
    private boolean isFaithfulSplit(Polygon wallPoly, List<Polygon> segments) {
        double origA = CityGmlUtils.calculateWallArea(CityGmlUtils.toPoints(wallPoly));
        if (origA <= 0) return false;
        double sumA = 0;
        for (Polygon seg : segments) {
            if (CityGmlUtils.ringSelfIntersects(CityGmlUtils.toPoints(seg))) return false;
            sumA += CityGmlUtils.calculateWallArea(CityGmlUtils.toPoints(seg));
        }
        return Math.abs(sumA - origA) <= Math.max(0.05, 0.01 * origA);
    }

    /** Konservativer Fallback: iterativer Einzelstueck-Schnitt, faltende Schnitte werden uebersprungen. */
    private List<Polygon> cutWallSinglePieceGuarded(Polygon wallPoly, List<Double> cutZValues) {
        List<Polygon> segments = new ArrayList<>();
        Polygon remaining = wallPoly;
        for (double cutZ : cutZValues) {
            Polygon[] result = CityGmlUtils.cutWallPolygonAtZ(remaining, cutZ, CUT_TOLERANCE);
            if (result == null) break;
            boolean folds = CityGmlUtils.ringSelfIntersects(CityGmlUtils.toPoints(result[0]))
                    || CityGmlUtils.ringSelfIntersects(CityGmlUtils.toPoints(result[1]));
            if (folds) continue;
            segments.add(result[0]);
            remaining = result[1];
        }
        segments.add(remaining);
        return segments;
    }

    /** Schneidet ein Wand-Polygon an mehreren Z-Hoehen in echte Einzelstuecke (siehe Doku.md Schritt 3). */
    private List<Polygon> cutWallMultiPiece(Polygon wallPoly, List<Double> cutZValues) {
        if (cutZValues.isEmpty()) return null;
        final double eps = 0.001;

        List<Double> cuts = new ArrayList<>(cutZValues);
        java.util.Collections.sort(cuts);

        // Wand als ein offenes Stueck; iterativ von unten nach oben an jeder Hoehe trennen.
        // current = noch weiter zu schneidende (obere) Stuecke, done = fertige Baender.
        List<List<Point3D>> current = new ArrayList<>();
        current.add(CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(wallPoly)));
        List<List<Point3D>> done = new ArrayList<>();

        for (double cutZ : cuts) {
            List<List<Point3D>> next = new ArrayList<>();
            for (List<Point3D> piece : current) {
                double pMin = Double.POSITIVE_INFINITY, pMax = Double.NEGATIVE_INFINITY;
                for (Point3D p : piece) { if (p.z < pMin) pMin = p.z; if (p.z > pMax) pMax = p.z; }
                if (cutZ <= pMin + eps) {            // Schnitt unter dem Stueck → ganz nach oben tragen
                    next.add(piece);
                } else if (cutZ >= pMax - eps) {     // Schnitt ueber dem Stueck → Band ist fertig
                    done.add(piece);
                } else {                             // Schnitt kreuzt → in Einzelstuecke trennen
                    CityGmlUtils.WallCut wc = CityGmlUtils.splitWallByZ(piece, cutZ, eps);
                    done.addAll(wc.lower());
                    next.addAll(wc.upper());
                }
            }
            current = next;
        }
        done.addAll(current); // oberste Baender

        List<Polygon> segments = new ArrayList<>();
        for (List<Point3D> piece : done) {
            List<Point3D> dd = CityGmlUtils.dedupConsecutive(piece, CityGmlUtils.POINT_MERGE_TOL);
            if (dd.size() >= 3) segments.add(CityGmlUtils.createPolygon(dd));
        }
        return segments.isEmpty() ? null : segments;
    }

    /** Findet das Geschoss fuer eine gegebene Z-Hoehe (exakt, sonst naechstliegend). */
    private StoreyInfo findStoreyForZ(List<StoreyInfo> storeys, double z) {
        // Exakte Zuordnung: Z liegt innerhalb der Geschossgrenzen
        for (StoreyInfo s : storeys) {
            if (z >= s.floorZ - CUT_TOLERANCE && z <= s.ceilingZ + CUT_TOLERANCE) {
                return s;
            }
        }

        // Fallback: naechstliegendes Geschoss (nach Mitte)
        return storeys.stream()
                .min(java.util.Comparator.comparingDouble(
                        s -> Math.abs(z - (s.floorZ + s.ceilingZ) / 2.0)))
                .orElse(null);
    }

    /** Fuegt Geschoss-Attribute zu einer bestehenden, nicht geschnittenen Wand hinzu. */
    private void assignGeschossToExistingWall(WallSurface wall, StoreyInfo storey) {
        CityGmlUtils.addStringAttribute(wall, "Geschoss", storey.geschoss);
    }

    // ==================== Innere Klassen ====================

    /** Beschreibt ein Geschoss mit seinen Z-Grenzen. */
    private record StoreyInfo(
            String geschoss,    // Tag: GF, UF_1, UF_2, ... (BA wird vom BasementGenerator erzeugt)
            double floorZ,      // Unterkante (absolut, m ue. NHN)
            double ceilingZ,    // Oberkante (absolut)
            boolean isTopStorey // true = oberstes Geschoss (reicht bis Traufe)
    ) {}

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int storeysCreated = 0;
        public int wallsCut = 0;
        public int wallSegmentsCreated = 0;
        public int floorsCreated = 0;
        public int ceilingsCreated = 0;
    }

    // ==================== Per-Polygon Hoehenberechnung ====================

    /**
     * Pro Kante des Grundpolygons die lokale Hoehengrenze (Wand-Basiskante + Dachflaeche an der
     * Kantenmitte), statt eines einzigen Maximums/Schwerpunkt-Werts ueber das ganze Polygon.
     * Verhindert schwebende Slabs ueber Anbauten, die sich ihr Grundpolygon (kein eigenes
     * BuildingPart) mit einem hoeheren Hauptbau teilen — siehe Doku.md, Abschnitt
     * "Anbau-Kerben-Entfernung". Match-Logik pro Wand/Kante identisch zur vormaligen
     * computePolygonTopZ/computePolygonRoofZ, nur nicht mehr zu einem Gesamtwert aggregiert.
     */
    private double[] computeEdgeLimits(List<Point3D> groundPts, List<WallSurface> walls,
            List<Polygon> roofPolygons, double defaultZ) {
        List<Point3D> edgePts = new ArrayList<>(groundPts);
        Point3D gFirst = edgePts.get(0);
        Point3D gLast  = edgePts.get(edgePts.size() - 1);
        if (Math.hypot(gFirst.x - gLast.x, gFirst.y - gLast.y) < 0.01) {
            edgePts.remove(edgePts.size() - 1);
        }
        int n = edgePts.size();

        // --- Wand-Grenze pro Kante ---
        double[] wallLimits = new double[n];
        boolean[] wallMatched = new boolean[n];

        for (WallSurface wall : walls) {
            Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
            if (wallPoly == null) continue;
            List<Point3D> wallPts = CityGmlUtils.toPoints(wallPoly);
            if (wallPts.size() < 4) continue;

            double wallMinZ = Double.MAX_VALUE;
            double wallMaxZ = -Double.MAX_VALUE;
            for (Point3D p : wallPts) {
                if (p.z < wallMinZ) wallMinZ = p.z;
                if (p.z > wallMaxZ) wallMaxZ = p.z;
            }

            List<Point3D> basePts = new ArrayList<>();
            for (Point3D p : wallPts) {
                if (Math.abs(p.z - wallMinZ) < 0.30) basePts.add(p);
            }
            if (basePts.size() < 2) continue;

            for (int i = 0; i < n; i++) {
                Point3D ga = edgePts.get(i);
                Point3D gb = edgePts.get((i + 1) % n);
                boolean matched = false;
                for (int j = 0; j < basePts.size() && !matched; j++) {
                    for (int k = j + 1; k < basePts.size() && !matched; k++) {
                        Point3D wa = basePts.get(j);
                        Point3D wb = basePts.get(k);
                        double d1 = Math.hypot(wa.x - ga.x, wa.y - ga.y);
                        double d2 = Math.hypot(wb.x - gb.x, wb.y - gb.y);
                        double d3 = Math.hypot(wa.x - gb.x, wa.y - gb.y);
                        double d4 = Math.hypot(wb.x - ga.x, wb.y - ga.y);
                        if ((d1 < XY_EDGE_TOLERANCE && d2 < XY_EDGE_TOLERANCE)
                                || (d3 < XY_EDGE_TOLERANCE && d4 < XY_EDGE_TOLERANCE)) {
                            matched = true;
                        }
                    }
                }
                if (matched) {
                    if (!wallMatched[i] || wallMaxZ > wallLimits[i]) wallLimits[i] = wallMaxZ;
                    wallMatched[i] = true;
                }
            }
        }

        // --- Dach-Grenze pro Kante (an der Kantenmitte statt am Gesamt-Schwerpunkt) ---
        double[] limits = new double[n];
        for (int i = 0; i < n; i++) {
            double wallLimit = wallMatched[i] ? wallLimits[i] : defaultZ;

            Point3D ga = edgePts.get(i);
            Point3D gb = edgePts.get((i + 1) % n);
            double midX = (ga.x + gb.x) / 2.0;
            double midY = (ga.y + gb.y) / 2.0;
            double roofLimit = Double.MAX_VALUE;
            for (Polygon rp : roofPolygons) {
                List<Point3D> rpts = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(rp));
                if (rpts.size() < 3) continue;
                double[][] poly2d = new double[rpts.size()][2];
                double rMinZ = Double.MAX_VALUE;
                for (int k = 0; k < rpts.size(); k++) {
                    poly2d[k][0] = rpts.get(k).x;
                    poly2d[k][1] = rpts.get(k).y;
                    rMinZ = Math.min(rMinZ, rpts.get(k).z);
                }
                if (CityGmlUtils.pointInPolygon2D(midX, midY, poly2d)) {
                    roofLimit = Math.min(roofLimit, rMinZ);
                }
            }
            if (roofLimit == Double.MAX_VALUE) roofLimit = defaultZ;

            limits[i] = Math.min(wallLimit, roofLimit);
        }
        log.trace("  computeEdgeLimits: {} Kanten, limits={}", n, Arrays.toString(limits));
        return limits;
    }

    /**
     * Liefert den bei floorZ noch aktiven Teil des Rings. Zusammenhaengende Laeufe abgelaufener
     * Kanten (z.B. ein niedrigerer Anbau ohne eigenes BuildingPart) werden als Kerbe entfernt:
     * ein Vertex faellt weg, wenn BEIDE anliegenden Kanten abgelaufen sind, die beiden
     * Lauf-Grenzen (je eine anliegende Kante noch aktiv) bleiben erhalten und bilden dadurch
     * automatisch eine neue, gerade Schliesskante. Null, wenn der gesamte Ring abgelaufen ist.
     * Faltungs-Schutz (wie beim Wandschnitt, siehe Doku.md): wuerde die neue Schliesskante bei
     * einem verwinkelten Anbau den Ring self-touching machen, wird NICHT geschnitten und der
     * volle Ring unveraendert zurueckgegeben — die alte (dokumentierte) Einschraenkung bleibt in
     * diesem Sonderfall bestehen, statt einen neuen Geometriefehler einzutauschen. Laengen-Schutz
     * (Nachtrag nach Regression an einem mehrfluegeligen Gebaeude, siehe Doku.md): geschnitten
     * wird nur, wenn der LAENGSTE zusammenhaengende abgelaufene Lauf hoechstens die Haelfte der
     * Ring-Kanten ausmacht — sonst waere die Schliesskante keine kleine Kerbe mehr, sondern
     * durchquert einen Grossteil des Gebaeudes (z.B. Innenhof-Gebaeude mit mehreren
     * unterschiedlich hohen Fluegeln im selben Grundpolygon). Beliebig viele kleine Laeufe sind
     * dabei erlaubt, nur ein dominanter Lauf blockiert. Siehe Doku.md, Abschnitt
     * "Anbau-Kerben-Entfernung" / "Laengen-Schutz nach Regression".
     */
    private List<Point3D> computeActiveSubPolygon(List<Point3D> groundPts, double[] edgeLimits,
            double floorZ, double tolerance) {
        List<Point3D> pts = new ArrayList<>(groundPts);
        Point3D first = pts.get(0);
        Point3D last  = pts.get(pts.size() - 1);
        if (Math.hypot(first.x - last.x, first.y - last.y) < 0.01) {
            pts.remove(pts.size() - 1);
        }
        int n = pts.size();
        if (n != edgeLimits.length) return pts; // Unerwartete Diskrepanz: unveraendert durchreichen

        boolean[] active = new boolean[n];
        int activeCount = 0;
        for (int i = 0; i < n; i++) {
            active[i] = edgeLimits[i] > floorZ - tolerance;
            if (active[i]) activeCount++;
        }
        if (activeCount == n) return pts;   // haeufigster Fall: alles aktiv, unveraendert
        if (activeCount == 0) return null;  // komplett abgelaufen

        // Laengsten zusammenhaengenden (zirkulaeren) Lauf abgelaufener Kanten ermitteln. Eine
        // gerade Schliesskante ist nur eine vertretbare Naeherung, wenn sie einen kleinen
        // Anbau-Vorsprung abschneidet (Minderheit des Rings) — frisst ein einzelner Lauf MEHR
        // ALS DIE HAELFTE der Kanten (z.B. ein mehrfluegeliges Gebaeude, bei dem der "Rest" der
        // eigentlich groessere Gebaeudeteil ist), wuerde die Kante quer durch das Gebaeude
        // schneiden statt nur eine kleine Kerbe zu entfernen — dann unveraendert durchreichen.
        int longestRun = 0, currentRun = 0;
        for (int i = 0; i < 2 * n; i++) {
            if (!active[i % n]) {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        longestRun = Math.min(longestRun, n); // Deckelung falls Ring komplett abgelaufen waere
        if (longestRun > n / 2) {
            log.debug("  computeActiveSubPolygon: laengster abgelaufener Lauf ({} von {} Kanten) "
                    + "ist mehr als die Haelfte des Rings — ungeschnitten belassen", longestRun, n);
            return pts;
        }

        List<Point3D> result = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            boolean edgeBefore = active[(k - 1 + n) % n];
            boolean edgeAfter = active[k];
            if (!edgeBefore && !edgeAfter) continue; // Kerben-Innen-Vertex: weglassen
            result.add(pts.get(k));
        }
        if (result.size() < 3) return null;

        if (CityGmlUtils.ringSelfIntersects(result)) {
            log.debug("  computeActiveSubPolygon: Kerben-Schnitt haette Ring self-touching "
                    + "gemacht, ungeschnitten belassen");
            return pts;
        }
        return result;
    }
}
