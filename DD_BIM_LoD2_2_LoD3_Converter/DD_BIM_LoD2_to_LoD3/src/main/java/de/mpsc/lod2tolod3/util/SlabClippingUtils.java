package de.mpsc.lod2tolod3.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Zuschnitt von Geschossflaechen (Boeden/Decken) auf Hoehe z bei Anbauten, mittels JTS.
 */
public final class SlabClippingUtils {

    private static final Logger log = LoggerFactory.getLogger(SlabClippingUtils.class);

    private SlabClippingUtils() {
        // Utility-Klasse
    }

    /** Ein Teilstueck einer Geschossflaeche bei fester Hoehe: offener Aussenring + (selten)
     * offene Innenringe. Mehrere Teilstuecke entstehen, wenn ein niedriger Anbau in der Mitte
     * das Gebaeude in getrennte Fluegel teilt. */
    public record SlabPiece(List<Point3D> exterior, List<List<Point3D>> interiors) {}

    /** Kleinere Zuschnitt-Ergebnisse sind Artefakte (Rundungsschlieren), keine echten Raeume. */
    private static final double MIN_SLAB_AREA = 0.50;

    /** Grundriss- und Dachpolygone stammen aus unabhaengig digitalisierten LoD2-Flaechen und
     * treffen sich an gemeinsamen Gebaeudeecken oft nicht exakt (Abweichungen im cm-Bereich). Ohne
     * Gegenmassnahme erzeugt JTS' difference() dort einen entarteten "Spike" statt eines sauberen
     * gemeinsamen Eckpunkts. Fix: `excluded` wird um diese Toleranz aufgeblaht (`buffer`) — eine
     * rein lokale, monotone Vergroesserung, die (anders als GeometrySnapper) nirgendwo im
     * Grundriss Punkte verschiebt und daher keine schwebenden Slabs erzeugen kann, siehe Doku.md.
     * Bewusst mit JOIN_BEVEL statt dem JTS-Standard JOIN_ROUND: ein
     * Rundungs-Join naehert jede Ecke durch mehrere kurze Kreisbogen-Segmente an (Default 8 pro
     * Viertelkreis) — bei 2cm Radius liegen deren Punkte nur Millimeter auseinander und wurden bei
     * einem realen Gebaeude als eigenstaendiger neuer Fehler erkannt (GE_R_CONSECUTIVE_POINTS_SAME,
     * siehe Doku.md). Bevel schneidet die Ecke stattdessen mit maximal einem zusaetzlichen,
     * ausreichend weit entfernten Punkt gerade ab — kein Spike-Risiko wie bei einem Mitre-Join. */
    private static final double SLAB_EXCLUSION_GROW_TOL = 0.02;

    private static final org.locationtech.jts.operation.buffer.BufferParameters
            SLAB_EXCLUSION_GROW_PARAMS = new org.locationtech.jts.operation.buffer.BufferParameters();
    static {
        SLAB_EXCLUSION_GROW_PARAMS.setJoinStyle(
                org.locationtech.jts.operation.buffer.BufferParameters.JOIN_BEVEL);
    }

    /**
     * Grundpolygon auf Hoehe z, abzueglich aller Dachanteile die auf/unter z liegen — verhindert
     * schwebende Slabs ueber Anbauten, die sich das Grundpolygon mit einem hoeheren Hauptbau
     * teilen (kein eigenes BuildingPart), siehe Doku.md. Ergebnispunkte liegen bereits auf z.
     * Leere Liste = an dieser Hoehe traegt nichts (mehr).
     */
    public static List<SlabPiece> clipSlabAtZ(List<Point3D> groundPts, List<Polygon> roofPolygons,
            double z, double tolerance) {
        List<Point3D> ground = GeometryUtils.removeClosingPoint(groundPts);
        if (ground.size() < 3) return List.of();

        org.locationtech.jts.geom.Geometry excluded = roofAreaBelowZ(roofPolygons, z, tolerance);
        if (excluded == null) {
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }

        org.locationtech.jts.geom.Polygon footprint = toJts(ground, List.of());
        if (footprint == null || !excluded.intersects(footprint)) {
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }

        try {
            org.locationtech.jts.geom.Geometry excludedGrown =
                    org.locationtech.jts.operation.buffer.BufferOp.bufferOp(
                            excluded, SLAB_EXCLUSION_GROW_TOL, SLAB_EXCLUSION_GROW_PARAMS);
            return toSlabPieces(footprint.difference(excludedGrown), z);
        } catch (RuntimeException e) {
            log.warn("  clipSlabAtZ: JTS-Differenz fehlgeschlagen bei z={} ({}), Flaeche unveraendert",
                    GeometryUtils.formatNum(z), e.toString());
            return List.of(new SlabPiece(GeometryUtils.projectToZ(ground, z), List.of()));
        }
    }

