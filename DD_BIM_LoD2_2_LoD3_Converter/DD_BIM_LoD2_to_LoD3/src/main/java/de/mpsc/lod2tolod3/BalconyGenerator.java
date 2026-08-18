package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.ModuleParameters.Gallery;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils.Point3D;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.building.BuildingInstallation;
import org.citygml4j.core.model.building.BuildingInstallationProperty;
import org.citygml4j.core.model.construction.AbstractFillingSurface;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.xmlobjects.gml.model.geometry.GeometryProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Schritt 6: Balkon-Generator, zweiphasig um die Fenster herum (siehe Doku.md, Abschnitt
 * "Schritt 6: Balkon-Generator"). Phase 1 (vor den Fenstern) platziert den fuehrenden Ga-Lauf
 * jeder Wand unabhaengig ueber HDistWaGa/HDistGaGa/HDistMinWaGa. Phase 2 (nach den Fenstern)
 * platziert restliche Ga-Token eines Musters (z.B. "GaWiGaWi") gegen die dann echten Fenster,
 * verankert ueber HDistWiGa statt sie zu zentrieren.
 *
 * Usage (Standalone):
 *   java -cp lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.BalconyGenerator input.gml jsonDir/ [output.gml]
 */
public class BalconyGenerator extends AbstractGenerator<BalconyGenerator.GenerationStats> {

    /** Sockelhoehe der Balkontuer ueber der Wandunterkante (wie DoorGenerator.DOOR_SILL_HEIGHT). */
    private static final double DOOR_SILL_HEIGHT = 0.05;

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

