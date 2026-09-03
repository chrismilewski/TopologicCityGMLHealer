package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.DgmLoader;
import de.mpsc.lod2tolod3.util.DgmProvider;
import de.mpsc.lod2tolod3.util.JunctionConformingUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.Building;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Haupt-Pipeline: Führt alle Schritte der LoD2→LoD3-Konvertierung aus.
 *
 * Single-Pass-Architektur: Jede Eingabedatei wird einmal gelesen, alle
 * Verarbeitungsschritte werden pro Gebaeude im Speicher ausgefuehrt,
 * und das Ergebnis wird einmal geschrieben. Keine Zwischendateien.
 *
 * Schritte:
 *   1. LoD2 → LoD3 Geometrie-Konvertierung
 *   2. Keller hinzufügen (basierend auf Baukörpermodulen)
 *   3. Geschosse unterteilen (BuildingStorey)
 *   4. Türen hinzufügen (DoorGenerator)
 *   5a. Balkone, fuehrender Ga-Lauf (BalconyGenerator Phase 1) — unabhaengig ueber HDistWaGa
 *       verankert, reserviert die belegte Wandspanne fuer Schritt 5b
 *   5b. Fenster hinzufügen (WindowGenerator) — respektiert die Phase-1-Reservierung
 *   5c. Balkone, restliche Ga-Token eines Musters (BalconyGenerator Phase 2) — nur falls GaPa
 *       nach dem fuehrenden Lauf noch weitere Ga-Token enthaelt (z.B. "GaWiGaWi")
 *   5d. Dachfenster (RoofWindowGenerator) — unabhaengig von Wand-/Tuer-/Fenster-/Balkonzustand,
 *       platziert Dachflaechenfenster auf geneigten RoofSurface-Flaechen anhand RO.window
 *   4d. Fallback-Tueren (DoorGenerator.processFallbackDoors) — laeuft NACH Fenstern/Dachfenstern
 *       (anders als Schritt 4), ergaenzt genau eine Tuer fuer Gebaeude mit Fenstern aber
 *       DoorCount=0 auf jeder Wand (bestaetigte Quelldatenluecke, siehe Doku.md)
 *   6. Junction-Conforming (T-Naht-Vertices, formneutral)
 *
 * Usage (Einzeldatei):
 *   java -jar lod2-zu-lod3.jar <input.gml> [jsonDir] [outputDir] [dgmPath]
 *   → schreibt genau eine Ausgabedatei nach outputDir, LoD2→LoD3 umbenannt
 *     (z.B. LoD2_..._BuildingPreferences.gml → LoD3_....gml).
 *
 * Usage (Batch-Modus, ganzer Ordner mit Kacheln):
 *   java -jar lod2-zu-lod3.jar <inputFolder> [jsonDir] [outputParentDir] [dgmPath]
 *   → wird automatisch erkannt, wenn args[0] ein Verzeichnis ist (kein separates Flag noetig,
 *     siehe HealedReplaceWorkflow in sql2gml_neu fuer dasselbe Muster). Verarbeitet ALLE
 *     .gml-Dateien im Ordner NACHEINANDER (sequenziell — ModuleParametersLoader cached intern
 *     in einer einfachen HashMap, nicht thread-sicher fuer parallele Kachel-Verarbeitung mit
 *     einer gemeinsamen Instanz). Legt unter outputParentDir einen neuen Unterordner an, dessen
 *     Name wie bei den Dateien LoD2→LoD3 umbenannt ist (z.B. CityGML_LoD2_260813 →
 *     CityGML_LoD3_260813; enthaelt der Ordnername kein "LoD2", wird "_LoD3" angehaengt).
 *
 * Oder einzelne Schritte:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.Lod2ToLod3Promoter <input.gml> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.BasementGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.StoreyGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.DoorGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.WindowGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.BalconyGenerator <input.gml> <jsonDir> [output.gml]
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.RoofWindowGenerator <input.gml> <jsonDir> [output.gml]
 */
public class Lod2ToLod3Pipeline {
    private static final Logger log = LoggerFactory.getLogger(Lod2ToLod3Pipeline.class);

    private static final String DEFAULT_INPUT = "sqltest/LoD2_33_416_5656_2_SN_BuildingPreferences.gml";
    private static final String DEFAULT_JSON = "sqltest/Baukörpermodule_json";
    private static final String DEFAULT_OUTPUT = "sqltest/output";

