package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.ModuleParameters.Gallery;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurface;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.citygml4j.core.model.core.AbstractSpaceBoundaryProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schritt 6: Balkon-Generator. Ersetzt Fenster-Slots einer Wand durch Balkone gemaess
 * GaPa-Muster (siehe Doku.md, Abschnitt "Schritt 6: Balkon-Generator").
 *
 * Usage (Standalone):
 *   java -cp lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.BalconyGenerator input.gml jsonDir/ [output.gml]
 */
public class BalconyGenerator extends AbstractGenerator<BalconyGenerator.GenerationStats> {

    /** Sockelhoehe der Balkontuer ueber der Wandunterkante (wie DoorGenerator.DOOR_SILL_HEIGHT). */
    private static final double DOOR_SILL_HEIGHT = 0.05;

    /** Platzhalter-Mindestlaenge einer Wand, um ueberhaupt als balkon-faehig zu gelten. */
    private static final double MIN_WALL_LENGTH_FOR_BALCONY = 2.0;

    private static final Pattern GA_PA_TOKEN = Pattern.compile("Ga|Wi");

    public static void main(String[] args) {
        BalconyGenerator gen = new BalconyGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_balconies"; }
    @Override protected String displayName()  { return "Balkon-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Balkone erzeugt: {}", stats.balconiesCreated);
        log.info("Waende mit Balkon: {}", stats.wallsWithBalconies);
        log.info("Waende uebersprungen: {}", stats.wallsSkipped);
        log.info("Balkone uebersprungen (ausserhalb Wandkontur, Tuer-Check): {}", stats.balconiesSkippedOutside);
        log.info("Balkone uebersprungen (Lauf/Balkon zu breit fuer Wandkontur): {}", stats.balconiesSkippedRunTooWide);
        log.info("Balkone uebersprungen (Konflikt mit bestehender Tuer): {}", stats.balconiesSkippedDoorConflict);
        log.info("Balkone uebersprungen (Mindestabstand HDistWiGa zu Nachbarfenster): {}", stats.balconiesSkippedNeighborConflict);
        log.info("Fenster durch Balkon ersetzt: {}", stats.windowsRemovedForBalcony);
        log.info("GaPa-Token ohne passendes Fenster an der Wand: {}", stats.galleryTokensUnfulfilled);
        log.info("Waende mit Balkon-Muster, aber ohne vorhandene Fenster: {}", stats.wallsWithNoWindowsForPattern);
        log.info("Balkone pro Wand (Histogramm, Anzahl->#Waende): {}", stats.balconyCountHistogram);
        log.warn("Hinweis: mehrere inferierte Annahmen zur Parameter-Semantik — siehe Doku.md, "
                + "Abschnitt \"Schritt 6: Balkon-Generator\".");
    }

    // ==================== Gebaeude-Verarbeitung ====================

    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters params = bp.params();

        Gallery gallery = params.getGallery();
        if (gallery == null || !gallery.isValid()) return;

