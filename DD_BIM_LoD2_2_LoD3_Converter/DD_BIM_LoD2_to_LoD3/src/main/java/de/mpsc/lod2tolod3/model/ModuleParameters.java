package de.mpsc.lod2tolod3.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Alle Baukoerpermodul-Parameter aus den JSON-Dateien (BA/GF/UF/RO/UT/GA/IN/FL/BU/FD),
 * per Gson-@SerializedName deserialisiert, mit fehlertoleranten Typ-Adaptern.
 */
public class ModuleParameters {

    private static final Logger log = LoggerFactory.getLogger(ModuleParameters.class);

    // ==================== Fehlertolerante Typ-Adapter ====================

    /** Double: null/NaN/ungueltig → null. */
    private static final TypeAdapter<Double> SAFE_DOUBLE = new TypeAdapter<>() {
        @Override public Double read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            try {
                double v = in.nextDouble();
                return Double.isNaN(v) ? null : v;
            } catch (RuntimeException e) { in.skipValue(); return null; }
        }
        @Override public void write(JsonWriter out, Double v) throws IOException { out.value(v); }
    };

    /** Integer: null/ungueltig → null. */
    private static final TypeAdapter<Integer> SAFE_INT = new TypeAdapter<>() {
        @Override public Integer read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) { in.nextNull(); return null; }
            try {
                return in.nextInt();
            } catch (RuntimeException e) { in.skipValue(); return null; }
        }
        @Override public void write(JsonWriter out, Integer v) throws IOException { out.value(v); }
    };

    /** Boolean: null → null, String → parseBoolean, sonstiges Ungueltiges → null. */
    private static final TypeAdapter<Boolean> SAFE_BOOL = new TypeAdapter<>() {
        @Override public Boolean read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            if (t == JsonToken.NULL) { in.nextNull(); return null; }
            if (t == JsonToken.BOOLEAN) return in.nextBoolean();
            if (t == JsonToken.STRING) return Boolean.parseBoolean(in.nextString());
            in.skipValue();
            return null;
        }
        @Override public void write(JsonWriter out, Boolean v) throws IOException { out.value(v); }
    };

    /** String: null → null, Zahl → Zahl-Literal, Boolean → "true"/"false". */
    private static final TypeAdapter<String> SAFE_STRING = new TypeAdapter<>() {
        @Override public String read(JsonReader in) throws IOException {
            JsonToken t = in.peek();
            if (t == JsonToken.NULL) { in.nextNull(); return null; }
            if (t == JsonToken.BOOLEAN) return String.valueOf(in.nextBoolean());
            if (t == JsonToken.STRING || t == JsonToken.NUMBER) return in.nextString();
            in.skipValue();
            return null;
        }
        @Override public void write(JsonWriter out, String v) throws IOException { out.value(v); }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Double.class, SAFE_DOUBLE)
            .registerTypeAdapter(Integer.class, SAFE_INT)
            .registerTypeAdapter(Boolean.class, SAFE_BOOL)
            .registerTypeAdapter(String.class, SAFE_STRING)
            .create();

    // ==================== Hauptkategorien ====================

    @SerializedName("BA") private Basement basement;
    @SerializedName("GF") private GroundFloor groundFloor;
    @SerializedName("UF") private UpperFloor upperFloor;
    @SerializedName("RO") private Roof roof;
    @SerializedName("UT") private Utilities utilities;
    @SerializedName("GA") private Gallery gallery;
    @SerializedName("IN") private Interior interior;
    @SerializedName("FL") private Stairwell stairwell;
    @SerializedName("BU") private Building building;
    @SerializedName("FD") private FacadeDetails facadeDetails;

    private transient String moduleId;

    // ==================== Factory-Methode ====================

    /**
     * Lädt ModuleParameters aus einer JSON-Datei.
     * Lenient-Modus, damit auch {@code NaN}-Literale in den Quelldateien lesbar sind
     * (werden vom Double-Adapter auf {@code null} abgebildet).
     */
    public static Optional<ModuleParameters> fromFile(Path jsonFile) {
        try {
            String content = Files.readString(jsonFile);
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setStrictness(Strictness.LENIENT);
            ModuleParameters mp = GSON.fromJson(reader, ModuleParameters.class);
            if (mp == null) return Optional.empty();
            mp.moduleId = jsonFile.getFileName().toString().replace(".json", "");
            if (mp.building != null && mp.building.entranceDirections != null) {
                mp.building.entranceDirections.removeIf(Objects::isNull);
            }
            return Optional.of(mp);
        } catch (IOException | RuntimeException e) {
            log.error("Fehler beim Laden von {}: {}", jsonFile, e.getMessage());
            return Optional.empty();
        }
    }

    // ==================== Getter ====================

    public String getModuleId() { return moduleId; }
    public Basement getBasement() { return basement; }
    public GroundFloor getGroundFloor() { return groundFloor; }
    public UpperFloor getUpperFloor() { return upperFloor; }
    public Roof getRoof() { return roof; }
    public Utilities getUtilities() { return utilities; }
    public Gallery getGallery() { return gallery; }
    public Interior getInterior() { return interior; }
    public Stairwell getStairwell() { return stairwell; }
    public Building getBuilding() { return building; }
    public FacadeDetails getFacadeDetails() { return facadeDetails; }

    // ==================== Convenience-Methoden ====================

    /**
     * Prüft ob das Gebäude einen Keller hat.
     */
    public boolean hasBasement() {
        return building != null && Boolean.TRUE.equals(building.hasBasement) &&
               basement != null && basement.height != null && basement.height > 0;
    }

    /**
     * Liest den oberirdischen Anteil des Kellers (GF.heightAboveGround).
     * Gibt 0 zurueck wenn nicht vorhanden oder ungueltig.
     */
    public double getHeightGr() {
        if (groundFloor == null) return 0;
        Double hg = groundFloor.heightAboveGround;
        if (hg == null || hg <= 0) return 0;
        return hg;
    }

    /**
     * Gibt die Fenster-Parameter fuer ein Geschoss zurueck.
     *
     * @param geschoss Geschoss-Tag (GF, UF_1, UF_2, ..., BA)
     * @return WindowParams oder null wenn nicht vorhanden
     */
    public WindowParams getWindowParamsForGeschoss(String geschoss) {
        return switch (geschoss) {
            case null  -> null;
            case "GF"  -> groundFloor != null ? groundFloor.window : null;
            case "BA"  -> basement    != null ? basement.window    : null;
            case String s when s.startsWith("UF_") -> upperFloor != null ? upperFloor.window : null;
            default    -> null;
        };
    }

    // ==================== Innere Klassen ====================

    /**
     * BA - Basement (Kellergeschoss)
     */
    public static class Basement {
        public Double height;                                   // Kellerhöhe
        @SerializedName("CeHe") public Double ceilingHeight;    // Deckendicke
        public WindowParams window;                             // Kellerfenster
        public DoorParams door;                                 // Kellertür
    }

    /**
     * GF - Ground Floor (Erdgeschoss)
     */
    public static class GroundFloor {
        public Double height;                                        // Geschosshöhe
        @SerializedName("heightGr") public Double heightAboveGround; // Höhe über Gelände (oberird. Kelleranteil)
        @SerializedName("CeHe") public Double ceilingHeight;         // Deckendicke
        public WindowParams window;                                  // Fenster
        public DoorParams door;                                      // Eingangstür

        /**
         * Gesamte Geschosshoehe (height + ceilingHeight).
         * Gibt 0 zurueck wenn height fehlt.
         */
        public double getTotalHeight() {
            if (height == null) return 0;
            return height + (ceilingHeight != null ? ceilingHeight : 0);
        }
    }

    /**
     * UF - Upper Floor (Obergeschoss)
     */
    public static class UpperFloor {
        @SerializedName("extPolyHeight") public Boolean extendPolygonHeight; // Polygon-Höhe erweitern?
        public Double height;                                                // Geschosshöhe
        @SerializedName("CeHe") public Double ceilingHeight;                 // Deckendicke
        public WindowParams window;                                          // Fenster

        /**
         * Gesamte Geschosshoehe (height + ceilingHeight).
         * Gibt 0 zurueck wenn height fehlt.
         */
        public double getTotalHeight() {
            if (height == null) return 0;
            return height + (ceilingHeight != null ? ceilingHeight : 0);
        }
    }

    /**
     * RO - Roof (Dach)
     */
    public static class Roof {
        public WindowParams window;                          // Dachfenster
        public RoofShape shape;                              // Dachform
    }

    /**
     * Dachform-Parameter
     */
    public static class RoofShape {
        @SerializedName("Typ")   public Integer type;        // Dachtyp (1=Satteldach, 2=Flachdach, etc.)
        @SerializedName("RiHe")  public Double ridgeHeight;  // Firsthöhe
        @SerializedName("HSiTi") public Double hSideTile;    // Horizontal Side Tile
        @SerializedName("VSiTi") public Double vSideTile;    // Vertical Side Tile
        @SerializedName("HFrTi") public Double hFrontTile;   // Horizontal Front Tile
        @SerializedName("VFrTi") public Double vFrontTile;   // Vertical Front Tile
    }

    /**
     * Fenster-Parameter (für alle Geschosse)
     */
    public static class WindowParams {
        @SerializedName("HDistWaWi")    public Double hDistWallWindow;    // Abstand Wandecke → 1. Fenster
        @SerializedName("VDistFlWi")    public Double vDistFloorWindow;   // Brüstungshöhe (Boden → Fensterunterkante)
        @SerializedName("HDistWiWi")    public Double hDistWindowWindow;  // Lichter Abstand Fenster-Fenster
        @SerializedName("HDistDoWi")    public Double hDistDoorWindow;    // Abstand Tür-Fenster
        @SerializedName("WiLen")        public Double windowWidth;        // Fensterbreite
        @SerializedName("WiHe")         public Double windowHeight;       // Fensterhöhe
        @SerializedName("HDistMinWaWi") public Double hDistMinWallWindow; // Mindestabstand Wandecke-Fensterkante
        @SerializedName("MaxWiPerRow")  public Integer maxWindowsPerRow;  // Max. Fenster pro Reihe (null=kein Limit)

        /**
         * Prüft ob gültige Fensterparameter vorhanden sind.
         */
        public boolean isValid() {
            return windowWidth != null && windowWidth > 0 &&
                   windowHeight != null && windowHeight > 0;
        }

        /**
         * Gibt den Wert zurück oder 0 wenn null.
         */
        public static double safeValue(Double val) {
            return val != null ? val : 0;
        }
    }

    /**
     * Tür-Parameter
     */
    public static class DoorParams {
        @SerializedName("DoHe")      public Double doorHeight;    // Türhöhe
        @SerializedName("DoLen")     public Double doorWidth;     // Türbreite
        @SerializedName("HDistDoWa") public Double hDistDoorWall; // Abstand der 1. Tür vom Wandrand

        /**
         * Prüft ob gültige Türparameter vorhanden sind.
         */
        public boolean isValid() {
            return doorWidth != null && doorWidth > 0 &&
                   doorHeight != null && doorHeight > 0;
        }
    }

    /**
     * UT - Utilities (Versorgungsschächte)
     */
    public static class Utilities {
        @SerializedName("HDistWaPo")  public Double hDistWallPipe;  // Horizontaler Abstand Wand-Schacht
        @SerializedName("HDistWaPo2") public Double hDistWallPipe2; // Zweiter Abstand
        @SerializedName("VDistFlPo")  public Double vDistFloorPipe; // Vertikaler Abstand Boden-Schacht
        @SerializedName("SiX")        public Double sizeX;          // Schachtgröße X
        @SerializedName("SiY")        public Double sizeY;          // Schachtgröße Y
        @SerializedName("SiZ")        public Double sizeZ;          // Schachtgröße Z
        @SerializedName("DoUF")       public Boolean inUpperFloor;  // Schacht in OG?
        @SerializedName("DoGF")       public Boolean inGroundFloor; // Schacht in EG?
        @SerializedName("DoBA")       public Boolean inBasement;    // Schacht in Keller?
        @SerializedName("NrUt")       public Integer count;         // Anzahl Schächte
    }

    /**
     * GA - Gallery (Balkon/Galerie/Terrasse)
     */
    public static class Gallery {
        @SerializedName("GaLen")        public Double length;              // Balkonlänge
        @SerializedName("GaWid")        public Double width;               // Balkonbreite (Tiefe)
        @SerializedName("GaHe")         public Double height;              // Brüstungshöhe
        @SerializedName("HDistWaGa")    public Double hDistWallGallery;    // Abstand Wand-Balkon
        @SerializedName("HDistGaGa")    public Double hDistGalleryGallery; // Abstand Balkon-Balkon
        @SerializedName("HDistMinWaGa") public Double hDistMinWallGallery; // Min. Abstand Wand-Balkon
        @SerializedName("HDistWiGa")    public Double hDistWindowGallery;  // Abstand Fenster-Balkon
        @SerializedName("WiLen")        public Double doorWidth;           // Balkontür-Breite
        @SerializedName("WiHe")         public Double doorHeight;          // Balkontür-Höhe
        @SerializedName("HDistWaWi")    public Double hDistWallDoor;       // Abstand Wand-Balkontür
        @SerializedName("DistWiGa")     public Double distDoorGallery;     // Abstand Balkontür-Balkon
        @SerializedName("GaPa")         public String pattern;             // Gallery Pattern (z.B. "GaWiWi")

        /**
         * Prüft ob gültige Balkon-Parameter vorhanden sind.
         */
        public boolean isValid() {
            return length != null && length > 0 &&
                   width != null && width > 0;
        }
    }

    /**
     * IN - Interior (Innenraum-Aufteilung)
     */
    public static class Interior {
        @SerializedName("HIn")     public Integer horizontalDivisions; // Horizontale Teilung (Anzahl Räume)
        @SerializedName("VIn")     public Integer verticalDivisions;   // Vertikale Teilung (Anzahl Räume)
        @SerializedName("MinInSi") public Double minRoomSize;          // Minimale Raumgröße (m²)
    }

    /**
     * FL - Stairwell (Treppenhaus)
     */
    public static class Stairwell {
        @SerializedName("IDistStWa") public Double innerDistStairWall; // Innerer Abstand Treppe-Wand
        @SerializedName("StLen")     public Double length;             // Treppenhauslänge
        @SerializedName("StWid")     public Double width;              // Treppenhausbreite
    }

    /**
     * BU - Building (Allgemeine Gebäudedaten)
     */
    public static class Building {
        @SerializedName("BA")   public Boolean hasBasement;                // Hat Keller?
        @SerializedName("GFRo") public Boolean groundFloorToRoof;          // EG bis Dach durchgehend?
        @SerializedName("BuLen") public Double length;                     // Gebäudelänge
        @SerializedName("BuWid") public Double width;                      // Gebäudebreite
        @SerializedName("BuHe")  public Double height;                     // Gebäudehöhe
        @SerializedName("RoSh")  public Integer roofShape;                 // Dachform (1-4)
        @SerializedName("BaDo")  public Boolean hasBasementDoor;           // Hat Kellertür?
        @SerializedName("EnDi")  public List<Integer> entranceDirections;  // Eingangsrichtungen
        @SerializedName("SID")   public String structureId;                // Struktur-ID (z.B. "ME3")
    }

    /**
     * FD - Facade Details (Materialien und Vulnerabilität)
     */
    public static class FacadeDetails {
        @SerializedName("WallAttr")            public MaterialInfo wall;
        @SerializedName("CeilingAttr")         public MaterialInfo ceiling;
        @SerializedName("Window")              public MaterialInfo window;
        @SerializedName("FacadeAttr")          public MaterialInfo facade;
        @SerializedName("Groundsurface")       public MaterialInfo groundSurface;
        @SerializedName("FoundationAttr")      public MaterialInfo foundation;
        @SerializedName("Interiorwallsurface") public MaterialInfo interiorWall;
        @SerializedName("Door")                public MaterialInfo door;
        @SerializedName("GalleryDoor")         public MaterialInfo galleryDoor;
        @SerializedName("Closuresurface")      public MaterialInfo closureSurface;
    }

    /**
     * Material-Information mit Typ, Beschreibung und Vulnerabilität
     */
    public static class MaterialInfo {
        @SerializedName("typ") public String type;
        public String description;
        public Double vulnerability;
    }

    @Override
    public String toString() {
        return String.format("ModuleParameters[%s: hasBasement=%s, GF.height=%s, UF.height=%s]",
            moduleId,
            hasBasement(),
            groundFloor != null ? groundFloor.height : "null",
            upperFloor != null ? upperFloor.height : "null"
        );
    }
}