    /** Vereinigte 2D-Flaeche aller Dachanteile auf/unter z (jeweils per {@link WallCuttingUtils#splitWallByZ}
     * aus dem, ggf. geneigten, Dachpolygon herausgeschnitten). Null = kein Dach reicht so tief. */
    private static org.locationtech.jts.geom.Geometry roofAreaBelowZ(
            List<Polygon> roofPolygons, double z, double tolerance) {
        org.locationtech.jts.geom.Geometry union = null;
        for (Polygon roof : roofPolygons) {
            List<Point3D> pts = GeometryUtils.removeClosingPoint(GeometryUtils.toPoints(roof));
            if (pts.size() < 3) continue;
            double minZ = Double.MAX_VALUE;
            for (Point3D p : pts) minZ = Math.min(minZ, p.z);
            if (minZ > z + tolerance) continue; // Fast-Path: Dach liegt komplett oberhalb z

            for (List<Point3D> piece : WallCuttingUtils.splitWallByZ(pts, z, tolerance).lower()) {
                org.locationtech.jts.geom.Polygon jtsPoly = toJts(piece, List.of());
                if (jtsPoly == null) continue;
                if (union == null) {
                    union = jtsPoly;
                    continue;
                }
                try {
                    union = union.union(jtsPoly);
                } catch (RuntimeException e) {
                    // Bekanntes JTS-Overlay-Robustheitsproblem ("non-noded intersection" bei
                    // fast-deckungsgleichen, aber nicht exakt identischen Sub-mm-Segmenten,
                    // beobachtet 33_408_5654 bei einem kompletten Dresden-Lauf, 2026-09-02).
                    // Dieses eine Dachstueck NICHT von der Ausschlussflaeche ausnehmen (union
                    // bleibt auf dem bisherigen Stand) statt das gesamte Gebaeude/die gesamte
                    // Kachel abstuerzen zu lassen — analog zum bestehenden JTS-Fallback in
                    // clipSlabAtZ. Konservativ: die betroffene Slab-Flaeche bleibt dadurch an
                    // dieser einen Stelle ggf. geringfuegig zu gross statt korrekt um dieses eine
                    // Dachstueck verkleinert zu werden.
                    log.warn("  roofAreaBelowZ: JTS-Union fehlgeschlagen bei z={} ({}), "
                                    + "Dachstueck uebersprungen (Ausschlussflaeche bleibt unveraendert)",
                            GeometryUtils.formatNum(z), e.toString());
                }
            }
        }
        return union;
    }

    /** Baut ein JTS-Polygon (2D, Z wird verworfen) aus offenen Punktlisten; null bei Entartung. */
    private static org.locationtech.jts.geom.Polygon toJts(List<Point3D> exterior,
            List<List<Point3D>> holes) {
        org.locationtech.jts.geom.GeometryFactory gf = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.LinearRing shell = toJtsRing(gf, exterior);
        if (shell == null) return null;
        org.locationtech.jts.geom.LinearRing[] jtsHoles = new org.locationtech.jts.geom.LinearRing[holes.size()];
        for (int i = 0; i < holes.size(); i++) {
            org.locationtech.jts.geom.LinearRing h = toJtsRing(gf, holes.get(i));
            if (h == null) return null;
            jtsHoles[i] = h;
        }
        return gf.createPolygon(shell, jtsHoles);
    }

