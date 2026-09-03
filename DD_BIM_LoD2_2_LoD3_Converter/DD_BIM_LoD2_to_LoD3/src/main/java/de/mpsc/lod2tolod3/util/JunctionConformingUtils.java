package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.AbstractFillingSurfaceProperty;
import org.citygml4j.core.model.construction.CeilingSurface;
import org.citygml4j.core.model.construction.FloorSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.AbstractRingProperty;
import org.xmlobjects.gml.model.geometry.primitives.LinearRing;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T-Naht-Konformierung (Einfuegen fehlender Vertices auf Nachbarkanten) und die nachgelagerte
 * Pinch-Point-Aufspaltung selbstberuehrender Ringe, die dabei entstehen koennen. Beide Schritte
 * gehoeren eng zusammen: die Aufspaltung MUSS nach der Konformierung laufen.
 */
public final class JunctionConformingUtils {

    private JunctionConformingUtils() {
        // Utility-Klasse
    }

    // ==================== Junction-Conforming (T-Nähte) ====================

    private static double dist3(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Verschmilzt Eckpunkte VERSCHIEDENER Huellen-Ringe, die naeher als {@code tol} beieinander
     * liegen, auf einen gemeinsamen Punkt (Union-Find fuer korrektes Clustering bei 3+ nahen
     * Punkten, nicht nur paarweise). Aendert die Form nicht messbar (Toleranz im mm-Bereich),
     * beseitigt aber echte T-Naht-Kollisionen an ihrer Wurzel statt nur beim Einfuegen zu filtern
     * — siehe {@link #conformJunctions} und Doku.md. */
    private static void weldNearbyRingVertices(List<LinearRing> rings, double tol) {
        List<List<Double>> ringVals = new ArrayList<>();
        for (LinearRing r : rings) {
            List<Double> v = ringValues(r);
            ringVals.add(v == null ? null : new ArrayList<>(v));
        }

        List<int[]> refs = new ArrayList<>(); // {ringIdx, vertIdx}
        List<double[]> coords = new ArrayList<>();
        for (int ri = 0; ri < ringVals.size(); ri++) {
            List<Double> v = ringVals.get(ri);
            if (v == null) continue;
            int m = v.size() / 3;
            for (int i = 0; i < m - 1; i++) { // letzter == erster (Schliesspunkt): weglassen
                refs.add(new int[]{ri, i});
                coords.add(new double[]{v.get(3 * i), v.get(3 * i + 1), v.get(3 * i + 2)});
            }
        }

        int n = refs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double tol2 = tol * tol;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (refs.get(i)[0] == refs.get(j)[0]) continue; // nur ringuebergreifend
                double[] a = coords.get(i), b = coords.get(j);
                double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
                if (dx * dx + dy * dy + dz * dz < tol2) unionFind(parent, i, j);
            }
        }

        boolean changed = false;
        Map<Integer, Integer> repByRoot = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = findRoot(parent, i);
            Integer existingRep = repByRoot.putIfAbsent(root, i); // erstes Element je Cluster = Vorbild
            int rep = existingRep != null ? existingRep : i;
            if (rep == i) continue;

            double[] target = coords.get(rep);
            int[] ref = refs.get(i);
            List<Double> v = ringVals.get(ref[0]);
            int idx = ref[1];
            int m = v.size() / 3;
            v.set(3 * idx, target[0]); v.set(3 * idx + 1, target[1]); v.set(3 * idx + 2, target[2]);
            if (idx == 0) { // Schliesspunkt am Ringende mitziehen
                v.set(3 * (m - 1), target[0]); v.set(3 * (m - 1) + 1, target[1]); v.set(3 * (m - 1) + 2, target[2]);
            }
            changed = true;
        }

