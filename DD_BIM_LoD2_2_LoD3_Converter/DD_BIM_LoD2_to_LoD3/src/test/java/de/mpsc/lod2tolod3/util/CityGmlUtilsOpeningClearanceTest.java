package de.mpsc.lod2tolod3.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressionstest fuer {@link OpeningUtils#openingInsideWallTopClearance2D}, siehe Doku.md
 * Abschnitt "Bugfix: Fenster lag exakt an der Traufe an (GE_P_INTERIOR_DISCONNECTED)". Ursache
 * des Original-Bugs: {@link OpeningUtils#openingInsideWall2D} nutzt einen strikten Ray-Casting-
 * Containment-Test, der an Randpunkten mehrdeutig ist — eine Fenster-Oberkante, die exakt auf der
 * Wandkontur liegt (z.B. oberstes Geschoss ohne GeschossDeckeZ, Fenster reicht bis zur Traufe),
 * wurde faelschlich als "passt" durchgewunken. */
class CityGmlUtilsOpeningClearanceTest {

    // Rechteckige Wand: u=0..10, v=0..8 (v=Hoehe ueber Wandfuss).
    private static final double[][] WALL = {{0, 0}, {10, 0}, {10, 8}, {0, 8}};

    @Test
    void rejectsWindowFlushWithWallTop() {
        // Fenster-Oberkante liegt exakt auf v=8 (Wand-Oberkante) — der imo-Bug.
        assertFalse(OpeningUtils.openingInsideWallTopClearance2D(3, 4, 6, 8, WALL),
                "Fenster-Oberkante exakt auf der Wandkontur muss abgelehnt werden");
    }

    @Test
    void acceptsWindowWithRealTopMargin() {
        // Fenster mit 1m Luft zur Oberkante — muss weiterhin ganz normal passen.
        assertTrue(OpeningUtils.openingInsideWallTopClearance2D(3, 4, 5, 7, WALL),
                "Fenster mit echtem Abstand zur Oberkante darf nicht faelschlich abgelehnt werden");
    }

    @Test
    void doesNotAffectLeftRightOrBottomEdges() {
        // Fenster beruehrt die UNTERE Kante (v=0) — dort gilt bewusst KEIN Clearance-Abstand
        // (echte Giebel-Abschnitte mit schraegen Seitenkanten duerfen nicht mitbeeinflusst werden).
        assertTrue(OpeningUtils.openingInsideWallTopClearance2D(3, 4, 0, 2, WALL),
                "Bodenbuendiges Fenster (v=0) muss weiterhin passen — Clearance gilt nur oben");
    }

    // Nicht-konvexe Wand mit einer Kerbe bei u=6 (z.B. Anbau-Notch): rechts davon reicht die
    // Wand nur bis v=4 statt v=8.
    private static final double[][] NOTCHED_WALL = {
            {0, 0}, {10, 0}, {10, 4}, {6, 4}, {6, 8}, {0, 8}
    };

    @Test
    void rejectsWindowFlushWithInteriorNotchOnRightEdge() {
        // Fenster-RECHTE Kante liegt exakt auf der Kerbe (u=6, oberhalb v=4, wo die Wand
        // schmaler wird) — analog zum i5d-Bug (Anbau-Notch), aber seitlich statt oben.
        assertFalse(OpeningUtils.openingInsideWallSideTopClearance2D(4, 6, 2, 6, NOTCHED_WALL),
                "Fensterkante exakt auf einer Wand-internen Kerbe muss abgelehnt werden");
    }

    @Test
    void acceptsWindowWithRealSideMargin() {
        assertTrue(OpeningUtils.openingInsideWallSideTopClearance2D(1, 3, 1, 3, NOTCHED_WALL),
                "Fenster mit echtem Abstand zu allen Kanten darf nicht faelschlich abgelehnt werden");
    }

    @Test
    void sideTopClearanceStillAllowsBottomFlushWindow() {
        assertTrue(OpeningUtils.openingInsideWallSideTopClearance2D(3, 4, 0, 2, WALL),
                "Bodenbuendiges Fenster muss weiterhin passen — auch die erweiterte Pruefung laesst v=0 unangetastet");
    }

    // "M"-foermige Wand (Satteldach mit Gaube, zwei Firstspitzen mit einem Tal dazwischen) —
    // nachgebildet nach dem echten Fallback-Tuer-Bug an Gebaeude DESNALK0q80046gq.
    private static final double[][] M_WALL = {
            {0, 0}, {10, 0}, {10, 6}, {8, 8}, {5, 6}, {2, 8}, {0, 6}
    };

    @Test
    void detectsWallContourEnteringOpeningAcrossValley() {
        // Beide Ecken liegen lokal unterhalb der jeweils benachbarten Firstspitze (bestuende den
        // reinen 4-Eckpunkt-Test), aber die Oberkante ueberspannt das Tal bei u=5,v=6.
        assertTrue(OpeningUtils.openingInsideWallSideTopClearance2D(3, 7, 1, 6.5, M_WALL),
                "4-Eckpunkt-Test allein muss diese Oeffnung als 'passt' durchwinken (Kontrollfall)");
        assertTrue(OpeningUtils.wallContourEntersOpening(3, 7, 1, 6.5, M_WALL),
                "Der Talpunkt der Wandkontur liegt innerhalb der Oeffnung — muss erkannt werden");
    }

    @Test
    void doesNotFlagOpeningSafelyBelowValley() {
        assertFalse(OpeningUtils.wallContourEntersOpening(3, 7, 1, 5, M_WALL),
                "Oeffnung bleibt unterhalb des Tals (v=6) — kein Wandkontur-Punkt darf gemeldet werden");
    }

    // Nachgebildet nach dem echten Fallback-Tuer-Bug an DESNALK0q80045BR: ein bestehendes Fenster
    // bei u=[10,11], v=[2,4] — dieselbe u-Kante, aber eine hoehere Tuer daneben.
    private static final List<double[]> EXISTING_WINDOW = List.of(new double[]{10, 11, 2, 4});

    @Test
    void detectsSharedUEdgeWithDifferentHeight() {
        // Tuer bei u=[9,10] (stoesst exakt an u=10 an), aber v=[1,5] — hoeher als das Fenster.
        assertTrue(OpeningUtils.overlapsAnyOpeningRect(EXISTING_WINDOW, 9, 10, 1, 5),
                "Gemeinsame u-Kante mit ueberlappender Hoehe muss als Konflikt erkannt werden");
    }

    @Test
    void allowsSameUEdgeWithSeparatedHeight() {
        // Tuer stoesst an derselben u-Kante an, liegt aber komplett UNTER dem Fenster (v bis 2).
        assertFalse(OpeningUtils.overlapsAnyOpeningRect(EXISTING_WINDOW, 9, 10, -1, 1.9),
                "Gemeinsame u-Kante mit sauber getrennter Hoehe darf nicht blockieren");
    }

    @Test
    void allowsRealHorizontalGap() {
        assertFalse(OpeningUtils.overlapsAnyOpeningRect(EXISTING_WINDOW, 5, 6, 2, 4),
                "Oeffnung mit echtem horizontalem Abstand darf nicht blockieren");
    }
}
