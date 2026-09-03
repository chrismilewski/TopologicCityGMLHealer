package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.util.Point3D;
import org.citygml4j.core.model.construction.WallSurface;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressionstests fuer {@link PartyWallCoverageUtils#computeCoveredSpans}, siehe Doku.md Abschnitt
 * "Bugfix: Fenster/Tueren/Balkone hinter Anbau — Party-Wand-Erkennung auf Segment-Ebene". */
class CityGmlUtilsPartyWallTest {

    private static WallSurface wallAt(List<Point3D> ring) {
        WallSurface wall = new WallSurface();
        wall.setLod3MultiSurface(
                CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(GeometryUtils.createPolygon(ring)));
        return wall;
    }

    /** Nachbau von gHh (2026-08-20): eine geknickte Hauptbau-Wand (u=0..10, Knick bei u=5) deckt
     * sich im Abschnitt u=5..10 exakt mit der Trennwand eines Anbaus. Ein Fenster links vom Knick
     * (u=1..2) muss frei bleiben, eines rechts (u=6..7) muss als verdeckt erkannt werden — nicht
     * die ganze Wand pauschal verwerfen (der urspruengliche Wand-Mittelpunkt-Dedup-Bug). */
    @Test
    void detectsPartialCoverageOnKinkedWall() {
        WallSurface mainWall = wallAt(List.of(
                new Point3D(0, 0, 3), new Point3D(0, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 3)));
        WallSurface annexWall = wallAt(List.of(
                new Point3D(5, 0, 3), new Point3D(5, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 3)));

        List<double[]> spans = PartyWallCoverageUtils.computeCoveredSpans(
                mainWall, List.of(mainWall, annexWall),
                new Point3D(0, 0, 0), 1.0, 0.0, 10.0, 0.0);

        assertFalse(PartyWallCoverageUtils.overlapsAnySpan(spans, 1, 2, PartyWallCoverageUtils.SPAN_OVERLAP_TOL),
                "Fenster links vom Knick (u=1..2) ist nicht verdeckt");
        assertTrue(PartyWallCoverageUtils.overlapsAnySpan(spans, 6, 7, PartyWallCoverageUtils.SPAN_OVERLAP_TOL),
                "Fenster rechts vom Knick (u=6..7) liegt hinter der Anbau-Trennwand");
        assertFalse(PartyWallCoverageUtils.isFullyCovered(spans, 10.0),
                "Nur ein Teil der Wand ist verdeckt -> die ganze Wand darf nicht verworfen werden");
    }

    /** Deckt die andere Wand die GESAMTE eigene Unterkante ab (keine Kerbe, reine Trennwand ohne
     * eigenen Aussenwand-Anteil), muss isFullyCovered true liefern — das ist der Fall, in dem die
     * ganze Wand uebersprungen wird statt einzelne Oeffnungen zu pruefen. */
    @Test
    void detectsFullCoverageWhenNeighborMatchesEntireWall() {
        WallSurface mainWall = wallAt(List.of(
                new Point3D(0, 0, 3), new Point3D(0, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 3)));
        WallSurface annexWall = wallAt(List.of(
                new Point3D(0, 0, 3), new Point3D(0, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 3)));

        List<double[]> spans = PartyWallCoverageUtils.computeCoveredSpans(
                mainWall, List.of(mainWall, annexWall),
                new Point3D(0, 0, 0), 1.0, 0.0, 10.0, 0.0);

        assertTrue(PartyWallCoverageUtils.isFullyCovered(spans, 10.0));
    }

    /** Eine Nachbarwand auf einem ANDEREN Geschoss (abweichendes zMin, ausserhalb der Toleranz)
     * darf keine Deckung erzeugen — sonst wuerde ein freiliegendes Obergeschoss-Fenster faelschlich
     * als verdeckt markiert, nur weil der Grundriss darunter kollinear ist (siehe Doku.md,
     * "Warum Wand-gegen-Wand, nicht Grundriss-gegen-Punkt"). */
    @Test
    void ignoresNeighborWallOnDifferentStorey() {
        WallSurface mainWall = wallAt(List.of(
                new Point3D(0, 0, 6), new Point3D(0, 0, 3),
                new Point3D(10, 0, 3), new Point3D(10, 0, 6)));
        WallSurface groundFloorAnnex = wallAt(List.of(
                new Point3D(0, 0, 3), new Point3D(0, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 3)));

        List<double[]> spans = PartyWallCoverageUtils.computeCoveredSpans(
                mainWall, List.of(mainWall, groundFloorAnnex),
                new Point3D(0, 0, 3), 1.0, 0.0, 10.0, 3.0);

        assertFalse(PartyWallCoverageUtils.overlapsAnySpan(spans, 0, 10, PartyWallCoverageUtils.SPAN_OVERLAP_TOL),
                "Nachbarwand liegt auf anderem Geschoss (zMin=0 statt 3) -> keine Deckung");
    }
}
