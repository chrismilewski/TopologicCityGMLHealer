package de.mpsc.sql2gml.legacy;

import de.mpsc.sql2gml.legacy.model.*;
import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.core.AbstractFeature;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.generics.StringAttribute;
import org.citygml4j.core.visitor.ObjectWalker;
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
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;
import org.xmlobjects.model.Child;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.citygml4j.core.model.building.AbstractBuilding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Complete Workflow: Database to CityGML
 * Reads database with improved/repaired data, reads CityGML 1.0, 
 * updates coordinates with data from DB, writes new CityGML 1.0
 * 
 * NEW in this version: 
 * - Supports Building table (not just BuildingParts)
 * - Supports generic attributes from DB in JSON format
 */
public class CompleteWorkflow {
    private static final Logger logger = LoggerFactory.getLogger(CompleteWorkflow.class);

    private static final Set<String> BOUNDARY_SURFACE_TYPES = Set.of(
        "WallSurface", "RoofSurface", "GroundSurface", "ClosureSurface",
        "OuterCeilingSurface", "OuterFloorSurface", "CeilingSurface",
        "FloorSurface", "InteriorWallSurface", "Door", "Window");

    /**
     * Execution mode, detected from command-line arguments.
     * AUTO_BATCH  : <database.db> <inputFolder> <outputFolder> --auto
     * BATCH_FOLDER: <inputFolder> <database.db> [<outputFolder>]
     * SINGLE_FILE : <input.gml>   <database.db> [<output.gml>]
     */
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
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Runs batch processing based on database file list.
     * Only processes files that have modifications (IsChanged=1 or deleted polygons).
     */
    private static void runBatchFromDatabase(String databasePath, Path inputFolder, Path outputFolder) throws Exception {
        logger.info("=== Batch Mode (Auto): Reading file list from database ===");
        logger.info("Database: {}", databasePath);
        logger.info("Input folder: {}", inputFolder);
        logger.info("Output folder: {}", outputFolder);
        
        // Ensure output folder exists
        Files.createDirectories(outputFolder);
        
        DatabaseReader dbReader = new DatabaseReader(databasePath);
        Map<Long, String> cityGmlFiles = dbReader.getCityGmlFiles();
        
        int totalFiles = cityGmlFiles.size();
        int processedFiles = 0;
        int skippedFiles = 0;
        int errorFiles = 0;
        
        logger.info("Found {} CityGML files in database", totalFiles);
        
        for (Map.Entry<Long, String> entry : cityGmlFiles.entrySet()) {
            long fileId = entry.getKey();
            String filename = entry.getValue();
            
            // Check if this file has any modifications
            if (!dbReader.hasModificationsForFile(fileId)) {
                logger.info("Skipping {} (no modifications)", filename);
                skippedFiles++;
                continue;
            }
            
            Path inputFile = inputFolder.resolve(filename);
            if (!Files.exists(inputFile)) {
                logger.warn("Input file not found: {}", inputFile);
                errorFiles++;
                continue;
            }
            
            // Generate output filename
            String outputFilename = generateOutputFilename(filename);
            Path outputFile = outputFolder.resolve(outputFilename);
            
            logger.info("\n--- Processing file {}/{}: {} ---", processedFiles + skippedFiles + 1, totalFiles, filename);
            
            try {
                runSingleFile(inputFile, databasePath, outputFile);
                processedFiles++;
            } catch (Exception e) {
                logger.error("Error processing {}: {}", filename, e.getMessage());
                errorFiles++;
            }
        }
        
        logger.info("\n=== Batch Processing Complete ===");
        logger.info("Total files in DB: {}", totalFiles);
        logger.info("Processed (with modifications): {}", processedFiles);
        logger.info("Skipped (no modifications): {}", skippedFiles);
        logger.info("Errors: {}", errorFiles);
    }
    
    /**
     * Runs batch processing for all GML files in a folder
     */
    private static void runBatchMode(Path inputFolder, String databasePath, Path outputFolder) throws Exception {
        logger.info("=== Batch Mode: Processing folder ===");
        logger.info("Input folder: {}", inputFolder);
        logger.info("Database: {}", databasePath);
        logger.info("Output folder: {}", outputFolder);
        
        // Ensure output folder exists
        Files.createDirectories(outputFolder);
        
        // Find all GML files
        File[] gmlFiles = inputFolder.toFile().listFiles((dir, name) -> 
            name.toLowerCase().endsWith(".gml"));
        
        if (gmlFiles == null || gmlFiles.length == 0) {
            logger.warn("No GML files found in {}", inputFolder);
            return;
        }
        
        logger.info("Found {} GML files to process", gmlFiles.length);
        
        int processedFiles = 0;
        int errorFiles = 0;
        
        for (File gmlFile : gmlFiles) {
            String filename = gmlFile.getName();
            String outputFilename = generateOutputFilename(filename);
            Path outputFile = outputFolder.resolve(outputFilename);
            
            logger.info("\n--- Processing file {}/{}: {} ---", processedFiles + errorFiles + 1, gmlFiles.length, filename);
            
            try {
                runSingleFile(gmlFile.toPath(), databasePath, outputFile);
                processedFiles++;
            } catch (Exception e) {
                logger.error("Error processing {}: {}", filename, e.getMessage());
                errorFiles++;
            }
        }
        
        logger.info("\n=== Batch Processing Complete ===");
        logger.info("Total files: {}", gmlFiles.length);
        logger.info("Successfully processed: {}", processedFiles);
        logger.info("Errors: {}", errorFiles);
    }
    
    /**
     * Generates output filename from input filename
     */
    private static String generateOutputFilename(String inputFilename) {
        if (inputFilename.toLowerCase().endsWith(".gml")) {
            return inputFilename.substring(0, inputFilename.length() - 4) + "_RepairAttempt.gml";
        }
        return inputFilename + "_RepairAttempt.gml";
    }
    
