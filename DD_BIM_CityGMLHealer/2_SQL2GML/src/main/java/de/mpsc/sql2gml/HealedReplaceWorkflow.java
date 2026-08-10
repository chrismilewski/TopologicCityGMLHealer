package de.mpsc.sql2gml;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingPart;
import org.citygml4j.core.model.building.BuildingPartProperty;
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
import org.citygml4j.xml.reader.CityGMLInputFactory;
import org.citygml4j.xml.reader.CityGMLReader;
import org.citygml4j.xml.reader.ChunkOptions;
import org.citygml4j.xml.writer.CityGMLChunkWriter;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.base.AbstractGML;
import org.xmlobjects.gml.model.feature.BoundingShape;
import org.xmlobjects.gml.model.geometry.DirectPositionList;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.AbstractSurface;
import org.xmlobjects.gml.model.geometry.primitives.Shell;
import org.xmlobjects.gml.model.geometry.primitives.ShellProperty;
import org.xmlobjects.gml.model.geometry.primitives.Solid;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.Triangle;
import org.xmlobjects.gml.model.geometry.primitives.TriangleArrayProperty;
import org.xmlobjects.gml.model.geometry.primitives.TriangulatedSurface;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Haupt-Workflow fuer das neue Healer-Schema (SurfaceGeometries+PosLists). Ersetzt komplette
 * Buildings/BuildingParts, sofern DB-seitig vollstaendig valide (siehe Doku.md, Abschnitt
 * "HealedReplaceWorkflow").
 *
 * Usage:
 *   Single file: <input.gml> <database.db> [<output.gml>]
 *   Batch mode:  <inputFolder> <database.db> [<outputFolder>]
 *   Auto mode:   <database.db> <inputFolder> <outputFolder> --auto
 */
