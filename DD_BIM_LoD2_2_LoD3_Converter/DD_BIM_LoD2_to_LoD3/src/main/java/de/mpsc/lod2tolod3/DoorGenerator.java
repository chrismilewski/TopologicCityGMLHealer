package de.mpsc.lod2tolod3;

import de.mpsc.lod2tolod3.model.ModuleParameters;
import de.mpsc.lod2tolod3.model.WindowPreference;
import de.mpsc.lod2tolod3.util.BuildingQueryUtils;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import de.mpsc.lod2tolod3.util.GeometryUtils;
import de.mpsc.lod2tolod3.util.OpeningUtils;
import de.mpsc.lod2tolod3.util.PartyWallCoverageUtils;
import de.mpsc.lod2tolod3.util.Point3D;
import de.mpsc.lod2tolod3.util.SolidShellUtils;
import de.mpsc.lod2tolod3.util.ModuleParametersLoader;
import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurface;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.DoorSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.construction.WindowSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.*;

/**
 * Schritt 4: Tuer-Generator. Erzeugt Tueren an GF-Wandsegmenten anhand des DoorCount-Attributs
 * (siehe Doku.md, Abschnitt "Schritt 4").
 *
 * Usage:
 *   java -cp lod2-zu-lod3.jar de.mpsc.lod2tolod3.DoorGenerator input.gml jsonDir [output.gml]
 */
public class DoorGenerator extends AbstractGenerator<DoorGenerator.GenerationStats> {

    /** Tuersockelhoehe: 5 cm ueber Wandunterkante. */
    private static final double DOOR_SILL_HEIGHT = 0.05;

    /** Minimaler Abstand zwischen Tueren und zum Wandrand (10 cm). */
    private static final double MIN_SPACING = 0.10;

