package de.mpsc.sql2gml;

/**
 * Variante von {@link HealedReplaceWorkflow}, die triangulierte Geometrien als einzelne
 * gml:Polygon statt gml:TriangulatedSurface schreibt (CityDoctor-3.18.2-Workaround, siehe
 * Doku.md).
 *
 * Aufruf identisch zum Standard-Workflow, nur mit anderer Hauptklasse:
 * <pre>
 *   java -cp sql2gml-complete.jar de.mpsc.sql2gml.PolygonOnlyReplaceWorkflow &lt;input.gml&gt; &lt;database.db&gt; [&lt;output.gml&gt;]
 * </pre>
 */
public class PolygonOnlyReplaceWorkflow {

    public static void main(String[] args) {
        HealedReplaceWorkflow.setGeometryMode(HealedReplaceWorkflow.GeometryMode.ALWAYS_POLYGON);
        HealedReplaceWorkflow.main(args);
    }
}