public class HealedReplaceWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(HealedReplaceWorkflow.class);

    // SurfaceTypeId → citygml4j-Klasse (0=None wird über den default-Zweig behandelt)
    private static final int TYPE_GROUND = 1;
    private static final int TYPE_WALL   = 2;
    private static final int TYPE_ROOF   = 3;

    private enum RunMode {
        AUTO_BATCH, BATCH_FOLDER, SINGLE_FILE;

        static RunMode detect(String[] args) {
            if (args.length >= 4 && "--auto".equals(args[3])) return AUTO_BATCH;
            if (args.length >= 2 && Files.isDirectory(Paths.get(args[0]))) return BATCH_FOLDER;
            if (args.length >= 2) return SINGLE_FILE;
            return null;
        }
    }

    public static void main(String[] args) {
        try {
            RunMode mode = RunMode.detect(args);
            if (mode == null) {
                logger.error("No arguments provided.");
                logger.error("Usage:");
                logger.error("  Single file: <input.gml> <database.db> [<output.gml>]");
                logger.error("  Batch mode:  <inputFolder> <database.db> [<outputFolder>]");
                logger.error("  Auto mode:   <database.db> <inputFolder> <outputFolder> --auto");
                System.exit(1);
                return;
            }
            switch (mode) {
                case AUTO_BATCH -> runBatchFromDatabase(
                        args[0], Paths.get(args[1]), Paths.get(args[2]));
                case BATCH_FOLDER -> {
                    Path outputFolder = args.length >= 3 ? Paths.get(args[2]) : Paths.get(args[0]);
                    runBatchMode(Paths.get(args[0]), args[1], outputFolder);
                }
                case SINGLE_FILE -> {
                    Path inputPath = Paths.get(args[0]);
                    Path outputPath = args.length >= 3
                            ? Paths.get(args[2])
                            : inputPath.getParent().resolve(generateOutputFilename(inputPath.getFileName().toString()));
                    runSingleFile(inputPath, args[1], outputPath);
                }
            }
        } catch (Exception e) {
            logger.error("Error during workflow: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Run-Modes
    // ─────────────────────────────────────────────────────────────────────────

    private static void runBatchFromDatabase(String databasePath, Path inputFolder, Path outputFolder)
            throws Exception {
        logger.info("=== HealedReplaceWorkflow Batch (Auto) ===");
        Files.createDirectories(outputFolder);
        DbReader dbReader = new DbReader(databasePath);
        Map<Long, String> cityGmlFiles = dbReader.getCityGmlFiles();

        // DB + Context EINMAL laden statt pro Datei
        CityGMLContext context = CityGMLContext.newInstance();
        Map<String, de.mpsc.sql2gml.model.Building> buildingIndex =
                indexBuildings(dbReader.readAllBuildings());

        int processed = 0, skipped = 0, errors = 0;
        for (Map.Entry<Long, String> entry : cityGmlFiles.entrySet()) {
            long fileId = entry.getKey();
            String filename = entry.getValue();
            if (!dbReader.hasModificationsForFile(fileId)) {
                logger.info("Skipping {} (no modifications)", filename);
                skipped++;
                continue;
            }
            Path inputFile = inputFolder.resolve(filename);
            if (!Files.exists(inputFile)) {
                logger.warn("Input file not found: {}", inputFile);
                errors++;
                continue;
            }
            Path outputFile = outputFolder.resolve(generateOutputFilename(filename));
            try {
                processFile(inputFile, outputFile, buildingIndex, context);
                processed++;
            } catch (Exception e) {
                logger.error("Error processing {}: {}", filename, e.getMessage());
                errors++;
            }
        }
        logger.info("Done: {} processed, {} skipped, {} errors", processed, skipped, errors);
    }

    private static void runBatchMode(Path inputFolder, String databasePath, Path outputFolder)
            throws Exception {
        logger.info("=== HealedReplaceWorkflow Batch ===");
        Files.createDirectories(outputFolder);
        File[] gmlFiles = inputFolder.toFile().listFiles(
                f -> f.isFile() && f.getName().toLowerCase().endsWith(".gml"));
        if (gmlFiles == null || gmlFiles.length == 0) {
            logger.warn("No GML files found in {}", inputFolder);
            return;
        }

        CityGMLContext context = CityGMLContext.newInstance();
        Map<String, de.mpsc.sql2gml.model.Building> buildingIndex = loadBuildingIndex(databasePath);

        for (File gmlFile : gmlFiles) {
            Path outputFile = outputFolder.resolve(generateOutputFilename(gmlFile.getName()));
            logger.info("Processing: {}", gmlFile.getName());
            try {
                processFile(gmlFile.toPath(), outputFile, buildingIndex, context);
            } catch (Exception e) {
                logger.error("Error processing {}: {}", gmlFile.getName(), e.getMessage());
            }
        }
    }

    private static String generateOutputFilename(String originalName) {
        if (originalName.toLowerCase().endsWith(".gml")) {
            return originalName.substring(0, originalName.length() - 4) + "_new.gml";
        }
        return originalName + "_new.gml";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kernlogik: Single File
    // ─────────────────────────────────────────────────────────────────────────

    private static void runSingleFile(Path inputGmlPath, String databasePath, Path outputPath)
            throws Exception {
        logger.info("--- Step 1: Reading Database ---");
        logger.info("Database:   {}", databasePath);
        CityGMLContext context = CityGMLContext.newInstance();
        Map<String, de.mpsc.sql2gml.model.Building> buildingIndex = loadBuildingIndex(databasePath);
        processFile(inputGmlPath, outputPath, buildingIndex, context);
    }

    private static Map<String, de.mpsc.sql2gml.model.Building> loadBuildingIndex(String databasePath)
            throws Exception {
        DbReader dbReader = new DbReader(databasePath);
        return indexBuildings(dbReader.readAllBuildings());
    }

    private static Map<String, de.mpsc.sql2gml.model.Building> indexBuildings(
            List<de.mpsc.sql2gml.model.Building> dbBuildings) {
        logger.info("Loaded {} buildings from database", dbBuildings.size());
        Map<String, de.mpsc.sql2gml.model.Building> buildingIndex = new HashMap<>();
        for (de.mpsc.sql2gml.model.Building b : dbBuildings) {
            if (b.getBuildingIdGml() != null) {
                buildingIndex.put(b.getBuildingIdGml(), b);
            }
        }
        logger.info("Building index: {} entries", buildingIndex.size());
        return buildingIndex;
    }

    private static void processFile(
            Path inputGmlPath,
            Path outputPath,
            Map<String, de.mpsc.sql2gml.model.Building> buildingIndex,
            CityGMLContext context)
            throws Exception {
        logger.info("=== HealedReplaceWorkflow: DB → CityGML ===");
        logger.info("Input GML:  {}", inputGmlPath);
        logger.info("Output GML: {}", outputPath);

        logger.info("--- Step 2: Processing CityGML ---");
        CityGMLVersion version = CityGMLVersion.v1_0;

        // Originales boundedBy lesen
        BoundingShape originalBoundedBy = null;
        CityGMLInputFactory inNoChunk = context.createCityGMLInputFactory();
        try (CityGMLReader metaReader = inNoChunk.createCityGMLReader(inputGmlPath.toFile())) {
            if (metaReader.hasNext()) {
                AbstractFeature cityModel = metaReader.next();
                originalBoundedBy = cityModel.getBoundedBy();
            }
        }

        CityGMLInputFactory in = context.createCityGMLInputFactory()
                .withChunking(ChunkOptions.defaults());
        CityGMLOutputFactory out = context.createCityGMLOutputFactory(version);

        Stats stats = new Stats();

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Paths.get("."));

        try (CityGMLReader reader = in.createCityGMLReader(inputGmlPath.toFile());
             CityGMLChunkWriter writer = out.createCityGMLChunkWriter(
                     outputPath, StandardCharsets.UTF_8.name())) {

            configureWriter(writer, originalBoundedBy);

            while (reader.hasNext()) {
                AbstractFeature feature = reader.next();
                stats.featuresRead++;

                if (feature instanceof Building gmlBuilding) {
                    String buildingId = gmlBuilding.getId();
                    de.mpsc.sql2gml.model.Building dbBuilding = buildingIndex.get(buildingId);

                    if (dbBuilding == null) {
                        // ── Building nicht in DB: unverändert übernehmen ──
                        stats.buildingsUnchanged++;
                        logger.debug("Unchanged building: {}", buildingId);
                    } else if (!isFullyValid(dbBuilding)) {
                        // Valid-Gate: Original-Geometrie vollstaendig erhalten.
                        stats.buildingsSkippedInvalid++;
                        logger.warn("Building {} is not fully valid in DB — original geometry preserved",
                                buildingId);
                    } else {
                        replaceBuilding(gmlBuilding, dbBuilding, stats);
                        stats.buildingsReplaced++;
                    }
                }

                writer.writeMember(feature);
            }
        }

        // Header fixieren (Namespaces, schemaLocation)
        fixHeader(inputGmlPath, outputPath);

        logger.info("--- Results ---");
        logger.info("Features read:            {}", stats.featuresRead);
        logger.info("Buildings replaced:       {}", stats.buildingsReplaced);
        logger.info("Buildings unchanged:      {}", stats.buildingsUnchanged);
        logger.info("Buildings kept (invalid): {}", stats.buildingsSkippedInvalid);
        logger.info("New BuildingParts:        {}", stats.newPartsCreated);
        logger.info("Superseded parts removed: {}", stats.partsRemoved);
        logger.info("Surfaces written:         {} ({} thereof TIN)", stats.surfacesWritten, stats.tinSurfacesWritten);

        // Triangulierungs-Aufschlüsselung: TIN ist eine Notlösung des Healers.
        // Besonders bei Wänden ist sie teuer, weil sie die LoD3-Ableitung
        // (Einrechnen von Fenster-/Türöffnungen) praktisch unmöglich macht.
        if (geometryMode == GeometryMode.ALWAYS_POLYGON) {
            logger.info("Geometry mode:            ALWAYS_POLYGON — {} TIN geometries written "
                    + "as {} single gml:Polygon", stats.tinSurfacesWritten, stats.polygonsFromTin);
        }

        logger.info("--- Triangulation (TIN) by surface type ---");
        stats.byType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int total = e.getValue()[0], tin = e.getValue()[1];
                    logger.info(String.format(Locale.ROOT, "  %-8s %d of %d triangulated (%.2f%%)",
                            surfaceTypeName(e.getKey()), tin, total,
                            total == 0 ? 0.0 : 100.0 * tin / total));
                });
        if (stats.buildingsWithTinWall.isEmpty()) {
            logger.info("  No triangulated walls — LoD3 derivation unaffected");
        } else {
            logger.warn("  {} building(s) contain triangulated WALLS — these will be hard to "
                    + "convert to LoD3 (no clean window insertion possible)",
                    stats.buildingsWithTinWall.size());
        }
        logger.info("Output:               {}", outputPath);
        logger.info("✓ SUCCESS");
    }

    private static class Stats {
        int featuresRead, buildingsReplaced, buildingsUnchanged, buildingsSkippedInvalid;
        int newPartsCreated, partsRemoved, surfacesWritten, tinSurfacesWritten;
        /** Nur im Modus ALWAYS_POLYGON: Anzahl Polygone, die aus TINs entstanden sind. */
        int polygonsFromTin;

        /** Flächen je SurfaceTypeId (1=Ground, 2=Wall, 3=Roof), gesamt und davon TIN. */
        final Map<Integer, int[]> byType = new LinkedHashMap<>();
        /** Buildings mit mindestens einer triangulierten WAND (relevant für LoD3). */
        final Set<String> buildingsWithTinWall = new LinkedHashSet<>();

        void count(int surfaceTypeId, boolean tin) {
            int[] c = byType.computeIfAbsent(surfaceTypeId, k -> new int[2]);
            c[0]++;
            if (tin) c[1]++;
        }
    }

    /** Wie triangulierte DB-Geometrien in die Ausgabe geschrieben werden (siehe Doku.md). */
    public enum GeometryMode {
        /** GeometryTypeId=1 → gml:TriangulatedSurface (Standard, 1:1 zur DB). */
        AS_IN_DATABASE,
        /** GeometryTypeId=1 → N einzelne gml:Polygon, je Dreieck eines. */
        ALWAYS_POLYGON
    }

    private static GeometryMode geometryMode = GeometryMode.AS_IN_DATABASE;

    /** Muss vor dem Start des Workflows gesetzt werden (siehe PolygonOnlyReplaceWorkflow). */
    public static void setGeometryMode(GeometryMode mode) {
        geometryMode = Objects.requireNonNull(mode);
    }

    /** SurfaceTypes-Lookup der Healer-DB (0=None, 1=Ground, 2=Wall, 3=Roof). */
    private static final int SURFACE_TYPE_WALL = 2;

    private static String surfaceTypeName(int id) {
        return switch (id) {
            case 1 -> "Ground";
            case 2 -> "Wall";
            case 3 -> "Roof";
            default -> "Other(" + id + ")";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Valid-Gate
    // ─────────────────────────────────────────────────────────────────────────

    /** Prueft die gesamte Hierarchie eines DB-Buildings auf Validitaet (Valid-Gate, siehe Doku.md). */
    private static boolean isFullyValid(de.mpsc.sql2gml.model.Building dbBuilding) {
        if (!dbBuilding.isValid()) return false;
        for (de.mpsc.sql2gml.model.BuildingPart part : dbBuilding.getBuildingParts()) {
            if (!part.isValid()) return false;
            for (de.mpsc.sql2gml.model.Surface surface : part.getSurfaces()) {
                if (!surface.isValid()) return false;
                de.mpsc.sql2gml.model.SurfaceGeometry geometry = surface.getGeometry();
                if (geometry == null || !geometry.isValid() || geometry.getPosLists().isEmpty()) return false;
                for (de.mpsc.sql2gml.model.PosList posList : geometry.getPosLists()) {
                    if (!posList.isValid()) return false;
                }
            }
        }
        return !dbBuilding.getBuildingParts().isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Building ersetzen
    // ─────────────────────────────────────────────────────────────────────────

    /** Ersetzt die gesamte Geometrie eines GML-Buildings durch die Daten aus der DB (siehe Doku.md). */
    private static void replaceBuilding(
            Building gmlBuilding,
            de.mpsc.sql2gml.model.Building dbBuilding,
            Stats stats) {

        // ── 1. Generic Attributes auf Building-Ebene ────────────────────────
        applyAttributes(gmlBuilding, "Log", dbBuilding.getLog(), dbBuilding.getAttributes());

        // GML-BuildingParts einmal nach Id indizieren
        Map<String, AbstractBuilding> gmlPartsById = new HashMap<>();
        if (gmlBuilding.isSetBuildingParts()) {
            for (BuildingPartProperty partProp : gmlBuilding.getBuildingParts()) {
                AbstractBuilding gmlPart = partProp.getObject();
                if (gmlPart != null && gmlPart.getId() != null) {
                    gmlPartsById.put(gmlPart.getId(), gmlPart);
                }
            }
        }

        // ── 2. Ziele bestimmen und Geometrie einfügen ───────────────────────
        Map<AbstractBuilding, List<String>> geomIdsByTarget = new LinkedHashMap<>();
        boolean buildingGeometryMovedToParts = false;

        for (de.mpsc.sql2gml.model.BuildingPart dbPart : dbBuilding.getBuildingParts()) {
            String partGmlId = dbPart.getPartIdGml();
            AbstractBuilding target;

            if (partGmlId == null || partGmlId.isBlank()) {
                // Geometrie gehört direkt zum Building
                target = gmlBuilding;
            } else if (gmlPartsById.containsKey(partGmlId)) {
                // Existierendes GML-BuildingPart in-place ersetzen
                target = gmlPartsById.get(partGmlId);
            } else {
                // Neues BuildingPart: der Healer hat einen Solid erzeugt, der keinem
                // einzelnen Original-BuildingPart 1:1 entspricht (Party-Wall-Merge
                // mehrerer Teile oder Aufspaltung eines Teils in mehrere Solids).
                // Laut Healer-Doku ist die vergebene PartIdGml bereits die vollständige,
                // dateiweit eindeutige Ziel-Id ("{Building.GmlId}_{PartIndex}_Part") —
                // NICHT mehr ein kurzes Kürzel wie das frühere "solid1", das wir selbst
                // hätten präfixen müssen. Wir übernehmen sie daher direkt.
                String newPartId = partGmlId.startsWith(dbBuilding.getBuildingIdGml())
                        ? partGmlId
                        : dbBuilding.getBuildingIdGml() + "_" + partGmlId;
                if (!partGmlId.startsWith(dbBuilding.getBuildingIdGml())) {
                    logger.warn("New BuildingPart id '{}' for building {} is not fully-qualified "
                            + "per the current Healer naming convention — prefixing defensively",
                            partGmlId, dbBuilding.getBuildingIdGml());
                }
                BuildingPart newPart = new BuildingPart();
                newPart.setId(newPartId);
                applyAttributes(newPart, "Log_Part", dbPart.getLog(), null);
                gmlBuilding.getBuildingParts().add(new BuildingPartProperty(newPart));
                stats.newPartsCreated++;
                buildingGeometryMovedToParts = true;
                target = newPart;
                logger.debug("Created new BuildingPart {} for building {}",
                        newPart.getId(), gmlBuilding.getId());
            }

            List<String> targetGeomIds = geomIdsByTarget.get(target);
            if (targetGeomIds == null) {
                // Erstes Mal für dieses Ziel: Boundaries genau einmal löschen
                target.getBoundaries().clear();
                targetGeomIds = new ArrayList<>();
                geomIdsByTarget.put(target, targetGeomIds);
            }
            appendBoundaries(target, dbPart, targetGeomIds, stats, dbBuilding.getBuildingIdGml());
        }

        // ── 3. Building-Geometrie entfernen, wenn sie in neue Parts gewandert ist ──
        if (buildingGeometryMovedToParts && !geomIdsByTarget.containsKey(gmlBuilding)) {
            gmlBuilding.getBoundaries().clear();
            gmlBuilding.setLod2Solid(null);
        }

        // ── 4. Überholte GML-Parts entfernen (Healer-Merge/Split, siehe Doku.md) ──
        if (gmlBuilding.isSetBuildingParts()) {
            int before = gmlBuilding.getBuildingParts().size();
            gmlBuilding.getBuildingParts().removeIf(partProp -> {
                AbstractBuilding gmlPart = partProp.getObject();
                // Behalten wird genau das, in das wir auch Geometrie geschrieben haben.
                if (gmlPart == null || geomIdsByTarget.containsKey(gmlPart)) return false;
                logger.debug("Removing superseded BuildingPart {} of building {}",
                        gmlPart.getId(), gmlBuilding.getId());
                return true;
            });
            int removed = before - gmlBuilding.getBuildingParts().size();
            if (removed > 0) {
                stats.partsRemoved += removed;
                logger.info("Building {}: {} superseded BuildingPart(s) removed "
                        + "(healer merged/split them into {} new part(s))",
                        gmlBuilding.getId(), removed, stats.newPartsCreated);
            }
        }

        // ── 5. Solid JE ZIEL neu bauen ──────────────────────────────────────
        for (Map.Entry<AbstractBuilding, List<String>> entry : geomIdsByTarget.entrySet()) {
            AbstractBuilding target = entry.getKey();
            List<String> geomIds = entry.getValue();
            if (!geomIds.isEmpty()) {
                rebuildSolid(target, geomIds);
                logger.debug("Rebuilt solid for {} with {} surface xlinks",
                        target.getId(), geomIds.size());
            } else {
                target.setLod2Solid(null);
                logger.warn("No geometry in DB for {} — boundaries cleared, solid removed",
                        target.getId());
            }
        }
    }

    /** Fuegt neue Surfaces aus einem DB-Part ein und sammelt alle Geometrie-GML-IDs in geomIds. */
    private static void appendBoundaries(
            AbstractBuilding target,
            de.mpsc.sql2gml.model.BuildingPart dbPart,
            List<String> geomIds,
            Stats stats,
            String buildingIdGml) {

        for (de.mpsc.sql2gml.model.Surface dbSurface : dbPart.getSurfaces()) {
            de.mpsc.sql2gml.model.SurfaceGeometry dbGeometry = dbSurface.getGeometry();
            if (dbGeometry == null) {
                logger.warn("Surface {} has no geometry, skipping", dbSurface.getSurfaceIdGml());
                continue;
            }

            List<IdentifiedSurface> gmlGeometries = buildGeometries(dbGeometry);
            if (gmlGeometries.isEmpty()) {
                logger.warn("Surface {} produced no usable geometry, skipping", dbSurface.getSurfaceIdGml());
                continue;
            }

            AbstractThematicSurface gmlSurface = createBoundarySurface(dbSurface);

            MultiSurface multiSurface = new MultiSurface();
            for (IdentifiedSurface geometry : gmlGeometries) {
                multiSurface.getSurfaceMember().add(new SurfaceProperty(geometry.surface()));
            }
            if (dbSurface.getSurfaceIdGml() != null) {
                multiSurface.setId(dbSurface.getSurfaceIdGml() + "_MS");
            }
            gmlSurface.setLod2MultiSurface(new MultiSurfaceProperty(multiSurface));

            applyAttributes(gmlSurface, "Log_Surface", dbSurface.getLog(), dbSurface.getAttributes());

            target.getBoundaries().add(new AbstractSpaceBoundaryProperty(gmlSurface));

            // Gezaehlt wird die DB-seitige Triangulierung, unabhaengig vom Ausgabe-GeometryMode.
            boolean tin = dbGeometry.isTriangulatedSurface();
            stats.surfacesWritten++;
            if (tin) {
                stats.tinSurfacesWritten++;
                if (geometryMode == GeometryMode.ALWAYS_POLYGON) {
                    stats.polygonsFromTin += gmlGeometries.size();
                }
            }
            stats.count(dbSurface.getSurfaceTypeId(), tin);

            // Triangulierte WAENDE blockieren spaetere LoD3-Fenstereinbau, darum einzeln protokolliert.
            if (tin && dbSurface.getSurfaceTypeId() == SURFACE_TYPE_WALL) {
                stats.buildingsWithTinWall.add(buildingIdGml);
                logger.warn("Triangulated WALL {} in building {} — blocks clean LoD3 window insertion",
                        dbSurface.getSurfaceIdGml(), buildingIdGml);
            }

            for (IdentifiedSurface geometry : gmlGeometries) {
                if (geometry.id() != null) {
                    geomIds.add(geometry.id());
                } else {
                    logger.warn("Geometry of surface {} has no GeometryIdGml — written as geometry "
                            + "but not referenced in solid", dbSurface.getSurfaceIdGml());
                }
            }
        }
    }

    /** Erstellt die passende citygml4j-Klasse fuer den SurfaceTypeId aus der DB (0=None→WallSurface). */
    private static AbstractThematicSurface createBoundarySurface(de.mpsc.sql2gml.model.Surface dbSurface) {
        String id = dbSurface.getSurfaceIdGml();
        AbstractThematicSurface surface = switch (dbSurface.getSurfaceTypeId()) {
            case TYPE_GROUND -> new GroundSurface();
            case TYPE_WALL   -> new WallSurface();
            case TYPE_ROOF   -> new RoofSurface();
            default          -> new WallSurface(); // Fallback für 0=None
        };
        if (id != null) {
            ((AbstractGML) surface).setId(id);
        }
        return surface;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geometrie-Aufbau: Polygon und TriangulatedSurface
    // ─────────────────────────────────────────────────────────────────────────

    /** Eine ausgabefertige Geometrie zusammen mit der gml:id, unter der das Solid sie referenziert. */
    private record IdentifiedSurface(String id, AbstractSurface surface) {}

    /** Baut alle GML-Geometrien fuer EINE DB-SurfaceGeometry (1, oder N Polygone im ALWAYS_POLYGON-Modus). */
    private static List<IdentifiedSurface> buildGeometries(
            de.mpsc.sql2gml.model.SurfaceGeometry dbGeometry) {

        String baseId = dbGeometry.getGeometryIdGml();

        if (!dbGeometry.isTriangulatedSurface()) {
            var polygon = buildGmlPolygon(dbGeometry);
            return polygon == null ? List.of() : List.of(new IdentifiedSurface(baseId, polygon));
        }

        if (geometryMode == GeometryMode.AS_IN_DATABASE) {
            var tin = buildGmlTriangulatedSurface(dbGeometry);
            return tin == null ? List.of() : List.of(new IdentifiedSurface(baseId, tin));
        }

        List<IdentifiedSurface> result = new ArrayList<>();
        int index = 0;
        for (de.mpsc.sql2gml.model.PosList posList : dbGeometry.getPosLists()) {
            index++;
            var polygon = new org.xmlobjects.gml.model.geometry.primitives.Polygon();
            polygon.setExterior(new AbstractRingProperty(createGmlLinearRing(posList)));
            String id = baseId == null ? null : baseId + "_T" + index;
            if (id != null) {
                polygon.setId(id);
            }
            result.add(new IdentifiedSurface(id, polygon));
        }
        return result;
    }

    /** Baut ein gml:Polygon (PosList Index 0 = Aussenring, >0 = Loecher); null ohne Aussenring. */
    private static org.xmlobjects.gml.model.geometry.primitives.Polygon buildGmlPolygon(
            de.mpsc.sql2gml.model.SurfaceGeometry dbGeometry) {

        de.mpsc.sql2gml.model.PosList exterior = null;
        for (de.mpsc.sql2gml.model.PosList posList : dbGeometry.getPosLists()) {
            if (posList.getPosListIndex() == 0) {
                exterior = posList;
                break;
            }
        }
        if (exterior == null) return null;

        var gmlPolygon = new org.xmlobjects.gml.model.geometry.primitives.Polygon();
        if (dbGeometry.getGeometryIdGml() != null) {
            gmlPolygon.setId(dbGeometry.getGeometryIdGml());
        }

        gmlPolygon.setExterior(new AbstractRingProperty(createGmlLinearRing(exterior)));

        for (de.mpsc.sql2gml.model.PosList posList : dbGeometry.getPosLists()) {
            if (posList.getPosListIndex() > 0) {
                gmlPolygon.getInterior().add(new AbstractRingProperty(createGmlLinearRing(posList)));
            }
        }

        return gmlPolygon;
    }

    /** Baut eine gml:TriangulatedSurface; jede PosList ist ein unabhaengiges geschlossenes Dreieck. */
    private static TriangulatedSurface buildGmlTriangulatedSurface(
            de.mpsc.sql2gml.model.SurfaceGeometry dbGeometry) {

        List<Triangle> triangles = new ArrayList<>(dbGeometry.getPosLists().size());
        for (de.mpsc.sql2gml.model.PosList posList : dbGeometry.getPosLists()) {
            triangles.add(new Triangle(createGmlLinearRing(posList)));
        }
        if (triangles.isEmpty()) return null;

        TriangulatedSurface tin = new TriangulatedSurface(new TriangleArrayProperty(triangles));
        if (dbGeometry.getGeometryIdGml() != null) {
            tin.setId(dbGeometry.getGeometryIdGml());
        }
        return tin;
    }

    private static org.xmlobjects.gml.model.geometry.primitives.LinearRing createGmlLinearRing(
            de.mpsc.sql2gml.model.PosList dbPosList) {
        DirectPositionList posList = new DirectPositionList(toCoordList(dbPosList.getPosListAsArray()));
        posList.setSrsDimension(3);
        return new org.xmlobjects.gml.model.geometry.primitives.LinearRing(posList);
    }

    /** Baut das lod2Solid komplett neu aus einer Liste von Geometrie-IDs (xlink:href je Eintrag). */
    private static void rebuildSolid(AbstractBuilding gmlBuilding, List<String> geomIds) {
        Shell shell = new Shell();
        for (String geomId : geomIds) {
            SurfaceProperty ref = new SurfaceProperty();
            ref.setHref("#" + geomId);
            shell.getSurfaceMembers().add(ref);
        }
        Solid solid = new Solid();
        solid.setExterior(new ShellProperty(shell));
        gmlBuilding.setLod2Solid(new SolidProperty(solid));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attribute setzen
    // ─────────────────────────────────────────────────────────────────────────

    /** Schreibt Log + DB-Attribute als Generic Attributes auf ein CityObject; der Log landet unter logKey. */
    private static void applyAttributes(
            AbstractCityObject target, String logKey, String log, Map<String, Object> dbAttributes) {

        Map<String, Object> attrs = new LinkedHashMap<>();

        if (log != null && !log.isEmpty()) {
            attrs.put(logKey, log);
        }
        if (dbAttributes != null) {
            attrs.putAll(dbAttributes);
        }

        if (!attrs.isEmpty()) {
            addGenericAttributes(target, attrs);
        }
    }

    private static void addGenericAttributes(AbstractCityObject target, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) return;
        List<AbstractGenericAttributeProperty> genericAttributes = target.getGenericAttributes();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue().toString();
            boolean found = false;
            for (AbstractGenericAttributeProperty prop : genericAttributes) {
                if (prop.getObject() instanceof StringAttribute existing && name.equals(existing.getName())) {
                    existing.setValue(value);
                    found = true;
                    break;
                }
            }
            if (!found) {
                genericAttributes.add(new AbstractGenericAttributeProperty(new StringAttribute(name, value)));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hilfsmethoden
    // ─────────────────────────────────────────────────────────────────────────

    private static List<Double> toCoordList(double[] coords) {
        List<Double> list = new ArrayList<>(coords.length);
        for (double c : coords) list.add(c);
        return list;
    }

    /** Writer konfigurieren (identisch zum alten ReplaceWorkflow). */
    private static void configureWriter(CityGMLChunkWriter writer, BoundingShape originalBoundedBy) {
        writer.withIndent("\t")
              .withDefaultPrefixes()
              .withPrefix("core", "http://www.opengis.net/citygml/1.0")
              .withPrefix("tex", "http://www.opengis.net/citygml/texturedsurface/1.0")
              .withPrefix("sch", "http://www.ascc.net/xml/schematron")
              .withPrefix("smil20", "http://www.w3.org/2001/SMIL20/")
              .withPrefix("smil20lang", "http://www.w3.org/2001/SMIL20/Language")
              .withPrefix("base", "http://www.citygml.org/citygml/profiles/base/1.0")
              .withSchemaLocation("http://www.opengis.net/citygml/building/1.0",
                  "http://repository.gdi-de.org/schemas/adv/citygml/building/1.0/buildingLoD2.xsd")
              .withSchemaLocation("http://www.opengis.net/citygml/cityobjectgroup/1.0",
                  "http://repository.gdi-de.org/schemas/adv/citygml/cityobjectgroup/1.0/cityObjectGroupLoD2.xsd")
              .withSchemaLocation("http://www.opengis.net/citygml/appearance/1.0",
                  "http://repository.gdi-de.org/schemas/adv/citygml/appearance/1.0/appearanceLoD2.xsd")
              .withSchemaLocation("http://www.opengis.net/citygml/1.0",
                  "http://repository.gdi-de.org/schemas/adv/citygml/1.0/cityGMLBaseLoD2.xsd")
              .withSchemaLocation("http://www.opengis.net/citygml/generics/1.0",
                  "http://repository.gdi-de.org/schemas/adv/citygml/generics/1.0/genericsLoD2.xsd");
        if (originalBoundedBy != null) {
            writer.getCityModelInfo().setBoundedBy(originalBoundedBy);
        }
    }

    /** Header des Output-GML durch den Original-Header ersetzen (Namespaces, schemaLocation). */
    private static void fixHeader(Path inputPath, Path outputPath) throws IOException {
        List<String> originalHeader = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<gml:boundedBy>")) break;
                originalHeader.add(line);
            }
        }
        if (originalHeader.isEmpty()) {
            logger.warn("Could not read header from original file, skipping header fix");
            return;
        }
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
        try (BufferedReader br = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
            for (String headerLine : originalHeader) {
                bw.write(headerLine);
                bw.newLine();
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<gml:boundedBy>")) {
                    bw.write(line);
                    bw.newLine();
                    break;
                }
            }
            char[] buffer = new char[65536];
            int read;
            while ((read = br.read(buffer)) != -1) {
                bw.write(buffer, 0, read);
            }
        }
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Fixed header ({} lines)", originalHeader.size());
    }
}