    public static void main(String[] args) {
        DoorGenerator gen = new DoorGenerator();
        try {
            gen.runCli(args);
        } catch (Exception e) {
            gen.log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    @Override protected String outputSuffix() { return "_doors"; }
    @Override protected String displayName()  { return "Tuer-Generator"; }
    @Override protected GenerationStats newStats() { return new GenerationStats(); }

    @Override
    protected void logResult(GenerationStats stats) {
        log.info("Tueren erzeugt: {}", stats.doorsCreated);
        log.info("Waende modifiziert: {}", stats.wallsModified);
        log.info("Waende uebersprungen: {}", stats.wallsSkipped);
        log.info("Tueren hinter Anbau verworfen: {}", stats.doorsSkippedCovered);
        log.info("Tueren ausserhalb Wandkontur verworfen: {}", stats.doorsSkippedOutside);
        log.info("Waende uebersprungen (geteilt zwischen BuildingParts): {}", stats.wallsSkippedCoveredByPart);
        log.info("Fallback-Tueren (Gebaeude mit Fenstern aber ohne Tuer): {}", stats.fallbackDoorsCreated);
        log.info("Fallback-Tuer-Versuche an Fenster-Konflikt gescheitert (vor Force-Modus): {}", stats.doorsSkippedWindowOverlap);
        log.info("Fenster fuer Fallback-Tuer entfernt: {}", stats.windowsRemovedForFallbackDoor);
    }

    // ==================== Gebaeude-Verarbeitung ====================

    /** Verarbeitet ein Gebaeude: delegiert an processAbstractBuilding fuer Building und BuildingParts. */
    @Override
    protected void processBuilding(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters params = bp.params();

        if (params.getGroundFloor() == null) return;
        ModuleParameters.DoorParams doorParams = params.getGroundFloor().door;
        if (doorParams == null || !doorParams.isValid()) return;

        List<AbstractBuilding> targets = BuildingQueryUtils.getBuildingTargets(building);

        // Fuer die Party-Wand-Deckungspruefung (Anbau-Trennwaende, siehe Doku.md): alle Waende
        // des Gebaeudes ueber ALLE Targets hinweg, nicht nur die des jeweils aktuellen.
        List<WallSurface> allWallsInBuilding = BuildingQueryUtils.collectAllWallSurfaces(building);

        for (var target : targets) {
            processAbstractBuilding(target, doorParams, allWallsInBuilding, stats);
            SolidShellUtils.rebuildSolidShell(target);
        }
    }

    /** Fallback-Schritt (in der Pipeline NACH Fenstern/Dachfenstern verdrahtet, siehe Doku.md):
     * Gebaeude mit mindestens einem Fenster (Wand- oder Dachfenster) aber ganz ohne Tuer (in den
     * LoD2-Quelldaten ueberall DoorCount=0 — bestaetigte Adresspunkt-/ALKIS-Datenluecke, kein
     * Pipeline-Fehler) bekommen genau eine Tuer an der breitesten geeigneten EG-Wand — ein
     * bewohntes Gebaeude ohne Tuer ist unrealistisch, die Tuer MUSS gesetzt werden. Da dieser
     * Schritt nach den Fenstern laeuft (anders als der normale Tuer-Schritt), kann die gewaehlte
     * Wand bereits echte Fenster tragen: zuerst wird eine fensterfreie Position gesucht
     * (Standardposition, sonst freie Abschnitte daneben, siehe {@link #computeFreeUSections}) —
     * findet sich keine, werden als letzter Ausweg kollidierende Fenster an der Standardposition
     * entfernt (siehe {@link #processWall}, {@code forceWindowRemoval}); weniger oder auch keine
     * Fenster mehr an dieser Wand sind dann der bewusst in Kauf genommene Preis fuer die Tuer.
     * Waende mit {@code WindowPreference} NONE/ABOVE_NEIGHBOR werden komplett ausgeschlossen —
     * sie sind (ganz oder im unteren, tuerrelevanten Bereich) durch ein Nachbargebaeude verdeckt
     * (Reihenhaus/Doppelhaushaelfte), dort waere eine Tuer architektonisch falsch. */
    public void processFallbackDoors(Building building, ModuleParametersLoader paramLoader,
            GenerationStats stats) {

        BuildingParams bp = resolveParams(building, paramLoader).orElse(null);
        if (bp == null) return;
        ModuleParameters params = bp.params();
        if (params.getGroundFloor() == null) return;
        ModuleParameters.DoorParams doorParams = params.getGroundFloor().door;
        if (doorParams == null || !doorParams.isValid()) return;

        List<AbstractBuilding> targets = BuildingQueryUtils.getBuildingTargets(building);

        // Bestandsaufnahme ueber ALLE Targets: gibt es schon eine Tuer? Gibt es ueberhaupt ein
        // Fenster (Wand- ODER Dachfenster)?
        boolean hasDoor = false;
        boolean hasWindow = false;
        List<Object[]> gfCandidates = new ArrayList<>(); // {WallSurface, AbstractBuilding}
        for (AbstractBuilding target : targets) {
            for (WallSurface wall : BuildingQueryUtils.collectWallSurfaces(target)) {
                for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
                    AbstractFillingSurface fs = fsp.getObject();
                    if (fs instanceof DoorSurface) hasDoor = true;
                    if (fs instanceof WindowSurface) hasWindow = true;
                }
                boolean isGf = "GF".equals(CityGmlUtils.getStringAttribute(wall, "Geschoss"));
                WindowPreference wp = WindowPreference.parse(
                        CityGmlUtils.getStringAttribute(wall, "WindowPreference"));
                if (isGf && wp == WindowPreference.NORMAL) {
                    gfCandidates.add(new Object[]{wall, target});
                }
            }
            for (RoofSurface roof : BuildingQueryUtils.collectBoundariesByType(target, RoofSurface.class)) {
                for (AbstractFillingSurfaceProperty fsp : roof.getFillingSurfaces()) {
                    if (fsp.getObject() instanceof WindowSurface) hasWindow = true;
                }
            }
        }
        if (hasDoor || !hasWindow || gfCandidates.isEmpty()) return;

        // Breiteste Wand zuerst versuchen (FACEAREA-Attribut, Parse-Fehler → 0.0 landet hinten).
        gfCandidates.sort((a, b) -> Double.compare(
                faceAreaOf((WallSurface) b[0]), faceAreaOf((WallSurface) a[0])));

        List<WallSurface> allWallsInBuilding = BuildingQueryUtils.collectAllWallSurfaces(building);
        double doorWidth = doorParams.doorWidth;

        // HDistDoWi (Abstand Tuer-Fenster): dieselbe JSON-Vorgabe, die WindowGenerator schon in
        // umgekehrter Richtung nutzt (neue Fenster von vorhandenen Tueren fernhalten), gilt auch
        // hier fuer die neue Fallback-Tuer gegenueber vorhandenen Fenstern auf derselben Wand.
        double hDistDoWi = params.getGroundFloor().window != null
                ? ModuleParameters.WindowParams.safeValue(params.getGroundFloor().window.hDistDoorWindow)
                : 0.0;

        for (Object[] candidate : gfCandidates) {
            WallSurface wall = (WallSurface) candidate[0];
            AbstractBuilding target = (AbstractBuilding) candidate[1];

            List<ExistingWindow> existingWindows = computeExistingWindows(wall, hDistDoWi);

            // 1. Versuch: Standardposition (HDistDoWa ab Wandanfang bzw. zentriert), Fenster bleiben unangetastet.
            int placed = processWall(wall, 1, false, doorParams, allWallsInBuilding,
                    existingWindows, false, null, stats);

            // 2. Versuch, falls die Standardposition an einem vorhandenen Fenster scheitert:
            // freie Wandabschnitte NEBEN den Fenstern probieren (groesster zuerst) — verliert kein
            // einziges Fenster, wenn irgendwo genug Platz ist.
            if (placed == 0 && !existingWindows.isEmpty()) {
                double wallLength = computeWallLength(wall);
                if (wallLength > 0) {
                    for (double offset : computeFreeUSections(wallLength, existingWindows, doorWidth)) {
                        placed = processWall(wall, 1, false, doorParams, allWallsInBuilding,
                                existingWindows, false, offset, stats);
                        if (placed > 0) break;
                    }
                }
            }

            // 3. Letzter Ausweg: eine Tuer ist wichtiger als jedes Fenster an dieser Wand (siehe
            // Doku.md) — Standardposition erzwingen, dabei kollidierende Fenster entfernen. Nur
            // Anbau-Verdeckung/Wandkontur koennen das noch verhindern, keine Fenster mehr.
            if (placed == 0) {
                placed = processWall(wall, 1, false, doorParams, allWallsInBuilding,
                        existingWindows, true, null, stats);
            }

            if (placed > 0) {
                SolidShellUtils.rebuildSolidShell(target);
                stats.fallbackDoorsCreated++;
                log.info("Fallback-Tuer erzeugt fuer Gebaeude {} an Wand {}",
                        building.getId(), wall.getId());
                return;
            }
        }
        log.info("Kein geeigneter Wandkandidat fuer Fallback-Tuer bei Gebaeude {}", building.getId());
    }

    /** 2D-Laenge der Wand-Unterkante, oder 0 wenn nicht bestimmbar (z.B. degeneriertes Polygon). */
    private static double computeWallLength(WallSurface wall) {
        Polygon wallPoly = BuildingQueryUtils.getWallPolygon(wall);
        if (wallPoly == null) return 0;
        List<Point3D> open = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(wallPoly));
        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(open);
        return edge != null ? edge.wallLength() : 0;
    }