    private static org.locationtech.jts.geom.LinearRing toJtsRing(
            org.locationtech.jts.geom.GeometryFactory gf, List<Point3D> pts) {
        List<Point3D> open = GeometryUtils.removeClosingPoint(
                GeometryUtils.dedupConsecutive(pts, GeometryUtils.POINT_MERGE_TOL));
        if (open.size() < 3) return null;
        org.locationtech.jts.geom.Coordinate[] coords = new org.locationtech.jts.geom.Coordinate[open.size() + 1];
        for (int i = 0; i < open.size(); i++) {
            coords[i] = new org.locationtech.jts.geom.Coordinate(open.get(i).x, open.get(i).y);
        }
        coords[open.size()] = coords[0];
        return gf.createLinearRing(coords);
    }

    /** Zerlegt ein JTS-Differenz-Ergebnis (Polygon oder MultiPolygon) in {@link SlabPiece}s auf
     * Hoehe z; zu kleine Teilstuecke/Loecher (Zuschnitt-Artefakte) werden verworfen. */
    private static List<SlabPiece> toSlabPieces(org.locationtech.jts.geom.Geometry result, double z) {
        List<SlabPiece> pieces = new ArrayList<>();
        for (int i = 0; i < result.getNumGeometries(); i++) {
            if (!(result.getGeometryN(i) instanceof org.locationtech.jts.geom.Polygon jtsPoly)) continue;
            List<Point3D> exterior = toOpenRing(jtsPoly.getExteriorRing(), z);
            if (exterior.size() < 3 || GeometryUtils.calculatePolygonArea2D(exterior) < MIN_SLAB_AREA) continue;

            List<List<Point3D>> interiors = new ArrayList<>();
            for (int h = 0; h < jtsPoly.getNumInteriorRing(); h++) {
                List<Point3D> hole = toOpenRing(jtsPoly.getInteriorRingN(h), z);
                if (hole.size() >= 3 && GeometryUtils.calculatePolygonArea2D(hole) >= MIN_SLAB_AREA) interiors.add(hole);
            }
            pieces.add(new SlabPiece(exterior, interiors));
        }
        return pieces;
    }

    /** JTS-Ring (geschlossen) als offene Punktliste auf Hoehe z. */
    private static List<Point3D> toOpenRing(org.locationtech.jts.geom.LineString jtsRing, double z) {
        org.locationtech.jts.geom.Coordinate[] coords = jtsRing.getCoordinates();
        List<Point3D> pts = new ArrayList<>(Math.max(0, coords.length - 1));
        for (int i = 0; i < coords.length - 1; i++) { // letzter == erster (geschlossen): weglassen
            pts.add(new Point3D(coords[i].x, coords[i].y, z));
        }
        return pts;
    }

    /** 2D-Nettoflaeche eines Teilstuecks (Aussenring minus Loecher). */
    public static double calculateNetArea2D(SlabPiece piece) {
        double area = GeometryUtils.calculatePolygonArea2D(piece.exterior());
        for (List<Point3D> hole : piece.interiors()) {
            area -= GeometryUtils.calculatePolygonArea2D(hole);
        }
        return area;
    }

    /** Wie {@link GeometryUtils#createPolygon(List)}, zusaetzlich mit Innenringen (gml:interior).
     * Ausschliesslich fuer clipSlabAtZ-Geschossdecken/-boeden verwendet, daher
     * {@link GeometryUtils#SLAB_RING_DEDUP_TOL} statt der allgemeinen {@link GeometryUtils#RING_DEDUP_TOL}
     * fuer den Aussenring. */
    public static Polygon createPolygonWithHoles(List<Point3D> exterior, List<List<Point3D>> interiors) {
        Polygon poly = GeometryUtils.createPolygon(exterior, GeometryUtils.SLAB_RING_DEDUP_TOL);
        for (List<Point3D> hole : interiors) {
            poly.getInterior().add(new AbstractRingProperty(GeometryUtils.createLinearRing(hole)));
        }
        return poly;
    }
}
