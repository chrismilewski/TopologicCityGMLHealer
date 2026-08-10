package de.mpsc.sql2gml.legacy;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
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
import org.xmlobjects.gml.model.geometry.primitives.Shell;
import org.xmlobjects.gml.model.geometry.primitives.ShellProperty;
import org.xmlobjects.gml.model.geometry.primitives.Solid;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * ReplaceWorkflow: Alternative zu CompleteWorkflow.
 *
 * Strategie: "Kompletter Replace auf Building-Ebene"
 * - Für jedes Building das in der DB vorkommt:
 *     → alle Surfaces/Polygone/LinearRings aus dem GML werden GELÖSCHT
 *     → neue Surfaces/Polygone/LinearRings aus der DB werden EINGEFÜGT
 *     → Solid + XLinks werden komplett neu gebaut aus den DB-Polygon-IDs
 *     → alle sonstigen CityGML-Attribute (measuredHeight, function, etc.) bleiben erhalten
 * - Buildings die NICHT in der DB sind: werden unverändert übernommen
 *
 * Voraussetzung: Die DB enthält ALLE Polygone/Surfaces eines Buildings (nicht nur geänderte).
 *
 * Usage (identisch zu CompleteWorkflow):
 *   Single file: <input.gml> <database.db> [<output.gml>]
 *   Batch mode:  <inputFolder> <database.db> [<outputFolder>]
 *   Auto mode:   <database.db> <inputFolder> <outputFolder> --auto
 */
public class ReplaceWorkflow {

    private static final Logger logger = LoggerFactory.getLogger(ReplaceWorkflow.class);

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
    // Run-Modes (identisch zu CompleteWorkflow)
    // ─────────────────────────────────────────────────────────────────────────

    private static void runBatchFromDatabase(String databasePath, Path inputFolder, Path outputFolder)
            throws Exception {
        logger.info("=== ReplaceWorkflow Batch (Auto) ===");
        Files.createDirectories(outputFolder);
        DatabaseReader dbReader = new DatabaseReader(databasePath);
        Map<Long, String> cityGmlFiles = dbReader.getCityGmlFiles();

        // DB + Context EINMAL laden statt pro Datei
        CityGMLContext context = CityGMLContext.newInstance();
        Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingIndex =
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
        logger.info("=== ReplaceWorkflow Batch ===");
        Files.createDirectories(outputFolder);
        File[] gmlFiles = inputFolder.toFile().listFiles(
                f -> f.isFile() && f.getName().toLowerCase().endsWith(".gml"));
        if (gmlFiles == null || gmlFiles.length == 0) {
            logger.warn("No GML files found in {}", inputFolder);
            return;
        }

        // DB + Context EINMAL laden statt pro Datei
        CityGMLContext context = CityGMLContext.newInstance();
        Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingIndex = loadBuildingIndex(databasePath);

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
        Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingIndex = loadBuildingIndex(databasePath);
        processFile(inputGmlPath, outputPath, buildingIndex, context);
    }

    /**
     * Liest alle Buildings aus der DB und baut den Index BuildingIdGml → DB-Building.
     * Wird pro Lauf genau EINMAL aufgerufen (nicht pro Datei) — vermeidet das
     * wiederholte Komplett-Einlesen der Datenbank im Batch.
     */
    private static Map<String, de.mpsc.sql2gml.legacy.model.Building> loadBuildingIndex(String databasePath)
            throws Exception {
        DatabaseReader dbReader = new DatabaseReader(databasePath);
        return indexBuildings(dbReader.readAllBuildings());
    }

    private static Map<String, de.mpsc.sql2gml.legacy.model.Building> indexBuildings(
            List<de.mpsc.sql2gml.legacy.model.Building> dbBuildings) {
        logger.info("Loaded {} buildings from database", dbBuildings.size());
        Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingIndex = new HashMap<>();
        for (de.mpsc.sql2gml.legacy.model.Building b : dbBuildings) {
            if (b.getBuildingIdGml() != null) {
                buildingIndex.put(b.getBuildingIdGml(), b);
            }
        }
        logger.info("Building index: {} entries", buildingIndex.size());
        return buildingIndex;
    }

