package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.util.Point3D;
import de.mpsc.lod2tolod3.util.SlabClippingUtils.SlabPiece;
import org.junit.jupiter.api.Test;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regressionstest fuer {@link SlabClippingUtils#clipSlabAtZ}, siehe Doku.md Abschnitt "Ablösung:
 * Anbau-Zuschnitt durch echte 2D-Polygon-Differenz statt Kanten-Matching (JTS, 2026-08-20)".
 * Verhindert schwebende Geschossdecken/-boeden ueber einem niedrigeren Anbau, der sich das
 * Grundpolygon mit dem hoeheren Hauptbau teilt (kein eigenes BuildingPart). */
class CityGmlUtilsSlabClipTest {

    // Grundriss 10x10 (X:0..10, Y:0..10).
    private static final List<Point3D> FOOTPRINT = List.of(
            new Point3D(0, 0, 0), new Point3D(10, 0, 0),
            new Point3D(10, 10, 0), new Point3D(0, 10, 0));

    // Flaches Anbau-Dach ueber der linken Haelfte (X:0..5), Traufe bei Z=3.
    private static final Polygon ANNEX_ROOF = GeometryUtils.createPolygon(List.of(
            new Point3D(0, 0, 3), new Point3D(5, 0, 3),
            new Point3D(5, 10, 3), new Point3D(0, 10, 3)));

    @Test
    void excludesAnnexFootprintForSlabAboveAnnexRoof() {
        // Hauptbau-Geschossdecke weit ueber der Anbau-Traufe (z.B. UF_2 bei Z=8) — die linke
        // Haelfte (Anbau-Bereich) darf nicht mit-schweben, nur die rechte Haelfte bleibt.
        List<SlabPiece> pieces = SlabClippingUtils.clipSlabAtZ(FOOTPRINT, List.of(ANNEX_ROOF), 8.0, 0.05);

        // 49.8 statt 50.0: die Ausschlussflaeche wird um SLAB_EXCLUSION_GROW_TOL (2cm) aufgeblaht
        // (verhindert Spikes bei real leicht abweichenden Grundriss-/Dachecken, siehe Doku.md) —
        // das kostet hier an der 10m langen Schnittkante 0.02*10=0.2 m2.
        double totalArea = pieces.stream().mapToDouble(SlabClippingUtils::calculateNetArea2D).sum();
        assertEquals(49.8, totalArea, 0.01,
                "Nur die rechte Haelfte (minus 2cm-Ausschluss-Puffer) darf bei Z=8 uebrig bleiben, "
                        + "die Anbau-Haelfte (0..5) ist bei dieser Hoehe bereits Luft");
    }

    @Test
    void keepsFullFootprintForSlabBelowAnnexRoof() {
        // Geschossdecke deutlich unter der Anbau-Traufe (z.B. Kellerdecke bei Z=1) — dort
        // existiert der Anbau noch vollstaendig, der volle Grundriss bleibt unveraendert.
        List<SlabPiece> pieces = SlabClippingUtils.clipSlabAtZ(FOOTPRINT, List.of(ANNEX_ROOF), 1.0, 0.05);

        double totalArea = pieces.stream().mapToDouble(SlabClippingUtils::calculateNetArea2D).sum();
        assertEquals(100.0, totalArea, 0.01,
                "Unterhalb der Anbau-Traufe ist der volle 10x10-Grundriss noch tragfaehig");
    }
}
