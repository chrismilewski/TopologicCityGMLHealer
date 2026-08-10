package de.mpsc.lod2tolod3;

import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.core.AbstractCityObject;
import org.citygml4j.core.model.core.AbstractSpace;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.citygml4j.core.model.core.AbstractGenericAttributeProperty;
import org.citygml4j.core.model.generics.StringAttribute;
import org.citygml4j.core.visitor.ObjectWalker;
import org.xmlobjects.gml.model.basictypes.Code;
import org.xmlobjects.gml.model.geometry.aggregates.MultiCurveProperty;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.SolidProperty;
import de.mpsc.lod2tolod3.util.CityGmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schritt 1: Stuft alle LoD2-Geometrien auf LoD3 hoch, unveraendert bis auf den Slot
 * (siehe Doku.md, Abschnitt "Schritt 1").
 *
 * Usage:
 *   java -jar lod2-to-lod3-promoter.jar <input.gml> [output.gml]
 */
public class Lod2ToLod3Promoter {
    private static final Logger log = LoggerFactory.getLogger(Lod2ToLod3Promoter.class);

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                System.err.println("Usage: Lod2ToLod3Promoter <input.gml> [output.gml]");
                System.exit(1);
            }

            Path inputPath = Paths.get(args[0]);
            Path outputPath;
            
            if (args.length >= 2) {
                outputPath = Paths.get(args[1]);
            } else {
                String baseName = inputPath.getFileName().toString();
                if (baseName.toLowerCase().endsWith(".gml")) {
                    baseName = baseName.substring(0, baseName.length() - 4);
                }
                String outputName = baseName.replace("LoD2", "LoD3") + "_converted.gml";
                outputPath = inputPath.getParent() != null 
                    ? inputPath.getParent().resolve(outputName)
                    : Paths.get(outputName);
            }

            AbstractGenerator.createParentDirs(outputPath);

            log.info("=== LoD2 → LoD3 Promoter (Geometrie-Hochstufung) ===");
            log.info("Input:  {}", inputPath);
            log.info("Output: {}", outputPath);

            Lod2ToLod3Promoter promoter = new Lod2ToLod3Promoter();
            PromotionStats stats = promoter.promote(inputPath, outputPath);

            log.info("=== Fertig ===");
            log.info("Gebaeude verarbeitet: {}", stats.buildingsProcessed);
            log.info("Geometrien hochgestuft: {}", stats.geometriesPromoted);
            log.info("Namen angepasst: {}", stats.namesRenamed);
            
            if (!stats.promotedTypes.isEmpty()) {
                log.info("Hochgestufte Geometrietypen:");
                for (String type : stats.promotedTypes) {
                    log.info("  - {}", type);
                }
            }

        } catch (Exception e) {
            log.error("Fehler: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /** Stuft eine CityGML-Datei von LoD2 auf LoD3 hoch. */
    public PromotionStats promote(Path inputFile, Path outputFile) throws Exception {
        PromotionStats stats = new PromotionStats();

        CityGmlUtils.processGmlFile(inputFile, outputFile, building -> {
            stats.buildingsProcessed++;
            BuildingPromotionResult result = promoteBuildingToLod3(building);
            stats.geometriesPromoted += result.promotedCount;
            stats.promotedTypes.addAll(result.promotedTypes);

            int namesConverted = renameLod2NamesToLod3(building);
            stats.namesRenamed += namesConverted;

            addPromotionMetadata(building, result);
        });

        return stats;
    }

    /** Stuft alle LoD2-Geometrien eines Gebaeudes (AbstractThematicSurface/AbstractSpace) auf LoD3 hoch. */
    public BuildingPromotionResult promoteBuildingToLod3(Building building) {
        BuildingPromotionResult result = new BuildingPromotionResult();

        building.accept(new ObjectWalker() {

            @Override
            public void visit(AbstractThematicSurface surface) {
                MultiSurfaceProperty lod2MultiSurface = surface.getMultiSurface(2);
                if (lod2MultiSurface != null) {
                    surface.setMultiSurface(3, lod2MultiSurface);
                    surface.setMultiSurface(2, null);
                    result.promotedCount++;
                    result.promotedTypes.add(surface.getClass().getSimpleName() + ".lod2MultiSurface");
                    log.trace("Hochgestuft: {}.lod2MultiSurface", surface.getClass().getSimpleName());
                }
                super.visit(surface);
            }
            
            @Override
            public void visit(AbstractSpace space) {
                SolidProperty lod2Solid = space.getSolid(2);
                if (lod2Solid != null) {
                    space.setSolid(3, lod2Solid);
                    space.setSolid(2, null);
                    result.promotedCount++;
                    result.promotedTypes.add(space.getClass().getSimpleName() + ".lod2Solid");
                    log.trace("Hochgestuft: {}.lod2Solid", space.getClass().getSimpleName());
                }
                
                // lod2MultiSurface -> lod3MultiSurface
                MultiSurfaceProperty lod2MultiSurface = space.getMultiSurface(2);
                if (lod2MultiSurface != null) {
                    space.setMultiSurface(3, lod2MultiSurface);
                    space.setMultiSurface(2, null);
                    result.promotedCount++;
                    result.promotedTypes.add(space.getClass().getSimpleName() + ".lod2MultiSurface");
                    log.trace("Hochgestuft: {}.lod2MultiSurface", space.getClass().getSimpleName());
                }
                
                // lod2MultiCurve -> lod3MultiCurve
                MultiCurveProperty lod2MultiCurve = space.getMultiCurve(2);
                if (lod2MultiCurve != null) {
                    space.setMultiCurve(3, lod2MultiCurve);
                    space.setMultiCurve(2, null);
                    result.promotedCount++;
                    result.promotedTypes.add(space.getClass().getSimpleName() + ".lod2MultiCurve");
                    log.trace("Hochgestuft: {}.lod2MultiCurve", space.getClass().getSimpleName());
                }
                
                super.visit(space);
            }
        });

        return result;
    }

    /** Fuegt Promotion-Metadaten als generisches Attribut zum Building hinzu. */
    void addPromotionMetadata(Building building, BuildingPromotionResult result) {
        if (result.promotedCount == 0) {
            return;
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String typesPromoted = String.join(", ", result.promotedTypes);
        String metadataValue = String.format("promoted=true; count=%d; types=[%s]; timestamp=%s",
                result.promotedCount, typesPromoted, timestamp);

        StringAttribute promotionAttr = new StringAttribute();
        promotionAttr.setName("lod2ToLod3Promotion");
        promotionAttr.setValue(metadataValue);

        if (building.getGenericAttributes() == null) {
            building.setGenericAttributes(new ArrayList<>());
        }
        building.getGenericAttributes().add(new AbstractGenericAttributeProperty(promotionAttr));
    }

    /** Benennt LOD2-Namen in LOD3 um (gml:name und gen:stringAttribute). */
    public int renameLod2NamesToLod3(Building building) {
        AtomicInteger convertedCount = new AtomicInteger(0);

        building.accept(new ObjectWalker() {
            @Override
            public void visit(AbstractThematicSurface surface) {
                convertedCount.addAndGet(renameNames(surface.getNames()));
                convertedCount.addAndGet(renameGenericAttributes(surface));
                super.visit(surface);
            }
            
            @Override
            public void visit(AbstractSpace space) {
                convertedCount.addAndGet(renameNames(space.getNames()));
                convertedCount.addAndGet(renameGenericAttributes(space));
                super.visit(space);
            }
        });

        return convertedCount.get();
    }

    /** Prueft, ob ein String eine LOD2-Variante (LOD2/LoD2/lod2) enthaelt. */
    private static boolean containsLod2(String s) {
        return s != null && (s.contains("LOD2") || s.contains("LoD2") || s.contains("lod2"));
    }

    /** Ersetzt alle LOD2-Varianten durch die entsprechende LOD3-Schreibweise. */
    private static String replaceLod2WithLod3(String s) {
        return s.replace("LOD2", "LOD3").replace("LoD2", "LoD3").replace("lod2", "lod3");
    }

    /** Benennt gml:name von LOD2 zu LOD3 um. */
    private int renameNames(List<Code> names) {
        if (names == null || names.isEmpty()) return 0;
        int count = 0;
        for (Code name : names) {
            String value = name.getValue();
            if (containsLod2(value)) {
                name.setValue(replaceLod2WithLod3(value));
                count++;
            }
        }
        return count;
    }

    /** Benennt gen:stringAttribute Namen von LOD2 zu LOD3 um. */
    private int renameGenericAttributes(AbstractCityObject cityObject) {
        if (cityObject.getGenericAttributes() == null) return 0;
        int count = 0;
        for (var attrProp : cityObject.getGenericAttributes()) {
            if (attrProp.getObject() instanceof StringAttribute strAttr && containsLod2(strAttr.getName())) {
                strAttr.setName(replaceLod2WithLod3(strAttr.getName()));
                count++;
            }
        }
        return count;
    }

    /** Ergebnis der Hochstufung eines einzelnen Buildings. */
    public static class BuildingPromotionResult {
        public int promotedCount = 0;
        public Set<String> promotedTypes = new HashSet<>();
    }

    /** Statistiken der Gesamt-Hochstufung. */
    public static class PromotionStats {
        public int buildingsProcessed = 0;
        public int geometriesPromoted = 0;
        public int namesRenamed = 0;
        public Set<String> promotedTypes = new HashSet<>();
    }
}