    /**
     * Verarbeitet eine einzelne GML-Datei mit bereits geladenem Building-Index und Context.
     * Beide werden vom Aufrufer einmal pro Lauf erstellt und über alle Dateien wiederverwendet.
     */
    private static void processFile(
            Path inputGmlPath,
            Path outputPath,
            Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingIndex,
            CityGMLContext context)
            throws Exception {
        logger.info("=== ReplaceWorkflow: DB → CityGML ===");
        logger.info("Input GML:  {}", inputGmlPath);
        logger.info("Output GML: {}", outputPath);

        // ── GML lesen + updaten + schreiben ──────────────────────────────────
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

        int featuresRead = 0, buildingsReplaced = 0, buildingsUnchanged = 0, buildingsNoPolygons = 0;

        Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Paths.get("."));

        try (CityGMLReader reader = in.createCityGMLReader(inputGmlPath.toFile());
             CityGMLChunkWriter writer = out.createCityGMLChunkWriter(
                     outputPath, StandardCharsets.UTF_8.name())) {

            configureWriter(writer, originalBoundedBy);

            while (reader.hasNext()) {
                AbstractFeature feature = reader.next();
                featuresRead++;

                if (feature instanceof Building gmlBuilding) {
                    String buildingId = gmlBuilding.getId();
                    de.mpsc.sql2gml.legacy.model.Building dbBuilding = buildingIndex.get(buildingId);

                    if (dbBuilding != null && dbBuilding.isValid()) {
                        boolean hadPolygons = replaceBuilding(gmlBuilding, dbBuilding);
                        buildingsReplaced++;
                        if (!hadPolygons) buildingsNoPolygons++;
                        logger.debug("Replaced building: {} (polygons: {})", buildingId, hadPolygons);
                    } else {
                        // ── Building nicht in DB: unverändert übernehmen ──
                        buildingsUnchanged++;
                        logger.debug("Unchanged building: {}", buildingId);
                    }
                }

                writer.writeMember(feature);
            }
        }

        // Header fixieren (Namespaces, schemaLocation)
        fixHeader(inputGmlPath, outputPath);

        logger.info("--- Results ---");
        logger.info("Features read:           {}", featuresRead);
        logger.info("Buildings replaced:      {}", buildingsReplaced);
        logger.info("Buildings unchanged:     {}", buildingsUnchanged);
        logger.info("Buildings no DB polygons:{}", buildingsNoPolygons);
        logger.info("Output:               {}", outputPath);
        logger.info("✓ SUCCESS");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Building ersetzen: Surfaces + Solid komplett neu bauen
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ersetzt die gesamte Geometrie eines GML-Buildings durch die Daten aus der DB.
     *
     * Was passiert:
     *   1. Generic Attributes (Log + Attributes) aus DB auf das Building schreiben
     *   2. Für jedes betroffene ZIEL (Building selbst ODER je BuildingPart einzeln):
     *      GML-Boundaries genau einmal löschen + neue aus DB einfügen
     *   3. Pro Ziel eigenes lod2Solid neu bauen — NUR aus den Polygon-XLinks DIESES
     *      Ziels (nicht aus allen Parts kombiniert). Ein Building mit mehreren
     *      BuildingParts hat pro Part ein eigenes Teilvolumen; ein gemeinsames
     *      Solid auf Building-Ebene wuerde diese Volumen faelschlich vereinen.
     *   4. Ziel ohne Polygone in DB: lod2Solid geloescht (keine kaputten XLinks)
     *   5. Ziele, die gar nicht in der DB vorkommen (z.B. Building ohne
     *      partId-lose Surfaces): bleiben komplett unangetastet (auch ihr Solid)
     *
     * Building-eigene GML-Attribute (measuredHeight, function, Adresse, etc.) bleiben erhalten.
     * Nur was explizit über Boundaries + Solid geht wird ersetzt.
     *
     * @return true wenn irgendwo (Building oder einem Part) Polygone vorhanden waren
     */
    private static boolean replaceBuilding(
            Building gmlBuilding,
            de.mpsc.sql2gml.legacy.model.Building dbBuilding) {

        // ── 1. Generic Attributes setzen ────────────────────────────────────
        applyAttributes(gmlBuilding, "Log", dbBuilding.getLog(), dbBuilding.getAttributes());

        // Polygon-IDs werden PRO ZIEL gesammelt (Building selbst ODER je BuildingPart),
        // damit jedes Ziel sein EIGENES lod2Solid bekommt statt eines gemeinsamen.
        Map<AbstractBuilding, List<String>> polyIdsByTarget = new LinkedHashMap<>();
        boolean anyPolygons = false;

        // GML-BuildingParts einmal nach Id indizieren (statt pro DB-Part linear zu suchen)
        Map<String, AbstractBuilding> gmlPartsById = new HashMap<>();
        if (gmlBuilding.isSetBuildingParts()) {
            for (var partProp : gmlBuilding.getBuildingParts()) {
                AbstractBuilding gmlPart = partProp.getObject();
                if (gmlPart != null && gmlPart.getId() != null) {
                    gmlPartsById.put(gmlPart.getId(), gmlPart);
                }
            }
        }

        for (de.mpsc.sql2gml.legacy.model.BuildingPart dbPart : dbBuilding.getBuildingParts()) {
            String partGmlId = dbPart.getPartIdGml();
            // Part ohne PartIdGml: Surfaces gehoeren direkt zum Building.
            // Part mit PartIdGml: passendes GML-BuildingPart als Ziel.
            AbstractBuilding target = (partGmlId == null || partGmlId.isBlank())
                    ? gmlBuilding
                    : gmlPartsById.get(partGmlId);

            if (target == null) {
                // Kein passendes GML-Part → Geometrie dieses DB-Parts ginge sonst still verloren
                logger.warn("DB part {} of building {} has no matching GML BuildingPart "
                        + "— its surfaces are dropped", partGmlId, gmlBuilding.getId());
                continue;
            }

            List<String> targetPolyIds = polyIdsByTarget.get(target);
            if (targetPolyIds == null) {
                // Erstes Mal fuer dieses Ziel: Boundaries genau einmal loeschen
                target.getBoundaries().clear();
                targetPolyIds = new ArrayList<>();
                polyIdsByTarget.put(target, targetPolyIds);
            }
            appendBoundaries(target, dbPart, targetPolyIds);
            if (!targetPolyIds.isEmpty()) anyPolygons = true;
        }

        // ── 2. Solid JE ZIEL anpassen (jedes Ziel bekommt sein eigenes Solid) ──
        for (var entry : polyIdsByTarget.entrySet()) {
            AbstractBuilding target = entry.getKey();
            List<String> polyIds = entry.getValue();
            if (!polyIds.isEmpty()) {
                // Solid neu bauen: XLinks zeigen auf neue DB-Polygon-IDs DIESES Ziels
                rebuildSolid(target, polyIds);
                logger.debug("Rebuilt solid for {} with {} polygon xlinks",
                        target.getId(), polyIds.size());
            } else {
                // Keine Polygone in DB → Solid entfernen (keine kaputten XLinks auf gelöschte Polygone)
                target.setLod2Solid(null);
                logger.warn("No polygons in DB for {} — boundaries cleared, solid removed",
                        target.getId());
            }
        }

        return anyPolygons;
    }