    /**
     * Runs the workflow for a single GML file
     */
    private static void runSingleFile(Path inputGmlPath, String databasePath, Path outputPath) throws Exception {
            logger.info("=== Complete Workflow: DB → CityGML ===");
            logger.info("Input GML: {}", inputGmlPath);
            logger.info("Database: {}", databasePath);
            logger.info("Output GML: {}", outputPath);
            
            // Step 1: Read database
            logger.info("\n--- Step 1: Reading Database ---");
            DatabaseReader dbReader = new DatabaseReader(databasePath);
            
            // Read buildings (new structure with Building table)
            List<de.mpsc.sql2gml.legacy.model.Building> dbBuildings = dbReader.readAllBuildings();
            logger.info("Loaded {} buildings from database", dbBuildings.size());
            
            PolygonIndex idx = PolygonIndex.buildFromDatabase(dbBuildings);
            
            // Step 2: Read and update CityGML
            logger.info("\n--- Step 2: Processing CityGML ---");
            CityGMLContext context = CityGMLContext.newInstance();
            CityGMLVersion version = CityGMLVersion.v1_0;
            
            // First, read CityModel metadata (boundedBy, srsName) without chunking
            BoundingShape originalBoundedBy = null;
            CityGMLInputFactory inNoChunk = context.createCityGMLInputFactory();
            try (CityGMLReader metaReader = inNoChunk.createCityGMLReader(inputGmlPath.toFile())) {
                if (metaReader.hasNext()) {
                    AbstractFeature cityModel = metaReader.next();
                    originalBoundedBy = cityModel.getBoundedBy();
                    if (originalBoundedBy != null) {
                        logger.info("Read original boundedBy from CityModel");
                    }
                }
            }
            
            // Now use chunking to get individual buildings
            // Note: The DefaultReferenceResolver was the problem, not chunking!
            CityGMLInputFactory in = context.createCityGMLInputFactory()
                    .withChunking(ChunkOptions.defaults());
            
            CityGMLOutputFactory out = context.createCityGMLOutputFactory(version);
            
            int featuresRead = 0;
            ProcessingStats stats = new ProcessingStats();
            GmlUpdateWalker walker = new GmlUpdateWalker(idx);
            
            try (CityGMLReader reader = in.createCityGMLReader(inputGmlPath.toFile());
                 CityGMLChunkWriter writer = out.createCityGMLChunkWriter(outputPath, StandardCharsets.UTF_8.name())) {

                configureWriter(writer, originalBoundedBy);

                logger.info("Reading and updating features...");

                // Note: Do NOT use DefaultReferenceResolver.resolveReferences() here!
                // It breaks inline geometry that is already correctly loaded.
                // The ObjectWalker can directly access the geometry without additional resolution.
                
                // Process each feature using ObjectWalker
                while (reader.hasNext()) {
                    AbstractFeature feature = reader.next();
                    featuresRead++;
                    
                    // Walk through and update geometries using citygml4j's ObjectWalker
                    feature.accept(walker);
                    
                    // Post-walker: Remove invalid polygons from their MultiSurface
                    if (!walker.polygonsToRemove.isEmpty()) {
                        for (var polyToRemove : walker.polygonsToRemove) {
                            MultiSurface parentMs = findParentMultiSurface(polyToRemove);
                            if (parentMs != null) {
                                Iterator<SurfaceProperty> it = parentMs.getSurfaceMember().iterator();
                                while (it.hasNext()) {
                                    SurfaceProperty sp = it.next();
                                    if (sp.getObject() == polyToRemove) {
                                        it.remove();
                                        stats.removedPolygons++;
                                        if (polyToRemove.getId() != null) {
                                            walker.removedPolygonIdsForXlink.add(polyToRemove.getId());
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        walker.polygonsToRemove.clear();
                    }
                    
                    // Post-walker: Remove invalid surfaces, empty surfaces, and update xlink:href refs
                    if (feature instanceof Building bldg) {
                        if (!idx.invalidSurfaceIds.isEmpty()) {
                            forBuildingAndParts(bldg, b -> removeSurfacesFromBuilding(
                                b, idx.invalidSurfaceIds, walker.removedPolygonIdsForXlink, stats));
                        }
                        forBuildingAndParts(bldg, b -> removeEmptySurfaces(
                            b, walker.removedPolygonIdsForXlink, stats, idx.removalLogsByBuilding));
                        if (!idx.newPolyRefsByExistingPoly.isEmpty()) {
                            forBuildingAndParts(bldg, b -> addNewRefsToSolid(
                                b.getLod2Solid(), idx.newPolyRefsByExistingPoly));
                        }
                        if (!walker.removedPolygonIdsForXlink.isEmpty()) {
                            forBuildingAndParts(bldg, b -> removeRefsFromSolid(
                                b.getLod2Solid(), walker.removedPolygonIdsForXlink));
                        }
                    }
                    // Always clear — even for non-Building features to prevent cross-feature leaking
                    walker.removedPolygonIdsForXlink.clear();
                    
                    // Write the updated feature
                    writer.writeMember(feature);
                }
            }
            
            // Fix header: Replace generated CityModel opening tag with the exact original
            // header (namespace declarations, schemaLocation, ordering).
            // This also removes standalone="yes" that Java's XMLStreamWriter adds.
            fixHeader(inputGmlPath, outputPath);

            logger.info("\n--- Results ---");
            logger.info("Features read: {}", featuresRead);
            logger.info("Polygons visited by GeometryWalker: {}", walker.visitedPolygons);
            logger.info("Buildings processed: {}", walker.processedBuildings);
            if (walker.skippedBuildings > 0) {
                logger.info("Buildings skipped (isValid=0): {}", walker.skippedBuildings);
            }
            logger.info("Polygons updated (isValid=1): {}", walker.updatedPolygons);
            if (walker.createdPolygons > 0) {
                logger.info("New polygons created (Splitted): {}", walker.createdPolygons);
            }
            if (stats.removedPolygons > 0) {
                logger.info("Polygons removed (isValid=0, e.g. Tesselated): {}", stats.removedPolygons);
            }
            if (stats.removedSurfaces > 0) {
                logger.info("Surfaces removed (isValid=0): {}", stats.removedSurfaces);
            }
            logger.info("Linear Rings updated: {}", walker.updatedRings);
            logger.info("Polygons not in DB (unchanged): {}", 
                walker.visitedPolygons - walker.updatedPolygons - stats.removedPolygons);
            logger.info("Output written to: {}", outputPath);
            logger.info("\n✓ SUCCESS: CityGML file created with updated coordinates!");
            logger.info("=== Workflow completed! ===");
    }

    /**
     * Configures the CityGML chunk writer with namespace prefixes, schema locations,
     * and the original CityModel boundedBy. Ensures output matches the original GDI-DE format.
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
            logger.info("Set original boundedBy on output CityModel");
        }
    }

    /**
     * Replaces the header of the output file (XML declaration + CityModel opening tag)
     * with the exact header from the original input file.
     * This ensures namespace declarations, schemaLocation, and ordering are identical
     * to the original, which is required for FME compatibility.
     * Also implicitly removes standalone="yes" since the original doesn't have it.
     */
    private static void fixHeader(Path inputPath, Path outputPath) throws IOException {
        // Read header lines from original (everything before <gml:boundedBy>)
        List<String> originalHeader = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<gml:boundedBy>")) {
                    break;
                }
                originalHeader.add(line);
            }
        }

        if (originalHeader.isEmpty()) {
            logger.warn("Could not read header from original file, skipping header fix");
            return;
        }

        // Write: original header + new file content from <gml:boundedBy> onwards
        Path tempPath = outputPath.resolveSibling(outputPath.getFileName().toString() + ".tmp");
        try (BufferedReader br = Files.newBufferedReader(outputPath, StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {

            // Write original header lines
            for (String headerLine : originalHeader) {
                bw.write(headerLine);
                bw.newLine();
            }

            // Skip new file's header (everything before <gml:boundedBy>)
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("<gml:boundedBy>")) {
                    bw.write(line);
                    bw.newLine();
                    break;
                }
            }

            // Copy the rest of the file efficiently
            char[] buffer = new char[65536];
            int read;
            while ((read = br.read(buffer)) != -1) {
                bw.write(buffer, 0, read);
            }
        }
        Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Fixed header to match original file (replaced {} header lines)", originalHeader.size());
    }

    /**
     * Applies an action to a Building and all its BuildingParts.
     */
    private static void forBuildingAndParts(Building building, Consumer<AbstractBuilding> action) {
        action.accept(building);
        if (building.isSetBuildingParts()) {
            for (var partProp : building.getBuildingParts()) {
                if (partProp.getObject() != null) {
                    action.accept(partProp.getObject());
                }
            }
        }
    }

    /**
     * Find a LinearRing in the DB polygon by its ring index
     */
    private static LinearRing findRingByIndex(Polygon dbPolygon, int ringIndex) {
        for (LinearRing ring : dbPolygon.getLinearRings()) {
            if (ring.getRingIndex() == ringIndex) {
                return ring;
            }
        }
        return null;
    }
    
    /**
     * Navigates from SolidProperty → Solid → Shell and returns the Shell, or null if any step is missing.
     */
    private static org.xmlobjects.gml.model.geometry.primitives.Shell getShell(
            org.xmlobjects.gml.model.geometry.primitives.SolidProperty solidProp) {
        if (solidProp == null || solidProp.getObject() == null) return null;
        var solid = solidProp.getObject();
        if (!(solid instanceof org.xmlobjects.gml.model.geometry.primitives.Solid s)) return null;
        if (s.getExterior() == null || s.getExterior().getObject() == null) return null;
        return s.getExterior().getObject();
    }

    /**
     * Adds xlink:href references for new polygons to the CompositeSurface/Shell inside a lod2Solid.
     * Uses citygml4j model navigation: SolidProperty → Solid → ShellProperty → Shell → surfaceMembers.
     */
    private static void addNewRefsToSolid(
            org.xmlobjects.gml.model.geometry.primitives.SolidProperty solidProp,
            Map<String, List<String>> newPolyRefsByExistingPoly) {
        var shell = getShell(solidProp);
        if (shell == null) return;
        
        List<SurfaceProperty> toAdd = new ArrayList<>();
        for (SurfaceProperty sp : shell.getSurfaceMembers()) {
            String href = sp.getHref();
            if (href != null) {
                String refId = href.startsWith("#") ? href.substring(1) : href;
                List<String> newIds = newPolyRefsByExistingPoly.get(refId);
                if (newIds != null) {
                    for (String newId : newIds) {
                        SurfaceProperty newRef = new SurfaceProperty();
                        newRef.setHref("#" + newId);
                        toAdd.add(newRef);
                    }
                }
            }
        }
        if (!toAdd.isEmpty()) {
            shell.getSurfaceMembers().addAll(toAdd);
            logger.info("Added {} new xlink:href refs to CompositeSurface/Shell", toAdd.size());
        }
    }
    
    /**
     * Navigate up the parent hierarchy from a GML Polygon to find the parent MultiSurface.
     * Used to add new polygons (from Splitted) as siblings.
     */
    private static MultiSurface findParentMultiSurface(
            org.xmlobjects.gml.model.geometry.primitives.Polygon polygon) {
        Object current = polygon;
        int maxDepth = 10;
        while (current instanceof Child child && maxDepth-- > 0) {
            current = child.getParent();
            if (current instanceof MultiSurface ms) {
                return ms;
            }
        }
        return null;
    }
    
    /**
     * Creates a new GML Polygon from DB data and adds it to the MultiSurface.
     * Used for polygons created by splitting (log contains "NewPolygon").
     * Creates the polygon with its exterior ring (and interior rings if present).
     */
    private static void addNewPolygonToMultiSurface(MultiSurface multiSurface, Polygon dbPolygon) {
        // Create new GML Polygon
        var newGmlPolygon = new org.xmlobjects.gml.model.geometry.primitives.Polygon();
        newGmlPolygon.setId(dbPolygon.getPolygonIdGml());
        
        // Create exterior ring (index 0)
        LinearRing dbExterior = findRingByIndex(dbPolygon, 0);
        if (dbExterior != null && dbExterior.isValid()) {
            var newRing = createGmlLinearRing(dbExterior);
            newGmlPolygon.setExterior(new AbstractRingProperty(newRing));
        }
        
        // Create interior rings (if any)
        for (LinearRing dbRing : dbPolygon.getLinearRings()) {
            if (dbRing.getRingIndex() > 0 && dbRing.isValid()) {
                var interiorRing = createGmlLinearRing(dbRing);
                newGmlPolygon.getInterior().add(new AbstractRingProperty(interiorRing));
            }
        }
        
        // Add as new surfaceMember to MultiSurface
        multiSurface.getSurfaceMember().add(new SurfaceProperty(newGmlPolygon));
    }
    
    /**
     * Creates a new GML LinearRing from DB LinearRing data with posList and srsDimension=3.
     */
    private static org.xmlobjects.gml.model.geometry.primitives.LinearRing createGmlLinearRing(LinearRing dbRing) {
        DirectPositionList posList = new DirectPositionList(toCoordList(dbRing.getPosListAsArray()));
        posList.setSrsDimension(3);
        return new org.xmlobjects.gml.model.geometry.primitives.LinearRing(posList);
    }
    
    /**
     * Adds Log information from the DB polygon (and its LinearRings) to the parent GML surface.
     * Collects Log entries from polygon level, ring level, and surface level.
     * Surface-level log is only written once per surface (tracked via writtenSurfaceLogs).
     */
    private static void addLogToParentSurface(
            AbstractCityObject parentSurface,
            Polygon dbPolygon,
            Surface dbSurface,
            Set<String> writtenSurfaceLogs) {
        
        Map<String, Object> logAttrs = new LinkedHashMap<>();
        
        // Polygon-level Log
        if (dbPolygon.getLog() != null && !dbPolygon.getLog().isEmpty()) {
            logAttrs.put("Log_" + dbPolygon.getPolygonIdGml(), dbPolygon.getLog());
        }
        
        // LinearRing-level Logs
        for (LinearRing ring : dbPolygon.getLinearRings()) {
            if (ring.getLog() != null && !ring.getLog().isEmpty()) {
                String ringName = ring.getRingIndex() == 0 ? "exterior" : "interior_" + ring.getRingIndex();
                logAttrs.put("Log_" + dbPolygon.getPolygonIdGml() + "_" + ringName, ring.getLog());
            }
        }
        
        // Surface-level Log (only once per surface)
        if (dbSurface != null && dbSurface.getLog() != null && !dbSurface.getLog().isEmpty()) {
            String surfaceLogKey = "Log_Surface_" + (dbSurface.getSurfaceIdGml() != null ? dbSurface.getSurfaceIdGml() : dbSurface.getId());
            if (writtenSurfaceLogs.add(surfaceLogKey)) {
                logAttrs.put(surfaceLogKey, dbSurface.getLog());
            }
        }
        
        if (!logAttrs.isEmpty()) {
            addGenericAttributes(parentSurface, logAttrs);
        }
    }
    
    /**
     * Collects all Surface/Polygon/Ring logs from an invalid building into a flat attribute map.
     * Used when a Building has isValid=0 — geometry stays unchanged, but logs must be preserved.
     */
    private static void collectInvalidBuildingLogs(
            de.mpsc.sql2gml.legacy.model.Building dbBuilding, Map<String, Object> attrs) {
        for (BuildingPart dbPart : dbBuilding.getBuildingParts()) {
            for (Surface surface : dbPart.getSurfaces()) {
                if (surface.getLog() != null && !surface.getLog().isEmpty()) {
                    String key = "Log_Surface_" + (surface.getSurfaceIdGml() != null 
                        ? surface.getSurfaceIdGml() : surface.getId());
                    attrs.put(key, surface.getLog());
                }
                for (Polygon poly : surface.getPolygons()) {
                    if (poly.getLog() != null && !poly.getLog().isEmpty()) {
                        attrs.put("Log_" + poly.getPolygonIdGml(), poly.getLog());
                    }
                    for (LinearRing ring : poly.getLinearRings()) {
                        if (ring.getLog() != null && !ring.getLog().isEmpty()) {
                            String ringName = ring.getRingIndex() == 0 
                                ? "exterior" : "interior_" + ring.getRingIndex();
                            attrs.put("Log_" + poly.getPolygonIdGml() + "_" + ringName, ring.getLog());
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Navigate up the parent hierarchy to find the BoundarySurface
     * (WallSurface, RoofSurface, GroundSurface, etc.)
     */
    private static AbstractCityObject findParentSurface(org.xmlobjects.gml.model.geometry.primitives.Polygon polygon) {
        Object current = polygon;
        int maxDepth = 10;
        while (current instanceof Child child && maxDepth-- > 0) {
            current = child.getParent();
            if (current instanceof AbstractCityObject cityObject
                    && BOUNDARY_SURFACE_TYPES.contains(current.getClass().getSimpleName())) {
                return cityObject;
            }
        }
        return null;
    }
    
    /**
     * Updates coordinates in a GML LinearRing with data from database
     */
    private static void updateLinearRingCoordinates(
            org.xmlobjects.gml.model.geometry.primitives.LinearRing gmlRing, 
            LinearRing dbRing) {
        if (gmlRing.getControlPoints() == null || gmlRing.getControlPoints().getPosList() == null) {
            return;
        }
        double[] dbCoords = dbRing.getPosListAsArray();
        if (dbCoords.length > 0) {
            gmlRing.getControlPoints().getPosList().setValue(toCoordList(dbCoords));
        }
    }

    private static List<Double> toCoordList(double[] coords) {
        List<Double> list = new ArrayList<>(coords.length);
        for (double c : coords) {
            list.add(c);
        }
        return list;
    }

    /**
     * Adds or updates generic string attributes on a CityObject.
     * If an attribute with the same name already exists, its value is updated.
     */
    private static void addGenericAttributes(AbstractCityObject target, Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        List<AbstractGenericAttributeProperty> genericAttributes = target.getGenericAttributes();
        
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue().toString();
            
            // Check if attribute with this name already exists
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
    
    /**
     * Removes surfaces with IDs in invalidSurfaceIds from a Building/BuildingPart's boundedBy list.
     * Before removal, collects all polygon GML IDs from the surface for later xlink cleanup.
     */
    private static void removeSurfacesFromBuilding(
            AbstractBuilding building,
            Map<String, String> invalidSurfaceIds,
            Set<String> removedPolygonIdsForXlink,
            ProcessingStats stats) {
        Iterator<AbstractSpaceBoundaryProperty> it = building.getBoundaries().iterator();
        while (it.hasNext()) {
            AbstractSpaceBoundaryProperty prop = it.next();
            if (prop.getObject() != null) {
                String surfId = ((org.xmlobjects.gml.model.base.AbstractGML) prop.getObject()).getId();
                if (surfId != null && invalidSurfaceIds.containsKey(surfId)) {
                    // Collect polygon IDs from this surface for xlink removal
                    prop.getObject().accept(new ObjectWalker() {
                        @Override
                        public void visit(org.xmlobjects.gml.model.geometry.primitives.Polygon polygon) {
                            if (polygon.getId() != null) {
                                removedPolygonIdsForXlink.add(polygon.getId());
                            }
                            super.visit(polygon);
                        }
                    });
                    it.remove();
                    stats.removedSurfaces++;
                    logger.info("Removed surface {} ({})", surfId, invalidSurfaceIds.get(surfId));
                }
            }
        }
    }
    
    /**
     * Removes xlink:href references from Shell that point to removed polygon IDs.
     */
    private static void removeRefsFromSolid(
            org.xmlobjects.gml.model.geometry.primitives.SolidProperty solidProp,
            Set<String> removedPolygonIds) {
        var shell = getShell(solidProp);
        if (shell == null) return;
        
        int removed = 0;
        Iterator<SurfaceProperty> it = shell.getSurfaceMembers().iterator();
        while (it.hasNext()) {
            SurfaceProperty sp = it.next();
            String href = sp.getHref();
            if (href != null) {
                String refId = href.startsWith("#") ? href.substring(1) : href;
                if (removedPolygonIds.contains(refId)) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            logger.info("Removed {} xlink:href refs from CompositeSurface/Shell", removed);
        }
    }
    
    /**
     * Removes BoundarySurfaces that have become empty (MultiSurface has 0 surfaceMembers)
     * after individual polygon removal. This happens when all polygons of a surface were
     * individually marked isValid=0 but the surface itself was not flagged in the DB.
     * Also collects polygon IDs for xlink cleanup (though they should already be collected).
     */
    private static void removeEmptySurfaces(
            AbstractBuilding building,
            Set<String> removedPolygonIdsForXlink,
            ProcessingStats stats,
            Map<String, List<String>> removalLogsByBuilding) {
        String buildingId = ((AbstractGML) building).getId();
        Iterator<AbstractSpaceBoundaryProperty> it = building.getBoundaries().iterator();
        while (it.hasNext()) {
            AbstractSpaceBoundaryProperty prop = it.next();
            if (prop.getObject() == null) continue;
            
            String surfId = ((AbstractGML) prop.getObject()).getId();
            
            // Check if this BoundarySurface has a MultiSurface with 0 polygons
            boolean isEmpty = false;
            if (prop.getObject() instanceof AbstractCityObject cityObj) {
                // Walk to find the MultiSurface
                final boolean[] foundNonEmpty = {false};
                final boolean[] foundMultiSurface = {false};
                cityObj.accept(new ObjectWalker() {
                    @Override
                    public void visit(MultiSurface ms) {
                        foundMultiSurface[0] = true;
                        if (!ms.getSurfaceMember().isEmpty()) {
                            foundNonEmpty[0] = true;
                        }
                        // Don't descend further
                    }
                });
                // Surface is empty if it has a MultiSurface but no surfaceMembers
                isEmpty = foundMultiSurface[0] && !foundNonEmpty[0];
            }
            
            if (isEmpty) {
                it.remove();
                stats.removedSurfaces++;
                logger.info("Removed empty surface {} (all polygons were individually removed)", surfId);
                
                // Add removal log
                if (buildingId != null) {
                    removalLogsByBuilding
                        .computeIfAbsent(buildingId, k -> new ArrayList<>())
                        .add("Surface " + surfId + " entfernt: alle Polygone ungültig (leere MultiSurface)");
                }
            }
        }
    }

    // ── Inner class: Polygon Index (all DB-derived lookup maps) ──────────────

    private static final class ProcessingStats {
        int removedPolygons;
        int removedSurfaces;
    }

    private static class PolygonIndex {
        final Map<String, de.mpsc.sql2gml.legacy.model.Building> buildingsByGmlId = new HashMap<>();
        final Map<String, Polygon> polygonIndex = new HashMap<>();
        final Map<Polygon, Surface> surfaceIndex = new HashMap<>();
        final Set<String> invalidBuildingIds = new HashSet<>();
        final Map<String, List<Polygon>> newPolygonsBySurfaceGmlId = new HashMap<>();
        final Map<String, Surface> surfacesByGmlId = new HashMap<>();
        final Map<String, String> invalidPolygonIds = new LinkedHashMap<>();
        final Map<String, String> invalidSurfaceIds = new LinkedHashMap<>();
        final Map<String, List<String>> removalLogsByBuilding = new LinkedHashMap<>();
        final Map<String, List<String>> newPolyRefsByExistingPoly = new HashMap<>();
        final Set<String> anchorlessSurfaces = new HashSet<>();

        static PolygonIndex buildFromDatabase(List<de.mpsc.sql2gml.legacy.model.Building> dbBuildings) {
            PolygonIndex idx = new PolygonIndex();
            int skippedByBuildingCascade = 0, skippedByPartCascade = 0, skippedBySurfaceCascade = 0;

            for (de.mpsc.sql2gml.legacy.model.Building building : dbBuildings) {
                idx.buildingsByGmlId.put(building.getBuildingIdGml(), building);

                if (!building.isValid()) {
                    idx.invalidBuildingIds.add(building.getBuildingIdGml());
                    skippedByBuildingCascade += building.getBuildingParts().stream()
                        .flatMap(p -> p.getSurfaces().stream())
                        .mapToInt(s -> s.getPolygons().size()).sum();
                    continue;
                }

                for (BuildingPart part : building.getBuildingParts()) {
                    if (!part.isValid()) {
                        skippedByPartCascade += part.getSurfaces().stream()
                            .mapToInt(s -> s.getPolygons().size()).sum();
                        continue;
                    }

                    for (Surface surface : part.getSurfaces()) {
                        if (!surface.isValid()) {
                            skippedBySurfaceCascade += surface.getPolygons().size();
                            if (surface.getSurfaceIdGml() != null) {
                                String reason = surface.getLog() != null ? surface.getLog() : "isValid=0";
                                idx.invalidSurfaceIds.put(surface.getSurfaceIdGml(), reason);
                                idx.removalLogsByBuilding
                                    .computeIfAbsent(building.getBuildingIdGml(), k -> new ArrayList<>())
                                    .add("Surface " + surface.getSurfaceIdGml() + " entfernt: " + reason);
                            }
                            continue;
                        }

                        if (surface.getSurfaceIdGml() != null) {
                            idx.surfacesByGmlId.put(surface.getSurfaceIdGml(), surface);
                        }

                        for (Polygon polygon : surface.getPolygons()) {
                            idx.categorizePolygon(polygon, surface, building.getBuildingIdGml());
                        }
                    }
                }
            }
            logger.info("Created polygon index with {} entries", idx.polygonIndex.size());
            if (!idx.newPolygonsBySurfaceGmlId.isEmpty()) {
                int totalNewPolygons = idx.newPolygonsBySurfaceGmlId.values().stream().mapToInt(List::size).sum();
                logger.info("New polygons to create (Splitted): {} across {} surfaces",
                    totalNewPolygons, idx.newPolygonsBySurfaceGmlId.size());
            }

            // Build map for CompositeSurface reference insertion
            for (Map.Entry<String, List<Polygon>> entry : idx.newPolygonsBySurfaceGmlId.entrySet()) {
                String surfaceGmlId = entry.getKey();
                List<String> newIds = new ArrayList<>();
                for (Polygon p : entry.getValue()) {
                    if (p.getPolygonIdGml() != null) newIds.add(p.getPolygonIdGml());
                }
                if (newIds.isEmpty()) continue;
                for (Map.Entry<Polygon, Surface> se : idx.surfaceIndex.entrySet()) {
                    if (surfaceGmlId.equals(se.getValue().getSurfaceIdGml())) {
                        idx.newPolyRefsByExistingPoly.put(se.getKey().getPolygonIdGml(), newIds);
                        break;
                    }
                }
            }

            // Identify anchor-less surfaces
            for (String surfId : idx.newPolygonsBySurfaceGmlId.keySet()) {
                boolean hasAnchor = false;
                for (Map.Entry<Polygon, Surface> se : idx.surfaceIndex.entrySet()) {
                    if (surfId.equals(se.getValue().getSurfaceIdGml())) {
                        hasAnchor = true;
                        break;
                    }
                }
                if (!hasAnchor) {
                    idx.anchorlessSurfaces.add(surfId);
                }
            }
            if (!idx.anchorlessSurfaces.isEmpty()) {
                int anchorlessNewPolygons = idx.anchorlessSurfaces.stream()
                    .mapToInt(s -> idx.newPolygonsBySurfaceGmlId.getOrDefault(s, List.of()).size()).sum();
                logger.info("Anchor-less Merged surfaces: {} surfaces with {} new polygons (IDs: {})",
                    idx.anchorlessSurfaces.size(), anchorlessNewPolygons, idx.anchorlessSurfaces);
            }

            // Log statistics
            int validCount = 0, invalidCount = 0;
            for (Polygon p : idx.polygonIndex.values()) {
                if (p.isValid()) validCount++; else invalidCount++;
            }
            logger.info("Polygon status: {} valid (update from DB), {} invalid (keep original, add Log)",
                validCount, invalidCount);
            if (!idx.invalidPolygonIds.isEmpty()) {
                logger.info("Polygons to remove from GML (isValid=0): {}", idx.invalidPolygonIds.size());
            }
            if (!idx.invalidSurfaceIds.isEmpty()) {
                logger.info("Surfaces to remove from GML (isValid=0): {}", idx.invalidSurfaceIds.size());
            }
            if (skippedByBuildingCascade + skippedByPartCascade + skippedBySurfaceCascade > 0) {
                logger.info("Skipped by cascade: {} (Building invalid), {} (Part invalid), {} (Surface invalid)",
                    skippedByBuildingCascade, skippedByPartCascade, skippedBySurfaceCascade);
            }
            if (!idx.invalidBuildingIds.isEmpty()) {
                logger.info("Buildings with isValid=0 (keeping original): {}", idx.invalidBuildingIds);
            }

            return idx;
        }

        private void categorizePolygon(Polygon polygon, Surface surface, String buildingGmlId) {
            String polyLog = polygon.getLog() != null ? polygon.getLog() : "";

            if (polyLog.contains("NewPolygon")) {
                if (polygon.isValid() && surface.getSurfaceIdGml() != null) {
                    newPolygonsBySurfaceGmlId
                        .computeIfAbsent(surface.getSurfaceIdGml(), k -> new ArrayList<>())
                        .add(polygon);
                }
                return;
            }

            if (!polygon.isValid()) {
                if (polygon.getPolygonIdGml() == null) return;
                String reason = polyLog.isEmpty() ? "isValid=0" : polyLog;
                invalidPolygonIds.put(polygon.getPolygonIdGml(), reason);
                List<String> buildingLogs = removalLogsByBuilding.computeIfAbsent(buildingGmlId, k -> new ArrayList<>());
                buildingLogs.add("Polygon " + polygon.getPolygonIdGml() + " entfernt: " + reason);
                for (LinearRing ring : polygon.getLinearRings()) {
                    String ringName = ring.getRingIndex() == 0 ? "exterior" : "interior_" + ring.getRingIndex();
                    buildingLogs.add("LinearRing " + polygon.getPolygonIdGml() + "_" + ringName
                        + " entfernt: Polygon " + polygon.getPolygonIdGml() + " nicht mehr vorhanden");
                }
                return;
            }

            // Valid non-split polygon: add to index
            if (polygon.getPolygonIdGml() != null) {
                polygonIndex.put(polygon.getPolygonIdGml(), polygon);
            }
            surfaceIndex.put(polygon, surface);
        }
    }

    // ── Inner class: GML Update Walker (visit logic for Polygon + Building) ──

    private static class GmlUpdateWalker extends ObjectWalker {
        private static final String[] SURFACE_ATTRIBUTE_KEYS = {
            "FACEAREA", "NORMAL_AZI", "NORMAL_H", "Z_Max", "Z_Min", "Z_MAX_ASL", "Z_MIN_ASL"
        };
        private final PolygonIndex idx;
        int updatedPolygons, updatedRings, processedBuildings, visitedPolygons;
        int skippedBuildings, createdPolygons;
        // Set to true while walking a Building that exists in the DB (and is valid).
        // Used to decide whether unmatched polygons should be removed.
        boolean currentBuildingInDb = false;
        final Set<String> writtenSurfaceLogs = new HashSet<>();
        final Set<String> processedSurfacesForNewPolygons = new HashSet<>();
        final List<org.xmlobjects.gml.model.geometry.primitives.Polygon> polygonsToRemove = new ArrayList<>();
        final Set<String> removedPolygonIdsForXlink = new HashSet<>();

        GmlUpdateWalker(PolygonIndex idx) {
            this.idx = idx;
        }

        @Override
        public void visit(org.xmlobjects.gml.model.geometry.primitives.Polygon polygon) {
            visitedPolygons++;
            String gmlId = polygon.getId();
            if (!idx.polygonIndex.containsKey(gmlId)) {
                handleUnmatchedPolygon(polygon, gmlId);
                return;
            }
            handleMatchedPolygon(polygon, gmlId);
        }

        private void handleUnmatchedPolygon(
                org.xmlobjects.gml.model.geometry.primitives.Polygon polygon, String gmlId) {
            // Anchor-less Merged surfaces: use the original polygon as navigation anchor
            // to find the parent MultiSurface and insert the new DB polygons.
            if (gmlId != null && !idx.anchorlessSurfaces.isEmpty()) {
                AbstractCityObject parentSurf = findParentSurface(polygon);
                if (parentSurf != null) {
                    String surfId = ((AbstractGML) parentSurf).getId();
                    if (surfId != null && idx.anchorlessSurfaces.contains(surfId)
                            && !processedSurfacesForNewPolygons.contains(surfId)) {
                        MultiSurface ms = findParentMultiSurface(polygon);
                        if (ms != null) {
                            Surface dbSurface = idx.surfacesByGmlId.get(surfId);
                            List<Polygon> newPolys = idx.newPolygonsBySurfaceGmlId.get(surfId);
                            if (newPolys != null) {
                                List<String> newIds = new ArrayList<>();
                                for (Polygon np : newPolys) {
                                    addNewPolygonToMultiSurface(ms, np);
                                    if (np.getPolygonIdGml() != null) newIds.add(np.getPolygonIdGml());
                                    addLogToParentSurface(parentSurf, np, dbSurface, writtenSurfaceLogs);
                                    createdPolygons++;
                                }
                                processedSurfacesForNewPolygons.add(surfId);
                                if (!newIds.isEmpty()) {
                                    idx.newPolyRefsByExistingPoly.put(gmlId, newIds);
                                }
                                logger.info("Merged surface {}: added {} new polygons (anchor-less, via original polygon {})",
                                    surfId, newPolys.size(), gmlId);
                            }
                        }
                    }
                }
            }
            // Building in DB: remove every unmatched original polygon (includes anchor-less
            // originals, explicitly invalid polygons, and unmatched polygons from old geometry).
            if (currentBuildingInDb) {
                polygonsToRemove.add(polygon);
                return;
            }
            // Building not in DB: only remove polygons explicitly flagged invalid in DB
            if (gmlId != null && idx.invalidPolygonIds.containsKey(gmlId)) {
                polygonsToRemove.add(polygon);
                return;
            }
            super.visit(polygon);
        }

        private void handleMatchedPolygon(
                org.xmlobjects.gml.model.geometry.primitives.Polygon polygon, String gmlId) {
            Polygon dbPolygon = idx.polygonIndex.get(gmlId);
            Surface dbSurface = idx.surfaceIndex.get(dbPolygon);
            AbstractCityObject parentSurface = findParentSurface(polygon);

            if (parentSurface != null) {
                addLogToParentSurface(parentSurface, dbPolygon, dbSurface, writtenSurfaceLogs);
            }

            // Update exterior ring coordinates from DB
            if (polygon.getExterior() != null
                    && polygon.getExterior().getObject() instanceof org.xmlobjects.gml.model.geometry.primitives.LinearRing gmlRing) {
                LinearRing dbRing = findRingByIndex(dbPolygon, 0);
                if (dbRing != null && dbRing.isValid()) {
                    updateLinearRingCoordinates(gmlRing, dbRing);
                    updatedRings++;
                }
            }

            // Update interior ring coordinates from DB
            if (polygon.getInterior() != null) {
                for (int i = 0; i < polygon.getInterior().size(); i++) {
                    if (polygon.getInterior().get(i).getObject() instanceof
                            org.xmlobjects.gml.model.geometry.primitives.LinearRing gmlRing) {
                        LinearRing dbRing = findRingByIndex(dbPolygon, i + 1);
                        if (dbRing != null && dbRing.isValid()) {
                            updateLinearRingCoordinates(gmlRing, dbRing);
                            updatedRings++;
                        }
                    }
                }
            }

            // Update surface attributes
            if (parentSurface != null && dbSurface != null
                    && dbSurface.getAttributes() != null && !dbSurface.getAttributes().isEmpty()) {
                Map<String, Object> surfaceAttrs = new LinkedHashMap<>();
                for (String k : SURFACE_ATTRIBUTE_KEYS) {
                    if (dbSurface.getAttributes().containsKey(k)) {
                        surfaceAttrs.put(k, dbSurface.getAttributes().get(k));
                    }
                }
                if (!surfaceAttrs.isEmpty()) {
                    addGenericAttributes(parentSurface, surfaceAttrs);
                }
            }

            updatedPolygons++;

            // Add new split polygons to the parent MultiSurface (Splitted case)
            if (dbSurface != null && dbSurface.getSurfaceIdGml() != null) {
                String surfaceGmlId = dbSurface.getSurfaceIdGml();
                if (!processedSurfacesForNewPolygons.contains(surfaceGmlId)) {
                    List<Polygon> newPolygons = idx.newPolygonsBySurfaceGmlId.get(surfaceGmlId);
                    if (newPolygons != null && !newPolygons.isEmpty()) {
                        MultiSurface parentMs = findParentMultiSurface(polygon);
                        if (parentMs != null) {
                            for (Polygon newPoly : newPolygons) {
                                addNewPolygonToMultiSurface(parentMs, newPoly);
                                if (parentSurface != null) {
                                    addLogToParentSurface(parentSurface, newPoly, dbSurface, writtenSurfaceLogs);
                                }
                                createdPolygons++;
                            }
                        }
                        processedSurfacesForNewPolygons.add(surfaceGmlId);
                    }
                }
            }

            super.visit(polygon);
        }

        @Override
        public void visit(Building building) {
            processedBuildings++;
            String buildingId = building.getId();

            // Track whether this building exists in the DB (and is valid).
            // Reset flag at the start of every building to prevent cross-building leaking.
            currentBuildingInDb = buildingId != null
                && idx.buildingsByGmlId.containsKey(buildingId)
                && !idx.invalidBuildingIds.contains(buildingId);

            Map<String, Object> trackingAttrs = new LinkedHashMap<>();

            if (buildingId != null && idx.buildingsByGmlId.containsKey(buildingId)) {
                de.mpsc.sql2gml.legacy.model.Building dbBuilding = idx.buildingsByGmlId.get(buildingId);

                if (dbBuilding.getLog() != null && !dbBuilding.getLog().isEmpty()) {
                    trackingAttrs.put("Log", dbBuilding.getLog());
                }

                if (dbBuilding.getAttributes() != null && !dbBuilding.getAttributes().isEmpty()) {
                    for (Map.Entry<String, Object> entry : dbBuilding.getAttributes().entrySet()) {
                        trackingAttrs.put(entry.getKey(), entry.getValue());
                    }
                }

                for (BuildingPart dbPart : dbBuilding.getBuildingParts()) {
                    if (dbPart.getLog() != null && !dbPart.getLog().isEmpty()) {
                        String attrName = dbPart.getPartIdGml() != null
                            ? "Log_" + dbPart.getPartIdGml()
                            : "Log_Part_" + dbPart.getId();
                        trackingAttrs.put(attrName, dbPart.getLog());
                    }
                }

                if (idx.invalidBuildingIds.contains(buildingId)) {
                    collectInvalidBuildingLogs(dbBuilding, trackingAttrs);
                }
            }

            if (buildingId != null && idx.removalLogsByBuilding.containsKey(buildingId)) {
                List<String> removalLogs = idx.removalLogsByBuilding.get(buildingId);
                for (int i = 0; i < removalLogs.size(); i++) {
                    trackingAttrs.put("Removal_" + (i + 1), removalLogs.get(i));
                }
            }

            if (!trackingAttrs.isEmpty()) {
                addGenericAttributes(building, trackingAttrs);
            }

            if (buildingId != null && idx.invalidBuildingIds.contains(buildingId)) {
                skippedBuildings++;
                currentBuildingInDb = false;  // Don't remove polygons from invalid buildings
                return;
            }

            super.visit(building);

            // Reset after processing so stale flag cannot affect subsequent features
            currentBuildingInDb = false;
        }
    }
    
}
