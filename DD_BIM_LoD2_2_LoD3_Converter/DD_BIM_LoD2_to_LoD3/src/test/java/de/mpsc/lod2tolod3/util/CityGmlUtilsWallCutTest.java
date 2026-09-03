package de.mpsc.lod2tolod3.util;

import de.mpsc.lod2tolod3.util.Point3D;
import org.junit.jupiter.api.Test;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressionstests fuer {@link WallCuttingUtils#cutWallAtMultipleZJTS}, siehe Doku.md Abschnitt
 * "Bugfix: Wand-Mehrfachschnitt schlug bei 'Anbau-Kerbe' fehl". */
class CityGmlUtilsWallCutTest {

    /** Nachbau von Face_00040DQ_0_8 (Gebaeude imo, 2026-08-24): eine Wand, die drei Grundriss-
     * Kanten in sich vereint, wobei die mittlere Kante wegen eines angrenzenden Anbaus nur bis zur
     * halben Hoehe reicht (u=4..6 nur bis Z=6 statt Z=10). Genau dieses Profil liess den alten
     * Wand-Mehrfachschnitt Schnitte innerhalb der Kerbe stillschweigend ueberspringen — die Wand
     * blieb bei Z=3 unzerschnitten stehen, obwohl sie in 2 Geschossstuecke geteilt werden sollte. */
    @Test
    void cutsWallWithAnnexNotchIntoCorrectPieces() {
        List<Point3D> wallRing = List.of(
                new Point3D(0, 0, 10),  // A_top
                new Point3D(0, 0, 0),   // A_bot
                new Point3D(4, 0, 0),   // B_bot
                new Point3D(4, 0, 6),   // B_mid — Kerbe: Wand reicht hier nur bis 6
                new Point3D(6, 0, 6),   // C_mid
                new Point3D(6, 0, 0),   // C_bot
                new Point3D(10, 0, 0),  // D_bot
                new Point3D(10, 0, 10), // D_top
                new Point3D(6, 0, 10),  // C_top
                new Point3D(4, 0, 10)   // B_top
        );

        List<Polygon> pieces = WallCuttingUtils.cutWallAtMultipleZJTS(wallRing, List.of(3.0, 8.0));

        assertNotNull(pieces, "Schnitt darf bei diesem Profil nicht fehlschlagen");
        assertEquals(4, pieces.size(),
                "Schnitt bei Z=3 liegt in der Kerben-Zone -> muss in 2 getrennte Stuecke "
                        + "zerfallen (Band [0,3]: 2 Beine links/rechts der Kerbe), plus 1 Stueck "
                        + "fuer Band [3,8] (dort waechst die Kerbe wieder zusammen) und 1 fuer "
                        + "[8,10] -> insgesamt 4. Der urspruengliche Bug lieferte hier nur 1 Stueck "
                        + "(die ganze Wand blieb unzerschnitten).");

        double totalArea = pieces.stream()
                .mapToDouble(p -> GeometryUtils.calculateWallArea(GeometryUtils.toPoints(p)))
                .sum();
        assertEquals(88.0, totalArea, 0.01,
                "Flaechenbilanz: 10x10-Rechteck (100) minus 2x6-Kerbe (12) = 88 — bestaetigt, "
                        + "dass beim Zerlegen keine Flaeche verloren geht oder doppelt gezaehlt wird");
    }

    /** Eckpunkte, die von KEINEM der beiden Schnitte (Z=3, Z=8) beruehrt werden, muessen exakt
     * (nicht nur ungefaehr) erhalten bleiben. Regressionsschutz fuer die Praezisions-Nachbesserung
     * vom 2026-08-24 (findOriginalPoint/interpolateOnOriginalEdge): ein (u,v)-Rundweg ueber
     * edgeStart+dir kann fuer unveraenderte Original-Ecken Sub-mm-Drift einfuehren, was benachbarte
     * Waende/Boeden/Decken nicht mehr exakt trifft (val3dity 302 SHELL_NOT_CLOSED) — sichtbar nur
     * auf der vollen Kachel, nicht am Einzelgebaeude, deshalb hier explizit als Test statt nur als
     * Nebeneffekt der Flaechenbilanz oben. */
    @Test
    void preservesExactCoordinatesOfUnchangedVertices() {
        List<Point3D> wallRing = List.of(
                new Point3D(0, 0, 10), new Point3D(0, 0, 0),
                new Point3D(4, 0, 0), new Point3D(4, 0, 6),
                new Point3D(6, 0, 6), new Point3D(6, 0, 0),
                new Point3D(10, 0, 0), new Point3D(10, 0, 10),
                new Point3D(6, 0, 10), new Point3D(4, 0, 10)
        );

        List<Polygon> pieces = WallCuttingUtils.cutWallAtMultipleZJTS(wallRing, List.of(3.0, 8.0));
        assertNotNull(pieces);

        List<Point3D> allPoints = pieces.stream()
                .flatMap(p -> GeometryUtils.toPoints(p).stream())
                .toList();

        assertTrue(containsExactly(allPoints, 0, 0, 0), "A_bot unveraendert");
        assertTrue(containsExactly(allPoints, 10, 0, 10), "D_top unveraendert");
        assertTrue(containsExactly(allPoints, 4, 0, 6), "B_mid (Kerben-Eckpunkt) unveraendert");
        assertTrue(containsExactly(allPoints, 6, 0, 6), "C_mid (Kerben-Eckpunkt) unveraendert");
    }

    private static boolean containsExactly(List<Point3D> points, double x, double y, double z) {
        final double eps = 1e-9;
        return points.stream().anyMatch(p ->
                Math.abs(p.x - x) < eps && Math.abs(p.y - y) < eps && Math.abs(p.z - z) < eps);
    }

    /** Ohne Kerbe (einfache rechteckige Wand) muss der Schnitt weiterhin die triviale Anzahl an
     * Stuecken liefern — Absicherung, dass die JTS-Umstellung den Normalfall nicht veraendert. */
    @Test
    void cutsSimpleRectangularWallIntoExpectedPieceCount() {
        List<Point3D> wallRing = List.of(
                new Point3D(0, 0, 0),
                new Point3D(10, 0, 0),
                new Point3D(10, 0, 10),
                new Point3D(0, 0, 10)
        );

        List<Polygon> pieces = WallCuttingUtils.cutWallAtMultipleZJTS(wallRing, List.of(3.0, 6.0));

        assertNotNull(pieces);
        assertEquals(3, pieces.size(), "2 Schnitte an einer einfachen Rechteckwand -> 3 Stuecke");

        double totalArea = pieces.stream()
                .mapToDouble(p -> GeometryUtils.calculateWallArea(GeometryUtils.toPoints(p)))
                .sum();
        assertEquals(100.0, totalArea, 0.01);
    }
}