    /**
     * Fügt neue Surfaces aus einem DB-Part in das GML-Building/Part ein.
     * Die Boundaries müssen VOR dem Aufruf bereits gelöscht worden sein.
     * Sammelt alle neuen Polygon-GML-IDs in allPolyIds (für das Solid).
     */
    private static void appendBoundaries(
            AbstractBuilding target,
            de.mpsc.sql2gml.legacy.model.BuildingPart dbPart,
            List<String> allPolyIds) {

        // Neue Surfaces aus DB einbauen (clear wurde bereits vom Aufrufer gemacht)
        for (de.mpsc.sql2gml.legacy.model.Surface dbSurface : dbPart.getSurfaces()) {
            if (!dbSurface.isValid()) continue;

            AbstractThematicSurface gmlSurface = createBoundarySurface(dbSurface);

            // Polygone erstellen und zur MultiSurface hinzufügen
            MultiSurface multiSurface = new MultiSurface();
            for (de.mpsc.sql2gml.legacy.model.Polygon dbPolygon : dbSurface.getPolygons()) {
                if (!dbPolygon.isValid()) continue;

                org.xmlobjects.gml.model.geometry.primitives.Polygon gmlPoly = buildGmlPolygon(dbPolygon);
                if (gmlPoly == null) continue;

                multiSurface.getSurfaceMember().add(new SurfaceProperty(gmlPoly));

                if (dbPolygon.getPolygonIdGml() != null) {
                    allPolyIds.add(dbPolygon.getPolygonIdGml());
                } else {
                    // Ohne ID kein XLink → Polygon liegt als Geometrie vor, wird aber nicht
                    // ins Solid referenziert (verwaiste Fläche). Sichtbar machen statt still.
                    logger.warn("Polygon in surface {} has no PolygonIdGml — written as geometry "
                            + "but not referenced in solid", dbSurface.getSurfaceIdGml());
                }
            }

            if (multiSurface.getSurfaceMember().isEmpty()) {
                logger.debug("Surface {} has no valid polygons, skipping", dbSurface.getSurfaceIdGml());
                continue;
            }

            // MultiSurface-ID und Geometrie setzen
            if (dbSurface.getSurfaceIdGml() != null) {
                multiSurface.setId(dbSurface.getSurfaceIdGml() + "_MS");
            }
            gmlSurface.setLod2MultiSurface(new MultiSurfaceProperty(multiSurface));

            // DB-Attribute der Surface als Generic Attributes setzen
            applyAttributes(gmlSurface, "Log_Surface", dbSurface.getLog(), dbSurface.getAttributes());

            // Surface dem Building hinzufügen
            target.getBoundaries().add(new AbstractSpaceBoundaryProperty(gmlSurface));
        }
    }

