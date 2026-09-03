package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.util.GeometryUtils.BottomEdge;
import de.mpsc.lod2tolod3.util.Point3D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regressionstests fuer {@link GeometryUtils#findBottomEdge} — die Grundlage fuer Fenster-/Tuer-/
 * Balkon-Platzierung UND (seit 2026-08-24) den Wand-Mehrfachschnitt bei geknickten Waenden. */
class CityGmlUtilsBottomEdgeTest {

    /** Eine geknickte Wand (z.B. gjj/gHh-Fall): 3 Punkte liegen auf zMin, weil die Wand zwei
     * Grundriss-Kanten in einem WallSurface zusammenfasst. Die Unterkante muss ueber das WEITESTE
     * Punktepaar bestimmt werden (volle Wandbreite), nicht ueber das ERSTE gefundene Paar — sonst
     * wird die Wandrichtung/-laenge falsch berechnet und alles, was darauf aufbaut, bricht. */
    @Test
    void picksWidestPairOnKinkedBottomEdge() {
        List<Point3D> open = List.of(
                new Point3D(0, 0, 3),   // top
                new Point3D(0, 0, 0),   // bottom start
                new Point3D(4, 0, 0),   // Knick auf der Sohle (naeher am Start)
                new Point3D(10, 0, 0),  // bottom end (weiteste Distanz vom Start)
                new Point3D(10, 0, 3)   // top
        );

        BottomEdge edge = GeometryUtils.findBottomEdge(open);

        assertNotNull(edge);
        assertEquals(10.0, edge.wallLength(), 0.0001,
                "Muss die volle Wandbreite (0..10) liefern, nicht das kuerzere Teilstueck (0..4)");
        assertEquals(0.0, edge.zMin(), 0.0001);
        assertEquals(3.0, edge.zMax(), 0.0001);
    }

    /** Einfache, ungeknickte Wand (nur 2 Punkte auf zMin) — Kontrollfall fuer den Normalfall. */
    @Test
    void handlesSimpleWallWithoutKink() {
        List<Point3D> open = List.of(
                new Point3D(0, 0, 0),
                new Point3D(5, 0, 0),
                new Point3D(5, 0, 3),
                new Point3D(0, 0, 3)
        );

        BottomEdge edge = GeometryUtils.findBottomEdge(open);

        assertNotNull(edge);
        assertEquals(5.0, edge.wallLength(), 0.0001);
    }
}