    /** Liefert Kandidaten-Offsets (linke Kante) fuer eine Tuer der Breite {@code doorWidth} in
     * freien Wandabschnitten NEBEN den gegebenen 2D-Sperrrechtecken {uMin,uMax,vMin,vMax} —
     * jeder Abschnitt zentriert, groesster Abschnitt zuerst. Nur die u-Ausdehnung der Rechtecke
     * zaehlt hier (die Hoehen-Pruefung uebernimmt bereits {@link CityGmlUtils#overlapsAnyOpeningRect}
     * in {@link #processWall} als Sicherheitsnetz); ein Fenster hoch oben wuerde also theoretisch
     * unnoetig einen Abschnitt sperren, das ist aber unkritisch — bei einer einzelnen Fallback-Tuer
     * bleiben in der Praxis genug Abschnitte uebrig. */
    private static List<Double> computeFreeUSections(double wallLength,
            List<ExistingWindow> existingWindows, double doorWidth) {
        List<double[]> sorted = new ArrayList<>();
        for (ExistingWindow w : existingWindows) sorted.add(new double[]{w.uMin(), w.uMax()});
        sorted.sort(Comparator.comparingDouble(r -> r[0]));

        List<double[]> free = new ArrayList<>(); // {start, end}
        double cursor = 0;
        for (double[] r : sorted) {
            double lo = Math.max(0, r[0]);
            double hi = Math.min(wallLength, r[1]);
            if (lo > cursor + MIN_SPACING) {
                free.add(new double[]{cursor, lo});
            }
            cursor = Math.max(cursor, hi);
        }
        if (cursor < wallLength - MIN_SPACING) {
            free.add(new double[]{cursor, wallLength});
        }

        double minSectionWidth = doorWidth + 2 * MIN_SPACING;
        List<double[]> viable = new ArrayList<>();
        for (double[] sec : free) {
            if (sec[1] - sec[0] >= minSectionWidth) viable.add(sec);
        }
        viable.sort((a, b) -> Double.compare((b[1] - b[0]), (a[1] - a[0])));

        List<Double> offsets = new ArrayList<>();
        for (double[] sec : viable) {
            offsets.add(sec[0] + (sec[1] - sec[0] - doorWidth) / 2.0);
        }
        return offsets;
    }

