package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.CityGMLVersion;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractFeature;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.generics.StringAttribute;
import org.citygml4j.xml.CityGMLContext;
import org.citygml4j.xml.reader.CityGMLReader;
import org.citygml4j.xml.reader.ChunkOptions;
import org.citygml4j.xml.writer.CityGMLChunkWriter;
import org.citygml4j.xml.writer.CityGMLOutputFactory;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.basictypes.Sign;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.OrientableSurface;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;
import org.xmlobjects.gml.model.geometry.primitives.SurfaceProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Gemeinsame Hilfsfunktionen für CityGML-Verarbeitung: Attribut-Zugriff, GML-Datei-I/O und
 * SRS-annotierte MultiSurfaceProperty-Erzeugung.
 */
public final class CityGmlUtils {

    private static final Logger log = LoggerFactory.getLogger(CityGmlUtils.class);

    /** STRUKTUR-Marker fuer Balkon-BuildingInstallations (siehe Doku.md Schritt 6). */
    public static final String STRUKTUR_BALCONY_DECK = "Balkondecke";
    public static final String STRUKTUR_BALCONY_RAILING = "Balkonbruestung";

    private CityGmlUtils() {
        // Utility-Klasse
    }

    // ==================== Attribut-Helfer ====================

    /** Liest ein StringAttribute aus einem AbstractCityObject. */
    public static String getStringAttribute(AbstractCityObject cityObject, String name) {
        if (cityObject.getGenericAttributes() == null) {
            return null;
        }
        return cityObject.getGenericAttributes().stream()
                .map(AbstractGenericAttributeProperty::getObject)
                .filter(Objects::nonNull)
                .filter(attr -> attr instanceof StringAttribute)
                .map(attr -> (StringAttribute) attr)
                .filter(attr -> name.equals(attr.getName()))
                .map(StringAttribute::getValue)
                .findFirst()
                .orElse(null);
    }

    /** Fuegt ein StringAttribute zu einem AbstractCityObject hinzu. */
    public static void addStringAttribute(AbstractCityObject cityObject, String name, String value) {
        StringAttribute attr = new StringAttribute(name, value);
        AbstractGenericAttributeProperty prop = new AbstractGenericAttributeProperty(attr);
        cityObject.getGenericAttributes().add(prop);
    }

    /** Setzt ein StringAttribute; aktualisiert den Wert falls es bereits existiert, sonst neu. */
    public static void setStringAttribute(AbstractCityObject cityObject, String name, String value) {
        if (cityObject.getGenericAttributes() != null) {
            for (var prop : cityObject.getGenericAttributes()) {
                if (prop.getObject() instanceof StringAttribute sa && name.equals(sa.getName())) {
                    sa.setValue(value);
                    return;
                }
            }
        }
        addStringAttribute(cityObject, name, value);
    }

    /** Liest ein StringAttribute als Double; null bei fehlendem Attribut oder Parse-Fehler. */
    public static Double parseDoubleAttribute(AbstractCityObject cityObject, String name) {
        String str = getStringAttribute(cityObject, name);
        if (str == null) return null;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Setzt den gml:name eines CityGML-Objekts. */
    public static void setGmlName(AbstractCityObject cityObject, String name) {
        cityObject.getNames().add(new Code(name));
    }

    // ==================== SRS / MultiSurfaceProperty ====================

    /** Standard-SRS fuer das Projekt. */
    public static final String SRS_NAME = "urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH";
    public static final int SRS_DIMENSION = 3;

    /** Erstellt eine MultiSurfaceProperty mit srsName/srsDimension. */
    private static MultiSurfaceProperty createMultiSurfacePropertyWithSrs(Polygon polygon,
            String srsName, int srsDimension) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(srsName);
        ms.setSrsDimension(srsDimension);
        ms.getSurfaceMember().add(new SurfaceProperty(polygon));
        return new MultiSurfaceProperty(ms);
    }

    /** Erstellt eine MultiSurfaceProperty mit Standard-SRS. */
    public static MultiSurfaceProperty createMultiSurfacePropertyWithDefaultSrs(Polygon polygon) {
        return createMultiSurfacePropertyWithSrs(polygon, SRS_NAME, SRS_DIMENSION);
    }

    /** MultiSurfaceProperty mit XLink-Referenz auf ein bestehendes Polygon, mit umgekehrter
     * Normale (gml:OrientableSurface orientation="-") — fuer geteilte Boden/Decke-Geometrie, deren
     * Eigentuemer-Polygon (Decke) fest auf eine Richtung erzwungen ist, siehe Doku.md. */
    public static MultiSurfaceProperty createReversedXLinkMultiSurfaceProperty(String polygonGmlId) {
        MultiSurface ms = new MultiSurface();
        ms.setSrsName(SRS_NAME);
        ms.setSrsDimension(SRS_DIMENSION);
        OrientableSurface os = new OrientableSurface(new SurfaceProperty("#" + polygonGmlId));
        os.setOrientation(Sign.MINUS);
        ms.getSurfaceMember().add(new SurfaceProperty(os));
        return new MultiSurfaceProperty(ms);
    }

    // ==================== GML-Datei-Verarbeitung ====================

    /** Liest alle Features einer CityGML-Datei, wendet processor auf jedes Building an, schreibt das Ergebnis. */
    public static void processGmlFile(Path input, Path output,
            Consumer<Building> processor) throws Exception {

        CityGMLContext context = CityGMLContext.newInstance();
        var in = context.createCityGMLInputFactory()
                .withChunking(ChunkOptions.defaults());
        CityGMLOutputFactory out = context.createCityGMLOutputFactory(CityGMLVersion.v1_0);

        org.xmlobjects.gml.model.feature.BoundingShape originalBoundedBy = null;
        try (CityGMLReader headerReader = context.createCityGMLInputFactory()
                .createCityGMLReader(input.toFile())) {
            if (headerReader.hasNext()) {
                var firstFeature = headerReader.next();
                if (firstFeature instanceof org.citygml4j.core.model.core.CityModel cm) {
                    originalBoundedBy = cm.getBoundedBy();
                }
            }
        }

        try (CityGMLReader reader = in.createCityGMLReader(input.toFile());
             CityGMLChunkWriter writer = out.createCityGMLChunkWriter(output,
                     StandardCharsets.UTF_8.name())) {

            writer.withIndent("\t").withDefaultPrefixes();
            if (originalBoundedBy != null) {
                writer.getCityModelInfo().setBoundedBy(originalBoundedBy);
                log.info("BoundedBy-Envelope uebernommen");
            }

            while (reader.hasNext()) {
                AbstractFeature feature = reader.next();
                if (feature instanceof Building building) {
                    processor.accept(building);
                }
                writer.writeMember(feature);
            }
        }
    }

    /** Erstellt den Ausgabe-Pfad aus Eingabe-Pfad + Suffix, oder verwendet explicitOutput falls gesetzt. */
    public static Path resolveOutputPath(Path inputPath, String suffix, Path explicitOutput) {
        if (explicitOutput != null) return explicitOutput;
        String baseName = inputPath.getFileName().toString();
        if (baseName.toLowerCase().endsWith(".gml")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String outputName = baseName + suffix + ".gml";
        return inputPath.getParent() != null
                ? inputPath.getParent().resolve(outputName)
                : Paths.get(outputName);
    }
}
