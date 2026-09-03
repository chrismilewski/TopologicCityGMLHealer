package de.mpsc.lod2tolod3.util;

import org.citygml4j.core.model.building.AbstractBuilding;
import org.citygml4j.core.model.building.Building;
import org.citygml4j.core.model.construction.GroundSurface;
import org.citygml4j.core.model.construction.RoofSurface;
import org.citygml4j.core.model.construction.WallSurface;
import org.citygml4j.core.model.core.AbstractThematicSurface;
import org.xmlobjects.gml.model.geometry.aggregates.MultiSurfaceProperty;
import org.xmlobjects.gml.model.geometry.primitives.Polygon;

import java.util.ArrayList;
import java.util.List;

/**
 * Abfragen auf Gebaeude-/BuildingPart-Ebene: Boundary-Sammlung, Verarbeitungsziele,
 * Dach-Z-Bereich.
 */
public final class BuildingQueryUtils {

    private BuildingQueryUtils() {
        // Utility-Klasse
    }

    /** Sammelt alle Boundary-Objekte eines bestimmten Typs. */
    public static <T> List<T> collectBoundariesByType(AbstractBuilding building, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (type.isInstance(boundary.getObject())) {
                result.add(type.cast(boundary.getObject()));
            }
        }
        return result;
    }

    /** Sammelt alle WallSurface-Objekte eines Gebaeudes oder BuildingParts. */
    public static List<WallSurface> collectWallSurfaces(AbstractBuilding building) {
        return collectBoundariesByType(building, WallSurface.class);
    }

    /** Verarbeitungsziele eines Buildings: BuildingParts (zuerst), dann das Building selbst. */
    public static List<AbstractBuilding> getBuildingTargets(Building building) {
        List<AbstractBuilding> targets = new ArrayList<>();
        if (building.getBuildingParts() != null) {
            for (var pp : building.getBuildingParts()) {
                if (pp.getObject() != null) targets.add(pp.getObject());
            }
        }
        if (!building.getBoundaries().isEmpty()) {
            targets.add(building);
        }
        return targets;
    }

    /** Sammelt alle WallSurfaces ueber alle Targets eines Buildings (BuildingParts + Building selbst). */
    public static List<WallSurface> collectAllWallSurfaces(Building building) {
        List<WallSurface> all = new ArrayList<>();
        for (AbstractBuilding target : getBuildingTargets(building)) {
            all.addAll(collectWallSurfaces(target));
        }
        return all;
    }

    /** Sammelt alle lod3MultiSurface-Polygone der BoundarySurfaces eines bestimmten Typs. */
    public static <T extends AbstractThematicSurface> List<Polygon> collectLod3Polygons(
            AbstractBuilding building, Class<T> surfaceType) {
        List<Polygon> polygons = new ArrayList<>();
        for (var boundary : building.getBoundaries()) {
            if (!surfaceType.isInstance(boundary.getObject())) continue;
            MultiSurfaceProperty msp = surfaceType.cast(boundary.getObject()).getMultiSurface(3);
            if (msp == null || msp.getObject() == null) continue;
            for (var member : msp.getObject().getSurfaceMember()) {
                if (member.getObject() instanceof Polygon poly) {
                    polygons.add(poly);
                }
            }
        }
        return polygons;
    }

    /** Sammelt alle GroundSurface-Polygone eines Gebaeudes oder BuildingParts. */
    public static List<Polygon> collectGroundPolygons(AbstractBuilding building) {
        return collectLod3Polygons(building, GroundSurface.class);
    }

    /** Sammelt alle RoofSurface-Polygone eines Gebaeudes oder BuildingParts. */
    public static List<Polygon> collectRoofPolygons(AbstractBuilding building) {
        return collectLod3Polygons(building, RoofSurface.class);
    }

    /** Ab welchem Flaechenanteil (relativ zur groessten geneigten Flaeche) eine geneigte
     *  Dachflaeche als "gross" gilt und damit die Traufhoehen-Konsensbildung mitbestimmt
     *  (verhindert, dass eine einzelne, komplexe Kehl-/Walm-Verschneidungsflaeche mit
     *  anomal niedrigem eigenem Minimum die Traufe alleine bestimmt). */
    private static final double MAJOR_FACET_AREA_RATIO = 0.5;

    /** Traufe/First der RoofSurface-Polygone, aus dem Konsens der grossen geneigten Dachflaechen
     *  (Mischdach: geneigte bevorzugt; siehe MAJOR_FACET_AREA_RATIO). */
    public static double[] getRoofZRange(AbstractBuilding building) {
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double dominantMinZ = Double.MAX_VALUE;
        double dominantArea = -1;
        // Separate Verfolgung geneigter Dachflaechen fuer Mischdach-Erkennung
        double slopedDominantMinZ = Double.MAX_VALUE;
        double slopedDominantArea = -1;
        double slopedRawMinZ = Double.MAX_VALUE; // Fallback: globales MinZ aller geneigten Flaechen
        List<double[]> slopedFacets = new ArrayList<>(); // {area, localMinZ} je geneigter Flaeche
        boolean hasSlopedRoof = false;
        for (var boundary : building.getBoundaries()) {
            if (boundary.getObject() instanceof RoofSurface rs) {
                // FACEAREA-Attribut lesen (gesetzt von Schritt 1 / Promoter)
                double area = -1;
                String faceAreaStr = CityGmlUtils.getStringAttribute(rs, "FACEAREA");
                if (faceAreaStr != null) {
                    try { area = Double.parseDouble(faceAreaStr); } catch (NumberFormatException ignored) {}
                }
                MultiSurfaceProperty msp = rs.getLod3MultiSurface();
                if (msp == null || msp.getObject() == null) continue;
                for (var member : msp.getObject().getSurfaceMember()) {
                    if (member.getObject() instanceof Polygon poly) {
                        double localMinZ = Double.MAX_VALUE;
                        double localMaxZ = -Double.MAX_VALUE;
                        for (Point3D p : GeometryUtils.toPoints(poly)) {
                            minZ = Math.min(minZ, p.z);
                            maxZ = Math.max(maxZ, p.z);
                            localMinZ = Math.min(localMinZ, p.z);
                            localMaxZ = Math.max(localMaxZ, p.z);
                        }
                        if (area > dominantArea) {
                            dominantArea = area;
                            dominantMinZ = localMinZ;
                        }
                        // Geometrische Flacherkennung: alle Z-Werte nahezu gleich → Flachdach-Polygon
                        boolean polyIsFlat = (localMaxZ - localMinZ) < 0.05;
                        if (!polyIsFlat) {
                            hasSlopedRoof = true;
                            slopedRawMinZ = Math.min(slopedRawMinZ, localMinZ);
                            slopedFacets.add(new double[]{area, localMinZ});
                            if (area > slopedDominantArea) {
                                slopedDominantArea = area;
                                slopedDominantMinZ = localMinZ;
                            }
                        }
                    }
                }
            }
        }
        if (minZ == Double.MAX_VALUE) return null;
        // Traufe: bei geneigtem Dach (auch Mischdach) Konsens der grossen geneigten Flaechen
        // (max. lokales Minimum unter allen Flaechen >= MAJOR_FACET_AREA_RATIO der groessten
        // geneigten Flaeche) statt blind der flaechengroessten Einzelflaeche zu vertrauen –
        // verhindert, dass eine komplexe Kehl-/Walm-Verschneidungsflaeche mit anomal niedrigem
        // eigenem Minimum die Traufe alleine bestimmt (siehe Doku.md).
        double traufeZ;
        if (hasSlopedRoof) {
            if (slopedDominantArea > 0) {
                double threshold = MAJOR_FACET_AREA_RATIO * slopedDominantArea;
                traufeZ = slopedFacets.stream()
                        .filter(f -> f[0] >= threshold)
                        .mapToDouble(f -> f[1])
                        .max().orElse(slopedDominantMinZ);
            } else {
                traufeZ = slopedRawMinZ;
            }
        } else {
            traufeZ = (dominantArea > 0) ? dominantMinZ : minZ;
        }
        // Index 0: traufeZ (dominante Dachflaeche), Index 1: maxZ,
        // Index 2: globalMinZ (Min-Z aller RoofSurface-Polygone, fuer Slab-Begrenzung),
        // Index 3: slopedRawMinZ (Min-Z nur geneigter Flaechen; MAX_VALUE = kein Mischdach)
        return new double[]{traufeZ, maxZ, minZ, slopedRawMinZ};
    }

    /** Liest das erste Polygon aus einer WallSurface (LoD3). */
    public static Polygon getWallPolygon(WallSurface wall) {
        MultiSurfaceProperty msp = wall.getLod3MultiSurface();
        if (msp == null || msp.getObject() == null) return null;
        var members = msp.getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return null;
        return members.get(0).getObject() instanceof Polygon poly ? poly : null;
    }

    /** Liest das erste Polygon aus einer RoofSurface (LoD3). */
    public static Polygon getRoofPolygon(RoofSurface roof) {
        MultiSurfaceProperty msp = roof.getLod3MultiSurface();
        if (msp == null || msp.getObject() == null) return null;
        var members = msp.getObject().getSurfaceMember();
        if (members == null || members.isEmpty()) return null;
        return members.get(0).getObject() instanceof Polygon poly ? poly : null;
    }
}