    /** Standalone-CLI: Eingabedatei hat bereits Fenster, daher beide Phasen direkt nacheinander. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {
        processBuildingLeading(building, paramLoader, stats);
        processBuildingRemaining(building, paramLoader, stats);
    }

    /** Pipeline-Einstiegspunkt Phase 1 (laeuft VOR dem WindowGenerator). */
    protected void processBuildingLeading(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {
        Gallery gallery = resolveGallery(building, paramLoader);
        if (gallery == null) return;
        for (AbstractBuilding target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, gallery, stats, true);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    /** Pipeline-Einstiegspunkt Phase 2 (laeuft NACH dem WindowGenerator). */
    protected void processBuildingRemaining(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {
        Gallery gallery = resolveGallery(building, paramLoader);
        if (gallery == null) return;
        for (AbstractBuilding target : CityGmlUtils.getBuildingTargets(building)) {
            processAbstractBuilding(target, gallery, stats, false);
            CityGmlUtils.rebuildSolidShell(target);
        }
    }

    private static Gallery resolveGallery(Building building, ModuleParametersLoader paramLoader) {
        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return null;
        ModuleParameters params = bp.params();
        Gallery gallery = params.getGallery();
        return (gallery != null && gallery.isValid()) ? gallery : null;
    }

    /** Verarbeitet jede eligible Wand eines AbstractBuilding gegen die gewaehlte Phase. */
    private void processAbstractBuilding(AbstractBuilding target, Gallery gallery,
            GenerationStats stats, boolean leadingPhase) {

        // Kopie: neu erzeugte Waende duerfen die Iteration nicht beeinflussen.
        List<WallSurface> walls = new ArrayList<>(CityGmlUtils.collectWallSurfaces(target));
        Point3D centroid = computeFootprintCentroid(walls);
        GaPaSequence sequence = parseGaPattern(gallery.pattern);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            CityGmlUtils.BottomEdge edge = bottomEdgeOf(wall);
            if (!isEligibleForBalcony(wall, geschoss, edge, gallery)) continue;
            if (leadingPhase) {
                placeLeadingBalconies(target, wall, geschoss, gallery, sequence, stats, centroid);
            } else {
                placeRemainingPatternBalconies(target, wall, geschoss, gallery, sequence, stats, centroid);
            }
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

    /** Eligibilitaet einer Wand fuer Balkone anhand WindowPreference, Geschoss und Mindestlaenge (siehe Doku.md). */
    private boolean isEligibleForBalcony(WallSurface wall, String geschoss,
            CityGmlUtils.BottomEdge edge, Gallery gallery) {
        if (geschoss == null) return false;
        boolean isGfOrUf = "GF".equals(geschoss) || geschoss.startsWith("UF_");
        if (!isGfOrUf) return false;
        if ("1".equals(CityGmlUtils.getStringAttribute(wall, "Innenwand"))) return false;
        double minLength = gallery.length + safe(gallery.hDistMinWallGallery, 0.0);
        if (edge == null || edge.wallLength() < minLength) return false;

        WindowPreference pref = WindowPreference.parse(
                CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
        if (pref == WindowPreference.NONE) return false;
        if (pref == WindowPreference.ABOVE_NEIGHBOR) {
            Double zFensterAsl = parseZFensterAsl(wall);
            if (zFensterAsl == null) return false; // Schwelle unbekannt: sicherheitshalber ablehnen
            if (zFensterAsl >= edge.zMax()) return false; // Geschoss komplett verdeckt (wie WindowGenerator)
        }
        return true;
    }

    /** Liest {@code Z_Fenster_ASL} (Hoehe, ab der WindowPreference=2-Waende frei sind), oder null. */
    private static Double parseZFensterAsl(WallSurface wall) {
        String s = CityGmlUtils.getStringAttribute(wall, "Z_Fenster_ASL");
        if (s == null) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
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

    /** Gemeinsame Wand-Geometrie fuer beide Balkon-Phasen (siehe {@link #resolveWallGeometry}). */
    private record WallGeom(
            CityGmlUtils.BottomEdge edge, Polygon wallPoly, List<Point3D> open,
            double gaLen, double gaWid, double gaHe,
            double hDistWallGallery, double hDistGalleryGallery, double hDistWindowGallery,
            double distDoorGallery, double doorWidth, double doorHeight,
            double dirX, double dirY, double normX, double normY, boolean extCCW,
            double zMin, double doorBottomZ, double doorTopZ, double deckZ, double railTopZ,
            double[][] wallPoly2D, String wallFaceId) {}

    /** Liest Wandkontur + Gallery-Parameter der Wand; null bei geometrischem Fruehabbruch. */
    private WallGeom resolveWallGeometry(WallSurface wall, Gallery gallery,
            Point3D footprintCentroid, GenerationStats stats) {

        Polygon wallPoly = CityGmlUtils.getWallPolygon(wall);
        if (wallPoly == null) return null;
        List<Point3D> open = CityGmlUtils.removeClosingPoint(CityGmlUtils.toPoints(wallPoly));
        if (open.size() < 3) return null;

        CityGmlUtils.BottomEdge edge = CityGmlUtils.findBottomEdge(open);
        if (edge == null) { stats.wallsSkipped++; return null; }

        double gaLen = gallery.length;
        double gaWid = gallery.width;
        double gaHe = (gallery.height != null && gallery.height > 0) ? gallery.height : 1.0;
        double hDistWallGallery = safe(gallery.hDistWallGallery, 0.0);
        double hDistGaGa = safe(gallery.hDistGalleryGallery, 1.0);
        double hDistWindowGallery = safe(gallery.hDistWindowGallery, 0.0);
        double distDoorGallery = safe(gallery.distDoorGallery, 0.0);

        double doorWidth = (gallery.doorWidth != null && gallery.doorWidth > 0) ? gallery.doorWidth : Math.min(1.0, gaLen);
        double doorHeight = (gallery.doorHeight != null && gallery.doorHeight > 0) ? gallery.doorHeight : 2.0;

        // Analog zum WindowGenerator: bei WindowPreference=2 beginnt der nutzbare Bereich erst bei Z_Fenster_ASL (siehe Doku.md).
        double effectiveFloorZ = edge.zMin();
        WindowPreference pref = WindowPreference.parse(
                CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
        if (pref == WindowPreference.ABOVE_NEIGHBOR) {
            Double zFensterAsl = parseZFensterAsl(wall);
            if (zFensterAsl != null && zFensterAsl > effectiveFloorZ) {
                effectiveFloorZ = zFensterAsl;
            }
        }

        double wallLength = edge.wallLength();
        double usableHeight = edge.zMax() - effectiveFloorZ;
        if (doorHeight + DOOR_SILL_HEIGHT > usableHeight) { stats.wallsSkipped++; return null; }
        if (doorWidth > gaLen) { stats.wallsSkipped++; return null; }

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
        double doorBottomZ = CityGmlUtils.roundZ(effectiveFloorZ + DOOR_SILL_HEIGHT);
        double doorTopZ = CityGmlUtils.roundZ(doorBottomZ + doorHeight);
        double deckZ = doorBottomZ;
        double railTopZ = CityGmlUtils.roundZ(deckZ + gaHe);

        double[][] wallPoly2D = CityGmlUtils.projectWallTo2D(open, edge.start(), dirX, dirY, zMin);

        String wallFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
        if (wallFaceId == null) wallFaceId = wall.getId() != null ? wall.getId() : "unknown";

        return new WallGeom(edge, wallPoly, open, gaLen, gaWid, gaHe,
                hDistWallGallery, hDistGaGa, hDistWindowGallery, distDoorGallery,
                doorWidth, doorHeight, dirX, dirY, normX, normY, extCCW,
                zMin, doorBottomZ, doorTopZ, deckZ, railTopZ, wallPoly2D, wallFaceId);
    }

    /** Laenge des FUEHRENDEN zusammenhaengenden Ga-Laufs (0, falls das Muster nicht mit Ga beginnt). */
    private static int leadingGalleryRunLength(List<String> tokens) {
        int k = 0;
        while (k < tokens.size() && "Ga".equals(tokens.get(k))) k++;
        return k;
    }

    /** Offsets eines k-Balkon-Laufs ab einem festen linken Rand (statt Zentrums-Anker). */
    private static double[] layoutGaRunFromStart(int k, double gaLen, double hDistGaGa, double blockStart) {
        double[] offsets = new double[k];
        for (int j = 0; j < k; j++) offsets[j] = blockStart + j * (gaLen + hDistGaGa);
        return offsets;
    }

    /**
     * Phase 1: platziert den fuehrenden Ga-Lauf einer Wand unabhaengig von Fenstern, verankert
     * ueber HDistWaGa (Wandanfang), mit HDistGaGa-Abstand innerhalb des Laufs. Reserviert die
     * belegte Spanne als Wand-Attribut fuer den WindowGenerator (siehe Doku.md).
     */
    private void placeLeadingBalconies(AbstractBuilding target, WallSurface wall, String geschoss,
            Gallery gallery, GaPaSequence sequence, GenerationStats stats, Point3D footprintCentroid) {

        int k = leadingGalleryRunLength(sequence.tokens());
        if (k == 0) return;

        WallGeom g = resolveWallGeometry(wall, gallery, footprintCentroid, stats);
        if (g == null) return;

        double total = k * g.gaLen() + (k - 1) * g.hDistGalleryGallery();
        double maxStart = g.edge().wallLength() - total - safe(gallery.hDistMinWallGallery, 0.0);
        double blockStart = Math.min(g.hDistWallGallery(), maxStart);
        if (blockStart < 0) { stats.balconiesSkippedRunTooWide++; return; }

        double[] offsets = layoutGaRunFromStart(k, g.gaLen(), g.hDistGalleryGallery(), blockStart);
        String wallFaceId = g.wallFaceId();
        int placed = 0;
        double minOffset = Double.POSITIVE_INFINITY, maxOffset = Double.NEGATIVE_INFINITY;

        for (int j = 0; j < k; j++) {
            String balconyId = wallFaceId + "_Ga_" + (placed + 1);
            if (!tryPlaceOneBalcony(target, wall, g, geschoss, offsets[j], balconyId, stats)) continue;
            placed++;
            minOffset = Math.min(minOffset, offsets[j]);
            maxOffset = Math.max(maxOffset, offsets[j] + g.gaLen());
        }
        stats.balconyCountHistogram.merge(placed, 1, Integer::sum);
        if (placed == 0) return;

        double wallAreaBefore = resolveCurrentWallArea(wall, g.open());
        double doorAreaTotal = placed * g.doorWidth() * g.doorHeight();
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                CityGmlUtils.formatNum(Math.max(0, wallAreaBefore - doorAreaTotal)));
        stats.wallsWithBalconies++;

        // Ausschluss-Zone fuer den WindowGenerator (dort duerfen keine Fenster mehr entstehen).
        // HDistWiGa wird hier schon eingerechnet (WindowGenerator kennt keine Gallery-Parameter),
        // damit auch links/rechts vom Balkon kein Fenster direkt bündig angrenzt.
        double reservedLo = Math.max(0, minOffset - g.hDistWindowGallery());
        double reservedHi = maxOffset + g.hDistWindowGallery();
        CityGmlUtils.addStringAttribute(wall, "GaReservedSpan",
                CityGmlUtils.formatNum(reservedLo) + "," + CityGmlUtils.formatNum(reservedHi));
    }

    /**
     * Phase 2: platziert restliche Ga-Token eines Musters NACH dem fuehrenden Lauf (z.B. das
     * dritte Token bei "GaWiGaWi") gegen die inzwischen echten, vom WindowGenerator platzierten
     * Fenster. Verankert ueber HDistWiGa ab dem letzten ueberlebenden Nachbarfenster statt zu
     * zentrieren — siehe Doku.md fuer die HDistGaGa/HDistWiGa-Abgrenzung.
     */
    private void placeRemainingPatternBalconies(AbstractBuilding target, WallSurface wall, String geschoss,
            Gallery gallery, GaPaSequence sequence, GenerationStats stats, Point3D footprintCentroid) {

        List<String> tokens = sequence.tokens();
        int leadK = leadingGalleryRunLength(tokens);
        List<String> remaining = tokens.subList(leadK, tokens.size());
        if (!remaining.contains("Ga")) return;

        WallGeom g = resolveWallGeometry(wall, gallery, footprintCentroid, stats);
        if (g == null) return;

        List<WindowSlot> slots = collectSortedWindowSlots(wall, g.edge().start(), g.dirX(), g.dirY());
        int n = Math.min(remaining.size(), slots.size());

        int excessGaTokens = 0;
        for (int t = n; t < remaining.size(); t++) if ("Ga".equals(remaining.get(t))) excessGaTokens++;
        stats.galleryTokensUnfulfilled += excessGaTokens;

        if (slots.isEmpty()) {
            stats.wallsWithNoWindowsForPattern++;
            stats.wallsSkipped++;
            return;
        }

        double wallAreaBefore = resolveCurrentWallArea(wall, g.open());
        double areaRestoredFromRemovedWindows = 0;
        int placed = 0;
        String wallFaceId = g.wallFaceId();

        int i = 0;
        while (i < n) {
            if (!"Ga".equals(remaining.get(i))) { i++; continue; }
            int runStart = i;
            while (i < n && "Ga".equals(remaining.get(i))) i++;
            int runEnd = i; // exklusiv
            int k = runEnd - runStart;
            List<WindowSlot> runSlots = slots.subList(runStart, runEnd);

            // HDistWiGa ab dem letzten ueberlebenden Nachbarfenster (garantiert vorhanden, da
            // Phase 1 einen direkt am Wandanfang stehenden fuehrenden Lauf bereits konsumiert hat).
            double blockStart;
            if (runStart > 0) {
                blockStart = slots.get(runStart - 1).uHi() + g.hDistWindowGallery();
            } else {
                double total = k * g.gaLen() + (k - 1) * g.hDistGalleryGallery();
                blockStart = (runSlots.get(0).uMid() + runSlots.get(k - 1).uMid()) / 2.0 - total / 2.0;
            }
            double[] offsets = layoutGaRunFromStart(k, g.gaLen(), g.hDistGalleryGallery(), blockStart);

            for (int j = 0; j < k; j++) {
                WindowSlot slot = runSlots.get(j);
                double galleryOffset = offsets[j];

                // Mindestabstand zu Nachbarfenstern ausserhalb dieses Laufs.
                if (hasWindowClearanceConflict(wall, runSlots, g.edge().start(), g.dirX(), g.dirY(),
                        galleryOffset, galleryOffset + g.gaLen(), g.hDistWindowGallery())) {
                    stats.balconiesSkippedNeighborConflict++;
                    continue;
                }

                String balconyId = wallFaceId + "_Ga_" + (leadK + placed + 1);
                if (!tryPlaceOneBalcony(target, wall, g, geschoss, galleryOffset, balconyId, stats)) continue;

                // --- Ersetztes Fenster entfernen (FillingSurface + Innenring), Flaeche zurueckbuchen ---
                areaRestoredFromRemovedWindows += parseAreaOrZero(slot.window());
                CityGmlUtils.removeMatchingInteriorRing(g.wallPoly(), slot.points());
                removeFillingSurfaceByIdentity(wall, slot.prop());
                stats.windowsRemovedForBalcony++;
                placed++;
            }
        }

        stats.balconyCountHistogram.merge(placed, 1, Integer::sum);
        if (placed == 0) return;

        double doorAreaTotal = placed * g.doorWidth() * g.doorHeight();
        double wallAreaAfter = wallAreaBefore + areaRestoredFromRemovedWindows - doorAreaTotal;
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                CityGmlUtils.formatNum(Math.max(0, wallAreaAfter)));
        stats.wallsWithBalconies++;
    }

    /**
     * Prueft Wandkontur + Tuer-Konflikt und baut bei Erfolg Zugangstuer, Deck und Bruestung eines
     * einzelnen Balkons an der gegebenen Wand-u-Position. False = abgelehnt (Grund in stats).
     */
    private boolean tryPlaceOneBalcony(AbstractBuilding target, WallSurface wall, WallGeom g,
            String geschoss, double galleryOffset, String balconyId, GenerationStats stats) {

        if (!CityGmlUtils.openingInsideWall2D(galleryOffset, galleryOffset + g.gaLen(),
                g.doorBottomZ() - g.zMin(), g.railTopZ() - g.zMin(), g.wallPoly2D())) {
            stats.balconiesSkippedRunTooWide++;
            return false;
        }

        double doorOffset = galleryOffset + g.distDoorGallery();
        doorOffset = Math.max(galleryOffset, Math.min(doorOffset, galleryOffset + g.gaLen() - g.doorWidth()));
        if (!CityGmlUtils.openingInsideWall2D(doorOffset, doorOffset + g.doorWidth(),
                g.doorBottomZ() - g.zMin(), g.doorTopZ() - g.zMin(), g.wallPoly2D())) {
            stats.balconiesSkippedOutside++;
            return false;
        }

        // Kollision mit bestehender Tuer (z.B. Hauseingang): Balkon wird uebersprungen, Tuer bleibt.
        if (hasOpeningConflict(wall, DoorSurface.class, g.edge().start(), g.dirX(), g.dirY(),
                galleryOffset, galleryOffset + g.gaLen())) {
            stats.balconiesSkippedDoorConflict++;
            return false;
        }

        // --- Zugangsoeffnung: Loch in die Hauswand, wie ein normaler Tuer-Ausschnitt ---
        Point3D dbl = pointOn(g.edge().start(), g.dirX(), g.dirY(), doorOffset, g.doorBottomZ());
        Point3D dbr = pointOn(g.edge().start(), g.dirX(), g.dirY(), doorOffset + g.doorWidth(), g.doorBottomZ());
        Point3D dtr = pointOn(g.edge().start(), g.dirX(), g.dirY(), doorOffset + g.doorWidth(), g.doorTopZ());
        Point3D dtl = pointOn(g.edge().start(), g.dirX(), g.dirY(), doorOffset, g.doorTopZ());
        Polygon doorPoly = CityGmlUtils.addOpeningToWall(g.wallPoly(), dbl, dbr, dtr, dtl, g.extCCW());

        DoorSurface door = new DoorSurface();
        door.setId("Face_" + balconyId + "_Door");
        CityGmlUtils.setGmlName(door, "LOD3_BalconyDoor");
        door.setLod3MultiSurface(CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(doorPoly));
        CityGmlUtils.addStringAttribute(door, "BldgFaceID", balconyId + "_Door");
        CityGmlUtils.addStringAttribute(door, "FACEAREA", CityGmlUtils.formatNum(g.doorWidth() * g.doorHeight()));
        CityGmlUtils.addStringAttribute(door, "Geschoss", geschoss);
        wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(door));

        // --- Deck und Bruestung als BuildingInstallation (CityGML 1.0, siehe Doku.md Schritt 6) ---
        Point3D p1 = pointOn(g.edge().start(), g.dirX(), g.dirY(), galleryOffset, g.deckZ());
        Point3D p2 = pointOn(g.edge().start(), g.dirX(), g.dirY(), galleryOffset + g.gaLen(), g.deckZ());
        Point3D p3 = offsetOutward(p2, g.normX(), g.normY(), g.gaWid());
        Point3D p4 = offsetOutward(p1, g.normX(), g.normY(), g.gaWid());

        List<Point3D> deckRing = orientUpward(List.of(p1, p2, p3, p4));
        Polygon deckPoly = CityGmlUtils.createPolygon(deckRing);
        double deckArea = CityGmlUtils.calculatePolygonArea2D(deckRing);
        BuildingInstallation deck = new BuildingInstallation();
        deck.setId("Face_" + balconyId + "_Deck");
        CityGmlUtils.setGmlName(deck, "LOD3_BalconyDeck");
        deck.getDeprecatedProperties().setLod3Geometry(new GeometryProperty<>(deckPoly));
        CityGmlUtils.addStringAttribute(deck, "BldgFaceID", balconyId + "_Deck");
        CityGmlUtils.addStringAttribute(deck, "FACEAREA", CityGmlUtils.formatNum(deckArea));
        CityGmlUtils.addStringAttribute(deck, "Geschoss", geschoss);
        CityGmlUtils.addStringAttribute(deck, "STRUKTUR", CityGmlUtils.STRUKTUR_BALCONY_DECK);
        target.getBuildingInstallations().add(new BuildingInstallationProperty(deck));

        // Bruestung: 1 BuildingInstallation mit 3 Seiten-Polygonen (P1-P2 = Hauswand-Seite, keine eigene Flaeche)
        addRailing(target, balconyId, p1, p2, p3, p4, g.railTopZ(), geschoss);

        stats.balconiesCreated++;
        return true;
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

    /** Bruestung als eine BuildingInstallation mit 3 Seiten-Polygonen (P1-P4, P4-P3, P3-P2) in einer MultiSurface. */
    private void addRailing(AbstractBuilding target, String balconyId,
            Point3D p1, Point3D p2, Point3D p3, Point3D p4,
            double zTop, String geschoss) {
        List<Polygon> sides = List.of(
                railingSidePolygon(p1, p4, zTop),
                railingSidePolygon(p4, p3, zTop),
                railingSidePolygon(p3, p2, zTop));

        MultiSurface multiSurface = new MultiSurface();
        multiSurface.setSrsName(CityGmlUtils.SRS_NAME);
        multiSurface.setSrsDimension(CityGmlUtils.SRS_DIMENSION);
        double totalArea = 0;
        for (Polygon side : sides) {
            multiSurface.getSurfaceMember().add(new SurfaceProperty(side));
            totalArea += CityGmlUtils.calculateWallArea(CityGmlUtils.toPoints(side));
        }

        BuildingInstallation railing = new BuildingInstallation();
        String faceId = balconyId + "_Railing";
        railing.setId("Face_" + faceId);
        CityGmlUtils.setGmlName(railing, "LOD3_BalconyRailing");
        railing.getDeprecatedProperties().setLod3Geometry(new GeometryProperty<>(multiSurface));
        CityGmlUtils.addStringAttribute(railing, "BldgFaceID", faceId);
        CityGmlUtils.addStringAttribute(railing, "FACEAREA", CityGmlUtils.formatNum(totalArea));
        CityGmlUtils.addStringAttribute(railing, "Geschoss", geschoss);
        CityGmlUtils.addStringAttribute(railing, "STRUKTUR", CityGmlUtils.STRUKTUR_BALCONY_RAILING);
        target.getBuildingInstallations().add(new BuildingInstallationProperty(railing));
    }

    private static Polygon railingSidePolygon(Point3D a, Point3D b, double zTop) {
        Point3D aTop = new Point3D(a.x, a.y, zTop);
        Point3D bTop = new Point3D(b.x, b.y, zTop);
        return CityGmlUtils.createPolygon(new ArrayList<>(List.of(a, b, bTop, aTop)));
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