        if (!changed) return;
        for (int ri = 0; ri < rings.size(); ri++) {
            if (ringVals.get(ri) == null) continue;
            rings.get(ri).getControlPoints().getPosList().setValue(ringVals.get(ri));
        }
    }

    private static int findRoot(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void unionFind(int[] parent, int a, int b) {
        int ra = findRoot(parent, a), rb = findRoot(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /** Fuegt fehlende T-Naht-Vertices auf bestehende Kanten ein (formneutral, siehe Doku.md Schritt 7). */
    public static int conformJunctions(Building building, double tol) {
        List<LinearRing> rings = new ArrayList<>();
        for (AbstractBuilding t : BuildingQueryUtils.getBuildingTargets(building)) collectShellExteriorRings(t, rings);
        if (rings.size() < 2) return 0;

        // Eng begrenztes Vertex-Welding VOR der T-Naht-Einfuegung: zwei unabhaengige Nachbarwaende
        // koennen an derselben realen Ecke einen eigenen, nur mm-genau abweichenden Eckpunkt haben
        // (separate LoD2-Digitalisierung). Ohne Verschmelzung wuerden beide als eigene T-Naht-
        // Kandidaten erkannt und je einzeln (fast identisch) eingefuegt — GE_R_CONSECUTIVE_POINTS_
        // SAME. Bewusst nur bis RING_DEDUP_TOL (2mm), deutlich enger als das urspruenglich
        // entfernte generelle Vertex-Welding (bis zu 5mm, echtes Reshaping), siehe Doku.md.
        weldNearbyRingVertices(rings, GeometryUtils.RING_DEDUP_TOL);

        // alle Vertices der Huellen-Ringe als Einfuege-Kandidaten sammeln
        List<double[]> verts = new ArrayList<>();
        for (LinearRing r : rings) {
            List<Double> v = ringValues(r);
            if (v == null) continue;
            int m = v.size() / 3;
            for (int i = 0; i < m - 1; i++) {
                verts.add(new double[]{v.get(3 * i), v.get(3 * i + 1), v.get(3 * i + 2)});
            }
        }

        int inserted = 0;
        for (LinearRing r : rings) {
            List<Double> v = ringValues(r);
            if (v == null) continue;
            int m = v.size() / 3;
            if (m < 4) continue;
            List<double[]> pts = new ArrayList<>(m - 1);
            for (int i = 0; i < m - 1; i++) {
                pts.add(new double[]{v.get(3 * i), v.get(3 * i + 1), v.get(3 * i + 2)});
            }

            List<double[]> out = new ArrayList<>();
            int nn = pts.size();
            boolean changed = false;
            for (int i = 0; i < nn; i++) {
                double[] a = pts.get(i), b = pts.get((i + 1) % nn);
                out.add(a);
                // Kandidaten, die auf der Kante a-b liegen (Innen, nicht Endpunkt)
                List<double[]> ins = new ArrayList<>();
                double abx = b[0] - a[0], aby = b[1] - a[1], abz = b[2] - a[2];
                double len2 = abx * abx + aby * aby + abz * abz;
                if (len2 < 1e-18) continue;
                for (double[] cand : verts) {
                    double tpar = ((cand[0] - a[0]) * abx + (cand[1] - a[1]) * aby + (cand[2] - a[2]) * abz) / len2;
                    if (tpar <= 1e-6 || tpar >= 1 - 1e-6) continue;
                    if (dist3(cand, a) < tol || dist3(cand, b) < tol) continue;
                    double cx = a[0] + tpar * abx, cy = a[1] + tpar * aby, cz = a[2] + tpar * abz;
                    double ex = cand[0] - cx, ey = cand[1] - cy, ez = cand[2] - cz;
                    if (ex * ex + ey * ey + ez * ez < tol * tol) {
                        ins.add(new double[]{tpar, cand[0], cand[1], cand[2]});
                    }
                }
                if (!ins.isEmpty()) {
                    ins.sort((x, y2) -> Double.compare(x[0], y2[0]));
                    double lastT = -1;
                    // Zwei Kandidaten von VERSCHIEDENEN Nachbarwaenden koennen nahe an derselben
                    // Stelle liegen (eigene, nur mm-genau uebereinstimmende Eckpunkte — siehe
                    // "Vertex-Welding wurde bewusst ENTFERNT" oben: genau dieses mm-Verschmelzen
                    // ist ABSICHTLICH nicht Aufgabe dieser Pipeline). Beide trotzdem einfuegen:
                    // wird nur der naeher liegende behalten, fehlt der ANDEREN Nachbarwand ihr
                    // eigener Anschlusspunkt und die Huelle bekommt eine neue Luecke (SHELL_NOT_
                    // CLOSED) statt des harmloseren CONSECUTIVE_POINTS_SAME — per A/B bestaetigt
                    // (siehe Doku.md), daher NICHT hier entfernt.
                    for (double[] c : ins) {
                        if (c[0] - lastT < 1e-6) continue; // doppeltes t
                        out.add(new double[]{c[1], c[2], c[3]});
                        lastT = c[0];
                        inserted++;
                        changed = true;
                    }
                }
            }

            if (changed) {
                List<Double> nv = new ArrayList<>((out.size() + 1) * 3);
                for (double[] c : out) { nv.add(c[0]); nv.add(c[1]); nv.add(c[2]); }
                nv.add(out.get(0)[0]); nv.add(out.get(0)[1]); nv.add(out.get(0)[2]);
                r.getControlPoints().getPosList().setValue(nv);
            }
        }
        return inserted;
    }

    // ==================== Pinch-Point-Aufspaltung (nach conformJunctions) ====================

    /**
     * Spaltet Ringe, die durch die T-Naht-Einfuegung an einer Stelle einen "Pinch Point"
     * (denselben 3D-Punkt an zwei nicht benachbarten Stellen) bekommen haben, in zwei einfache
     * Teilringe auf — statt (wie mehrfach versucht und wegen Tauschgeschaeften verworfen, siehe
     * Doku.md "T-Naht-Splitter") die verursachende Einfuegung wegzulassen. Keine Verbindung geht
     * dabei verloren: beide Haelften bleiben Teil desselben MultiSurface (in CityGML normal).
     * MUSS NACH {@link #conformJunctions} laufen (der Pinch entsteht erst dort) und ist der
     * letzte geometrieveraendernde Schritt der Pipeline — nachgelagerter Code darf sich NICHT
     * mehr auf "genau ein Polygon pro Flaeche" verlassen (z.B. getWallPolygon/getRoofPolygon
     * lesen nur members.get(0)).
     */
    public static int splitSelfTouchingRings(Building building) {
        int splitCount = 0;
        for (AbstractBuilding target : BuildingQueryUtils.getBuildingTargets(building)) {
            for (var boundary : target.getBoundaries()) {
                var surface = boundary.getObject();
                if (!(surface instanceof AbstractThematicSurface ats)) continue;
                if (surface instanceof FloorSurface || surface instanceof CeilingSurface) continue;
                splitCount += splitSelfTouchingRingsInSurface(ats);
                if (surface instanceof WallSurface wall) {
                    for (AbstractFillingSurfaceProperty fp : wall.getFillingSurfaces()) {
                        var fill = fp.getObject();
                        if (fill != null) splitCount += splitSelfTouchingRingsInSurface(fill);
                    }
                }
            }
        }
        return splitCount;
    }

    private static int splitSelfTouchingRingsInSurface(AbstractThematicSurface ats) {
        MultiSurfaceProperty msp = ats.getMultiSurface(3);
        if (msp == null || msp.getObject() == null) return 0;
        List<SurfaceProperty> members = msp.getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return 0;
        List<SurfaceProperty> newMembers = new ArrayList<>();
        int splits = 0;
        for (SurfaceProperty sm : members) {
            if (!(sm.getObject() instanceof Polygon poly)) { newMembers.add(sm); continue; }
            List<Polygon> pieces = splitAtPinchPointsRecursive(poly, 5);
            if (pieces == null) { newMembers.add(sm); continue; }
            splits += pieces.size() - 1;
            for (Polygon p : pieces) newMembers.add(new SurfaceProperty(p));
        }
        if (splits > 0) {
            members.clear();
            members.addAll(newMembers);
        }
        return splits;
    }

    /** Spaltet ein Polygon wiederholt an Pinch-Points auf, bis keiner mehr uebrig ist (oder
     * maxIterations erreicht — Sicherung gegen pathologische Faelle). Liefert null, wenn kein
     * Pinch gefunden wurde (Original unveraendert lassen, kein unnoetiger GML-Diff). */
    private static List<Polygon> splitAtPinchPointsRecursive(Polygon poly, int maxIterations) {
        List<Polygon> queue = new ArrayList<>();
        queue.add(poly);
        boolean anySplit = false;
        for (int iter = 0; iter < maxIterations; iter++) {
            boolean splitThisRound = false;
            List<Polygon> next = new ArrayList<>();
            for (Polygon p : queue) {
                List<Polygon> two = splitOnePolygonAtPinchPoint(p);
                if (two != null) { next.addAll(two); splitThisRound = true; anySplit = true; }
                else next.add(p);
            }
            queue = next;
            if (!splitThisRound) break;
        }
        if (!anySplit) return null;
        // Nur committen, wenn das GESAMTERGEBNIS vollstaendig sauber ist (kein Teilstueck
        // beruehrt sich noch selbst) — ein "Spike" (Weg geht zu einem Punkt raus und exakt
        // zurueck) laesst sich per Zweiseiten-Aufspaltung nicht aufloesen (beide Haelften
        // wuerden auf <3 Punkte entarten, siehe splitOnePolygonAtPinchPoint). Wuerde man das
        // verbleibende Spike-Stueck trotzdem als eigenes Polygon uebernehmen, bliebe es selbst
        // weiterhin selbstberuehrend UND koennte an anderer Stelle eine neue Luecke (GE_S_
        // NOT_CLOSED) aufreissen — exakt das Tauschgeschaeft, das bei jedem "Kandidat
        // weglassen"-Versuch dieser Baustelle beobachtet wurde (siehe Doku.md). Lieber die
        // gesamte Aufspaltung verwerfen und das Original unveraendert lassen (bleibt dann wie
        // bisher als GE_R_SELF_INTERSECTION gemeldet, aber ohne neuen Fehler an anderer Stelle).
        for (Polygon p : queue) {
            if (GeometryUtils.ringSelfIntersects(GeometryUtils.toPoints(p))) return null;
        }
        return queue;
    }

    /** Findet EINEN Pinch-Point im Aussenring und spaltet das Polygon dort in zwei Teilpolygone
     * auf (Innenringe werden per 3D-Punkt-in-planarem-Ring-Test dem jeweils passenden Teil
     * zugeordnet). Liefert null, wenn kein Pinch gefunden wurde oder die Aufspaltung degeneriert
     * waere (z.B. ein "Spike" statt eines echten zweiseitigen Pinch — bleibt dann unveraendert,
     * kein bekannter Fall dieser Art bisher beobachtet). */
    private static List<Polygon> splitOnePolygonAtPinchPoint(Polygon poly) {
        if (poly.getExterior() == null || !(poly.getExterior().getObject() instanceof LinearRing extRing)) return null;
        List<Point3D> pts = GeometryUtils.removeClosingPoint(GeometryUtils.pointsOfRing(extRing));
        int n = pts.size();
        if (n < 5) return null; // kleinstmoegliche zwei echte Teilringe brauchen zusammen >=5 Punkte

        // Kandidaten sammeln (nicht nur den ersten nehmen): bei verschachtelten Pinch-Points
        // (mehr als zwei Stellen desselben Punktes) kann das erste gefundene Paar eine entartete
        // Teilflaeche (<3 Punkte) erzeugen, obwohl ein ANDERES Paar im selben Ring einen gueltigen
        // Schnitt liefern wuerde — z.B. wenn j nahe am Ringende liegt und dadurch kaum noch
        // Punkte zwischen j und i (zyklisch) uebrig sind. Erstes Paar nehmen, das eine gueltige
        // (>=3 Punkte je Seite) Aufspaltung ergibt, statt beim ersten Treffer aufzugeben.
        double tol2 = GeometryUtils.RING_DEDUP_TOL * GeometryUtils.RING_DEDUP_TOL;
        List<Point3D> loopA = null, loopB = null;
        for (int i = 0; i < n && loopA == null; i++) {
            for (int j = i + 3; j < n; j++) { // j-i>=3, sonst hat loopA < 3 Punkte
                if (i == 0 && j == n - 1) continue; // zyklisch benachbart, kein echter Pinch
                double dx = pts.get(i).x - pts.get(j).x, dy = pts.get(i).y - pts.get(j).y, dz = pts.get(i).z - pts.get(j).z;
                if (dx * dx + dy * dy + dz * dz >= tol2) continue;
                List<Point3D> candA = new ArrayList<>(pts.subList(i, j)); // i..j-1
                List<Point3D> candB = new ArrayList<>();
                candB.addAll(pts.subList(j, n));
                candB.addAll(pts.subList(0, i));
                if (candA.size() < 3 || candB.size() < 3) continue; // entartet, naechstes Paar
                loopA = candA; loopB = candB;
                break;
            }
        }
        if (loopA == null) return null;

        Polygon polyA = GeometryUtils.createPolygon(loopA);
        Polygon polyB = GeometryUtils.createPolygon(loopB);
        if (polyA == null || polyB == null) return null;

        for (AbstractRingProperty irp : poly.getInterior()) {
            if (!(irp.getObject() instanceof LinearRing hole)) { polyB.getInterior().add(irp); continue; }
            List<Point3D> holePts = GeometryUtils.removeClosingPoint(GeometryUtils.pointsOfRing(hole));
            if (holePts.isEmpty()) { polyB.getInterior().add(irp); continue; }
            Point3D test = holePts.get(0);
            if (pointInPlanarRing(test, loopA)) polyA.getInterior().add(irp);
            else polyB.getInterior().add(irp);
        }
        return List.of(polyA, polyB);
    }

    /** 3D-Punkt-in-planarem-Ring-Test via dominante-Achse-Projektion (wie {@link GeometryUtils#ringSelfIntersects}). */
    private static boolean pointInPlanarRing(Point3D test, List<Point3D> ring) {
        int n = ring.size();
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < n; i++) {
            Point3D a = ring.get(i), b = ring.get((i + 1) % n);
            nx += (a.y - b.y) * (a.z + b.z);
            ny += (a.z - b.z) * (a.x + b.x);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        double[][] poly2D = new double[n][2];
        for (int i = 0; i < n; i++) {
            Point3D v = ring.get(i);
            if (az >= ax && az >= ay)      { poly2D[i][0] = v.x; poly2D[i][1] = v.y; }
            else if (ay >= ax && ay >= az) { poly2D[i][0] = v.x; poly2D[i][1] = v.z; }
            else                           { poly2D[i][0] = v.y; poly2D[i][1] = v.z; }
        }
        double testX, testY;
        if (az >= ax && az >= ay)      { testX = test.x; testY = test.y; }
        else if (ay >= ax && ay >= az) { testX = test.x; testY = test.z; }
        else                           { testX = test.y; testY = test.z; }
        return GeometryUtils.pointInPolygon2D(testX, testY, poly2D);
    }

    private static List<Double> ringValues(LinearRing r) {
        if (r.getControlPoints() == null || r.getControlPoints().getPosList() == null) return null;
        return r.getControlPoints().getPosList().getValue();
    }

    /** Sammelt die Aussenring-LinearRings aller Huellen-Polygone (wie {@link SolidShellUtils#rebuildSolidShell}). */
    private static void collectShellExteriorRings(AbstractBuilding target, List<LinearRing> out) {
        for (var boundary : target.getBoundaries()) {
            var surface = boundary.getObject();
            if (!(surface instanceof AbstractThematicSurface ats)) continue;
            if (surface instanceof FloorSurface || surface instanceof CeilingSurface) continue;
            addExteriorRings(ats.getMultiSurface(3), out);
            if (surface instanceof WallSurface wall) {
                for (AbstractFillingSurfaceProperty fp : wall.getFillingSurfaces()) {
                    var fill = fp.getObject();
                    if (fill != null) addExteriorRings(fill.getMultiSurface(3), out);
                }
            }
        }
    }

    private static void addExteriorRings(MultiSurfaceProperty msp, List<LinearRing> out) {
        if (msp == null || msp.getObject() == null) return;
        for (SurfaceProperty m : msp.getObject().getSurfaceMember()) {
            if (m.getObject() instanceof Polygon poly
                    && poly.getExterior() != null
                    && poly.getExterior().getObject() instanceof LinearRing r) {
                out.add(r);
            }
        }
    }
}
