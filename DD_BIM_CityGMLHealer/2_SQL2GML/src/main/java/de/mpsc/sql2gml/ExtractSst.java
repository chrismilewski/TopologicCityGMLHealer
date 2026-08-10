package de.mpsc.sql2gml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts only buildings with "sst" generic attribute from CityGML file(s).
 * Uses text-based streaming to handle large files efficiently.
 *
 * Usage:
 *   java -cp sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <file.gml> [<output.gml>]
 *   java -cp sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> [<outputFolder>]
 *
 * Without output argument: files are written next to the input with suffix "_sst.gml".
 * With output argument (single file): output is written to the specified path.
 * With output argument (folder): output files are written into the specified folder.
 */
public class ExtractSst {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: ExtractSst <file.gml> [<output.gml>]");
            System.out.println("       ExtractSst <folder> [<outputFolder>]");
            System.out.println("  Single file: extracts sst buildings to output path (or _sst.gml next to input).");
            System.out.println("  Folder: processes all .gml files, writes to outputFolder (or next to input).");
            System.exit(1);
        }

        Path inputPath = Paths.get(args[0]);
        Path outputArg = args.length >= 2 ? Paths.get(args[1]) : null;

        if (Files.isRegularFile(inputPath)) {
            // Single file mode
            Path outputPath;
            if (outputArg != null) {
                // If output arg is a directory, generate filename inside it
                if (Files.isDirectory(outputArg)) {
                    String outName = inputPath.getFileName().toString().replaceFirst("(?i)\\.gml$", "_sst.gml");
                    outputPath = outputArg.resolve(outName);
                } else {
                    outputPath = outputArg;
                }
            } else {
                String outName = inputPath.getFileName().toString().replaceFirst("(?i)\\.gml$", "_sst.gml");
                outputPath = inputPath.resolveSibling(outName);
            }

            System.out.println("--- " + inputPath.getFileName() + " ---");
            processFile(inputPath, outputPath);
            System.out.println("\nDone. 1 file processed.");

        } else if (Files.isDirectory(inputPath)) {
            // Folder mode
            List<Path> gmlFiles = new ArrayList<>();
            try (var stream = Files.list(inputPath)) {
                stream.filter(f -> f.toString().toLowerCase().endsWith(".gml"))
                      .filter(f -> !f.getFileName().toString().contains("_sst"))
                      .forEach(gmlFiles::add);
            }

            if (gmlFiles.isEmpty()) {
                System.out.println("No .gml files found in " + inputPath);
                System.exit(1);
            }

            if (outputArg != null) {
                Files.createDirectories(outputArg);
            }

            System.out.println("Processing " + gmlFiles.size() + " file(s)...\n");
            int totalFilesProcessed = 0;
            for (Path gmlFile : gmlFiles) {
                String outName = gmlFile.getFileName().toString().replaceFirst("(?i)\\.gml$", "_sst.gml");
                Path outputPath = outputArg != null ? outputArg.resolve(outName) : gmlFile.resolveSibling(outName);

                System.out.println("--- " + gmlFile.getFileName() + " ---");
                processFile(gmlFile, outputPath);
                totalFilesProcessed++;
                System.out.println();
            }
            System.out.println("Done. " + totalFilesProcessed + " file(s) processed.");

        } else {
            System.err.println("ERROR: Not found: " + inputPath);
            System.exit(1);
        }
    }

    private static void processFile(Path inputPath, Path outputPath) throws IOException {
        GmlMemberFilter.Result result = GmlMemberFilter.filter(
                inputPath, outputPath, block -> block.contains("name=\"sst\""));

        System.out.println("  Total buildings: " + result.totalMembers());
        System.out.println("  SST buildings:   " + result.writtenMembers());
        System.out.println("  Output: " + outputPath);
    }
}
