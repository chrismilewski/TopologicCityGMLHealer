package de.mpsc.sql2gml;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Predicate;

/** Zeilenbasierter Streaming-Filter fuer CityGML-Dateien; behaelt nur cityObjectMember-Bloecke, die keepBlock akzeptiert. */
public final class GmlMemberFilter {

    private static final String MEMBER_OPEN  = "<core:cityObjectMember>";
    private static final String MEMBER_CLOSE = "</core:cityObjectMember>";

    private GmlMemberFilter() {}

    /** Ergebnis eines Filter-Laufs. */
    public record Result(int totalMembers, int writtenMembers) {}

    public static Result filter(Path input, Path output, Predicate<String> keepBlock) throws IOException {
        int total = 0;
        int written = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(input.toFile()), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output.toFile()), StandardCharsets.UTF_8))) {

            String line;
            StringBuilder memberBlock = null;
            boolean inMember = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains(MEMBER_OPEN)) {
                    inMember = true;
                    memberBlock = new StringBuilder();
                    memberBlock.append(line).append('\n');
                } else if (inMember && line.contains(MEMBER_CLOSE)) {
                    memberBlock.append(line).append('\n');
                    total++;
                    String block = memberBlock.toString();
                    if (keepBlock.test(block)) {
                        writer.write(block);
                        written++;
                    }
                    inMember = false;
                    memberBlock = null;
                } else if (inMember) {
                    memberBlock.append(line).append('\n');
                } else {
                    // Header / boundedBy / schließendes CityModel — unverändert übernehmen
                    writer.write(line);
                    writer.newLine();
                }
            }
        }
        return new Result(total, written);
    }
}