    public static void main(String[] args) {
        try {
            Path workspaceRoot = Paths.get("").toAbsolutePath();
            Path inputPath = args.length >= 1 ? Paths.get(args[0]) : workspaceRoot.resolve(DEFAULT_INPUT);
            Path jsonDir = args.length >= 2 ? Paths.get(args[1]) : workspaceRoot.resolve(DEFAULT_JSON);
            Path outputArg = args.length >= 3 ? Paths.get(args[2]) : workspaceRoot.resolve(DEFAULT_OUTPUT);
            Path dgmPath = args.length >= 4 ? Paths.get(args[3]) : null;

            DgmProvider dgm = loadDgm(dgmPath);

            if (Files.isDirectory(inputPath)) {
                runBatch(inputPath, jsonDir, outputArg, dgm);
            } else {
                Files.createDirectories(outputArg);
                Path outputFile = outputArg.resolve(lod3FileName(inputPath.getFileName().toString()));
                log.info("============================================================");
                log.info("  LoD2 -> LoD3 Konvertierungs-Pipeline (Single-Pass)      ");
                log.info("============================================================");
                log.info("Input:  {}", inputPath);
                log.info("JSON:   {}", jsonDir);
                log.info("Output: {}", outputFile);
                log.info("DGM:    {}", dgm != null ? dgmPath : "(kein DGM — flache TIC)");

                TileResult r = processSingleFile(inputPath, outputFile, jsonDir, dgm);
                logTileSummary(r, "                  Pipeline abgeschlossen                    ", outputFile);
            }
        } catch (Exception e) {
            log.error("Fehler in der Pipeline: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Batch-Modus: ganzer Ordner
    // ─────────────────────────────────────────────────────────────────────────

    private static void runBatch(Path inputFolder, Path jsonDir, Path outputParent, DgmProvider dgm)
            throws IOException {
        List<Path> gmlFiles;
        try (var stream = Files.list(inputFolder)) {
            gmlFiles = stream
                    .filter(p -> Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".gml"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (gmlFiles.isEmpty()) {
            log.warn("Keine .gml-Dateien in {} gefunden — nichts zu tun", inputFolder);
            return;
        }

        String outputFolderName = lod3FolderName(inputFolder.getFileName().toString());
        Path outputFolder = outputParent.resolve(outputFolderName);
        Files.createDirectories(outputFolder);

        log.info("============================================================");
        log.info("  LoD2 -> LoD3 Batch-Modus: {} Kacheln (sequenziell)", gmlFiles.size());
        log.info("============================================================");
        log.info("Input-Ordner:  {}", inputFolder);
        log.info("Output-Ordner: {}", outputFolder);
        log.info("JSON:          {}", jsonDir);
        log.info("DGM:           {}", dgm != null ? dgm.describe() : "(kein DGM — flache TIC)");
        log.info("");

        long batchStart = System.currentTimeMillis();
        List<TileResult> results = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (int i = 0; i < gmlFiles.size(); i++) {
            Path inputFile = gmlFiles.get(i);
            Path outputFile = outputFolder.resolve(lod3FileName(inputFile.getFileName().toString()));
            String progress = String.format("[%d/%d]", i + 1, gmlFiles.size());
            log.info("{} Verarbeite {} ...", progress, inputFile.getFileName());
            try {
                TileResult r = processSingleFile(inputFile, outputFile, jsonDir, dgm);
                results.add(r);
                log.info("{} fertig: {} Gebaeude, {}.{} s -> {}",
                        progress, r.promStats.buildingsProcessed,
                        r.elapsedMs / 1000, String.format("%03d", r.elapsedMs % 1000),
                        outputFile.getFileName());
            } catch (Exception e) {
                log.error("{} FEHLER bei {}: {}", progress, inputFile.getFileName(), e.getMessage(), e);
                failed.add(inputFile.getFileName().toString());
            }
        }

        long batchElapsed = System.currentTimeMillis() - batchStart;

        log.info("");
        log.info("============================================================");
        log.info("  Batch abgeschlossen: {} von {} Kacheln erfolgreich",
                results.size(), gmlFiles.size());
        log.info("============================================================");
        log.info("Gesamtzeit: {}.{} s", batchElapsed / 1000, String.format("%03d", batchElapsed % 1000));
        if (!failed.isEmpty()) {
            log.warn("Fehlgeschlagene Kacheln ({}): {}", failed.size(), failed);
        }
        if (!results.isEmpty()) {
            logTileSummary(TileResult.sum(results),
                    "        Batch-Gesamtstatistik (" + results.size() + " Kacheln)          ", null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Kernlogik: eine Datei verarbeiten (Einzeldatei- UND Batch-Modus)
    // ─────────────────────────────────────────────────────────────────────────

    /** Buendelt alle Statistik-Objekte + Laufzeit eines einzelnen Kachel-Durchlaufs. */
    private static final class TileResult {
        long elapsedMs;
        final Lod2ToLod3Promoter.PromotionStats promStats = new Lod2ToLod3Promoter.PromotionStats();
        final BasementGenerator.GenerationStats basementStats = new BasementGenerator.GenerationStats();
        final StoreyGenerator.GenerationStats storeyStats = new StoreyGenerator.GenerationStats();
        final DoorGenerator.GenerationStats doorStats = new DoorGenerator.GenerationStats();
        final WindowGenerator.GenerationStats windowStats = new WindowGenerator.GenerationStats();
        final BalconyGenerator.GenerationStats balconyStats = new BalconyGenerator.GenerationStats();
        final RoofWindowGenerator.GenerationStats roofWindowStats = new RoofWindowGenerator.GenerationStats();
        int pinchSplits;

        TileResult() {
        }

        TileResult(long elapsedMs) {
            this.elapsedMs = elapsedMs;
        }

        /** Aufsummierte Kennzahlen ueber alle Kacheln eines Batch-Laufs (fuer den Abschlussbericht). */
        static TileResult sum(List<TileResult> all) {
            long totalElapsed = all.stream().mapToLong(r -> r.elapsedMs).sum();
            TileResult agg = new TileResult(totalElapsed);
            for (TileResult r : all) {
                agg.promStats.buildingsProcessed += r.promStats.buildingsProcessed;
                agg.promStats.geometriesPromoted += r.promStats.geometriesPromoted;
                agg.promStats.namesRenamed += r.promStats.namesRenamed;

                agg.basementStats.basementsAdded += r.basementStats.basementsAdded;
                agg.basementStats.groundSurfacesReplaced += r.basementStats.groundSurfacesReplaced;
                agg.basementStats.ticsCreated += r.basementStats.ticsCreated;

                agg.storeyStats.storeysCreated += r.storeyStats.storeysCreated;
                agg.storeyStats.wallSegmentsCreated += r.storeyStats.wallSegmentsCreated;
                agg.storeyStats.floorsCreated += r.storeyStats.floorsCreated;
                agg.storeyStats.ceilingsCreated += r.storeyStats.ceilingsCreated;

                agg.doorStats.doorsCreated += r.doorStats.doorsCreated;
                agg.doorStats.wallsModified += r.doorStats.wallsModified;
                agg.doorStats.wallsSkipped += r.doorStats.wallsSkipped;
                agg.doorStats.fallbackDoorsCreated += r.doorStats.fallbackDoorsCreated;
                agg.doorStats.windowsRemovedForFallbackDoor += r.doorStats.windowsRemovedForFallbackDoor;

                agg.windowStats.windowsCreated += r.windowStats.windowsCreated;
                agg.windowStats.wallsWithWindows += r.windowStats.wallsWithWindows;
                agg.windowStats.wallsSkipped += r.windowStats.wallsSkipped;
                agg.windowStats.gableWindowsDropped += r.windowStats.gableWindowsDropped;
                agg.windowStats.windowsDroppedCoveredByPart += r.windowStats.windowsDroppedCoveredByPart;
                agg.windowStats.windowsNudgedDown += r.windowStats.windowsNudgedDown;
                agg.windowStats.wwrWarnings += r.windowStats.wwrWarnings;
                // toSummary()/toGeschossSummary() lesen NICHT die obigen Summen-Felder, sondern
                // die EnumMap/TreeMap direkt — ohne diese beiden Merges bleiben "Skip-Gruende"
                // und "Per-Geschoss" im Batch-Gesamtbericht faelschlich leer/null.
                r.windowStats.skips.forEach((reason, count) ->
                        agg.windowStats.skips.merge(reason, count, Integer::sum));
                r.windowStats.perGeschoss.forEach((geschoss, counts) -> {
                    // computeIfAbsent statt merge(): merge() wuerde bei einem neuen Schluessel
                    // das ORIGINAL-Array aus r (nicht kopiert) uebernehmen — spaeteres
                    // Aufaddieren eines weiteren Tiles wuerde dann versehentlich das Original
                    // dieses einen Tiles mitveraendern.
                    int[] a = agg.windowStats.perGeschoss.computeIfAbsent(geschoss, k -> new int[2]);
                    a[0] += counts[0];
                    a[1] += counts[1];
                });

                agg.balconyStats.balconiesCreated += r.balconyStats.balconiesCreated;
                agg.balconyStats.wallsWithBalconies += r.balconyStats.wallsWithBalconies;
                agg.balconyStats.windowsRemovedForBalcony += r.balconyStats.windowsRemovedForBalcony;
                agg.balconyStats.wallsSkipped += r.balconyStats.wallsSkipped;

                agg.roofWindowStats.roofWindowsCreated += r.roofWindowStats.roofWindowsCreated;
                agg.roofWindowStats.roofsWithWindows += r.roofWindowStats.roofsWithWindows;
                agg.roofWindowStats.roofsSkipped += r.roofWindowStats.roofsSkipped;
                agg.roofWindowStats.gableRoofWindowsDropped += r.roofWindowStats.gableRoofWindowsDropped;
                agg.roofWindowStats.wwrWarnings += r.roofWindowStats.wwrWarnings;
                r.roofWindowStats.skips.forEach((reason, count) ->
                        agg.roofWindowStats.skips.merge(reason, count, Integer::sum));

                agg.pinchSplits += r.pinchSplits;
            }
            return agg;
        }
    }

    /** Verarbeitet genau eine GML-Datei durch die komplette Pipeline (alle Schritte 1–7). */
    private static TileResult processSingleFile(
            Path inputFile, Path outputFile, Path jsonDir, DgmProvider dgm) throws Exception {

        // Verarbeitungs-Komponenten initialisieren — bewusst PRO DATEI neu, nicht ueber Kacheln
        // hinweg geteilt: ModuleParametersLoader cached intern in einer einfachen (nicht
        // thread-sicheren, aber auch bei rein sequenzieller Wiederverwendung unnoetig
        // zustandsbehafteten) HashMap; frische Instanzen pro Kachel sind die einfachste Garantie
        // gegen jede Art von Kachel-uebergreifender Seiteneffekt-Kontamination.
        Lod2ToLod3Promoter promoter = new Lod2ToLod3Promoter();

        BasementGenerator basementGen = new BasementGenerator();
        if (dgm != null) {
            basementGen.setDgm(dgm);
        }
        ModuleParametersLoader paramLoader = new ModuleParametersLoader(jsonDir);

        StoreyGenerator storeyGen = new StoreyGenerator();
        DoorGenerator doorGen = new DoorGenerator();
        WindowGenerator windowGen = new WindowGenerator();
        BalconyGenerator balconyGen = new BalconyGenerator();
        RoofWindowGenerator roofWindowGen = new RoofWindowGenerator();

        TileResult result = new TileResult(); // elapsedMs wird unten gesetzt, sobald bekannt
        var promStats = result.promStats;
        var basementStats = result.basementStats;
        var storeyStats = result.storeyStats;
        var doorStats = result.doorStats;
        var windowStats = result.windowStats;
        var balconyStats = result.balconyStats;
        var roofWindowStats = result.roofWindowStats;

        // Generator-Schritte registrieren (Schritte 2–5c). Balkone laufen zweiphasig um
        // Fenster herum: Phase 1 platziert den fuehrenden Ga-Lauf unabhaengig VOR den
        // Fenstern (reserviert deren Wandspanne), Phase 2 platziert restliche Ga-Token
        // eines Musters NACH den Fenstern gegen die dann echten Fensterpositionen (siehe
        // BalconyGenerator-Javadoc und Doku.md, Abschnitt "Schritt 6: Balkon-Generator").
        record PipelineStep(String label, Consumer<Building> action) {}
        List<PipelineStep> buildingSteps = List.of(
            new PipelineStep("Keller",         b -> { basementStats.buildingsProcessed++; basementGen.processBuilding(b, paramLoader, basementStats); }),
            new PipelineStep("Geschosse",      b -> { storeyStats.buildingsProcessed++;   storeyGen.processBuilding(b, paramLoader, storeyStats); }),
            new PipelineStep("Tueren",         b -> { doorStats.buildingsProcessed++;     doorGen.processBuilding(b, paramLoader, doorStats); }),
            new PipelineStep("Balkone-Phase1", b -> { balconyStats.buildingsProcessed++;  balconyGen.processBuildingLeading(b, paramLoader, balconyStats); }),
            new PipelineStep("Fenster",        b -> { windowStats.buildingsProcessed++;   windowGen.processBuilding(b, paramLoader, windowStats); }),
            new PipelineStep("Balkone-Phase2", b -> { balconyGen.processBuildingRemaining(b, paramLoader, balconyStats); }),
            new PipelineStep("Dachfenster",    b -> { roofWindowStats.buildingsProcessed++; roofWindowGen.processBuilding(b, paramLoader, roofWindowStats); }),
            new PipelineStep("Fallback-Tueren", b -> doorGen.processFallbackDoors(b, paramLoader, doorStats))
        );

        // ==================== Single-Pass Verarbeitung ====================
        // Lese-/Schreib-Zyklus (Header-Envelope, Chunk-Reader/-Writer) kommt aus der
        // gemeinsamen CityGmlUtils.processGmlFile() — identisch zu allen Standalone-
        // Generatoren, keine zweite Kopie dieses Boilerplates mehr.
        long startTime = System.currentTimeMillis();

        CityGmlUtils.processGmlFile(inputFile, outputFile, building -> {
            // Schritt 1: LoD2 -> LoD3 Hochstufung
            promStats.buildingsProcessed++;
            var promResult = promoter.promoteBuildingToLod3(building);
            promStats.geometriesPromoted += promResult.promotedCount;
            promStats.promotedTypes.addAll(promResult.promotedTypes);
            promStats.namesRenamed += promoter.renameLod2NamesToLod3(building);
            promoter.addPromotionMetadata(building, promResult);

            // Schritte 2–5c: registrierte Generator-Schritte
            for (var step : buildingSteps) {
                step.action().accept(building);
            }

            // Schritt 6: Junction-Conforming — fuegt fehlende T-Naht-Vertices auf
            // Huellenkanten ein. STRENG formneutral (an echter Geometrie gemessen: kein
            // bestehender Vertex wird bewegt; es werden nur Punkte auf bestehende Kanten
            // eingefuegt). Naeht ausschliesslich UNSERE eigenen, unabhaengig erzeugten
            // Zusatzflaechen (Geschosse, Keller, Boden-/Deckenslabs) an den Naehten zusammen
            // → gegen GE_S_NOT_CLOSED / NON_MANIFOLD.
            //
            // Vertex-Welding wurde bewusst ENTFERNT: es verschob ~0,3% der Vertices um bis
            // zu 5 mm (echtes Geometrie-Reshaping). Das Schliessen solcher mm-Naehte
            // uebernimmt der nachgelagerte Healer, nicht dieses LoD3-Update.
            JunctionConformingUtils.conformJunctions(building, 0.005);

            // Schritt 7: Pinch-Point-Aufspaltung — spaltet Ringe, die durch die T-Naht-
            // Einfuegung oben an einer Stelle einen "Pinch Point" (denselben Punkt an zwei
            // nicht benachbarten Stellen) bekommen haben, in zwei einfache Teilringe auf,
            // statt (wie mehrfach versucht) die Einfuegung wegzulassen. Siehe Doku.md
            // "T-Naht-Splitter" und JunctionConformingUtils.splitSelfTouchingRings-Javadoc.
            result.pinchSplits += JunctionConformingUtils.splitSelfTouchingRings(building);
        });

        result.elapsedMs = System.currentTimeMillis() - startTime;
        return result;
    }

    private static String lod3FileName(String originalFileName) {
        String baseName = originalFileName;
        if (baseName.toLowerCase().endsWith(".gml")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        return baseName.replace("LoD2", "LoD3").replaceAll("_BuildingPreferences", "") + ".gml";
    }

    /** Wie {@link #lod3FileName}, fuer den Batch-Ausgabeordner: enthaelt der Ordnername kein
     * "LoD2", wird "_LoD3" angehaengt statt eine Ersetzung zu erzwingen, die daneben ginge. */
    private static String lod3FolderName(String originalFolderName) {
        if (originalFolderName.contains("LoD2")) {
            return originalFolderName.replace("LoD2", "LoD3");
        }
        return originalFolderName + "_LoD3";
    }

    private static DgmProvider loadDgm(Path dgmPath) throws Exception {
        if (dgmPath == null) return null;
        if (!Files.exists(dgmPath)) {
            log.warn("DGM-Pfad nicht gefunden: {} — verwende flache TIC bei H_DGM", dgmPath);
            return null;
        }
        DgmProvider dgm = DgmLoader.load(dgmPath);
        log.info("DGM geladen: {} — {}", dgmPath, dgm.describe());
        return dgm;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Zusammenfassung
    // ─────────────────────────────────────────────────────────────────────────

    private static void logTileSummary(TileResult r, String header, Path outputFile) {
        log.info("");
        log.info("============================================================");
        log.info("{}", header);
        log.info("============================================================");
        log.info("Verarbeitungszeit: {}.{} s", r.elapsedMs / 1000, String.format("%03d", r.elapsedMs % 1000));
        if (outputFile != null) {
            log.info("Ausgabedatei: {}", outputFile);
        }
        log.info("");
        log.info("Schritt 1 — Promotion:  {} Gebaeude, {} Geometrien hochgestuft, {} Namen",
                r.promStats.buildingsProcessed, r.promStats.geometriesPromoted, r.promStats.namesRenamed);
        log.info("Schritt 2 — Keller:     {} Keller, {} GS ersetzt, {} TICs",
                r.basementStats.basementsAdded, r.basementStats.groundSurfacesReplaced,
                r.basementStats.ticsCreated);
        log.info("Schritt 3 — Geschosse:  {} Geschosse, {} Wandsegmente, {} Boeden, {} Decken",
                r.storeyStats.storeysCreated, r.storeyStats.wallSegmentsCreated,
                r.storeyStats.floorsCreated, r.storeyStats.ceilingsCreated);
        log.info("Schritt 4 — Tueren:     {} Tueren, {} Waende modifiziert, {} uebersprungen",
                r.doorStats.doorsCreated, r.doorStats.wallsModified, r.doorStats.wallsSkipped);
        log.info("Schritt 4d — Fallback-Tueren (Gebaeude mit Fenstern, aber ohne Tuer): {}, "
                        + "dafuer entfernte Fenster: {}",
                r.doorStats.fallbackDoorsCreated, r.doorStats.windowsRemovedForFallbackDoor);
        log.info("Schritt 5b — Fenster:   {} Fenster, {} Waende, {} uebersprungen, {} Giebel-Drops, {} WWR-Warn.",
                r.windowStats.windowsCreated, r.windowStats.wallsWithWindows,
                r.windowStats.wallsSkipped, r.windowStats.gableWindowsDropped,
                r.windowStats.wwrWarnings);
        log.info("Schritt 5b — {}", r.windowStats.toSummary());
        log.info("Schritt 5b — {}", r.windowStats.toGeschossSummary());
        log.info("Schritt 5a+5c — Balkone: {} Balkone, {} Waende, {} Fenster ersetzt, {} uebersprungen",
                r.balconyStats.balconiesCreated, r.balconyStats.wallsWithBalconies,
                r.balconyStats.windowsRemovedForBalcony, r.balconyStats.wallsSkipped);
        log.info("Schritt 5d — Dachfenster: {} Fenster, {} Dachflaechen, {} uebersprungen, {} WWR-Warn.",
                r.roofWindowStats.roofWindowsCreated, r.roofWindowStats.roofsWithWindows,
                r.roofWindowStats.roofsSkipped, r.roofWindowStats.wwrWarnings);
        log.info("Schritt 5d — {}", r.roofWindowStats.toSummary());
        log.info("Schritt 7 — Pinch-Point-Aufspaltung: {} Ringe aufgespalten", r.pinchSplits);
    }
}