        for (AbstractBuilding target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, gallery, stats);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /** Verarbeitet jede eligible Wand eines AbstractBuilding unabhaengig gegen das GaPa-Muster. */
    private void processAbstractBuilding(AbstractBuilding target, Gallery gallery,
            GenerationStats stats) {

        // Kopie: neu erzeugte Waende duerfen die Iteration nicht beeinflussen.
        List<WallSurface> walls = new ArrayList<>(CityGmlUtils.collectWallSurfaces(target));
        Point3D centroid = computeFootprintCentroid(walls);
        GaPaSequence sequence = parseGaPattern(gallery.pattern);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            CityGmlUtils.BottomEdge edge = bottomEdgeOf(wall);
            if (!isEligibleForBalcony(wall, geschoss, edge)) continue;
            processWall(target, wall, geschoss, gallery, sequence, stats, centroid);
        }
    }

    /** Groberer 2D-Schwerpunkt des Gebaeude-Footprints, als Referenz fuer die Auswaerts-Richtung der Wandnormalen. */
    private static Point3D computeFootprintCentroid(List<WallSurface> walls) {
        double sx = 0, sy = 0;
        int n = 0;
        for (WallSurface wall : walls) {
            CityGmlUtils.BottomEdge edge = bottomEdgeOf(wall);
            if (edge == null) continue;
            sx += edge.start().x + edge.end().x;
            sy += edge.start().y + edge.end().y;
            n += 2;
        }
        return n == 0 ? new Point3D(0, 0, 0) : new Point3D(sx / n, sy / n, 0);
    }

    private static CityGmlUtils.BottomEdge bottomEdgeOf(WallSurface wall) {
        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) return null;
        List<Point3D> open = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(wallPoly));
        return CityGmlUtils.findBottomEdge(open);
    }

    /** Eligibilitaet einer Wand fuer Balkone anhand WindowPreference, Geschoss und Mindestlaenge. */
    private boolean isEligibleForBalcony(WallSurface wall, String geschoss,
            CityGmlUtils.BottomEdge edge) {
        if (geschoss == null) return false;
        boolean isGfOrUf = "GF".equals(geschoss) || geschoss.startsWith("UF_");
        if (!isGfOrUf) return false;
        if ("1".equals(CityGmlUtils.getStringAttribute(wall, "Innenwand"))) return false;
        if (edge == null || edge.wallLength() < MIN_WALL_LENGTH_FOR_BALCONY) return false;

        WindowPreference pref = WindowPreference.parse(
                CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
        if (pref == WindowPreference.NONE) return false;
        if (pref == WindowPreference.ABOVE_NEIGHBOR) {
            String zFensterAslStr = CityGmlUtils.getStringAttribute(wall, "Z_Fenster_ASL");
            if (zFensterAslStr == null) return false;
            try {
                double zFensterAsl = Double.parseDouble(zFensterAslStr);
                if (edge.zMin() < zFensterAsl - 0.01) return false; // Geschoss noch (teilweise) verdeckt
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // ==================== GaPa-Parser ====================

    /** Ergebnis des GaPa-Parsings: Reihenfolge der Token sowie Anzahl je Typ. */
    record GaPaSequence(List<String> tokens, int galleryCount, int windowCount) {}

    /** Zerlegt das GaPa-Muster in Ga/Wi-Token; fehlt es, wird von genau 1 Balkon ausgegangen. */
    static GaPaSequence parseGaPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return new GaPaSequence(List.of("Ga"), 1, 0);
        }
        List<String> tokens = new ArrayList<>();
        int ga = 0, wi = 0;
        Matcher m = GA_PA_TOKEN.matcher(pattern);
        while (m.find()) {
            String tok = m.group();
            tokens.add(tok);
            if ("Ga".equals(tok)) ga++; else wi++;
        }
        if (tokens.isEmpty()) {
            return new GaPaSequence(List.of("Ga"), 1, 0);
        }
        return new GaPaSequence(tokens, ga, wi);
    }

    // ==================== Wand-Verarbeitung ====================

    /** Ersetzt die per GaPa-Muster markierten Fenster-Slots dieser Wand durch Balkone. */
    private void processWall(AbstractBuilding target, WallSurface wall, String geschoss,
            Gallery gallery, GaPaSequence sequence, GenerationStats stats,
            Point3D footprintCentroid) {

        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) return;
        List<Point3D> open = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(wallPoly));
        if (open.size() < 3) return;

        CityGmlUtils.BottomEdge edge = CityGmlUtils.findBottomEdge(open);
        if (edge == null) { stats.wallsSkipped++; return; }

        double gaLen = gallery.length;
        double gaWid = gallery.width;
        double gaHe = (gallery.height != null && gallery.height > 0) ? gallery.height : 1.0;
        double hDistGaGa = safe(gallery.hDistGalleryGallery, 1.0);
        double hDistWindowGallery = safe(gallery.hDistWindowGallery, 0.0);
        double distDoorGallery = safe(gallery.distDoorGallery, 0.0);

        double doorWidth = (gallery.doorWidth != null && gallery.doorWidth > 0) ? gallery.doorWidth : Math.min(1.0, gaLen);
        double doorHeight = (gallery.doorHeight != null && gallery.doorHeight > 0) ? gallery.doorHeight : 2.0;

        double wallLength = edge.wallLength();
        double wallHeight = edge.zMax() - edge.zMin();
        if (doorHeight + DOOR_SILL_HEIGHT > wallHeight) { stats.wallsSkipped++; return; }
        if (doorWidth > gaLen) { stats.wallsSkipped++; return; }

        double dx = edge.end().x - edge.start().x;
        double dy = edge.end().y - edge.start().y;
        double dirX = dx / wallLength;
        double dirY = dy / wallLength;
        // Auswaerts-Normale, gegen den Gebaeude-Schwerpunkt geprueft und ggf. umgedreht.
        double normX = -dirY;
        double normY = dirX;
        double wallMidX = (edge.start().x + edge.end().x) / 2.0;
        double wallMidY = (edge.start().y + edge.end().y) / 2.0;
        if (normX * (wallMidX - footprintCentroid.x) + normY * (wallMidY - footprintCentroid.y) < 0) {
            normX = -normX;
            normY = -normY;
        }

        boolean extCCW = CityGmlUtils.isExteriorRingCCW(open, edge.start(), dirX, dirY);
        double zMin = edge.zMin();
        double doorBottomZ = CityGmlUtils.roundZ(zMin + DOOR_SILL_HEIGHT);
        double doorTopZ = CityGmlUtils.roundZ(doorBottomZ + doorHeight);
        double deckZ = doorBottomZ;
        double railTopZ = CityGmlUtils.roundZ(deckZ + gaHe);

        double[][] wallPoly2D = CityGmlUtils.projectWallTo2D(open, edge.start(), dirX, dirY, zMin);

        String wallFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
        if (wallFaceId == null) wallFaceId = wall.getId() != null ? wall.getId() : "unknown";

        // --- GaPa-Token mit den vorhandenen, links->rechts sortierten Fenstern verzippen ---
        List<WindowSlot> slots = collectSortedWindowSlots(wall, edge.start(), dirX, dirY);
        List<String> tokens = sequence.tokens();
        int n = Math.min(tokens.size(), slots.size());

        int excessGaTokens = 0;
        for (int t = n; t < tokens.size(); t++) if ("Ga".equals(tokens.get(t))) excessGaTokens++;
        stats.galleryTokensUnfulfilled += excessGaTokens;

        if (slots.isEmpty()) {
            if (sequence.galleryCount() > 0) stats.wallsWithNoWindowsForPattern++;
            stats.wallsSkipped++;
            return;
        }

        double wallAreaBefore = resolveCurrentWallArea(wall, open);
        double areaRestoredFromRemovedWindows = 0;
        int placed = 0;

        // --- Ga-Token in zusammenhaengende Laeufe gruppieren, Lauf fuer Lauf platzieren ---
        int i = 0;
        while (i < n) {
            if (!"Ga".equals(tokens.get(i))) { i++; continue; }
            int runStart = i;
            while (i < n && "Ga".equals(tokens.get(i))) i++;
            int runEnd = i; // exklusiv
            int k = runEnd - runStart;
            List<WindowSlot> runSlots = slots.subList(runStart, runEnd);

            double anchor = (runSlots.get(0).uMid() + runSlots.get(k - 1).uMid()) / 2.0;
            double[] offsets = layoutGaRun(k, gaLen, hDistGaGa, anchor);

            for (int j = 0; j < k; j++) {
                WindowSlot slot = runSlots.get(j);
                double galleryOffset = offsets[j];

                if (!CityGmlUtils.openingInsideWall2D(galleryOffset, galleryOffset + gaLen,
                        doorBottomZ - zMin, railTopZ - zMin, wallPoly2D)) {
                    stats.balconiesSkippedRunTooWide++;
                    continue;
                }

                double doorOffset = galleryOffset + distDoorGallery;
                doorOffset = Math.max(galleryOffset, Math.min(doorOffset, galleryOffset + gaLen - doorWidth));
                if (!CityGmlUtils.openingInsideWall2D(doorOffset, doorOffset + doorWidth,
                        doorBottomZ - zMin, doorTopZ - zMin, wallPoly2D)) {
                    stats.balconiesSkippedOutside++;
                    continue;
                }

                // Kollision mit bestehender Tuer: Balkon wird uebersprungen, Tuer bleibt.
                if (hasOpeningConflict(wall, DoorSurface.class, edge.start(), dirX, dirY,
                        galleryOffset, galleryOffset + gaLen)) {
                    stats.balconiesSkippedDoorConflict++;
                    continue;
                }

                // Mindestabstand zu Nachbarfenstern ausserhalb dieses Laufs.
                if (hasWindowClearanceConflict(wall, runSlots, edge.start(), dirX, dirY,
                        galleryOffset, galleryOffset + gaLen, hDistWindowGallery)) {
                    stats.balconiesSkippedNeighborConflict++;
                    continue;
                }

                // --- Ersetztes Fenster entfernen (FillingSurface + Innenring), Flaeche zurueckbuchen ---
                areaRestoredFromRemovedWindows += parseAreaOrZero(slot.window());
                CityGmlUtils.removeMatchingInteriorRing(wallPoly, slot.points());
                removeFillingSurfaceByIdentity(wall, slot.prop());

                String balconyId = wallFaceId + "_Ga_" + (placed + 1);

                // --- Zugangsoeffnung: Loch in die Hauswand, wie ein normaler Tuer-Ausschnitt ---
                Point3D dbl = pointOn(edge.start(), dirX, dirY, doorOffset, doorBottomZ);
                Point3D dbr = pointOn(edge.start(), dirX, dirY, doorOffset + doorWidth, doorBottomZ);
                Point3D dtr = pointOn(edge.start(), dirX, dirY, doorOffset + doorWidth, doorTopZ);
                Point3D dtl = pointOn(edge.start(), dirX, dirY, doorOffset, doorTopZ);
                Polygon doorPoly = CityGmlUtils.addOpeningToWall(wallPoly, dbl, dbr, dtr, dtl, extCCW);

                DoorSurface door = new DoorSurface();
                door.setId("Face_" + balconyId + "_Door");
                CityGmlUtils.setGmlName(door, "LOD3_BalconyDoor");
                door.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(doorPoly));
                CityGmlUtils.addStringAttribute(door, "BldgFaceID", balconyId + "_Door");
                CityGmlUtils.addStringAttribute(door, "FACEAREA", CityGmlUtils.formatNum(doorWidth * doorHeight));
                CityGmlUtils.addStringAttribute(door, "Geschoss", geschoss);
                wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(door));

                // --- Deck (RoofSurface-Approximation) ---
                Point3D p1 = pointOn(edge.start(), dirX, dirY, galleryOffset, deckZ);
                Point3D p2 = pointOn(edge.start(), dirX, dirY, galleryOffset + gaLen, deckZ);
                Point3D p3 = offsetOutward(p2, normX, normY, gaWid);
                Point3D p4 = offsetOutward(p1, normX, normY, gaWid);

                List<Point3D> deckRing = orientUpward(List.of(p1, p2, p3, p4));
                Polygon deckPoly = CityGmlUtils.createPolygon(deckRing);
                RoofSurface deck = new RoofSurface();
                deck.setId("Face_" + balconyId + "_Deck");
                CityGmlUtils.setGmlName(deck, "LOD3_BalconyDeck");
                deck.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(deckPoly));
                double deckArea = CityGmlUtils.calculatePolygonArea2D(deckRing);
                CityGmlUtils.addStringAttribute(deck, "BldgFaceID", balconyId + "_Deck");
                CityGmlUtils.addStringAttribute(deck, "FACEAREA", CityGmlUtils.formatNum(deckArea));
                CityGmlUtils.addStringAttribute(deck, "Geschoss", geschoss);
                CityGmlUtils.addStringAttribute(deck, "STRUKTUR", CityGmlUtils.STRUKTUR_BALCONY_DECK);
                target.getBoundaries().add(new AbstractSpaceBoundaryProperty(deck));

                // --- Bruestung: 3 Seiten (P1-P2 = Hauswand-Seite, KEINE eigene Flaeche) ---
                addRailing(target, balconyId, 1, p1, p4, deckZ, railTopZ, geschoss);
                addRailing(target, balconyId, 2, p4, p3, deckZ, railTopZ, geschoss);
                addRailing(target, balconyId, 3, p3, p2, deckZ, railTopZ, geschoss);

                stats.windowsRemovedForBalcony++;
                stats.balconiesCreated++;
                placed++;
            }
        }

        stats.balconyCountHistogram.merge(placed, 1, Integer::sum);
        if (placed == 0) return;

        double doorAreaTotal = placed * doorWidth * doorHeight;
        double wallAreaAfter = wallAreaBefore + areaRestoredFromRemovedWindows - doorAreaTotal;
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                CityGmlUtils.formatNum(Math.max(0, wallAreaAfter)));
        stats.wallsWithBalconies++;
    }

    // ==================== Fenster-Slots ====================

    /** Eine bereits vorhandene {@code WindowSurface} mit ihrer u-Position entlang der Wand. */
    private record WindowSlot(AbstractFillingSurfaceProperty prop, WindowSurface window,
            List<Point3D> points, double uLo, double uHi, double uMid) {}

    /** Sammelt WindowSurfaces der Wand, sortiert nach u-Position (links → rechts). */
    private static List<WindowSlot> collectSortedWindowSlots(WallSurface wall, Point3D origin,
            double dirX, double dirY) {
        List<WindowSlot> slots = new ArrayList<>();
        for (AbstractFillingSurfaceProperty fsp : new ArrayList<>(wall.getFillingSurfaces())) {
            if (!(fsp.getObject() instanceof WindowSurface ws)) continue;
            Polygon poly = firstPolygonOf(ws);
            if (poly == null) continue;
            List<Point3D> pts = CityGmlUtils.toPoints(poly);
            double[] span = uSpanOf(pts, origin, dirX, dirY);
            slots.add(new WindowSlot(fsp, ws, pts, span[0], span[1], (span[0] + span[1]) / 2.0));
        }
        slots.sort(Comparator.comparingDouble(WindowSlot::uMid));
        return slots;
    }

    /** Layoutet einen Lauf von k Balkonen als einen Block, zentriert auf anchor. */
    private static double[] layoutGaRun(int k, double gaLen, double hDistGaGa, double anchor) {
        double total = k * gaLen + Math.max(0, k - 1) * hDistGaGa;
        double blockStart = anchor - total / 2.0;
        double[] offsets = new double[k];
        for (int j = 0; j < k; j++) offsets[j] = blockStart + j * (gaLen + hDistGaGa);
        return offsets;
    }

    // ==================== Kollision mit Fenstern/Tueren ====================

    /** Aktuelle Netto-Wandflaeche (FACEAREA-Attribut, sonst Bruttoflaeche). */
    private static double resolveCurrentWallArea(WallSurface wall, List<Point3D> open) {
        String faceAreaStr = CityGmlUtils.getStringAttribute(wall, "FACEAREA");
        if (faceAreaStr != null) {
            try { return Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
        }
        return CityGmlUtils.calculateWallArea(open);
    }

    /** Projiziert Punkte auf die Wandrichtung (u-Achse ab {@code origin}) und liefert [min, max]. */
    private static double[] uSpanOf(List<Point3D> pts, Point3D origin, double dirX, double dirY) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Point3D p : pts) {
            double u = (p.x - origin.x) * dirX + (p.y - origin.y) * dirY;
            lo = Math.min(lo, u);
            hi = Math.max(hi, u);
        }
        return new double[]{lo, hi};
    }

    /** Erstes Polygon einer FillingSurface (Fenster-/Tuer-"Scheibe"), oder null. */
    private static Polygon firstPolygonOf(AbstractFillingSurface fs) {
        if (fs == null || fs.getLod3MultiSurface() == null
                || fs.getLod3MultiSurface().getObject() == null) return null;
        var members = fs.getLod3MultiSurface().getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return null;
        return members.get(0).getObject() instanceof Polygon p ? p : null;
    }

    /** True, wenn Ueberlappungslaenge > diese Toleranz ist (blosse Kantenberuehrung zaehlt nicht). */
    private static final double SPAN_OVERLAP_TOL = 0.02;

    private static boolean spansOverlap(double aLo, double aHi, double bLo, double bHi) {
        return Math.min(aHi, bHi) - Math.max(aLo, bLo) > SPAN_OVERLAP_TOL;
    }

    /** Prueft Ueberlappung mit einer bestehenden FillingSurface vom Typ type im Intervall [spanMin, spanMax]. */
    private static boolean hasOpeningConflict(WallSurface wall, Class<? extends AbstractFillingSurface> type,
            Point3D origin, double dirX, double dirY, double spanMin, double spanMax) {
        for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
            var fs = fsp.getObject();
            if (!type.isInstance(fs)) continue;
            Polygon poly = firstPolygonOf(fs);
            if (poly == null) continue;
            double[] span = uSpanOf(CityGmlUtils.toPoints(poly), origin, dirX, dirY);
            if (spansOverlap(spanMin, spanMax, span[0], span[1])) return true;
        }
        return false;
    }

    /** Prueft Mindestabstand clearance zu Fenstern der Wand ausserhalb von runSlots. */
    private static boolean hasWindowClearanceConflict(WallSurface wall, List<WindowSlot> runSlots,
            Point3D origin, double dirX, double dirY, double spanMin, double spanMax,
            double clearance) {
        for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
            boolean isRunMember = false;
            for (WindowSlot rs : runSlots) {
                if (rs.prop() == fsp) { isRunMember = true; break; }
            }
            if (isRunMember) continue;
            if (!(fsp.getObject() instanceof WindowSurface ws)) continue;
            Polygon poly = firstPolygonOf(ws);
            if (poly == null) continue;
            double[] span = uSpanOf(CityGmlUtils.toPoints(poly), origin, dirX, dirY);
            if (spansOverlap(spanMin - clearance, spanMax + clearance, span[0], span[1])) return true;
        }
        return false;
    }

    /** Entfernt genau die FillingSurface-Property mit dieser Objektidentitaet (nicht per Span-Scan). */
    private static void removeFillingSurfaceByIdentity(WallSurface wall, AbstractFillingSurfaceProperty target) {
        var it = wall.getFillingSurfaces().iterator();
        while (it.hasNext()) {
            if (it.next() == target) { it.remove(); return; }
        }
    }

    /** Liest das {@code FACEAREA}-Attribut einer FillingSurface, oder 0 wenn fehlend/ungueltig. */
    private static double parseAreaOrZero(AbstractFillingSurface fs) {
        String s = CityGmlUtils.getStringAttribute(fs, "FACEAREA");
        if (s == null) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Ein Bruestungs-Wandsegment von A nach B, von zBottom bis zTop (analog Basement-Kantenextrusion). */
    private void addRailing(AbstractBuilding target, String balconyId, int idx,
            Point3D a, Point3D b, double zBottom, double zTop, String geschoss) {
        Point3D aTop = new Point3D(a.x, a.y, zTop);
        Point3D bTop = new Point3D(b.x, b.y, zTop);
        List<Point3D> pts = List.of(a, b, bTop, aTop);
        Polygon poly = CityGmlUtils.createPolygon(new ArrayList<>(pts));

        WallSurface railing = new WallSurface();
        String faceId = balconyId + "_Railing_" + idx;
        railing.setId("Face_" + faceId);
        CityGmlUtils.setGmlName(railing, "LOD3_BalconyRailing");
        railing.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(poly));
        CityGmlUtils.addWallAttributes(railing, pts, faceId, null, geschoss, null,
                CityGmlUtils.STRUKTUR_BALCONY_RAILING, null);
        CityGmlUtils.setStringAttribute(railing, "STRUKTUR", CityGmlUtils.STRUKTUR_BALCONY_RAILING);
        target.getBoundaries().add(new AbstractSpaceBoundaryProperty(railing));
    }

    // ==================== Geometrie-Hilfsmethoden ====================

    private static Point3D pointOn(Point3D origin, double dirX, double dirY, double along, double z) {
        return new Point3D(origin.x + along * dirX, origin.y + along * dirY, z);
    }

    private static Point3D offsetOutward(Point3D p, double normX, double normY, double dist) {
        return new Point3D(p.x + dist * normX, p.y + dist * normY, p.z);
    }

    /** Kehrt die Punktreihenfolge um, falls die Newell-Normale nach unten zeigt (Deck soll nach oben zeigen). */
    private static List<Point3D> orientUpward(List<Point3D> ring) {
        double nz = 0;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            Point3D a = ring.get(i), b = ring.get((i + 1) % n);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        if (nz >= 0) return ring;
        List<Point3D> rev = new ArrayList<>(ring);
        java.util.Collections.reverse(rev);
        return rev;
    }

    private static double safe(Double v, double fallback) {
        return (v != null && !Double.isNaN(v)) ? v : fallback;
    }

    // ==================== Statistiken ====================

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int balconiesCreated = 0;
        public int wallsWithBalconies = 0;
        public int wallsSkipped = 0;
        public int balconiesSkippedOutside = 0;
        /** Balkon nicht platziert, weil er eine bestehende Tuer (z.B. Hauseingang) ueberlappt. */
        public int balconiesSkippedDoorConflict = 0;
        /** Fenster, die durch einen Balkon ersetzt wurden (per GaPa-Muster, nicht per Kollision). */
        public int windowsRemovedForBalcony = 0;
        /** Balkon nicht platziert: Mindestabstand (HDistWiGa) zu einem ueberlebenden Nachbarfenster unterschritten. */
        public int balconiesSkippedNeighborConflict = 0;
        /** Einzelner Balkon eines Laufs passt nicht in die Wandkontur (Balkonbreite > verfuegbarer Platz). */
        public int balconiesSkippedRunTooWide = 0;
        /** Ga-Token im GaPa-Muster ohne entsprechendes Fenster an dieser Wand (Muster laenger als Fensterliste). */
        public int galleryTokensUnfulfilled = 0;
        /** Eligible Wand mit Ga-Token im Muster, aber ohne ein einziges vorhandenes Fenster zum Ersetzen. */
        public int wallsWithNoWindowsForPattern = 0;
        /** Verteilung: Anzahl platzierter Balkone (Schluessel) -> Anzahl Waende mit dieser Anzahl. */
        public final java.util.Map<Integer, Integer> balconyCountHistogram = new java.util.TreeMap<>();
    }
}
