package de.mpsc.sql2gml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extracts specific buildings (by gml:id) from a CityGML file.
 * Keeps all metadata (header, boundedBy, etc.) intact.
 *
 * Usage: java -cp sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> <ID2> ...
 */
public class ExtractBuildings {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ExtractBuildings <input.gml> <output.gml> <buildingId1> [buildingId2] ...");
            System.exit(1);
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        Set<String> targetIds = new LinkedHashSet<>(Arrays.asList(Arrays.copyOfRange(args, 2, args.length)));

        System.out.println("Input:  " + inputPath);
        System.out.println("Output: " + outputPath);
        System.out.println("Target IDs (" + targetIds.size() + "): " + targetIds);

        // Track which requested IDs were actually matched (for the not-found report)
        Set<String> foundIds = new LinkedHashSet<>();

        GmlMemberFilter.Result result = GmlMemberFilter.filter(inputPath, outputPath, block -> {
            for (String id : targetIds) {
                if (block.contains("gml:id=\"" + id + "\"")) {
                    foundIds.add(id);
                    return true;
                }
            }
            return false;
        });

        System.out.println("Total buildings scanned: " + result.totalMembers());
        System.out.println("Buildings written: " + result.writtenMembers());

        Set<String> notFound = new LinkedHashSet<>(targetIds);
        notFound.removeAll(foundIds);
        if (!notFound.isEmpty()) {
            System.out.println("WARNING: " + notFound.size() + " requested ID(s) not found: " + notFound);
        }

        System.out.println("Output: " + outputPath);
    }
}
