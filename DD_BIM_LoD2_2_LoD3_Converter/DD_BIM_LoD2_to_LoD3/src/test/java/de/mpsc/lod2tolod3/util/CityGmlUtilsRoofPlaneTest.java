package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.util.Point3D;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressionstests fuer die geneigte-Flaeche-Geometrie (RoofWindowGenerator, Schritt 5c), siehe
 * Doku.md Abschnitt "Schritt 5c: Dachfenster". Testflaeche: ein 10m breites, 45 Grad geneigtes
 * Dachrechteck, Traufe entlang der X-Achse bei Z=0, First 5m weiter in Y und 5m hoeher in Z
 * (reine Handrechnung, siehe Kommentare). */
class CityGmlUtilsRoofPlaneTest {

    private static final double SQRT2_HALF = Math.sqrt(2) / 2; // 0.70710678...

    // Traufe (0,0,0)->(10,0,0), First (10,5,5)->(0,5,5): CCW von aussen/oben betrachtet.
    private static final List<Point3D> ROOF_CCW = List.of(
            new Point3D(0, 0, 0),
            new Point3D(10, 0, 0),
            new Point3D(10, 5, 5),
            new Point3D(0, 5, 5)
    );

    @Test
    void computesUpSlopeVectorAlongPitch() {
        double[] up = GeometryUtils.computeUpSlopeVector(ROOF_CCW, 1.0, 0.0);

        assertNotNull(up);
        assertEquals(0.0, up[0], 0.0001, "Aufwaerts-Vektor darf keine X-Komponente haben (First laeuft parallel zur Traufe)");
        assertEquals(SQRT2_HALF, up[1], 0.0001, "Y-Komponente entspricht der 45-Grad-Neigung");
        assertEquals(SQRT2_HALF, up[2], 0.0001, "Z-Komponente muss positiv sein (zeigt zum First)");
    }

    @Test
    void projectsEaveAtVZeroAndRidgeAtSlopeDistance() {
        double[] up = GeometryUtils.computeUpSlopeVector(ROOF_CCW, 1.0, 0.0);
        assertNotNull(up);

        double[][] poly2D = GeometryUtils.projectPlaneTo2D(
                ROOF_CCW, ROOF_CCW.get(0), 1.0, 0.0, up[0], up[1], up[2]);

        // Traufpunkte (Index 0,1): v=0
        assertEquals(0.0, poly2D[0][1], 0.0001);
        assertEquals(0.0, poly2D[1][1], 0.0001);
        // Firstpunkte (Index 2,3): v = Schraeglaenge = sqrt(5^2+5^2)
        double expectedSlopeLen = Math.sqrt(5 * 5 + 5 * 5);
        assertEquals(expectedSlopeLen, poly2D[2][1], 0.0001);
        assertEquals(expectedSlopeLen, poly2D[3][1], 0.0001);
        // u-Koordinaten entsprechen der Traufkanten-Projektion
        assertEquals(0.0, poly2D[0][0], 0.0001);
        assertEquals(10.0, poly2D[1][0], 0.0001);
        assertEquals(10.0, poly2D[2][0], 0.0001);
        assertEquals(0.0, poly2D[3][0], 0.0001);
    }

    @Test
    void detectsCCWWindingOnPlane() {
        double[] up = GeometryUtils.computeUpSlopeVector(ROOF_CCW, 1.0, 0.0);
        assertNotNull(up);

        assertTrue(SolidShellUtils.isRingCCWOnPlane(
                ROOF_CCW, ROOF_CCW.get(0), 1.0, 0.0, up[0], up[1], up[2]));
    }

    @Test
    void detectsCWWindingOnPlaneForReversedRing() {
        double[] up = GeometryUtils.computeUpSlopeVector(ROOF_CCW, 1.0, 0.0);
        assertNotNull(up);

        List<Point3D> reversed = List.copyOf(ROOF_CCW);
        List<Point3D> reversedList = new java.util.ArrayList<>(reversed);
        Collections.reverse(reversedList);

        assertFalse(SolidShellUtils.isRingCCWOnPlane(
                reversedList, ROOF_CCW.get(0), 1.0, 0.0, up[0], up[1], up[2]));
    }
}