    /** Liest das FACEAREA-Attribut einer Wand fuer die Fallback-Tuer-Kandidatensortierung. */
    private static double faceAreaOf(WallSurface wall) {
        String s = CityGmlUtils.getStringAttribute(wall, "FACEAREA");
        if (s == null) return 0.0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
    }

    /** Eine bestehende Fenster-Oeffnung einer Wand, so wie sie die Fallback-Tuer braucht: die 2D-
     * Sperrspanne {@code {uMin,uMax,vMin,vMax}} (uMin/uMax bereits um HDistDoWi gepuffert, siehe
     * {@link #computeExistingWindows}) fuer den Konflikt-Check, plus alles Noetige, um das Fenster
     * im Ernstfall wieder zu entfernen ({@code prop} fuer die FillingSurface-Liste der Wand,
     * {@code points} fuer den Innenring, {@code area} fuer die FACEAREA-Korrektur). */
    private record ExistingWindow(AbstractFillingSurfaceProperty prop, List<Point3D> points,
            double uMin, double uMax, double vMin, double vMax, double area) {}

    /** Bestehende Fenster-Oeffnungen einer Wand (fuer die Fallback-Tuer, siehe
     * {@link #processFallbackDoors}). {@code hDistDoWi} (horizontaler Mindestabstand Tuer-Fenster
     * aus der JSON) wird bereits hier als u-Puffer auf jede Spanne aufgeschlagen, analog zu
     * {@code WindowGenerator.extractFreeSections}, die denselben Parameter in umgekehrter Richtung
     * nutzt — nur die u-Ausdehnung, nicht die Hoehe, da HDistDoWi ein rein horizontaler Abstand ist. */
    private static List<ExistingWindow> computeExistingWindows(WallSurface wall, double hDistDoWi) {
        Polygon wallPoly = BuildingQueryUtils.getWallPolygon(wall);
        if (wallPoly == null) return List.of();
        List<Point3D> open = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(wallPoly));
        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(open);
        if (edge == null) return List.of();
        double wallLength = edge.wallLength();
        double dirX = (edge.end().x - edge.start().x) / wallLength;
        double dirY = (edge.end().y - edge.start().y) / wallLength;
        Point3D edgeStart = edge.start();
        double zMin = edge.zMin();