    /**
     * Erstellt die passende citygml4j-Klasse für den SurfaceTypeId aus der DB.
     * 0=None→WallSurface, 1=Ground, 2=Wall, 3=Roof
     */
    private static AbstractThematicSurface createBoundarySurface(de.mpsc.sql2gml.legacy.model.Surface dbSurface) {
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

    /**
     * Baut ein GML-Polygon aus einem DB-Polygon (mit allen LinearRings).
     * Gibt null zurück wenn kein gültiger Exterior-Ring vorhanden.
     */
    private static org.xmlobjects.gml.model.geometry.primitives.Polygon buildGmlPolygon(
            de.mpsc.sql2gml.legacy.model.Polygon dbPolygon) {

        de.mpsc.sql2gml.legacy.model.LinearRing dbExterior = findRingByIndex(dbPolygon, 0);
        if (dbExterior == null || !dbExterior.isValid()) return null;

        var gmlPolygon = new org.xmlobjects.gml.model.geometry.primitives.Polygon();
        if (dbPolygon.getPolygonIdGml() != null) {
            gmlPolygon.setId(dbPolygon.getPolygonIdGml());
        }

        // Exterior Ring
        gmlPolygon.setExterior(new AbstractRingProperty(createGmlLinearRing(dbExterior)));

        // Interior Rings (Löcher)
        for (de.mpsc.sql2gml.legacy.model.LinearRing dbRing : dbPolygon.getLinearRings()) {
            if (dbRing.getRingIndex() > 0 && dbRing.isValid()) {
                gmlPolygon.getInterior().add(new AbstractRingProperty(createGmlLinearRing(dbRing)));
            }
        }

        return gmlPolygon;
    }

    /**
     * Erstellt einen GML LinearRing aus DB-Daten.
     */
    private static org.xmlobjects.gml.model.geometry.primitives.LinearRing createGmlLinearRing(
            de.mpsc.sql2gml.legacy.model.LinearRing dbRing) {
        DirectPositionList posList = new DirectPositionList(toCoordList(dbRing.getPosListAsArray()));
        posList.setSrsDimension(3);
        return new org.xmlobjects.gml.model.geometry.primitives.LinearRing(posList);
    }

    /**
     * Baut das lod2Solid komplett neu aus einer Liste von Polygon-IDs.
     * Jeder Eintrag wird als xlink:href="#PolyId" in die CompositeSurface eingetragen.
     */
    private static void rebuildSolid(AbstractBuilding gmlBuilding, List<String> polyIds) {
        Shell shell = new Shell();
        for (String polyId : polyIds) {
            SurfaceProperty ref = new SurfaceProperty();
            ref.setHref("#" + polyId);
            shell.getSurfaceMembers().add(ref);
        }
        Solid solid = new Solid();
        solid.setExterior(new ShellProperty(shell));
        gmlBuilding.setLod2Solid(new SolidProperty(solid));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attribute setzen
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schreibt Log + DB-Attribute als Generic Attributes auf ein CityObject
     * (Building oder Surface). Der Log landet unter {@code logKey}.
     */
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

    private static de.mpsc.sql2gml.legacy.model.LinearRing findRingByIndex(
            de.mpsc.sql2gml.legacy.model.Polygon dbPolygon, int ringIndex) {
        for (de.mpsc.sql2gml.legacy.model.LinearRing ring : dbPolygon.getLinearRings()) {
            if (ring.getRingIndex() == ringIndex) return ring;
        }
        return null;
    }

    private static List<Double> toCoordList(double[] coords) {
        List<Double> list = new ArrayList<>(coords.length);
        for (double c : coords) list.add(c);
        return list;
    }

    /**
     * Writer konfigurieren — identisch zu CompleteWorkflow.
     */
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

    /**
     * Header des Output-GML durch den Original-Header ersetzen
     * (Namespace-Deklarationen, schemaLocation, kein standalone="yes").
     * Identisch zu CompleteWorkflow.
     */
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
