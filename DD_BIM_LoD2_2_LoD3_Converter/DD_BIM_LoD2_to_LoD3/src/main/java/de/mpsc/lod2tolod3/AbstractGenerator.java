package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.Building;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Gemeinsame Template-Method-Basis fuer alle JSON-parametrisierten LoD3-Generatoren. Unterklassen
 * implementieren outputSuffix/displayName/newStats/processBuilding/logResult.
 */
public abstract class AbstractGenerator<S extends AbstractGenerator.BaseStats> {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** Basis-Statistik; jeder Generator erweitert sie um eigene Zaehler. */
    public static class BaseStats {
        public int buildingsProcessed = 0;
    }

    /** Buendelt sst-Tag und aufgeloeste Moduldaten eines Gebaeudes. */
    protected record BuildingParams(String sst, ModuleParameters params) {}

    // ==================== Von Unterklassen zu implementieren ====================

    /** Suffix fuer den Standalone-Output-Dateinamen, z.B. {@code "_doors"}. */
    protected abstract String outputSuffix();

    /** Generator-Name fuer den Log-Header, z.B. {@code "Tuer-Generator"}. */
    protected abstract String displayName();

    /** Frisches Statistik-Objekt. */
    protected abstract S newStats();

    /** Verarbeitet ein einzelnes Gebaeude (Kernlogik des Generators). */
    protected abstract void processBuilding(Building building,
            ModuleParametersLoader loader, S stats);

    /** Loggt die generator-spezifischen Kennzahlen (ohne {@code buildingsProcessed}). */
    protected abstract void logResult(S stats);

    /** Hook fuer zusaetzliche CLI-Argumente (z.B. DGM beim BasementGenerator); No-op per Default. */
    protected void onConfigure(String[] args) {
        // optional override
    }

    // ==================== Gemeinsames Geruest ====================

    /** Verarbeitet eine GML-Datei: liest jedes Gebaeude, ruft processBuilding, schreibt das Ergebnis. */
    public S process(Path input, Path output, ModuleParametersLoader loader) throws Exception {
        S stats = newStats();
        CityGmlUtils.processGmlFile(input, output, building -> {
            stats.buildingsProcessed++;
            processBuilding(building, loader, stats);
        });
        return stats;
    }

    /** Standard-CLI-Einstieg: {@code <input.gml> <jsonDir> [output.gml] [extra...]}. */
    protected final void runCli(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: " + getClass().getSimpleName()
                    + " <input.gml> <jsonDir> [output.gml]");
            System.exit(1);
            return;
        }

        Path input = Paths.get(args[0]);
        Path jsonDir = Paths.get(args[1]);
        Path output = CityGmlUtils.resolveOutputPath(input, outputSuffix(),
                args.length >= 3 ? Paths.get(args[2]) : null);

        createParentDirs(output);

        log.info("=== {} ===", displayName());
        log.info("Input:  {}", input);
        log.info("JSON:   {}", jsonDir);
        log.info("Output: {}", output);

        onConfigure(args);

        ModuleParametersLoader loader = new ModuleParametersLoader(jsonDir);
        S stats = process(input, output, loader);

        log.info("=== Fertig ===");
        log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
        logResult(stats);
    }

    /** Erstellt das Eltern-Verzeichnis der Ausgabedatei. NPE-sicher bei {@code parent == null}. */
    protected static void createParentDirs(Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** Loest das sst-Attribut eines Gebaeudes und die zugehoerigen Moduldaten auf. */
    protected static Optional<BuildingParams> resolveParams(Building building,
            ModuleParametersLoader loader) {
        String sst = CityGmlUtils.getStringAttribute(building, "sst");
        if (sst == null || sst.isBlank()) return Optional.empty();
        return loader.getParameters(sst).map(p -> new BuildingParams(sst, p));
    }
}