        List<ExistingWindow> result = new ArrayList<>();
        for (AbstractFillingSurfaceProperty fsp : wall.getFillingSurfaces()) {
            AbstractFillingSurface fs = fsp.getObject();
            if (!(fs instanceof WindowSurface) || fs.getLod3MultiSurface() == null
                    || fs.getLod3MultiSurface().getObject() == null) continue;
            double uMin = Double.POSITIVE_INFINITY, uMax = Double.NEGATIVE_INFINITY;
            double vMin = Double.POSITIVE_INFINITY, vMax = Double.NEGATIVE_INFINITY;
            List<Point3D> winPoints = List.of();
            for (var member : fs.getLod3MultiSurface().getObject().getSurfaceMember()) {
                if (!(member.getObject() instanceof Polygon winPoly)) continue;
                winPoints = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(winPoly));
                for (Point3D p : winPoints) {
                    double u = (p.x - edgeStart.x) * dirX + (p.y - edgeStart.y) * dirY;
                    uMin = Math.min(uMin, u);
                    uMax = Math.max(uMax, u);
                    vMin = Math.min(vMin, p.z - zMin);
                    vMax = Math.max(vMax, p.z - zMin);
                }
            }
            if (uMin <= uMax) {
                result.add(new ExistingWindow(fsp, winPoints, uMin - hDistDoWi, uMax + hDistDoWi,
                        vMin, vMax, parseAreaOrZero(fs)));
            }
        }
        return result;
    }

    /** Liest das FACEAREA-Attribut einer FillingSurface, oder 0 wenn fehlend/ungueltig. */
    private static double parseAreaOrZero(AbstractFillingSurface fs) {
        String s = CityGmlUtils.getStringAttribute(fs, "FACEAREA");
        if (s == null) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Entfernt genau die FillingSurface-Property mit dieser Objektidentitaet (nicht per Span-Scan). */
    private static void removeFillingSurfaceByIdentity(WallSurface wall, AbstractFillingSurfaceProperty target) {
        var it = wall.getFillingSurfaces().iterator();
        while (it.hasNext()) {
            if (it.next() == target) { it.remove(); return; }
        }
    }

    /** Sucht alle GF-WallSurfaces mit DoorCount-Attribut und fuegt Tueren ein. */
    private void processAbstractBuilding(AbstractBuilding target,
            ModuleParameters.DoorParams doorParams, List<WallSurface> allWallsInBuilding,
            GenerationStats stats) {

        List<WallSurface> walls = BuildingQueryUtils.collectWallSurfaces(target);

        for (WallSurface wall : walls) {
            String geschoss = CityGmlUtils.getStringAttribute(wall, "Geschoss");
            if (!"GF".equals(geschoss)) continue;

            String doorCountStr = CityGmlUtils.getStringAttribute(wall, "DoorCount");
            if (doorCountStr == null) continue;

            int doorCount;
            try {
                doorCount = Integer.parseInt(doorCountStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (doorCount < -1) continue; // ungueltig

            // 0 = keine Tuer; -1 = Hintertuer (1 Tuer mit Hintertuer-Attribut)
            if (doorCount == 0) continue;

            if (doorCount == -1) {
                processWall(wall, 1, true, doorParams, allWallsInBuilding, List.of(), false, null, stats);
            } else {
                processWall(wall, doorCount, false, doorParams, allWallsInBuilding, List.of(), false, null, stats);
            }
        }
    }

    // ==================== Wand-Verarbeitung ====================

    /** Fuegt doorCount Tueren in eine GF-WallSurface ein (siehe Doku.md Schritt 4). Liefert die
     * Anzahl tatsaechlich platzierter Tueren zurueck. {@code existingWindows} sind bereits
     * vorhandene Fenster auf dieser Wand (bei der Fallback-Tuer, siehe {@link #processFallbackDoors})
     * — im Normalfall leer, da Tueren immer vor Fenstern laufen. Kollidiert eine Tuer mit einem
     * davon: bei {@code forceWindowRemoval=false} wird die Tuer verworfen (Zaehler
     * {@code doorsSkippedWindowOverlap}); bei {@code true} wird stattdessen das Fenster entfernt
     * (Innenring + FillingSurface, FACEAREA korrigiert) und die Tuer trotzdem gesetzt — eine Tuer
     * ist wichtiger als ein Fenster an dieser Stelle (siehe Doku.md). {@code forcedOffset}
     * ueberschreibt (nur sinnvoll bei doorCount==1) die normale HDistDoWa-/Zentrier-Positionierung
     * mit einer konkreten linken Kante — fuer die Fallback-Tuer, wenn die Standardposition an
     * einem vorhandenen Fenster scheitert und stattdessen ein freier Wandabschnitt daneben
     * probiert wird; {@code null} im Normalfall (unveraendertes Verhalten). */
    private int processWall(WallSurface wall, int doorCount, boolean isHintertuer,
            ModuleParameters.DoorParams doorParams, List<WallSurface> allWallsInBuilding,
            List<ExistingWindow> existingWindows, boolean forceWindowRemoval,
            Double forcedOffset, GenerationStats stats) {

        Polygon wallPoly = BuildingQueryUtils.getWallPolygon(wall);
        if (wallPoly == null) return 0;

        List<Point3D> allPoints = GeometryUtils.toPoints(wallPoly);
        List<Point3D> open = GeometryUtils.removeClosingPoint(allPoints);
        if (open.size() < 3) return 0;

        double doorWidth = doorParams.doorWidth;
        double doorHeight = doorParams.doorHeight;
        double hDistDoorWall = (doorParams.hDistDoorWall != null) ? doorParams.hDistDoorWall : 0.5;

        // --- Unterkante der Wand ermitteln (laengste Kante bei zMin) ---
        GeometryUtils.BottomEdge edge = GeometryUtils.findBottomEdge(open);
        if (edge == null) {
            log.warn("Keine Unterkante gefunden fuer Wand {}", wall.getId());
            stats.wallsSkipped++;
            return 0;
        }
        double zMin = edge.zMin();
        double wallHeight = edge.zMax() - edge.zMin();

        // Validierung: Tuerhoehe + Sockel muss in die Wand passen
        if (doorHeight + DOOR_SILL_HEIGHT > wallHeight) {
            log.warn("Tuer passt nicht in Wand {} (Tuerhoehe {} + Sockel > Wandhoehe {})",
                    wall.getId(), GeometryUtils.formatNum(doorHeight),
                    GeometryUtils.formatNum(wallHeight));
            stats.wallsSkipped++;
            return 0;
        }

        Point3D edgeStart = edge.start();
        Point3D edgeEnd = edge.end();
        double wallLength = edge.wallLength();

        // --- Richtungsvektoren der Unterkante ---
        double dx = edgeEnd.x - edgeStart.x;
        double dy = edgeEnd.y - edgeStart.y;
        double dirX = dx / wallLength;
        double dirY = dy / wallLength;

        // Party-Wand-Deckung: Abschnitte, an denen ein anderer Gebaeudeteil (Anbau) exakt
        // dieselbe Bodenkante beansprucht — dort waeren Tueren physisch verborgen. Ist die
        // GESAMTE Wand betroffen, komplett ueberspringen; sonst werden einzelne Tueren
        // spaeter pro Kandidat gefiltert (siehe Doku.md).
        List<double[]> coveredSpans = PartyWallCoverageUtils.computeCoveredSpans(
                wall, allWallsInBuilding, edgeStart, dirX, dirY, wallLength, zMin);
        if (PartyWallCoverageUtils.isFullyCovered(coveredSpans, wallLength)) {
            stats.wallsSkippedCoveredByPart++;
            return 0;
        }

        // --- Tuer-Positionen berechnen ---
        double totalDoorWidth = doorCount * doorWidth;
        double requiredWidth = hDistDoorWall + totalDoorWidth;

        double[] doorLeftOffsets = new double[doorCount];

        if (forcedOffset != null) {
            doorLeftOffsets[0] = forcedOffset;
        } else if (requiredWidth <= wallLength + 0.01) {
            // Normalfall: HDistDoWa einhalten
            doorLeftOffsets[0] = hDistDoorWall;

            if (doorCount > 1) {
                double remainingSpace = wallLength - hDistDoorWall - doorWidth;
                int n = doorCount - 1;
                double spacing = (remainingSpace - n * doorWidth) / (n + 1);

                if (spacing < MIN_SPACING) {
                    log.warn("Zu wenig Abstand zwischen Tueren in Wand {} (spacing={}m)",
                            wall.getId(), GeometryUtils.formatNum(spacing));
                    stats.wallsSkipped++;
                    return 0;
                }

                for (int i = 1; i < doorCount; i++) {
                    doorLeftOffsets[i] = hDistDoorWall + i * (doorWidth + spacing);
                }
            }
        } else {
            // Fallback: Tuer(en) zentrieren wenn HDistDoWa nicht passt
            double gapBetween = (doorCount > 1) ? MIN_SPACING : 0.0;
            double minNeeded = totalDoorWidth + (doorCount - 1) * gapBetween + 2 * MIN_SPACING;

            if (minNeeded > wallLength) {
                log.warn("Wand {} zu schmal fuer {} Tuer(en) (min {}m benoetigt, Wandlaenge {}m)",
                        wall.getId(), doorCount, GeometryUtils.formatNum(minNeeded),
                        GeometryUtils.formatNum(wallLength));
                stats.wallsSkipped++;
                return 0;
            }

            log.info("HDistDoWa ({}) passt nicht in Wand {} ({}m) - Tuer(en) werden zentriert",
                    GeometryUtils.formatNum(hDistDoorWall), wall.getId(),
                    GeometryUtils.formatNum(wallLength));
            double leftMargin = (wallLength - totalDoorWidth - (doorCount - 1) * gapBetween) / 2;
            doorLeftOffsets[0] = leftMargin;
            for (int i = 1; i < doorCount; i++) {
                doorLeftOffsets[i] = leftMargin + i * (doorWidth + gapBetween);
            }
        }

        // Orientierung des Aussenrings bestimmen
        boolean extCCW = SolidShellUtils.isExteriorRingCCW(open, edgeStart, dirX, dirY);

        double doorBottomZ = GeometryUtils.roundZ(zMin + DOOR_SILL_HEIGHT);
        double doorTopZ = GeometryUtils.roundZ(zMin + DOOR_SILL_HEIGHT + doorHeight);

        // Flaeche der Wand vor Modifikation (Bruttoflaeche des Aussenrings, ohne Abzug bereits
        // vorhandener Oeffnungen — siehe FACEAREA-Korrektur am Ende).
        double wallAreaBefore = GeometryUtils.calculateWallArea(open);
        double existingWindowAreaBefore = existingWindows.stream()
                .mapToDouble(ExistingWindow::area).sum();
        double removedWindowArea = 0;
        List<ExistingWindow> liveWindows = new ArrayList<>(existingWindows);

        // --- WallFaceID fuer Tuer-IDs ---
        String wallFaceId = CityGmlUtils.getStringAttribute(wall, "BldgFaceID");
        if (wallFaceId == null) {
            wallFaceId = wall.getId() != null ? wall.getId() : "unknown";
        }

        // --- 2D-Wandkontur fuer den Oeffnungs-Check (Tuer muss vollstaendig in der Wandflaeche liegen) ---
        double[][] wallPoly2D = GeometryUtils.projectWallTo2D(open, edgeStart, dirX, dirY, zMin);
        double doorVBottom = doorBottomZ - zMin, doorVTop = doorTopZ - zMin;

        // --- Tuer-Geometrien berechnen und DoorSurfaces erzeugen ---
        int doorsPlaced = 0;

        for (int d = 0; d < doorCount; d++) {
            double offset = doorLeftOffsets[d];

            // Anbau-Verdeckung: liegt die Tuer (ganz oder teilweise) auf einem Abschnitt, den
            // ein anderer Gebaeudeteil beansprucht, wuerde sie hinter dem Anbau liegen.
            if (PartyWallCoverageUtils.overlapsAnySpan(coveredSpans, offset, offset + doorWidth,
                    PartyWallCoverageUtils.SPAN_OVERLAP_TOL)) {
                stats.doorsSkippedCovered++;
                continue;
            }

            // Kontur-Check: alle 4 Tuer-Ecken muessen im Wandpolygon liegen, mit demselben
            // Sicherheitsabstand an Seiten-/Oberkante wie bei Fenstern (siehe Doku.md,
            // "Fenster-Seitenkante-auf-Anbau-Kerbe-Fix") — verhindert eine Tuerkante, die exakt
            // auf der Wandkontur liegt. Bewusst VOR dem Fenster-Konflikt-Check: Anbau-Verdeckung
            // und Wandkontur lassen sich nie per Fenster-Entfernung reparieren, also erst pruefen,
            // bevor im Force-Modus (siehe unten) ueberhaupt ein Fenster angefasst wird.
            if (!OpeningUtils.openingInsideWallSideTopClearance2D(offset, offset + doorWidth,
                    doorVBottom, doorVTop, wallPoly2D)) {
                stats.doorsSkippedOutside++;
                continue;
            }
            // Zusaetzlich: durchquert die Wandkontur selbst die Oeffnung (z.B. "M"-foermige Wand
            // unter Satteldach mit Gaube, Tal zwischen zwei Firstspitzen) — reine Eckpunkt-Tests
            // koennen das nicht erkennen, siehe Doku.md "Fallback-Tuer durchquert Wandkontur".
            if (OpeningUtils.wallContourEntersOpening(offset, offset + doorWidth,
                    doorVBottom, doorVTop, wallPoly2D)) {
                stats.doorsSkippedOutside++;
                continue;
            }

            // Konflikt mit bereits vorhandenen Fenstern (Fallback-Tuer nach den Fenstern: hier
            // stehen bereits echte Fenster, siehe processFallbackDoors) — im Normalfall leer.
            // 2D-Vergleich (nicht nur u-Bereich): zwei Oeffnungen koennen an derselben u-Kante
            // aneinanderstossen, aber unterschiedlich hoch sein (GE_P_INTERSECTING_RINGS).
            List<ExistingWindow> conflicting = liveWindows.stream()
                    .filter(w -> !(offset + doorWidth <= w.uMin() || offset >= w.uMax()
                            || doorVTop <= w.vMin() || doorVBottom >= w.vMax()))
                    .toList();
            if (!conflicting.isEmpty()) {
                if (!forceWindowRemoval) {
                    stats.doorsSkippedWindowOverlap++;
                    continue;
                }
                // Tuer ist wichtiger als ein Fenster an dieser Stelle (siehe Doku.md) — Fenster
                // entfernen (Innenring + FillingSurface), FACEAREA am Ende entsprechend korrigiert.
                for (ExistingWindow w : conflicting) {
                    OpeningUtils.removeMatchingInteriorRing(wallPoly, w.points());
                    removeFillingSurfaceByIdentity(wall, w.prop());
                    removedWindowArea += w.area();
                    liveWindows.remove(w);
                    stats.windowsRemovedForFallbackDoor++;
                    log.info("Fenster {} fuer Fallback-Tuer an Wand {} entfernt",
                            w.prop().getObject().getId(), wall.getId());
                }
            }

            // Tuer-Eckpunkte: BL, BR, TR, TL (von aussen gesehen)
            Point3D bl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorBottomZ);
            Point3D br = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorBottomZ);
            Point3D tr = new Point3D(
                    edgeStart.x + (offset + doorWidth) * dirX,
                    edgeStart.y + (offset + doorWidth) * dirY,
                    doorTopZ);
            Point3D tl = new Point3D(
                    edgeStart.x + offset * dirX,
                    edgeStart.y + offset * dirY,
                    doorTopZ);

            // Innenring in die Wand einfuegen + DoorSurface-Polygon konsistent orientiert erzeugen
            String doorId = wallFaceId + "_Door_" + (d + 1);
            Polygon doorPoly = OpeningUtils.addOpeningToWall(wallPoly, bl, br, tr, tl, extCCW);

            DoorSurface doorSurface = new DoorSurface();
            doorSurface.setId("Face_" + doorId);
            CityGmlUtils.setGmlName(doorSurface, "LOD3_Door");
            doorSurface.setLod3MultiSurface(
                    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(doorPoly));

            // Attribute
            double doorArea = doorWidth * doorHeight;
            CityGmlUtils.addStringAttribute(doorSurface, "BldgFaceID", doorId);
            CityGmlUtils.addStringAttribute(doorSurface, "FACEAREA",
                    GeometryUtils.formatNum(doorArea));
            CityGmlUtils.addStringAttribute(doorSurface, "Geschoss", "GF");

            if (isHintertuer) {
                CityGmlUtils.addStringAttribute(doorSurface, "Hintertuer", "true");
            }

            // Als FillingSurface an der WallSurface verankern
            wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(doorSurface));
            stats.doorsCreated++;
            doorsPlaced++;
        }

        // Alle Tueren verworfen (Anbau-Verdeckung/Kontur)? → Wand unveraendert lassen
        if (doorsPlaced == 0) {
            return 0;
        }

        // FACEAREA der Wand aktualisieren: Bruttoflaeche minus verbliebene (nicht entfernte)
        // Fenster minus neue Tueroeffnungen. Im Normalfall (existingWindows leer) reduziert sich
        // das auf die urspruengliche Formel "Bruttoflaeche minus Tueren".
        double totalDoorArea = doorsPlaced * doorWidth * doorHeight;
        double remainingWindowArea = existingWindowAreaBefore - removedWindowArea;
        CityGmlUtils.setStringAttribute(wall, "FACEAREA",
                GeometryUtils.formatNum(Math.max(0, wallAreaBefore - remainingWindowArea - totalDoorArea)));

        stats.wallsModified++;
        return doorsPlaced;
    }

    // ==================== Statistiken ====================

    public static class GenerationStats extends AbstractGenerator.BaseStats {
        public int doorsCreated = 0;
        public int wallsModified = 0;
        public int wallsSkipped = 0;
        public int doorsSkippedCovered = 0; // Tueren hinter Anbau verworfen
        public int doorsSkippedOutside = 0; // Tueren ausserhalb der Wandkontur verworfen
        public int wallsSkippedCoveredByPart = 0; // Wand geometrisch von anderem BuildingPart geteilt
        public int fallbackDoorsCreated = 0; // Tueren, die processFallbackDoors ergaenzt hat
        public int doorsSkippedWindowOverlap = 0; // Fallback-Tuer wuerde vorhandenes Fenster ueberlappen
        public int windowsRemovedForFallbackDoor = 0; // Fenster fuer eine Fallback-Tuer entfernt
    }
}
