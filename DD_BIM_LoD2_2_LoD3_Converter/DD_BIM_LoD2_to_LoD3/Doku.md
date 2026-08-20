# LoD2 → LoD3 Konvertierungspipeline

Modulare Java-Pipeline zur Konvertierung von CityGML LoD2-Modellen nach LoD3 mit citygml4j 3.2.7.

## Architektur

**Single-Pass-Pipeline**: Die Eingabedatei wird genau einmal gelesen. Fuer jedes
Gebaeude werden alle Verarbeitungsschritte im Speicher ausgefuehrt, bevor das Ergebnis
einmal geschrieben wird. Es entstehen keine Zwischendateien.

```
+-------------------------------------------------------------+
|                    Lod2ToLod3Pipeline                       |
|               (Single-Pass-Verarbeitung)                    |
+-------------------------------------------------------------+
      |                                               |
  CityGMLReader                               CityGMLChunkWriter
  (1x lesen)                                  (1x schreiben)
      |                                               |
      v                                               |
  +--- pro Building im Speicher: ------------------+  |
  |  1. Promoter:  LoD2 → LoD3 Geometrie          |  |
  |  2. Basement:  Keller hinzufuegen              |  |
  |  3. Storeys:   Geschosse unterteilen           |--+
  |  4. Doors:     Tueren einfuegen                |  |
  |  5. Windows:   Fenster einfuegen               |  |
  |  ----------------------------------------------|  |
  |  6. Conform:   T-Naht-Vertices einfuegen       |  |
  +------------------------------------------------+
```

Die Schritte 1–5 erzeugen die eigentliche LoD3-Geometrie; Schritt 6 ist eine
**streng formneutrale Topologie-Bereinigung** (kein bestehender Punkt wird verschoben,
nur Punkte auf bestehende Kanten eingefuegt), die die geometrische Validitaet des
Solids erhoeht — siehe
[Topologie-Bereinigung & geometrische Validitaet](#topologie-bereinigung--geometrische-validitaet).

Die Verarbeitungsschritte 1–5 koennen auch einzeln ausgefuehrt werden
(jeweils eigene `main()`-Methode mit eigenem Lese-/Schreibzyklus). Die
Nachbearbeitung (6) laeuft nur im Pipeline-Modus.

### Gemeinsame Generator-Basis (`AbstractGenerator`)

Die JSON-parametrisierten Generatoren (Keller, Geschosse, Tueren, Fenster — und
kuenftig Balkone) erben von der abstrakten Basisklasse `AbstractGenerator<S>`.
Sie kapselt das immer gleiche Geruest nach dem **Template-Method-Muster**:

- **CLI** (`runCli`): Arg-Parsing `<input.gml> <jsonDir> [output.gml]`,
  NPE-sichere Verzeichnis-Erstellung, Header-/Ergebnis-Logging.
- **Datei-Lauf** (`process`): Lese-/Schreibschleife ueber `CityGmlUtils.processGmlFile`.
- **Statistik** (`BaseStats` mit `buildingsProcessed`); jeder Generator erweitert sie.
- **Parameter-Aufloesung** (`resolveParams`): `sst`-Attribut → passendes Baukoerpermodul.
- **Hook** `onConfigure(args)` fuer Zusatz-Argumente (z.B. DGM beim BasementGenerator).

Eine konkrete Unterklasse implementiert nur noch die generator-spezifischen Stellen:
`outputSuffix()`, `displayName()`, `newStats()`, `processBuilding(...)` und `logResult(...)`.
Ein neuer Schritt (z.B. Balkone) ist damit auf die reine Fachlogik reduziert — CLI,
Datei-Loop und Statistik-Geruest kommen aus der Basis.

> Der `Lod2ToLod3Promoter` (Schritt 1) faellt bewusst aus diesem Schema, da er
> ohne JSON-Module und ohne `sst`-Aufloesung arbeitet.

## Schnellstart

### Komplette Pipeline (empfohlen)

```bash
# Mit Maven direkt ausfuehren (empfohlen fuer Entwicklung)
mvn exec:java -Dexec.mainClass=de.mpsc.lod2tolod3.Lod2ToLod3Pipeline \
  -Dexec.args="input.gml Baukoerpermodule_json/ output/"

# Mit optionalem Gelaendemodell (DGM) fuer praezise TerrainIntersectionCurves
# Unterstuetzt: .asc (ASCII Grid), .tif (GeoTIFF), .zip oder Verzeichnis (Mosaik)
mvn exec:java -Dexec.mainClass=de.mpsc.lod2tolod3.Lod2ToLod3Pipeline \
  -Dexec.args="input.gml Baukoerpermodule_json/ output/ DGM/Dresden/"

# Oder als JAR
mvn clean package -q
java -jar target/lod2-zu-lod3-pipeline.jar \
  input.gml \
  Baukoerpermodule_json/ \
  output/ \
  [dgm-pfad]          # optional: .asc, .tif, .zip oder Verzeichnis (Mosaik)
```

### Einzelne Schritte

**Schritt 1: LoD2 → LoD3 Geometrie-Hochstufung**
```bash
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.Lod2ToLod3Promoter input.gml output.gml
```

**Schritt 2: Keller hinzufuegen**
```bash
# Ohne DGM (flache TIC bei H_DGM)
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.BasementGenerator input.gml jsonDir/ output.gml

# Mit DGM (bilinear interpolierte TIC) — .asc, .tif, .zip oder Verzeichnis
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.BasementGenerator input.gml jsonDir/ output.gml DGM/Dresden/
```

**Schritt 3: Geschosse unterteilen**
```bash
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.StoreyGenerator input.gml jsonDir/ output.gml
```

**Schritt 4: Tueren hinzufuegen**
```bash
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.DoorGenerator input.gml jsonDir/ output.gml
```

**Schritt 5: Fenster hinzufuegen**
```bash
java -cp target/lod2-zu-lod3-pipeline.jar de.mpsc.lod2tolod3.WindowGenerator input.gml jsonDir/ output.gml
```

## Pipeline-Ablauf

Die Pipeline liest die Eingabedatei einmal, verarbeitet jedes Gebaeude im Speicher
durch alle Schritte, und schreibt das Ergebnis einmal. Keine Zwischendateien.

```
input.gml
    |
    v
[CityGMLReader — einmaliges Lesen]
    |
    +--- pro Building: -------------+
    |  1. LoD2 → LoD3                |
    |  2. Keller                     |
    |  3. Geschosse                  |
    |  4. Tueren                     |
    |  5a. Balkone (fuehrender Lauf) |
    |  5b. Fenster                   |
    |  5c. Balkone (Rest, falls Muster > 1 Lauf) |
    +---------------------------------+
    |
    v
[CityGMLChunkWriter — einmaliges Schreiben]
    |
    v
output.gml (final)
```

## Schritt 1: LoD2 → LoD3 Promoter

Stuft alle LoD2-Geometrien automatisch auf LoD3 hoch mittels generischer citygml4j-Basisklassen.
Die Geometrie selbst wird dabei nicht veraendert – sie wird lediglich vom LoD2-Slot in den LoD3-Slot verschoben.

### Funktionen

- **Generischer Ansatz**: Nutzt `AbstractThematicSurface` und `AbstractSpace`, um automatisch ALLE LoD2-Geometrien zu erfassen
- **Unterstuetzte Geometrietypen**:
  - `lod2MultiSurface` → `lod3MultiSurface` (alle BoundarySurfaces)
  - `lod2Solid` → `lod3Solid` (Building, BuildingPart)
  - `lod2MultiCurve` → `lod3MultiCurve`
- **Erfasste Klassen** (automatisch durch Vererbung):
  - WallSurface, RoofSurface, GroundSurface
  - CeilingSurface, FloorSurface
  - OuterCeilingSurface, OuterFloorSurface
  - InteriorWallSurface, ClosureSurface
  - Building, BuildingPart
  - Und alle zukuenftigen Unterklassen!
- **Namensanpassung**: `LOD2_Wall` → `LOD3_Wall`, `DachTyp_LOD2` → `DachTyp_LOD3`
- **Metadaten**: Fuegt `lod2ToLod3Promotion` Attribut zu jedem Building hinzu

### Beispiel-Output
```
=== LoD2 -> LoD3 Promoter (Geometrie-Hochstufung) ===
Input:  LoD2_33_416_5656_2_SN.gml
Output: LoD3_33_416_5656_2_SN.gml
=== Fertig ===
Gebaeude verarbeitet: 3801
Geometrien hochgestuft: 67079
Namen angepasst: 175676
Hochgestufte Geometrietypen:
  - Building.lod2Solid
  - RoofSurface.lod2MultiSurface
  - WallSurface.lod2MultiSurface
  - BuildingPart.lod2Solid
  - GroundSurface.lod2MultiSurface
```

## Schritt 2: Keller-Generator (BasementGenerator)

Fuegt Kellergeometrie basierend auf JSON-Baukoerpermodulen hinzu. Erzeugt CityGML-konforme
GroundSurface-, WallSurface- und CeilingSurface-Elemente mit allen geometrischen Attributen.

Der Keller reicht von H_DGM + heightGr (oberirdischer Anteil) nach unten bis
H_DGM + heightGr - (BA.height + BA.CeHe). Die Gesamthoehe des Kellers ist
BA.height + BA.CeHe (Raumhoehe + Deckendicke).

### Semantische Korrekturen

#### GroundSurface-Ersetzung und TerrainIntersectionCurve (TIC)

Bei Gebaeuden mit Keller entsteht ein semantisches Problem: Die originale **GroundSurface**
aus den LoD2-Daten liegt auf Gelaendeniveau (H_DGM). Die erzeugten Kellerwaende durchstossen
diese GroundSurface jedoch, da sie von der Kelleroberkante (H_DGM + heightGr) bis zum
Kellerboden reichen. Die GroundSurface bei H_DGM ist somit geometrisch inkonsistent.

**Loesung nach Kolbe (2009):**

1. **GroundSurface-Ersetzung**: Die originale GroundSurface bei H_DGM wird entfernt.
   Der Kellerboden wird stattdessen als neue **GroundSurface** (Semantik: physische Bodenplatte
   des Gebaeudes) erzeugt, anstelle einer FloorSurface.

2. **TerrainIntersectionCurve (TIC)**: Die Information, wo das Gebaeude das Gelaende schneidet,
   wird als `lod3TerrainIntersectionCurve` (gml:MultiCurve) am Building bzw. BuildingPart
   dokumentiert. Die TIC ist ein „Interface-Objekt" zwischen Gebaeude und Gelaendemodell
   (Kolbe, 2009).

```
Seitenansicht (Kellergebaeude):

     Dach ==================
          |              |
     Wand |  Gebaeude    | Wand         } LOD3_Wall
          |              |
     ======================== ← H_DGM + heightGr (Kelleroberkante)
     ||                    ||
     || K e l l e r r a u m||           } LOD3_BasementWall
     ||                    ||
     ======================== ← Kellerboden (neue GroundSurface / "Bodenplatte")

     ~~~~~~~~~~~~~~~~~~~~~~~~ ← H_DGM (Gelaendeniveau)
         ↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑↑
         TerrainIntersectionCurve (3D-Ring bei H_DGM)

     Vorher: GroundSurface bei H_DGM → geometrisch FALSCH (Waende durchstossen sie)
     Jetzt:  GroundSurface als Bodenplatte, TIC dokumentiert Gelaendeschnitt
```

**Ohne DGM (Standard):**
Die TIC wird als flacher 3D-Ring bei Z = H_DGM aus dem GroundSurface-Grundriss erzeugt.

**Mit DGM (optional):**
Die TIC wird mit bilinearer Interpolation aus dem Gelaendemodell erzeugt — jeder Vertex
des Grundrisses erhaelt seine tatsaechliche Gelaendehoehe aus dem DGM.

### Algorithmus im Detail

Der Keller-Generator arbeitet pro Gebaeude in folgenden Schritten:

#### 1. Modul-Zuordnung

```
Building (gml:id="DESNALK0pF0007iT")
   |
   +-- gen:stringAttribute name="sst" -> "EFH_2"
   |
   +-- JSON-Datei: EFH_2.json -> BA.height = 2.0, BA.CeHe = 0.55, GF.heightGr = 0.8
```

#### 2. GroundSurface-Polygone sammeln

Alle `bldg:GroundSurface`-Boundaries des Gebaeudes werden gesammelt.
Ein Gebaeude kann mehrere GroundSurface-Polygone haben (z.B. bei L-foermigen Grundrissen).

```
Draufsicht: GroundSurface-Polygon (Beispiel mit 4 Eckpunkten)

    P1 (416290.8, 5657417.4, 159.57)
      +---------------------------+ P4 (416277.5, 5657425.2, 159.57)
      |                           |
      |    GroundSurface          |   Alle Punkte auf Z = 159.57
      |    (Grundplatte)          |   (= Gelaendeniveau, H_DGM)
      |                           |
      +---------------------------+
    P2 (416283.6, 5657405.1, 159.57)  P3 (416270.3, 5657413.0, 159.57)
```

#### 3. Kellerboden erzeugen (GroundSurface / „Bodenplatte")

Die originale GroundSurface bei H_DGM wird ENTFERNT (da die Kellerwaende sie durchstossen).
Stattdessen wird der Kellerboden als neue GroundSurface erzeugt — die physische Bodenplatte
des Gebaeudes. ALLE Punkte des GroundSurface-Polygons werden auf die Kellerboden-Hoehe projiziert:

```
    basementTotalHeight = BA.height + BA.CeHe = 2.0 + 0.55 = 2.55
    basementTopZ = H_DGM + heightGr = 159.57 + 0.8 = 160.37
    basementFloorZ = basementTopZ - basementTotalHeight = 160.37 - 2.55 = 157.82
```

Das ergibt die neue GroundSurface (Bodenplatte) - ein zum Grundriss identisches Polygon, nur tiefer:

```
    Seitenansicht:

    P1 ========================= P2        Z = 160.37 (Keller-Oberkante)
    ||                           ||             = H_DGM + heightGr
    ||    Kellerraum             ||        BA.height + BA.CeHe = 2.55 m
    ||                           ||
    P1'========================= P2'       Z = 157.82 (GroundSurface / Bodenplatte)
         ^^^^^^^^^^^^^^^^^^^^^^^^^
         LOD3_Ground (STRUKTUR = "Bodenplatte")
```

Der Kellerboden ist eine `bldg:GroundSurface` mit `gml:name = "LOD3_Ground"` und Attribut
`STRUKTUR = "Bodenplatte"`. Die Gelaendeschnittlinie wird zusaetzlich als
TerrainIntersectionCurve (TIC) dokumentiert.

#### 4. Kellerwaende erzeugen (WallSurface)

Fuer JEDE Kante des GroundSurface-Polygons wird ein Wand-Rechteck erzeugt.
Die Oberkante liegt bei H_DGM + heightGr, die Unterkante bei basementFloorZ.
Der Schlusspunkt des Polygons wird vorher entfernt (P1==Plast bei geschlossenen Polygonen).

```
Fuer Kante P1 -> P2:

    A = P1 (oben, projiziert)           B = P2 (oben, projiziert)
    (416290.8, 5657417.4, 160.37)   (416283.6, 5657405.1, 160.37)
      +-------------------------------+       Z = 160.37 (H_DGM + heightGr)
      |                               |
      |        Kellerwand             |       Hoehe = BA.height + BA.CeHe = 2.55 m
      |        (WallSurface)          |
      |                               |
      +-------------------------------+       Z = 157.82 (Kellerboden)
    A'= P1' (unten)                B'= P2' (unten)
    (416290.8, 5657417.4, 157.82)   (416283.6, 5657405.1, 157.82)

    Polygon-Punkte: A -> B -> B' -> A' -> A (geschlossen)
```

Jede Kellerwand ist eine `bldg:WallSurface` mit `gml:name = "LOD3_BasementWall"`.

#### 5. Gesamtbild (3D-Ansicht)

```
    Draufsicht:                      Seitenansicht (Schnitt):

    P1 -------- P4                   Dach ==================
    |            |                        |              |
    | GroundSurf |                   Wand |  Gebaeude    | Wand    } LOD3_Wall
    |            |                        |              |
    P2 -------- P3                   ===========================   H_DGM + heightGr
                                     ||                        ||
                                     || K e l l e r r a u m   ||  } LOD3_BasementWall
                                     ||   (teilw. oberirdisch) ||
                                     ============================  LOD3_Ground (Bodenplatte)
                                     ^                          ^
                                     Z = 160.37 - 2.55          Z = 160.37
                                       = 157.82                   (H_DGM+heightGr)

    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~  H_DGM (TIC-Ring)

    Bei 4 Kanten entstehen:
    - 1x GroundSurface (Bodenplatte, ersetzt Original-GroundSurface)
    - 4x WallSurface   (Kellerwaende, eine pro Kante)
    - 1x CeilingSurface (Kellerdecke)
    - 1x TerrainIntersectionCurve (3D-Ring bei H_DGM)
    Original-GroundSurface bei H_DGM wird entfernt.
```

### Attribute der Keller-Elemente

Alle neuen Surfaces erhalten die gleichen Attribut-Typen wie die bestehenden Surfaces.
Alle Werte sind `gen:stringAttribute` (konsistent mit den Input-Daten).

#### GroundSurface (Bodenplatte) Attribute

| Attribut | Wert | Beschreibung |
|----------|------|--------------|
| `gml:name` | `LOD3_Ground` | GroundSurface des Gebaeudes (physische Bodenplatte) |
| `gml:id` | `Face_{buildingId}_BA_Ground_{n}` | Eindeutige ID |
| `BldgFaceID` | `{buildingId}_BA_Ground_{n}` | Face-Identifikator |
| `Z_MIN_ASL` | z.B. `157.07` | Absolute Hoehe ueber Meeresspiegel |
| `Z_Min` | z.B. `-2.5` | Relative Hoehe zu H_DGM (negativ = unter Gelaende) |
| `FACEAREA` | z.B. `220.688` | Flaeche in m2 (Gauss'sche Trapezformel) |
| `STRUKTUR` | `Bodenplatte` | Semantische Klassifikation (physische Bodenplatte) |
| `Lage` | `belowGround` | Keller-Lage |

#### WallSurface (Kellerwand) Attribute

| Attribut | Wert | Beschreibung |
|----------|------|--------------|
| `gml:name` | `LOD3_BasementWall` | Analog zu `LOD3_Wall` |
| `gml:id` | `Face_{buildingId}_BW_{n}` | Eindeutige ID |
| `BldgFaceID` | `{buildingId}_BW_{n}` | Face-Identifikator |
| `Z_MAX_ASL` | z.B. `160.37` | Oberkante absolut (= H_DGM + heightGr) |
| `Z_MIN_ASL` | z.B. `157.82` | Unterkante absolut (= Kellerboden) |
| `Z_Max` | z.B. `0.8` | Oberkante relativ zu H_DGM (= heightGr) |
| `Z_Min` | z.B. `-1.75` | Unterkante relativ zu H_DGM |
| `FACEAREA` | z.B. `35.676` | Wandflaeche in m2 |
| `NORMAL_AZI` | z.B. `120.673` | Azimut der Wandnormalen in Grad |
| `NORMAL_H` | `0` | Neigung der Normalen (0 = vertikal) |
| `STRUKTUR` | `Kellerwand` | Semantische Klassifikation |
| `Innenwand` | `0` | Aussenwand (keine Innenwand) |
| `Lage` | `belowGround` | Keller-Lage |
| `WindowPreference` | z.B. `ME2_BA` | Von Original-Wand uebernommen (fuer Fenstergeneration) |

#### Building-Attribute

| Attribut | Typ | Wert |
|----------|-----|------|
| `bldg:storeysBelowGround` | Natives CityGML-Element | `1` |
| `LoD3_Basement` | gen:stringAttribute | `generated` |

### Berechnungsformeln

#### FACEAREA (Kellerboden) - Gauss'sche Trapezformel (Shoelace)

```
A = 0.5 * |SUM(x_i * y_{i+1} - x_{i+1} * y_i)|

Beispiel mit 4 Eckpunkten (P1..P4):
A = 0.5 * |  (x1*y2 - x2*y1)
           + (x2*y3 - x3*y2)
           + (x3*y4 - x4*y3)
           + (x4*y1 - x1*y4) |
```

#### FACEAREA (Kellerwand) - Newell's Method (3D-Polygon-Flaeche)

```
Funktioniert fuer ALLE Polygon-Formen (Dreiecke, Rechtecke, Fuenfecke, etc.)

Algorithmus (Newell's Method):
1. Flaechennormale berechnen durch Kreuzprodukt-Summe:
   N.x = Summe( (y_i - y_{i+1}) * (z_i + z_{i+1}) )
   N.y = Summe( (z_i - z_{i+1}) * (x_i + x_{i+1}) )
   N.z = Summe( (x_i - x_{i+1}) * (y_i + y_{i+1}) )

2. Flaeche = halbe Laenge des Normalenvektors:
   FACEAREA = 0.5 * sqrt(N.x^2 + N.y^2 + N.z^2)

Fuer Rechtecke (4P) ist das Ergebnis identisch mit Breite*Hoehe.
Fuer Giebel (5P), Walm (6P), Dreiecke (3P), etc. wird die
tatsaechliche 3D-Flaeche des planaren Polygons berechnet.
```

#### NORMAL_AZI - Azimut der Wandnormalen

```
Fuer eine Kante A -> B:

  Kantenrichtung:  dx = B.x - A.x,  dy = B.y - A.y
  Wandnormale:     nx = -dy,         ny = dx

  Azimut = atan2(nx, ny) * 180/PI        (Ergebnis: -180..180)
  if (Azimut < 0) Azimut += 360          (Ergebnis:    0..360)

  Kompassrichtung: 0=Nord, 90=Ost, 180=Sued, 270=West

      N (0/360)
       |
  W ---+--- O (90)
       |
     S (180)
```

#### Z_Max / Z_Min - Relative Hoehen

```
Z_Max = Z_MAX_ASL - H_DGM     (Oberkante relativ zum Gelaende)
Z_Min = Z_MIN_ASL - H_DGM     (Unterkante relativ zum Gelaende)

Beispiel (Kellerwand):
  Z_MAX_ASL = 159.57    (Gelaendeniveau)
  H_DGM     = 159.57    (Gelaendehoehe aus Building-Attribut)
  Z_Max     = 0.0       (Oberkante = Gelaende -> 0)
  Z_Min     = -2.5      (Unterkante = 2.5m unter Gelaende)
```

### Beispiel-Output (XML)

```xml
<!-- GroundSurface (Bodenplatte — ersetzt die originale GroundSurface bei H_DGM) -->
<bldg:boundedBy>
  <bldg:GroundSurface gml:id="Face_DESNALK0pF0007iT_BA_Ground_1">
    <gml:name>LOD3_Ground</gml:name>
    <gen:stringAttribute name="BldgFaceID">
      <gen:value>DESNALK0pF0007iT_BA_Ground_1</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_MIN_ASL">
      <gen:value>157.07</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_Min">
      <gen:value>-2.5</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="FACEAREA">
      <gen:value>220.68799</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="STRUKTUR">
      <gen:value>Bodenplatte</gen:value>
    </gen:stringAttribute>
    <bldg:lod3MultiSurface>
      <gml:MultiSurface>
        <gml:surfaceMember>
          <gml:Polygon>
            <gml:exterior>
              <gml:LinearRing>
                <gml:posList srsDimension="3">
                  416290.843 5657417.357 157.07
                  416283.563 5657405.083 157.07
                  416270.262 5657412.972 157.07
                  416277.542 5657425.246 157.07
                  416290.843 5657417.357 157.07
                </gml:posList>
              </gml:LinearRing>
            </gml:exterior>
          </gml:Polygon>
        </gml:surfaceMember>
      </gml:MultiSurface>
    </bldg:lod3MultiSurface>
  </bldg:GroundSurface>
</bldg:boundedBy>

<!-- WallSurface (Kellerwand) -->
<bldg:boundedBy>
  <bldg:WallSurface gml:id="Face_DESNALK0pF0007iT_BW_1">
    <gml:name>LOD3_BasementWall</gml:name>
    <gen:stringAttribute name="BldgFaceID">
      <gen:value>DESNALK0pF0007iT_BW_1</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_MAX_ASL">
      <gen:value>159.57</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_MIN_ASL">
      <gen:value>157.07</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_Max">
      <gen:value>0</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Z_Min">
      <gen:value>-2.5</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="FACEAREA">
      <gen:value>35.67645</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="NORMAL_AZI">
      <gen:value>120.67318</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="NORMAL_H">
      <gen:value>0</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="STRUKTUR">
      <gen:value>Kellerwand</gen:value>
    </gen:stringAttribute>
    <gen:stringAttribute name="Innenwand">
      <gen:value>0</gen:value>
    </gen:stringAttribute>
    <bldg:lod3MultiSurface>
      <gml:MultiSurface>
        <gml:surfaceMember>
          <gml:Polygon>
            <gml:exterior>
              <gml:LinearRing>
                <gml:posList srsDimension="3">
                  416290.843 5657417.357 159.57
                  416283.563 5657405.083 159.57
                  416283.563 5657405.083 157.07
                  416290.843 5657417.357 157.07
                  416290.843 5657417.357 159.57
                </gml:posList>
              </gml:LinearRing>
            </gml:exterior>
          </gml:Polygon>
        </gml:surfaceMember>
      </gml:MultiSurface>
    </bldg:lod3MultiSurface>
  </bldg:WallSurface>
</bldg:boundedBy>

<!-- Building erhaelt natives CityGML-Element -->
<bldg:storeysBelowGround>1</bldg:storeysBelowGround>

<!-- TerrainIntersectionCurve (3D-Ring bei Gelaendeniveau) -->
<bldg:lod3TerrainIntersection>
  <gml:MultiCurve srsName="urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH" srsDimension="3">
    <gml:curveMember>
      <gml:LineString>
        <gml:posList srsDimension="3">
          416290.843 5657417.357 159.57
          416283.563 5657405.083 159.57
          416270.262 5657412.972 159.57
          416277.542 5657425.246 159.57
          416290.843 5657417.357 159.57
        </gml:posList>
      </gml:LineString>
    </gml:curveMember>
  </gml:MultiCurve>
</bldg:lod3TerrainIntersection>
```

## Schritt 3: Geschoss-Generator

Unterteilt Gebaeude in Geschosse basierend auf den Hoehen aus den JSON-Baukoerpermodulen.

### Funktionen

- Verwendet die Geschoss-Hoehen aus den JSON-Modulen:
  - `BA`: Basement (Keller) — Geschoss-Tag: `BA`
  - `GF`: Ground Floor (Erdgeschoss) — Geschoss-Tag: `GF`
  - `UF`: Upper Floor (Obergeschoss) — Geschoss-Tags: `UF_1`, `UF_2`, `UF_3`, ...
- **Dynamische UF-Berechnung**: `storeysAboveGround` aus CityGML wird **IGNORIERT**.
  Die Anzahl der Obergeschosse (UFs) wird dynamisch berechnet: so viele UFs wie in die
  Gebaeudehoehe zwischen GF-Decke und Traufe passen.
- **Keller-Overlap-Vermeidung**: Wandbereiche unterhalb `egFloorZ` (H_DGM + heightGr)
  werden verworfen, damit keine Ueberlappung mit den Kellerwaenden entsteht.
- **Wand-Schnitt mit Sutherland-Hodgman-Algorithmus**:
  - Schneidet ALLE Wandformen geschossweise (nicht nur Rechtecke!)
  - Unterstuetzte Polygon-Formen: Dreiecke (3P), Rechtecke (4P), Giebel (5P), Walm (6P), komplexe Formen (7+ P)
  - Iteratives Schneiden von unten nach oben an den Geschossgrenzen
- Erstellt fuer jedes Geschoss:
  - **FloorSurface** (Boden des Geschosses)
  - **CeilingSurface** (Decke des Geschosses)
- **Flachdach-Erkennung**: Bei Flachdaechern (First - Traufe < 0.30m) wird keine CeilingSurface am obersten Geschoss erzeugt, da die RoofSurface bereits die Decke bildet
- **Mischdach-Erkennung**: Bei Gebaeuden mit gemischtem Dach (Walm/Satteldach + Flachdach) wird die CeilingSurface am obersten Geschoss nur unter den geneigten Dachflaechen erzeugt, nicht unter dem Flachdach-Anteil (536 betroffene Gebaeude im Testdatensatz)
- **Fitzelchen-Schutz**: Wenn die Resthoehe bis zur Traufe unter `MIN_STOREY_HEIGHT` (1.20m) liegt,
  wird kein neues Geschoss erzeugt, sondern das vorherige Geschoss bis zur Traufe erweitert.
  - **Sonderregel Flachdach**: Wenn durch den Fitzelchen-Merge das resultierende Geschoss
    ueber `MAX_STOREY_HEIGHT_FLACHDACH` (4.0m) Hoehe bekommen wuerde, wird das Fitzelchen
    stattdessen als eigenes kurzes Geschoss beibehalten. Damit werden unrealistisch hohe
    Geschosse (>4m) auf Flachdaechern vermieden.
  - Diese Sonderregel gilt **nur fuer Flachdaecher** (First - Traufe < 0.30m).
    Bei geneigten Daechern (Sattel-/Walmdach) bleibt das Merge-Verhalten unveraendert,
    da dort die variierende Wandhoehe durch die Dachform natuerlich ist.
- **Polygon-basiert**: Nutzt die tatsaechlichen GroundSurface-Polygone als Grundriss (nicht Bounding Box)
- **Multi-Polygon-Unterstuetzung**: Verarbeitet Gebaeude mit mehreren Grundriss-Polygonen korrekt
- **GroundSurface-Erhaltung**: Original-GroundSurface wird mit Attribut `Original_GroundSurface=preserved` markiert (ausgenommen BA-Bodenplatten mit `STRUKTUR=Bodenplatte` vom BasementGenerator)
- Fuegt `storeysGenerated` Attribut zu jedem Building hinzu
- Aktualisiert `storeysAboveGround` entsprechend der dynamisch berechneten Geschossanzahl
- **lod3Solid Shell-Rebuild**: Nach der Verarbeitung wird die lod3Solid-Shell komplett neu aufgebaut (s. [lod3Solid Shell-Rebuild](#lod3solid-shell-rebuild))

### Benennung / Polygon-IDs

| Typ | Format | Beispiel |
|-----|--------|----------|
| Wand-Segment | `Face_{OrigPolyId}_{StoreyTag}_{LaufendeNr}` | `Face_000BVXQ_0_1_GF_1` |
| Floor | `Face_{BuildingId}_{StoreyTag}_Floor_{PolyIdx}` | `Face_DESNALK0pF001iWz_GF_Floor_1` |
| Ceiling | `Face_{BuildingId}_{StoreyTag}_Ceiling_{PolyIdx}` | `Face_DESNALK0pF001iWz_UF_1_Ceiling_1` |
| Slab-Geometrie | `Slab_{BuildingId}_{StoreyTag}_{PolyIdx}` | `Slab_DESNALK0pF001iWz_GF_1` |
| Keller-Wand | `Face_{BuildingId}_BA_Wall_{Nr}` | `Face_DESNALK0pF0007iT_BA_Wall_1` |
| Keller-Boden (GroundSurface) | `Face_{BuildingId}_BA_Ground_{Nr}` | `Face_DESNALK0pF0007iT_BA_Ground_1` |
| Keller-Decke | `Face_{BuildingId}_BA_Ceiling_{Nr}` | `Face_DESNALK0pF0007iT_BA_Ceiling_1` |

### Geschossdecken: XLink-Referenzen statt doppelter Geometrie

#### Problem: Doppelte Polygone an Geschossgrenzen

An jeder Geschossgrenze liegen die **CeilingSurface** des unteren Geschosses und die
**FloorSurface** des oberen Geschosses auf exakt derselben Z-Hoehe mit identischem
Grundriss. Naiv implementiert bedeutet das: zwei Polygon-Definitionen mit identischen
Koordinaten — eine mit CCW-Winding (Floor, Normale nach oben) und eine mit CW-Winding
(Ceiling, Normale nach unten).

**Zwei Optionen standen zur Wahl:**

| | Option A: Doppelte Polygone | Option B: XLink-Referenz |
|---|---|---|
| **Geometrie** | 2× gleiche Koordinaten, unterschiedliche Orientierung | 1× Polygon mit `gml:id`, 1× `xlink:href` |
| **Dateigroesse** | Groesser (jede Koordinate doppelt) | Kleiner (~50% weniger Slab-Daten) |
| **Konsistenz** | Risiko: Floor/Ceiling koennten geometrisch auseinanderlaufen | Garantiert identisch (gleiche Quelle) |
| **CityGML-konform** | Ja, aber redundant | Ja — XLink ist der vorgesehene GML-Mechanismus fuer geteilte Geometrie |
| **Orientierung** | Explizit unterschiedlich pro Surface | Semantischer Typ (FloorSurface/CeilingSurface) ist autoritativ |

#### Entscheidung: XLink (Option B)

Wir verwenden **XLink-Referenzen** (`xlink:href`) fuer geteilte Geschossdecken. Gruende:

1. **„Geometry once, semantics twice"**: Das Polygon wird einmal als echte Geometrie definiert
   (mit `gml:id`), und von beiden Surfaces referenziert. Die Semantik (Floor vs. Ceiling) wird
   durch den CityGML-Elementtyp bestimmt, nicht durch die Polygon-Orientierung.

2. **Datensparsamkeit**: Bei ~5.700 Geschossen werden tausende doppelte Polygon-Koordinaten
   eingespart.

3. **Topologische Konsistenz**: Es ist physisch unmoeglich, dass Floor und Ceiling einer
   Geschossgrenze geometrisch auseinanderlaufen — sie verweisen auf dasselbe Objekt.

4. **CityGML-kanonisch**: XLink-Referenzen in `gml:surfaceMember` sind ein Standard-Mechanismus
   in GML 3.1/CityGML 1.0. Die citygml4j-Bibliothek unterstuetzt dies ueber
   `SurfaceProperty(String href)` nativ.

5. **Polygon-Orientierung ist kein Problem**: Fuer `lod3MultiSurface` (nicht Solid/B-Rep) ist
   die Flaechennormale informativ, nicht topologisch bindend. Alle gaengigen CityGML-Viewer
   (FME, 3DCityDB, citygml4j) nutzen den semantischen Typ zur Interpretation.

#### Zuordnungsschema

```
BA  Ceiling → inline, gml:id="Slab_{id}_BA_1"           ← Geometrie definiert
GF  Floor   → xlink:href="#Slab_{id}_BA_1"               ← Referenz (shared!)
GF  Ceiling → inline, gml:id="Slab_{id}_GF_1"           ← Geometrie definiert
UF_1 Floor  → xlink:href="#Slab_{id}_GF_1"               ← Referenz (shared!)
UF_1 Ceiling→ inline, gml:id="Slab_{id}_UF_1_1"         ← Geometrie definiert
UF_2 Floor  → xlink:href="#Slab_{id}_UF_1_1"             ← Referenz (shared!)
...
```

**Sonderfaelle:**
- **GF Floor ohne Keller**: Kein vorhergehendes Ceiling → inline (eigene Geometrie)
- **Oberstes Ceiling bei Flachdach**: Wird nicht erzeugt (RoofSurface = Decke) → kein XLink
- **BA Boden**: Kein Floor darunter → immer inline
- **Mischdach-Ceiling**: Projizierte Dachpolygone → immer inline (andere Geometrie)

### Schwellwerte (StoreyGenerator)

| Konstante | Wert | Beschreibung |
|-----------|------|--------------|
| `CUT_TOLERANCE` | 0.05m | Mindesthoehe fuer Schnitt-Ergebnisse, verhindert degenerierte Geometrien |
| `MIN_WALL_SEGMENT_HEIGHT` | 0.50m | Duennstreifen-Schutz: ragt eine Wand nur knapp ueber eine Geschossgrenze, wird der letzte Schnitt verworfen (kein hauchduennes Top-Segment) |
| `MIN_STOREY_HEIGHT` | 1.20m | Fitzelchen-Schwelle: Resthoehe unter diesem Wert wird ins vorherige Geschoss gemerged |
| `MAX_STOREY_HEIGHT_FLACHDACH` | 4.00m | Nur Flachdach: Wenn Merge-Ergebnis diesen Wert uebersteigt, wird stattdessen ein kurzes Fitzelchen-Geschoss erzeugt |
| `XY_EDGE_TOLERANCE` | 0.50m | Toleranz fuer die Zuordnung von Wandsegment-Basiskanten zu Grundriss-Polygonen (Per-Polygon-Hoehenbegrenzung der Slabs) |
| Flachdach-Erkennung | First - Traufe < 0.30m | Schwelle fuer Flachdach vs. geneigtes Dach |

### Sutherland-Hodgman: Wandschnitt fuer beliebige Polygone

Der Wandschnitt verwendet den Sutherland-Hodgman-Algorithmus, um **beliebige** Wand-Polygone
an einer horizontalen Ebene (Z-Hoehe) zu teilen. Das ist ein generischer Polygon-Clipping-
Algorithmus, der fuer jede Polygon-Form funktioniert.

#### Algorithmus im Detail

```
Eingabe: Polygon P mit n Eckpunkten, Schnitthoehe zCut, Toleranz

Fuer jeden Eckpunkt P[i]:
  1. Klassifiziere P[i] als:
     - UNTERHALB: P[i].z < zCut - eps
     - OBERHALB:  P[i].z > zCut + eps
     - AUF:       |P[i].z - zCut| <= eps  (liegt auf der Schnittebene)

  2. Punkt zuordnen:
     - UNTERHALB → nur zum unteren Polygon
     - OBERHALB  → nur zum oberen Polygon
     - AUF       → zu BEIDEN Polygonen (liegt auf der gemeinsamen Kante)

  3. Kante P[i] → P[i+1] pruefen:
     - Wenn die Kante die Schnittebene kreuzt (einer oben, einer unten):
       → Schnittpunkt per linearer Interpolation berechnen:
         t = (zCut - P[i].z) / (P[i+1].z - P[i].z)
         I.x = P[i].x + t * (P[i+1].x - P[i].x)
         I.y = P[i].y + t * (P[i+1].y - P[i].y)
         I.z = zCut
       → Schnittpunkt I zu BEIDEN Polygonen hinzufuegen

Ausgabe: [unteres Polygon, oberes Polygon]
```

#### Beispiele fuer verschiedene Wandformen

```
Rechteck (4 Punkte) → Standard-Wand:
  D ─── C              D ─── C    oberes Polygon (Rechteck)
  │     │    zCut →     E ─── F
  │     │              E ─── F    unteres Polygon (Rechteck)
  A ─── B              A ─── B

Fuenfeck (5 Punkte) → Giebelwand:
       C                    C     oberes Polygon (Dreieck)
      ╱ ╲        zCut →   I1 ── I2
    D     B              I1 ── I2 unteres Polygon (Rechteck)
    │     │
    E ─── A              E ─── A

Sechseck (6 Punkte) → Walmdach-Wand:
   D ─── C              D ─── C    oberes Polygon (Trapez)
  ╱       ╲   zCut →   I1     I2
 E         B           I1     I2   unteres Polygon (Rechteck)
 F ─────── A           F ──── A

Dreieck (3 Punkte) → Giebelspitze:
       B                    B      oberes Polygon (Dreieck)
      ╱ ╲     zCut →     I1 ── I2
    C     A              (unteres Polygon nur wenn Punkte unterhalb)
```

#### Empirische Verteilung der Wandformen im Testdatensatz

Analyse von 100.837 Wandpolygonen aus dem LoD3-Output:

| Punkte | Anzahl | Anteil | Typische Form |
|--------|--------|--------|---------------|
| 3      | 374    | 0.4%   | Dreiecke (Giebelspitzen) |
| 4      | 91.152 | 90.4%  | Rechtecke (Standard-Waende) |
| 5      | 2.936  | 2.9%   | Fuenfecke (Giebelwaende) |
| 6      | 3.940  | 3.9%   | Sechsecke (Walmdach-Waende) |
| 7      | 792    | 0.8%   | Komplexe Dachverschneidungen |
| 8      | 774    | 0.8%   | L-Formen, Stufen |
| 9-34   | 869    | 0.9%   | Gauben, Erker, komplexe Dachlandschaften |

→ **Vorher**: Nur 90.4% der Waende konnten geschnitten werden (4-Punkt-Rechtecke).
→ **Jetzt**: 100% werden geschnitten (Sutherland-Hodgman fuer alle Formen).

### Geschoss-Berechnung und Wandschnitt

Die Hoehen werden aus den JSON-Modulen gelesen und gestapelt:

```
                                    Traufe (Z_MIN RoofSurface)
          ╱╲  ╱╲  ╱╲               ↓
         ╱  ╲╱  ╲╱  ╲  ←Dach      ============================
         |   |       |             |                          |
         |   | UF_n  |             | letztes UF bis Traufe    |
         |   |       |             |                          |
         +===+===+===+  <- UF_n   ============================
         |   |       |             |                          |
         |   | UF_1  |             | Ceiling + Floor an jeder |
         |   |       |             | Geschossgrenze           |
         +===+===+===+  <- UF_1   ============================
         |   |       |             |                          |
         |   |  GF   |             | GF-Hoehe = GF.height     |
         |   |       |             |          + GF.CeHe        |
    -----+===+===+===+-----       ============================ <- H_DGM + heightGr
         ||          ||           ||                          ||
         || Keller   ||           || BA.height + BA.CeHe      ||
         || (BA)     ||           || (Schritt 2)              ||
         +============+           ============================  <- Kellerboden
```

**Regeln:**
- Startpunkt fuer alles: `heightGr` (GF.heightAboveGround, Default: 0)
- GF beginnt bei egFloorZ (= H_DGM + heightGr)
- GF-Hoehe = GF.height + GF.CeHe aus JSON
- UF-Hoehe = UF.height + UF.CeHe aus JSON
- **Dynamisch**: Anzahl UFs wird aus der verfuegbaren Hoehe berechnet (NICHT aus storeysAboveGround!)
- Letztes Geschoss endet exakt an der Traufe
- Geschosse unter 1.20m werden uebersprungen (Fitzelchen-Schutz)
- Wandbereiche unterhalb egFloorZ werden verworfen (Keller-Overlap-Vermeidung)
- **Flachdach**: Keine CeilingSurface am obersten Geschoss wenn First-Traufe < 0.30m
- **Mischdach**: CeilingSurface nur unter geneigten Dachflaechen (projizierte Dach-Polygone)

**Wandschnitt:**
- Jede Wand wird an allen Geschossgrenzen geschnitten (Sutherland-Hodgman)
- Iteratives Schneiden von unten nach oben
- Ergebnis: Ein Wandsegment pro Geschoss mit eigenem Geschoss-Tag
- Bei Schnittfehler: Wand bleibt ungeteilt, erhaelt nur Geschoss-Tag

### Beispiel-Output
```
=== Geschoss-Generator ===
Input:  LoD3_building.gml
JSON:   Baukoerpermodule_json/
Output: LoD3_building_storeys.gml
=== Fertig ===
Gebaeude verarbeitet: 3801
Geschosse erstellt: 5719
Wand-Segmente erstellt: 59287
Boeden erstellt: 5768
Decken erstellt: 6642
```

### lod3Solid Shell-Rebuild

#### Problem

Der Promoter (Schritt 1) kopiert den `lod2Solid` 1:1 als `lod3Solid`. Die darin enthaltene
`CompositeSurface`-Shell referenziert ueber `xlink:href` die `gml:id`s aller Polygon-Geometrien
der BoundarySurfaces. In den folgenden Pipeline-Schritten werden jedoch Flaechen entfernt,
ersetzt und neu erzeugt:

- **BasementGenerator (Schritt 2)**: Entfernt die originale GroundSurface (und damit deren `gml:id`), 
  fuegt neue Keller-Waende, Boden und Decke hinzu.
- **StoreyGenerator (Schritt 3)**: Entfernt originale WallSurfaces, erstellt neue 
  Wand-Segmente pro Geschoss mit neuen `gml:id`s, erzeugt Floor-/CeilingSurfaces.

Dadurch entstehen **dangling references** (Verweise auf nicht mehr existierende Polygone)
und neue Polygone fehlen im Solid. Im Testdatensatz (3801 Gebaeude): 24.580 dangling von
67.605 Referenzen gesamt.

#### Loesung: `CityGmlUtils.rebuildSolidShell()`

Die Methode baut die Shell des `lod3Solid` komplett neu auf:

1. **Alle aktuellen Polygon-IDs sammeln**: Iteriert ueber alle `BoundarySurface`s des
   Gebaeudes/Parts und sammelt die `gml:id`s aller inline-Polygone (XLink-Referenzen
   wie z.B. bei Geschossdecken werden uebersprungen, da das referenzierte Polygon bereits
   ueber die CeilingSurface gezaehlt wird).
2. **Auto-ID-Vergabe**: Polygone ohne `gml:id` erhalten automatisch eine ID nach dem Schema
   `Poly_<surfaceId>` (z.B. `Poly_K0pF001gmt_Wall_BA_N` fuer `Face_K0pF001gmt_Wall_BA_N`).
3. **Shell ersetzen**: Die vorhandenen `surfaceMember`-Eintraege werden geloescht und durch
   neue `xlink:href`-Referenzen auf die gesammelten IDs ersetzt.

#### Platzierung im Code

Der Aufruf erfolgt in `StoreyGenerator.processBuilding()` **nach** jedem
`processAbstractBuilding()`-Aufruf — bewusst auf dieser Ebene, nicht innerhalb von
`processAbstractBuilding()` selbst. Grund: Es gibt Randfaelle, bei denen der StoreyGenerator
vorzeitig abbricht (z.B. fehlende Traufe), der BasementGenerator aber bereits Boundaries
geaendert hat. Durch den Aufruf auf `processBuilding()`-Ebene wird die Shell **immer**
korrekt neu aufgebaut.

```java
// StoreyGenerator.processBuilding()
processAbstractBuilding(part, sst, hDgm, params, stats);
CityGmlUtils.rebuildSolidShell(part);   // Shell immer neu aufbauen

processAbstractBuilding(building, sst, hDgm, params, stats);
CityGmlUtils.rebuildSolidShell(building);
```

#### Ergebnis

Nach dem Rebuild: **134.011 gueltige Referenzen, 0 dangling, 0 unreferenzierte Polygone**.

### Bugfix: Wand haengt unter egFloorZ (2026-08-04)

Gefunden ueber CityDoctor2 durch den Nutzer (Screenshot mit markierter Kante), verifiziert
mit val3dity: an Gebaeude `DESNALK0pF001iSj` (Walmdach + Keller) zeigten zwei
`Innenwand="1"`-Waende unter dem Dach (`Face_000434X_0_1_UF_1_1`, `Face_000434X_0_15_UF_1_7`)
`NON_MANIFOLD_CASE`/`SHELL_NOT_CLOSED`. Beide behielten ihre Unterkante beim rohen
Gelaendeniveau (`H_DGM`, hier 167,92) statt bei `egFloorZ` (168,37 = `H_DGM+heightGr`) —
0,45 m Ueberlappung mit der vom `BasementGenerator` unabhaengig erzeugten Kellerwand, die
exakt bei `egFloorZ` endet.

**Ursache:** Schraeg zulaufende Giebel-/Walmdach-Innenwaende, deren Kontur der normale
horizontale Mehrfach-Z-Schnitt (`cutWallAtMultipleZ`) nicht sauber fasst. Drei Codepfade
liessen die Wand dabei unveraendert (samt zu tiefer Unterkante) durch:

1. `applicableCuts` leer (keine Schnitthoehe faellt in den Wand-Z-Bereich) → Wand wird nur
   getaggt, nicht geschnitten.
2. `cutWallAtMultipleZ` liefert `null`/leer ("Schnitt fehlgeschlagen") → dieselbe
   Nur-Tag-Behandlung.
3. **Der subtilste Fall:** `cutWallAtMultipleZ` liefert formal >= 1 Segment (also
   "erfolgreich"), aber innerhalb des konservativen Fallbacks
   `cutWallSinglePieceGuarded` wurde der `egFloorZ`-Schnitt selbst als faltend
   uebersprungen (`if (folds) continue;`) — das betroffene Segment behaelt dadurch
   trotzdem seine urspruengliche, zu tiefe Unterkante. Da das Segment-Mittel-Z ueber
   `egFloorZ` liegt, greift auch die bestehende Rand-Verwerfung
   (`hasEgFloorCut && midZ < egFloorZ`) nicht.

**Fix:** Neue Hilfsmethode `trimWallBelowEgFloor` — schneidet EINMAL bei `egFloorZ` (nahe
der Wandunterkante, fernab der komplexen schraegen Oberkante, daher deutlich seltener
faltend als ein Mehrfach-Schnitt) und behaelt nur das obere Stueck; schlaegt selbst das
fehl, bleibt die Original-Kontur unveraendert (Logging) statt eine zweite, schlimmere
Verletzung zu riskieren. An allen drei oben genannten Stellen eingebaut, jeweils nur wenn
`params.hasBasement()` und die (Segment-)Unterkante unter `egFloorZ` liegt.

**Verifiziert:** `DESNALK0pF001iSj` danach 100% valide (0 Fehler, beide Primitive). An
Gebaeude `DESNALK0pF001gmt` (4 BuildingParts) zusaetzlich per Vorher/Nachher-Vergleich
bestaetigt, dass ein **davon unabhaengiger** `CONSECUTIVE_POINTS_SAME`/`NON_MANIFOLD_CASE`
an einer 1mm-Koordinatendifferenz (`416808.716` vs. `416808.717`) bereits in der
Original-LoD2-Quelldatei vorhanden ist — dieser Fix aendert daran nichts, das ist ein
separates, vorbestehendes Rohdaten-Problem (siehe Projektwissen: mm-Naehte sind bewusst
Aufgabe des nachgelagerten Healers, nicht dieser Pipeline).

### Bugfix: Fliegendes Stockwerk bei Anbauten ohne eigenes BuildingPart (2026-08-12)

> **UEBERHOLT (2026-08-20):** der hier beschriebene Kanten-Matching-Mechanismus
> (`computeEdgeLimits`/`computeActiveSubPolygon`) wurde vollstaendig durch echte 2D-Polygon-
> Differenz (JTS) ersetzt — siehe Abschnitt "Anbau-Zuschnitt: JTS-basierte Polygon-Differenz"
> weiter unten. Als historischer Hintergrund belassen.

Vom Nutzer beim Sichttest gefunden (Gebaeude `DESNALK0pF001fYq`, 2026-08-11): eine Geschossdecke/
-boden wird ueber einem einstoeckigen, flachdach Anbau erzeugt, obwohl an dieser Stelle keine
Wand so hoch reicht — die Decke "schwebt" ueber dem Anbau. Zunaechst nur dokumentiert (siehe
History unten), am 2026-08-12 behoben.

**Ursache:** `computePolygonTopZ(groundPts, walls, defaultZ)` berechnete **einen einzigen**
Hoehenwert pro Grundriss-Polygon: das Maximum von `wallMaxZ` ueber **alle** Waende, deren
Basiskante zu **irgendeiner** Kante des Polygons passt. `computePolygonRoofZ` fragte nur am
2D-**Schwerpunkt** des gesamten Polygons ab, welche Dachflaeche dort liegt. Hat der Anbau kein
eigenes `BuildingPart` und damit kein eigenes `GroundSurface`-Polygon, teilt er sich das
Grundpolygon mit dem hohen Hauptbau — und erbt dessen hohe Traufe als Stopp-Grenze, obwohl seine
eigenen Waende (bestaetigt an `fYq`: Flachdach `Zmin=Zmax=170.46`, Waende
`Face_00041YG_0_3_GF_2`/`..._0_4_GF_3` enden ebenfalls bei 170.46, ohne UF_1-Gegenstueck) dort
laengst enden. **Notwendige Bedingung:** kein eigenes BuildingPart fuer den Anbau (→ geteiltes
Grundpolygon) UND eine echte Hoehendifferenz zwischen den Bereichen, die sich das Polygon teilen
— "Flachdach" ist das bisher einzige real bestaetigte Muster (deutlich haeufigster Fall: niedrige
Anbauten wie Garagen/Eingaenge unter hoeheren Hauptbauten), aber keine zwingende Voraussetzung
des Fehlers selbst, der Code unterscheidet nicht nach Dachform.

**Warum kein einfacher MAX→MIN-Fix:** haette bei echten Split-Level-Gebaeuden (ein Baukoerper,
ein Grundpolygon, aber zwei legitime unterschiedliche Geschosshoehen) das hoehere Gebaeudeteil
faelschlich zu frueh abgeschnitten — das Problem liegt am **einen Skalarwert pro Polygon**, nicht
an MAX vs. MIN.

**Fix — Kanten-basierte "Kerben-Entfernung" statt allgemeinem Polygon-Clipping:** Prüfung von
`CityGmlUtils.java` ergab, dass im Code kein Polygon-gegen-Polygon-Zuschneiden existiert (nur ein
Sonderfall fuer eine horizontale Z-Ebene beim Wandschnitt) — allgemeines Clipping (Weiler-Atherton
o.ae.) waere viel neuer, fehleranfaelliger Code fuer konkave Formen, ohne vorhandene Bibliothek
(kein JTS/Clipper). Stattdessen (mit dem Nutzer abgestimmt): die in `computePolygonTopZ` bereits
vorhandene Kanten-Zuordnung wird **pro Kante** statt aggregiert ausgewertet:

- `computeEdgeLimits(groundPts, walls, roofPolygons, defaultZ)` (ersetzt `computePolygonTopZ`/
  `computePolygonRoofZ`) liefert pro Grundpolygon-Kante die lokale Hoehengrenze — Wand-Basiskante
  wie bisher, Dachflaeche jetzt an der **Kantenmitte** statt am Gesamt-Schwerpunkt abgefragt.
- `computeActiveSubPolygon(groundPts, edgeLimits, floorZ, tolerance)` klassifiziert pro Storey
  jede Kante als aktiv/abgelaufen. Ein Vertex faellt weg, wenn BEIDE anliegenden Kanten
  abgelaufen sind — die beiden Lauf-Grenzen (je eine anliegende Kante noch aktiv) bleiben
  erhalten und bilden dadurch automatisch eine neue, gerade Schliesskante ("Kerbe" entfernt).
  Sind alle Kanten aktiv: Polygon unveraendert (haeufigster Fall, 0 Overhead). Sind alle
  abgelaufen: `null` (komplett gestoppt, wie zuvor).
- **Faltungs-Schutz** (gleiches Prinzip wie beim Wandschnitt, siehe "Schritt 7a" unten): wuerde
  die neue Schliesskante bei einem verwinkelten Anbau den Ring self-touching machen
  (`CityGmlUtils.ringSelfIntersects`), wird NICHT geschnitten und der volle Ring unveraendert
  zurueckgegeben — die alte Einschraenkung bleibt fuer diesen Sonderfall bestehen, statt einen
  neuen Geometriefehler einzutauschen. Notwendig geworden, weil der erste Versuch ohne diesen
  Schutz val3dity `RING_SELF_INTERSECTION` von 5 auf 44 Primitive hochtrieb (39 neue Faelle) —
  mit Schutz exakt wieder auf dem alten Stand (siehe Verifikation unten).
- **Boden/Decke getrennt ausgewertet:** ein zweiter, beim Implementieren gefundener Bug — Boden
  und Decke DESSELBEN Geschosses liegen auf unterschiedlicher Z-Hoehe (`storey.floorZ` vs.
  `storey.ceilingZ`); die erste Fassung nutzte faelschlich dieselbe (am Boden berechnete) Kontur
  auch fuer die Decke. Jetzt zwei unabhaengige `computeActiveSubPolygon`-Aufrufe pro Storey.

**Verifikation:**
- `DESNALK0pF001fYq`: `GF_Ceiling`/`UF_1_Floor` (per XLink identisch) schrumpfen von 128,74 m²
  (volle, den Anbau einschliessende Flaeche) auf 53,34 m² — 5 innere Vertices des Anbau-Vorsprungs
  entfernt, direkte Schliesskante zwischen den beiden Lauf-Grenzen; die GF-Decke des Hauptbaus
  bleibt korrekt bestehen, der Anbau behaelt seinen (unveraenderten, immer schon vorhandenen)
  eigenen Boden + Waende + RoofSurface — nur die faelschliche zusaetzliche Etage verschwindet.
- Volle 3.801-Gebaeude-Kachel: Boeden 6128→6154 (+26), Decken 4619→4613 (-6) — Geschosse/
  Wandsegmente unveraendert (6524/66875), da nur Flaechen-Formen betroffen sind, nicht die
  Geschoss-Einteilung selbst. (Zahlen nach dem Laengen-Schutz unten; siehe dort fuer die
  Zwischenstaende.)
- `citygml-tools validate`: weiterhin schema-valide (volle Kachel + 14 Testgebaeude).
- **val3dity vorher/nachher** (via `citygml-tools to-cityjson` + `val3dity`): mit beiden Schutz-
  mechanismen (Faltungs-Schutz + Laengen-Schutz, siehe unten) **exakt identisch** zum Stand vor
  diesem Fix (5.566/5.951 valide Features, 93,5 %; Fehlerbild
  102=6/104=5/201=2/204=31/302=38/303=13/306=2/307=8/601=299 — Zahl fuer Zahl gleich). Der Fix
  behebt also den vom Nutzer per Sichtpruefung gefundenen (semantischen, nicht val3dity-
  pflichtigen) Fehler ohne messbare val3dity-Verschlechterung, aber auch ohne val3dity-
  Verbesserung, da die "schwebende Decke" selbst kein val3dity-Fehlerkriterium verletzt hatte
  (topologisch valide, nur architektonisch falsch).

### Nachtrag: Laengen-Schutz nach Regression an mehrfluegeligem Gebaeude (2026-08-12)

Vom Nutzer beim Sichttest an den 14 Testgebaeuden gefunden: Gebaeude `DESNALK0pF001iMM`
(komplexer, mehrfluegeliger Baukoerper mit Innenhof, 44 Dachflaechen, Grundpolygon mit 50 Kanten)
bekam durch den obigen Fix eine **neue**, falsch aussehende, weit auskragende Zusatzflaeche.

**Ursache:** Die Kerben-Entfernung ging von GENAU EINEM zusammenhaengenden abgelaufenen Lauf aus
(wie bei `fYq`). Bei `iMM` gab es aber **drei getrennte** Laeufe im selben Grundpolygon (Laengen
11, 1, 27 von 50 Kanten) — ein mehrfluegeliges Gebaeude mit mehreren unterschiedlich hohen
Bereichen im selben (geteilten) Polygon, nicht nur ein einzelner kleiner Anbau. Der laengste Lauf
(27 von 50 Kanten, mehr als die Haelfte!) erzeugte eine Schliesskante von ueber 21 m Laenge quer
durch das Gebaeude — geometrisch nicht self-touching (der Faltungs-Schutz griff nicht), aber
architektonisch komplett falsch, da die Kante quer durch den Innenhof lief statt eine kleine Kerbe
abzuschneiden.

**Erster Versuch (zu streng):** "nur bei genau 1 Lauf schneiden" — behob `iMM`, brach aber
`fYq` gleich mit (dessen Grundpolygon hat tatsaechlich **zwei** Laeufe: ein 1-Kanten-Lauf, dessen
einziger Vertex fast exakt auf der Verbindungslinie seiner Nachbarn liegt — geometrisch praktisch
ein No-Op — und der eigentliche Anbau-Lauf mit 4 Kanten). Reines Zaehlen der Laeufe unterscheidet
also nicht zwischen "harmloser Nebenkante" und "haelftiger Baukoerper".

**Finaler Schutz — Laengen-basiert statt Anzahl-basiert:** geschnitten wird nur, wenn der
**laengste** zusammenhaengende abgelaufene Lauf **hoechstens die Haelfte** der Kanten des
Grundpolygons ausmacht (`longestRun > n/2` → ungeschnitten durchreichen). Deckt `fYq` (laengster
Lauf 4 von 12 = 33 %) weiterhin ab, blockiert `iMM` (laengster Lauf 27 von 50 = 54 %) korrekt.
Beliebig viele Laeufe sind erlaubt, solange keiner die Mehrheit des Rings einnimmt — ein "kleiner
Anbau" ist per Definition eine Minderheit des Umfangs, ein mehrfluegeliges Gebaeude mit einem
dominanten Nebenteil ist es nicht.

**Verifikation:** `iMM` Polygon 2 (Grundflaeche 452,04 m²) bleibt jetzt ungeschnitten
(`GF_Ceiling_2` = 452,04 m² statt der fehlerhaften 121,64 m² aus dem ersten Versuch) — die alte
(bereits dokumentierte) Einschraenkung bleibt fuer dieses komplexe Gebaeude bestehen, statt eine
neue, sichtbar falsche Flaeche zu erzeugen. `fYq` bleibt weiterhin korrekt geschnitten (53,34 m²).
Volle Kachel: Boeden 6128→6154 (+26), Decken 4619→4613 (-6) — mehr als beim uebermaessig strengen
Zwischenstand (der `runCount>1`-Schutz allein haette auch `fYq`-artige Faelle blockiert), aber
strikt weniger als der ungeschuetzte erste Versuch (der auch `iMM`-artige Faelle faelschlich
zuliess). val3dity weiterhin exakt auf Baseline-Niveau (siehe oben).

## Schritt 4: Tuer-Generator (DoorGenerator)

Erzeugt Tueren (DoorSurface) an den Erdgeschoss-Wandsegmenten (GF-WallSurfaces).
Die Tueren werden als `DoorSurface`-Elemente (`FillingSurface`) an den WallSurfaces verankert.
Die Tueroeffnung wird — analog zum WindowGenerator (Schritt 5) — als **innerer Polygon-Ring**
(Loch) ins Wandpolygon eingefuegt; der aeussere Ring bleibt unveraendert. Beide Generatoren
nutzen dafuer dieselbe Hilfsmethode `CityGmlUtils.addOpeningToWall(...)`.

> **Hinweis:** Fruehere Versionen des DoorGenerators modifizierten den aeusseren Ring direkt
> (Ankerpunkte auf der Unterkante, siehe alte Fassung dieses Abschnitts). Seit dem
> Refactoring auf die gemeinsame Oeffnungs-Logik mit dem WindowGenerator gilt das nicht
> mehr — siehe Schritt 4.4.

### Funktionen

- Liest `DoorCount`-Attribut von GF-WallSurfaces (gesetzt aus Building-Preferences)
- Liest Tuerparameter aus JSON-Baukoerpermodulen (`GF.door`)
- Erzeugt `DoorSurface`-Elemente als FillingSurface der WallSurface
- Fuegt die Tueroeffnung als inneren Polygon-Ring ins Wandpolygon ein (Loch, aeusserer Ring
  unveraendert — wie beim WindowGenerator)
- Aktualisiert FACEAREA der Wand und baut Solid-Shell neu auf
- **DoorCount nur an GF-Segmenten**: Der StoreyGenerator propagiert das `DoorCount`-Attribut
  nur an Wandsegmente mit Geschoss-Tag `GF`, nicht an OG- oder Keller-Waende
- **Geteilte Wand zwischen BuildingParts**: Wie beim WindowGenerator wird pro Wand ein
  Mittelpunkt-Key aus der Unterkante berechnet (`CityGmlUtils.wallBottomMidKey`, gemeinsam
  fuer Tuer- und Fenster-Generator). Teilen sich zwei BuildingParts eine geometrisch
  identische Wand, bekommt nur der erste Part die Tuer(en) — der zweite ueberspringt sie
  (`wallsSkippedCoveredByPart`). `DoorCount` stammt aus demselben Upstream-Prozess wie
  `WindowPreference` und ist daher ebenso oft auf beiden Kopien einer geteilten Wand gesetzt
  (im 14-Gebaeude-Testset: 10 von 355 Waenden betroffen, meist mit `DoorCount=0` auf beiden
  Seiten — ohne Dedup waeren aber auch beidseitig gesetzte `DoorCount>0`-Faelle doppelt
  ausgefuehrt worden).
- **Anbau-Verdeckung**: Liegt die Mitte einer Tuer auf der gemeinsamen Kante von zwei
  Footprint-Polygonen (eigenes Gebaeude + Anbau als separater BuildingPart), wuerde die Tuer
  hinter dem Anbau liegen — sie wird verworfen (`doorsSkippedCovered`). Die Footprints werden
  dafuer ueber **alle** Targets des Gebaeudes (Building + alle BuildingParts) gesammelt, nicht
  nur ueber das jeweils verarbeitete Target — sonst kann der Anbau (typischerweise ein
  eigener BuildingPart) gar nicht erkannt werden.
- **Kontur-Check**: Alle 4 Tuer-Ecken muessen im Wandpolygon liegen (`openingInsideWall2D`),
  sonst wird die Tuer verworfen (`doorsSkippedOutside`) — verhindert Tueren in Aussparungen
  gestufter Wandsegmente (analog zum Kontur-Check beim WindowGenerator).

### Tuer-Parameter (aus JSON)

| JSON-Feld | ModuleParameters | Beschreibung |
|-----------|-----------------|---------------|
| `GF.door.DoHe` | `doorHeight` | Tuerhoehe in m |
| `GF.door.DoLen` | `doorWidth` | Tuerbreite in m |
| `GF.door.HDistDoWa` | `hDistDoorWall` | Abstand erste Tuer vom linken Wandrand in m (Default: 0.5) |

### DoorCount-Semantik

| Wert | Bedeutung |
|------|-----------|
| `0` | Keine Tuer an dieser Wand |
| `1`, `2`, ... | Anzahl Tueren |
| `-1` | Hintertuer (1 Tuer + Attribut `Hintertuer=true`) |

### Algorithmus im Detail

Der DoorGenerator arbeitet pro GF-WallSurface in folgenden Schritten:

#### 1. Unterkante der Wand ermitteln

Die Unterkante wird ueber die gemeinsame Hilfsmethode `CityGmlUtils.findBottomEdge(...)`
ermittelt: aus allen Ring-Punkten auf `zMin` (innerhalb 1 cm) wird das **Punktepaar mit
dem groessten 2D-Abstand** gewaehlt — also die volle Wandbreite am Fuss. Das ist robuster
als „die erste Kante bei zMin", wenn mehrere Punkte auf `zMin` liegen (z.B. bei L-/Stufen-
Grundrissen oder nach Geschoss-Schnitten mit Zwischenpunkten auf der Sohle). DoorGenerator
und WindowGenerator nutzen dieselbe Logik.

```
Beispiel: Giebel-Fuenfeck (CCW von aussen)

       C (zMax=First)
      / \
     /   \
    D     B (zMax=Traufe)
    |     |
    E --- A (zMin=GF-Unterkante)

Ring (open): [A, B, C, D, E]
Unterkante: E→A = Index 4 → Index 0 (Wrap-Around!)
edgeStartIdx=4, edgeEndIdx=0
```

#### 2. Tuer-Positionen berechnen

```
Normalfall (HDistDoWa passt):
|--HDistDoWa--|--Tuer1--|--spacing--|--Tuer2--|--spacing--|
```

- **Normalfall** (`HDistDoWa + doorCount*doorWidth <= wallLength`): Erste Tuer bei
  `HDistDoWa` vom Start der Unterkante, weitere Tueren gleichmaessig im verbleibenden
  Wandbereich verteilt.
- **Fallback: Zentrierung** (`HDistDoWa` passt nicht in die Wand): Statt die Wand zu
  uebergehen, werden die Tuer(en) als Block zentriert (Randabstand dann `MIN_SPACING`
  beidseitig statt `HDistDoWa`). Wird geloggt ("... Tuer(en) werden zentriert") und
  betrifft in der Praxis viele Waende (z.B. 7 von 14 Testgebaeuden), da `HDistDoWa`
  oft grosszuegiger bemessen ist als kurze Fassadenabschnitte.
- **Abbruchkriterien** (Wand wird uebersprungen):
  - Auch der zentrierte Block passt nicht (`totalDoorWidth + (n-1)*MIN_SPACING + 2*MIN_SPACING > wallLength`)
  - Spacing zwischen Tueren im Normalfall < 10 cm
  - Tuerhoehe + 5 cm Sockel > Wandhoehe

#### 3. Tuer-Geometrie erzeugen

Jede Tuer wird als Rechteck (BL, BR, TR, TL) auf der Wandebene berechnet:
- **Sockelloehe**: 5 cm ueber Wandunterkante (vermeidet kollineare Punkte auf der Basiskante)
- **doorBottomZ**: zMin + 0.05 m
- **doorTopZ**: zMin + 0.05 m + doorHeight
- **Horizontale Position**: entlang der Unterkanten-Richtung, Offset per `HDistDoWa`

#### 4. Wandpolygon modifizieren (innerer Ring)

Der aeussere Ring des Wandpolygons bleibt **unveraendert**. Stattdessen wird ueber
`CityGmlUtils.addOpeningToWall(wallPoly, bl, br, tr, tl, extCCW)` ein **innerer Ring**
(Loch) fuer das Tuer-Rechteck eingefuegt — dieselbe Hilfsmethode, die auch der
WindowGenerator fuer Fensteroeffnungen verwendet:

```
Aussenring (unveraendert):        Innenring (Loch) fuer die Tuer:

D ──────── C                      D ──────── C
│          │                      │  TL──TR  │
│          │           →          │  │Tuer│  │   ← innerer Ring, 5cm ueber
│          │                      │  BL──BR  │     Wandunterkante (Sockel)
A ──────── B                      A ──────── B
```

Der Tuer-Sockel (`DOOR_SILL_HEIGHT` = 5 cm) sorgt dafuer, dass der Innenring **nicht**
die Wandunterkante beruehrt — ein Innenring, der den Aussenring beruehrt, waere
topologisch degeneriert (Punkte auf der gemeinsamen Kante). Der 5cm-Streifen bleibt
also als reales Wandmaterial unter der Tuer erhalten; er reduziert die Wandflaeche
NICHT zusaetzlich (siehe Validierung unten).

Die Orientierung des Innenrings ist immer **entgegengesetzt** zum Aussenring (GML-Pflicht
fuer Loecher): Bei CW-Aussenring (Sachsen-LoD2-Waende) ist der Innenring CCW, bei
CCW-Aussenring (z.B. generierte Kellerwaende) ist er CW. `isExteriorRingCCW(...)`
bestimmt die Orientierung einmalig pro Wand; `addOpeningToWall` waehlt danach die
passende Punktreihenfolge fuer Innenring und FillingSurface-Polygon (das FillingSurface-
Polygon bekommt dieselbe Orientierung wie der Aussenring — nur so wird jede Innenring-
Kante im Solid genau einmal in entgegengesetzter Richtung abgedeckt).

#### 5. DoorSurface erzeugen

Fuer jede Tuer wird eine `DoorSurface` erzeugt (CityGML 3.0 API: `AbstractFillingSurface`).
In der CityGML 1.0-Ausgabe wird dies automatisch als `bldg:opening > bldg:Door` serialisiert.

### Attribute auf DoorSurface

| Attribut | Wert | Beschreibung |
|----------|------|--------------|
| `gml:name` | `LOD3_Door` | Tuer-Element |
| `gml:id` | `Face_{WandFaceID}_Door_{N}` | Eindeutige ID |
| `BldgFaceID` | `{WandFaceID}_Door_{N}` | Face-Identifikator |
| `FACEAREA` | Tuerflaeche in m² | doorWidth × doorHeight |
| `Geschoss` | `GF` | Immer Erdgeschoss |
| `Hintertuer` | `true` | Nur bei DoorCount=-1 |

### CityGML 1.0 Ausgabe

```xml
<bldg:WallSurface gml:id="Face_...">
  <bldg:lod3MultiSurface>...</bldg:lod3MultiSurface>
  <bldg:opening>
    <bldg:Door gml:id="Face_..._Door_1">
      <gml:name>LOD3_Door</gml:name>
      <gen:stringAttribute name="BldgFaceID">
        <gen:value>..._Door_1</gen:value>
      </gen:stringAttribute>
      <gen:stringAttribute name="FACEAREA">
        <gen:value>2.1</gen:value>
      </gen:stringAttribute>
      <gen:stringAttribute name="Geschoss">
        <gen:value>GF</gen:value>
      </gen:stringAttribute>
      <bldg:lod3MultiSurface>
        <gml:MultiSurface srsName="urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH" srsDimension="3">
          <gml:surfaceMember>
            <gml:Polygon>
              <gml:exterior>
                <gml:LinearRing>
                  <gml:posList srsDimension="3">
                    416290.0 5657417.0 159.62
                    416291.0 5657417.0 159.62
                    416291.0 5657417.0 161.72
                    416290.0 5657417.0 161.72
                    416290.0 5657417.0 159.62
                  </gml:posList>
                </gml:LinearRing>
              </gml:exterior>
            </gml:Polygon>
          </gml:surfaceMember>
        </gml:MultiSurface>
      </bldg:lod3MultiSurface>
    </bldg:Door>
  </bldg:opening>
</bldg:WallSurface>
```

### Validierung

- **Flaechenerhaltung**: `wallAreaBefore ≈ wallAreaAfter + doorsPlaced × doorWidth × doorHeight`
  Da die Tueroeffnung als reiner Innenring (Loch) eingefuegt wird, entspricht die entfernte
  Flaeche exakt der Tuerflaeche (`doorWidth × doorHeight`) — der 5cm-Sockelstreifen bleibt
  als Wandmaterial erhalten und wird NICHT mitgerechnet (anders als bei der fruehen
  Aussenring-Implementierung, siehe Hinweis oben in Schritt 4.4). `doorsPlaced` kann kleiner
  als `doorCount` sein, wenn einzelne Tueren durch Anbau-Verdeckung oder Kontur-Check
  verworfen wurden.
- **Hoehenpruefung**: doorHeight + 5 cm Sockel ≤ Wandhoehe
- **Breitenpruefung**: HDistDoWa + doorCount × doorWidth ≤ Wandlaenge (sonst Zentrierungs-Fallback)
- **Abstandspruefung**: Spacing zwischen Tueren ≥ 10 cm (im Normalfall)
- **Kontur-Check**: Alle 4 Tuer-Ecken muessen im Wandpolygon liegen (`openingInsideWall2D`)
- **Anbau-Verdeckung**: Tuermitte darf nicht auf der gemeinsamen Kante zweier Footprints liegen
- **Toleranz**: Abweichungen < 1 cm² werden akzeptiert (Floating-Point-Artefakte)

### Schwellwerte (DoorGenerator)

| Konstante | Wert | Beschreibung |
|-----------|------|--------------|
| `DOOR_SILL_HEIGHT` | 0.05 m | Sockelhoehe ueber Wandunterkante (haelt den Innenring von der Unterkante fern) |
| `MIN_SPACING` | 0.10 m | Minimaler Abstand zwischen Tueren und zum Wandrand |
| `FOOTPRINT_SHARE_TOL` | 0.10 m | Toleranz fuer „Tuermitte liegt auf gemeinsamer Footprint-Kante" (Anbau-Verdeckung) |
| zTol | 0.01 m | Toleranz fuer Unterkanten-Erkennung (Z bei zMin) |
| Flaechentoleranz | 0.01 m² | Schwelle fuer Flaechenabweichungs-Warnung |

## Schritt 5: Fenster-Generator (WindowGenerator)

Erzeugt Fenster (WindowSurface) an Aussenwand-Segmenten aller Geschosse (GF, UF, BA).
Im Gegensatz zum DoorGenerator, der den **aeusseren Polygon-Ring** der Wand modifiziert
(Tuer-Ausschnitte), werden Fenster als **innere Polygon-Ringe** (Loecher) in das
Wand-Polygon eingefuegt. Der aeussere Ring bleibt unveraendert.

> „Waende mit Fenstern setzen sich dabei aus einem aeusseren Polygon-Ring und einem
> oder mehreren inneren Polygon-Ringen zusammen. Im Unterschied zu Tueren beruehren
> Fenster den aeusseren Ring nicht."

### Fenster-Parameter (aus JSON)

| JSON-Feld | ModuleParameters | Beschreibung |
|-----------|-----------------|--------------|
| `*.window.HDistWaWi` | `hDistWallWindow` | Abstand Wandecke → erstes Fenster (m) |
| `*.window.VDistFlWi` | `vDistFloorWindow` | Bruestungshoehe: Abstand Fussboden → Fensterunterkante (m) |
| `*.window.HDistWiWi` | `hDistWindowWindow` | Lichter Abstand (edge-to-edge) zwischen Fenstern (m) |
| `*.window.HDistDoWi` | `hDistDoorWindow` | Abstand Tuer → naechstes Fenster (m, nur GF) |
| `*.window.WiLen` | `windowWidth` | Fensterbreite (m) |
| `*.window.WiHe` | `windowHeight` | Fensterhoehe (m) |
| `*.window.HDistMinWaWi` | `hDistMinWallWindow` | Mindestabstand Wandecke → Fensterkante (m) |

Parameter-Quelle je Geschoss:
- **GF**: `params.getGroundFloor().window` (inkl. HDistDoWi fuer Tuer-Fenster-Abstand)
- **UF**: `params.getUpperFloor().window` (kein HDistDoWi, da keine Tueren)
- **BA**: `params.getBasement().window` (kleinere Fenster, z.B. WiLen=0.8, WiHe=0.4)
- **RO**: Keine Fenster — Dachfenster (RO.window.XXX, z.B. `RO.shape.RiHe` = Firsthoehe)
  sind noch nicht implementiert (📋 TODO, siehe Geplante Erweiterungen)

Beispielwerte (ME4_4.json):
```
GF: WiLen=1.1  WiHe=1.4  VDistFlWi=0.8  HDistWaWi=2.25  HDistWiWi=2.2  HDistMinWaWi=0.6  HDistDoWi=1.5
UF: WiLen=1.1  WiHe=1.4  VDistFlWi=0.8  HDistWaWi=2.25  HDistWiWi=2.2  HDistMinWaWi=0.6
BA: WiLen=0.8  WiHe=0.4  VDistFlWi=0.3  HDistWaWi=2.4   HDistWiWi=2.2  HDistMinWaWi=0.7
```

### Datenlage (Analyse der LoD3-GML mit 3.801 Gebaeuden)

**WindowPreference-Attribut:**

`WindowPreference` ist ein Attribut **pro `WallSurface`**, das aus den
Upstream-BuildingPreferences-Daten stammt (nicht aus den JSON-Modulen). Der
StoreyGenerator propagiert es beim Wandschnitt auf die Geschoss-Segmente, der
BasementGenerator uebertraegt es auf die zugehoerigen Kellerwaende.

| Wert | Anzahl | Bedeutung |
|------|--------|-----------|
| `0` / fehlt | 10.995 | Keine Fenster (Wand wird uebersprungen) |
| `1`  | 74.826 | Fenster gewuenscht (normale Platzierung) |
| `2`  | —      | Wand **ueberragt eine Nachbarwand**: Fenster nur **oberhalb** der Nachbarhoehe |

**Sonderfall `WindowPreference = "2"`:** Die Wand grenzt unten an ein niedrigeres
Nachbarbauteil und ist nur im oberen Bereich frei. Voraussetzung ist `Z_Differenz > 0`
(um wie viel die Wand die Nachbarwand ueberragt). Der StoreyGenerator berechnet daraus
`Z_Fenster_ASL` (absolute Hoehe, ab der Fenster erlaubt sind); der WindowGenerator
platziert Fenster nur oberhalb dieser Hoehe und ueberspringt die Wand, wenn sie
komplett verdeckt ist (`Z_Fenster_ASL >= Wandoberkante`).

**Geschoss-Verteilung der Waende:**

| Geschoss   | Anzahl | Fenster-Quelle |
|------------|--------|----------------|
| BA         | 31.162 | `basement.window` |
| GF         | 28.150 | `groundFloor.window` |
| UF_1       | 23.405 | `upperFloor.window` |
| UF_2       | 13.829 | `upperFloor.window` |
| UF_3       |  4.881 | `upperFloor.window` |
| UF_4–UF_8  |  2.022 | `upperFloor.window` |
| 1000 (Dach)|  2.906 | — |
| 2000 (Boden)| 2.195 | — |

**BA-Waende (Keller):**
- 26.820 BA-Waende gesamt, 16.198 davon mit WindowPreference (Stand VOR dem Bugfix unten —
  d.h. **10.622 BA-Waende (~40%) hatten gar kein `WindowPreference`-Attribut** und wurden
  von `WindowGenerator` deshalb wie eine Party-Wall behandelt, unabhaengig von ihrer
  tatsaechlichen Groesse oder Hoehe ueber Gelaende)
- 26.394 haben Z_Max > 0 (oberirdischer Anteil vorhanden)
- 426 haben Z_Max = 0 (exakt auf Gelaendeniveau)
- Nutzbare Hoehe fuer Fenster = Z_Max (= oberirdischer Anteil ueber Gelaende)

**Bugfix `findWindowPreferenceForEdge` (BasementGenerator):** Die obigen ~40% fehlenden
Werte waren kein Datenluecken-Artefakt, sondern ein Matching-Bug. `BasementGenerator`
uebertraegt `WindowPreference` von der "zugehoerigen" Original-Wand auf jede neu erzeugte
Kellerwand, indem der XY-Mittelpunkt der neuen Kante mit dem XY-Mittelpunkt (Durchschnitt
aller Polygonpunkte) jeder Original-Wand verglichen wird (Toleranz 50 cm). Das versagt
systematisch, sobald eine einzelne lange Original-Wand — z.B. durch den Gelaendeschnitt
(Sutherland-Hodgman) oder eine komplexere Grundriss-Zerlegung — in MEHRERE kuerzere
Kellerwand-Segmente aufgeteilt wird: nur das eine Segment, dessen eigener Mittelpunkt
zufaellig nah am Mittelpunkt der GANZEN Original-Wand liegt, traf noch; alle
Geschwister-Segmente blieben ohne `WindowPreference`.

Verifiziert an einem echten Gebaeude mit kurviger Fassade (`K0pF001hmm`, 3 BuildingParts,
45 Kellerwaende): 9 Waende — darunter die groesste (28,9 m²) — verloren so ihr
`WindowPreference`, obwohl sie exakt denselben oberirdischen Anteil (`Z_Max=1,26`) hatten
wie die einzige Wand, die zuvor ein Fenster bekam.

Fix: statt Mittelpunkt-Distanz wird geprueft, ob der Mittelpunkt der Kellerwand-Kante auf
der (unendlich verlaengerten) Grundkanten-Linie der Original-Wand liegt (senkrechter
Abstand < Toleranz) UND innerhalb von deren Laengenbereich — erkennt so auch Teilsegmente
einer laengeren, zerschnittenen Original-Wand, nicht nur volle 1:1-Entsprechungen.

Wirkung am 14-Gebaeude-Testfall: bei `K0pF001hmm` bekamen danach 6 statt 1 Kellerwand ein
Fenster (u.a. beide groessten Waende, 26,8 m² und 23,3 m²). Pipeline-weit:
**Fenster gesamt 457 → 515 (+58)** — vollstaendig auf Keller-Fenster zurueckzufuehren
(BA-Fenster 7 → 65; GF/UF/Dach nutzen `WindowPreference` direkt aus den
Original-LoD2-Attributen, nicht ueber dieses Edge-Matching, und sind vom Fix nicht
beruehrt). Die 26.820/16.198-Zahlen oben sind der VOR-Fix-Stand aus der 3.801-Gebaeude-
Analyse und noch nicht auf den vollen Datensatz neu verifiziert — auf dem 14-Gebaeude-
Testfall ist der Fix aber eindeutig bestaetigt.

### Vorbedingungen (Gate-Checks)

Eine Wand wird **uebersprungen** wenn:
- `WindowPreference` fehlt oder = `"0"`
- `WindowPreference = "2"`, aber `Z_Differenz` fehlt oder ≤ 0 (Wand ueberragt die Nachbarwand nicht)
- `Geschoss` ist `1000` (Dach), `2000` (Boden), oder fehlt
- `Geschoss = "BA"` und `Z_Max <= 0` (komplett unterirdisch)
- Window-Params ungueltig (`windowWidth <= 0` oder `windowHeight <= 0`)
- Wand-Polygon hat < 4 Punkte
- Geteilte Wand zwischen zwei BuildingParts (Duplikat erkannt per 3D-Mittelpunkt-Key)
- **Fassadenanteil-Pruefung** ueberschritten (siehe Realismus-Pruefung)

> **Hinweis zu `Innenwand="1"`:** Dieses Attribut ist ein LoD4-Indikator und zeigt an,
> dass an dieser Stelle zukuenftig eine Innenwand abgehen koennte. Es bedeutet NICHT,
> dass die Wand eine Innenwand ist. Da die Pipeline kein LoD4 erzeugt, werden Waende
> mit `Innenwand="1"` normal behandelt und bekommen Fenster wie alle anderen Aussenwaende.

### Algorithmus: Fensteranzahl berechnen

**Grundformel (UF/BA — ohne Tueren):**

HDistWiWi ist der **lichte Abstand** (edge-to-edge) zwischen Fenstern.
HDistMinWaWi ist der Mindestabstand Wandecke → Fensterkante (beidseitig).

```
benoetigte_breite(n) = HDistMinWaWi + n × WiLen + (n-1) × HDistWiWi + HDistMinWaWi
n_max = floor((wallLength - 2 × HDistMinWaWi + HDistWiWi) / (WiLen + HDistWiWi))
```

Falls `n_max < 1`: keine Fenster auf dieser Wand.

**GF-Waende mit Tueren — Links/Rechts-Aufteilung:**

1. Tuerpositionen aus vorhandenen DoorSurface-Objekten lesen
   (`wall.getFillingSurfaces()` → DoorSurface-Instanzen)
2. Horizontale Position jeder Tuer relativ zur Wandunterkante bestimmen
3. Wand in Abschnitte aufteilen:
   - **Links** der Tuer(en): Wandanfang → `doorLeftEdge - HDistDoWi`
   - **Rechts** der Tuer(en): `doorRightEdge + HDistDoWi` → Wandende
   - Zwischenabschnitte bei mehreren Tueren analog
4. Fuer jeden Abschnitt separat Fensteranzahl berechnen

### Fensterpositionen

**Horizontale Positionierung (2026-08-03 auf zentriert umgestellt, Nutzer-Vorgabe):**
Der Fensterblock wird **immer** auf den Wandabschnitt zentriert, `HDistWaWi` wird dafuer
nicht mehr als Anker verwendet (vorher: `HDistWaWi`-Start, Zentrierung nur als Fallback
wenn das nicht passte — wirkte auf echten Gebaeuden bei linksbuendiger Platzierung schief,
siehe Screenshot-Feedback). `HDistWaWi` wird an keiner anderen Stelle im Code gelesen.
```
totalWidth = n × WiLen + (n-1) × HDistWiWi
startOffset = (Abschnittlaenge - totalWidth) / 2
offset_k = startOffset + k × (WiLen + HDistWiWi)    fuer k = 0..n-1
```
`HDistMinWaWi` bleibt als Mindestabstand zum Rand massgeblich — durch die Zentrierung
symmetrisch statt nur einseitig garantiert. `n` (Fensteranzahl) bleibt unveraendert aus der
Grundformel oben ("so viele wie passen"); da diese Formel bereits `HDistMinWaWi` auf BEIDEN
Seiten einrechnet, passt der zentrierte Block praktisch immer beim ersten Versuch.
Verifiziert an `Face_000C6Y7_0_3_GF_3` (5 Fenster, Wandlaenge 15,46 m): Randabstand links
2,808 m, rechts 2,807 m. Gilt auch pro Abschnitt bei tuer-geteilten GF-Waenden
(Links/Rechts-Aufteilung unten) — jeder Abschnitt wird unabhaengig zentriert.

**Vertikale Positionierung:**
```
windowBottomZ = floorZ + VDistFlWi
windowTopZ    = windowBottomZ + WiHe
```
- GF/UF: `floorZ` = Z_MIN_ASL (Unterkante des Geschoss-Segments)
- BA: `floorZ` = Kellerboden = `zMin` aus dem Wandpolygon (absolute Koordinate)

### Mehrere Fensterreihen

Nach der ersten Reihe pruefen, ob eine weitere Reihe darueberpasst:
```
naechsteReiheBottomZ = windowTopZ + VDistFlWi
naechsteReiheTopZ    = naechsteReiheBottomZ + WiHe
```
Falls `naechsteReiheTopZ <= wandOberkante`: weitere Reihe platzieren
(gleiche horizontale Verteilung). Wiederhole bis kein Platz mehr.

**Realismus-Pruefung (Fassadenanteil):** Um unrealistische Fassaden zu vermeiden,
wird der **Window-to-Wall Ratio (WWR)** pro Wand berechnet und begrenzt:

```
WWR = (Anzahl_Fenster × WiLen × WiHe) / FACEAREA_original
```

| WWR-Bereich | Bewertung | Aktion |
|-------------|-----------|--------|
| 0–60 % | OK | Alle Fenster platzieren |
| > 60 % | Zu hoch | Ganze Fensterreihen von oben entfernen (bis ≤ 60 % oder 1 Reihe) |

Der WWR-Check erfolgt **nach** der Berechnung aller Fensterreihen:
1. Gesamt-Fensterflaeche berechnen
2. Falls WWR > MAX_WWR (0.60): oberste Reihe(n) entfernen, bis WWR ≤ 60 % oder nur
   noch eine Reihe uebrig ist
3. Falls selbst eine Einzelreihe noch > 60 % ergibt: die Reihe **bleibt erhalten**,
   es wird nur eine Warnung geloggt (tatsaechlicher WWR, Wandflaeche, Fensterflaeche)

> **Bewusste Designentscheidung:** Einzelne Fenster, die laut JSON-Maszen passen,
> werden vom WWR-Cap **nicht** wieder entfernt — der Cap kappt nur ganze Reihen.
> So bleibt das Prinzip „so viele Fenster wie passen" erhalten. Sehr kleine Waende
> mit breiten Fenstern koennen daher im Einzelfall ueber 60 % liegen (nur Warnung).

### Giebelwaende — Point-in-Polygon-Check

Giebelwaende (DachTyp_LOD3 ≠ 1000/Flachdach) erzeugen nach dem StoreyGenerator
nicht-rechteckige Polygone (Trapeze, Dreiecke, Fuenfecke).

**Pruefung:** Alle 4 Fenster-Ecken muessen innerhalb des Wand-Polygons liegen.
Da alle Wandpunkte koplanar sind: Projektion auf 2D-Wandkoordinaten (u/v entlang
Unterkante und senkrecht nach oben), dann Ray-Casting- oder Winding-Number-Algorithmus.

Fenster die nicht vollstaendig im Polygon liegen werden **uebersprungen**
(gezaehlt in `gableWindowsDropped`).

### Fenster als innere Polygon-Ringe

**Aeusserer Ring** → bleibt unveraendert (Kernunterschied zum DoorGenerator!)

Fuer jedes Fenster wird ein **innerer Ring** (CW-Orientierung) erzeugt:

```xml
<gml:Polygon gml:id="Poly_...">
  <gml:exterior>
    <gml:LinearRing>
      <gml:posList>... (aeusserer Ring, unveraendert) ...</gml:posList>
    </gml:LinearRing>
  </gml:exterior>
  <gml:interior>   <!-- NEU: Fenster als Loch -->
    <gml:LinearRing>
      <gml:posList>... (CW-Ring: BL → TL → TR → BR → BL) ...</gml:posList>
    </gml:LinearRing>
  </gml:interior>
  <gml:interior>   <!-- zweites Fenster -->
    <gml:LinearRing>
      <gml:posList>... </gml:posList>
    </gml:LinearRing>
  </gml:interior>
</gml:Polygon>
```

**Orientierung:** Innerer Ring = **clockwise** (CW), da aeusserer Ring CCW ist.

### WindowSurface als FillingSurface

Analog zum DoorGenerator wird jede WindowSurface als FillingSurface an der
WallSurface verankert:

```java
WindowSurface windowSurface = new WindowSurface();
windowSurface.setId("Face_" + windowId);
CityGmlUtils.setGmlName(windowSurface, "LOD3_Window");
windowSurface.setLod3MultiSurface(
    CityGmlUtils.createMultiSurfacePropertyWithDefaultSrs(windowPoly));

// Attribute
CityGmlUtils.addStringAttribute(windowSurface, "BldgFaceID", windowId);
CityGmlUtils.addStringAttribute(windowSurface, "FACEAREA",
    CityGmlUtils.formatNum(windowWidth * windowHeight));
CityGmlUtils.addStringAttribute(windowSurface, "Geschoss", geschoss);

wall.getFillingSurfaces().add(new AbstractFillingSurfaceProperty(windowSurface));
```

**CityGML 1.0 Ausgabe (erwartet):**
```xml
<bldg:WallSurface gml:id="Face_...">
  <bldg:lod3MultiSurface>...</bldg:lod3MultiSurface>
  <bldg:opening>
    <bldg:Window gml:id="Face_..._Window_1">
      <gml:name>LOD3_Window</gml:name>
      <bldg:lod3MultiSurface>...</bldg:lod3MultiSurface>
    </bldg:Window>
  </bldg:opening>
</bldg:WallSurface>
```

### Attribute auf WindowSurface

| Attribut | Wert | Beschreibung |
|----------|------|--------------|
| `gml:name` | `LOD3_Window` | Fenster-Element |
| `gml:id` | `Face_{WandFaceID}_Window_{N}` | Eindeutige ID |
| `BldgFaceID` | `{WandFaceID}_Window_{N}` | Face-Identifikator |
| `FACEAREA` | Fensterflaeche in m² | WiLen × WiHe |
| `Geschoss` | `GF`, `UF_1`, `BA`, ... | Geschoss-Zuordnung |

### Spezialfall: Kellerfenster (BA)

- Nur wenn `WindowPreference = "1"` UND `Z_Max > 0`
- **`floorZ` = `zMin` des Wandpolygons** (absoluter Kellerboden — nicht Gelaendeniveau!)
- `windowBottomZ = zMin + VDistFlWi` (VDistFlWi = Bruestungshoehe ab Kellerboden)
- `windowTopZ = windowBottomZ + WiHe`
- Nutzbare Wandhoehe = tatsaechliche Wandhoehe + 10 cm Toleranz (fuer Gleitkomma-Ungenauigkeiten)
- **Unterirdische Reihen werden gefiltert**: Fensterreihen, deren Oberkante vollstaendig
  unterhalb Gelaendeniveau liegt (`rowTopZ < terrainZ - 0.001m`), werden verworfen.
  So werden nur die oberirdisch sichtbaren Kellerfenster erzeugt.
- `terrainZ = Z_MAX_ASL - Z_Max` (Gelaendeniveau, wird nur fuer den Unterirdisch-Filter genutzt)
- **Hart auf 1 Fensterreihe begrenzt** (Z_Max typisch 0.3–1.5 m) — siehe Bugfix unten
- Keine Tueren auf BA → keine Links/Rechts-Aufspaltung noetig

```
Beispiel EE3-Modul (BA.height=2.1, BA.CeHe=0.3, heightGr=0.45m ueber Gelaende):
  H_DGM = 167.92     terrainZ = 167.92
  zMin  = 165.97     floorZ   = 165.97  (Kellerboden)
  VDistFlWi = 1.5,   WiHe = 0.45
  windowBottomZ = 165.97 + 1.5  = 167.47
  windowTopZ    = 167.47 + 0.45 = 167.92  → auf Gelaendeniveau, noch sichtbar ✓
```

### Bugfix: Doppelte/gestapelte Kellerfenster (2026-08-11)

Vom Nutzer beim Sichttest gefunden (Gebaeude `DESNALK0pF001hQX`, Wand `BA_Wall_11`):
zwei Fenster uebereinander (`Win_1` Z 181.29–181.69, `Win_2` Z 181.79–182.19, 0,1 m
echter Abstand — geometrisch kein Fehler, aber architektonisch fuer ein Kellerfenster
unplausibel).

**Ursache:** `computeRowPositions()` ist eine generische, fuer GF/UF/BA gemeinsam
genutzte Schleife, die so lange weitere Reihen uebereinander stapelt, wie Platz ist
(`rowTopZ <= wallTopZ + 0.001`). Der bestehende Unterirdisch-Filter faengt nur komplett
unter Gelaendeniveau liegende Reihen ab — bei ungewoehnlich hohem oberirdischem
Kelleranteil blieb eine 2. Reihe technisch gueltig (kein Ueberlapp, WWR unter 60%) und
wurde daher nicht entfernt, obwohl echte Kellerfenster laut Datenlage praktisch immer
einreihig sind.

**Fix:** Neues Flag `singleRowOnly = "BA".equals(ctx.geschoss())` in
`computeRowPositions()` — die Reihen-Schleife bricht fuer BA-Waende nach der ersten
Reihe hart ab, unabhaengig von verfuegbarem Platz. GF/UF bleiben unveraendert
mehrreihig.

**Verifiziert:** An `DESNALK0pF001hQX` hat `BA_Wall_11` danach nur noch `Win_1`.
Andere BA-Waende mit weiterhin 2 Fenstern (z.B. `BA_Wall_7`) wurden per Koordinaten
gegengeprueft: gleiche Z (181.29–181.69), unterschiedliche X/Y → echte **nebeneinander**
liegende Fenster derselben Reihe, kein Stapel-Fall, bleiben zu Recht erhalten. Volle
3.801-Gebaeude-Kachel danach weiterhin `citygml-tools validate`-schema-valide.

### Vergleich DoorGenerator vs. WindowGenerator

| Aspekt | DoorGenerator | WindowGenerator |
|--------|---------------|-----------------|
| Geschosse | Nur GF | GF + UF + BA |
| Anzahl pro Wand | Aus Attribut `DoorCount` | **Berechnet** aus Wandlaenge + Params |
| Polygon-Modifikation | Innerer Ring (Loch), 5cm-Sockel | Innerer Ring (Loch) — gleiche Hilfsmethode |
| Mehrere Reihen | Nein | **Ja** (wenn Wandhoehe ausreicht) |
| Giebelwaende | Kontur-Check (`openingInsideWall2D`) | Point-in-Polygon-Check (pro Reihe) |
| Tuer-Interaktion | — | Liest Tuerpositionen, splittet links/rechts |
| BA-Sonderlogik | Keine | Nur oberirdischer Anteil (Z_Max > 0) |
| Realismus-Check | Nicht noetig (count vorgegeben) | **WWR-Pruefung** (max. Fassadenanteil) |
| Geteilte Wand (BuildingParts) | Dedup ueber `wallBottomMidKey` (`wallsSkippedCoveredByPart`) | Dedup ueber `wallBottomMidKey` (`coveredByPart`) |
| Anbau-Verdeckung | Footprint-Check ueber alle Parts (`doorsSkippedCovered`) | Nicht relevant |

### Beispiel-Ausgabe (WindowGenerator)

Testlauf mit 3.801 Gebaeuden (LoD2_33_416_5656_2_SN):

```
Schritt 5 — Fenster:  44655 Fenster, 20448 Waende, 65659 uebersprungen, 360 Giebel-Drops, 17 WWR-Warn

Per-Geschoss:
  BA:   3844 Fenster (25247 uebersprungen)
  GF:  12339 Fenster (16452 uebersprungen)
  UF_1: 17409 Fenster (11351 uebersprungen)
  UF_2:  8435 Fenster  (7895 uebersprungen)
  UF_3:  1915 Fenster  (3302 uebersprungen)
  UF_4:   471 Fenster   (953 uebersprungen)
  UF_5:   124 Fenster   (327 uebersprungen)
  UF_6:    59 Fenster   (112 uebersprungen)
  UF_7:    38 Fenster     (9 uebersprungen)
  UF_8:    21 Fenster    (11 uebersprungen)

Skip-Gruende:
  WP0/null=15788  coveredByPart=5752  BA_noZMax=215  noParams=1813
  tooShort=32351  tooLow=8974  noFit=372  noBottom=333  pipFail=61
```

Von den ~74.826 Waenden mit `WindowPreference=1` erhalten 20.448 Waende tatsaechlich
Fenster. Die meisten Uebersprungenen sind zu kurz fuer auch nur ein Fenster (`tooShort`)
oder haben `WindowPreference=0/null`. 5.752 Waende werden als geteilte Waende zwischen
BuildingParts erkannt und uebersprungen (`coveredByPart`) — der erste BuildingPart erhaelt
die Fenster, der zweite wird dedupliziert.

## Projektstruktur

```
LoD2_zu_LoD3/
|-- pom.xml
|-- README.md
|-- src/main/java/de/mpsc/lod2tolod3/
|   |-- Lod2ToLod3Pipeline.java      # Haupt-Pipeline (Single-Pass, Schritte 1-7)
|   |-- Lod2ToLod3Promoter.java      # Schritt 1: Geometrie-Hochstufung (LoD2 → LoD3)
|   |-- AbstractGenerator.java       # Gemeinsame Basis der JSON-parametrisierten Generatoren (Template-Method)
|   |-- BasementGenerator.java       # Schritt 2: Keller-Generierung
|   |-- StoreyGenerator.java         # Schritt 3: Geschoss-Unterteilung
|   |-- DoorGenerator.java           # Schritt 4: Tuer-Generierung
|   |-- WindowGenerator.java         # Schritt 5: Fenster-Generierung
|   |-- BalconyGenerator.java        # Schritt 6: Balkone — siehe Abschnitt "Schritt 6"
|   |-- model/
|   |   |-- ModuleParameters.java    # Datenklasse fuer JSON-Parameter
|   |-- util/
|       |-- CityGmlUtils.java        # Gemeinsame Hilfsfunktionen (Polygone, Attribute, Solid-Shell-Rebuild,
|       |                            #   processGmlFile, resolveOutputPath, createLinearRing, pointInPolygon2D)
|       |-- DgmProvider.java          # Interface fuer DGM-Quellen (getHeight, contains)
|       |-- DgmReader.java            # ESRI ASCII Grid Parser (.asc)
|       |-- GeoTiffReader.java         # GeoTIFF Parser (.tif/.tiff, javax.imageio)
|       |-- DgmMosaic.java             # Kombiniert mehrere DGM-Tiles zu einem Mosaik
|       |-- DgmLoader.java             # Smart Factory: Format-Erkennung, ZIP, Verzeichnis
|       |-- ModuleParametersLoader.java  # JSON-Parameter laden und cachen
|-- output/                          # Pipeline-Ausgabedateien
```

## JSON-Baukoerpermodule

Die Pipeline verwendet JSON-Dateien mit Baukoerper-Parametern. Jedes Gebaeude wird ueber sein `sst`-Attribut einem Modul zugeordnet.

### Struktur

```json
{
  "sst": "EFH_2",
  "BA": { "height": 2.5 },
  "GF": { "height": 3.0 },
  "UF": { "height": 2.5 }
}
```

| Feld | Beschreibung |
|------|--------------|
| `sst` | Strukturtyp (Schluessel fuer Zuordnung) |
| `BA.height` | Kellerhoehe in Metern |
| `GF.height` | Erdgeschoss-Hoehe in Metern |
| `UF.height` | Obergeschoss-Hoehe in Metern |

## TerrainIntersectionCurve (TIC) — Methodik und Referenzen

### Konzept nach Kolbe

Die **TerrainIntersectionCurve (TIC)** ist ein zentrales Konzept in CityGML, das von Thomas
Kolbe als „Interface-Objekt" zwischen dem 3D-Gebaeudemodell und dem Digitalen Gelaendemodell
(DGM/DTM) beschrieben wird (Kolbe, 2009). Die TIC dokumentiert exakt, wo ein Gebaeude das
Gelaende schneidet, und loest damit ein fundamentales Problem der 3D-Stadtmodellierung:
Gebaeude und Gelaende werden typischerweise getrennt modelliert und stimmen geometrisch
nicht perfekt ueberein.

**Definition (CityGML 3.0, OGC 20-010):**
> „The terrain intersection curve marks the boundary line of the building where
> it touches the ground." (AbstractPhysicalSpace, Modul Building)

**Verwendung in dieser Pipeline:**

Fuer Gebaeude mit Keller wird die originale GroundSurface durch die physische Bodenplatte
ersetzt (s. Schritt 2). Die TIC dokumentiert den urspruenglichen Gelaendeschnitt als
`lod3TerrainIntersectionCurve` — ein `gml:MultiCurve` bestehend aus geschlossenen 3D-Ringen.

```
Draufsicht: TIC als geschlossener Ring um den Gebaeude-Grundriss

     +---------------------------+
     |                           |
     |  Grundriss (GroundSurface)|   TIC-Ring: geschlossener 3D-LineString
     |                           |   Z-Werte: H_DGM (flach) oder bilinear
     |                           |            interpoliert aus DGM
     +---------------------------+

Mehrere Ringe moeglich bei:
  - Gebaeude mit Innenhof (2 Ringe: aussen + innen)
  - BuildingParts mit eigenem Grundriss
```

### Geometrietyp

| Eigenschaft | Wert |
|---|---|
| CityGML-Element | `bldg:lod3TerrainIntersection` |
| Geometrie | `gml:MultiCurve` (1 oder mehr `gml:curveMember`) |
| Kurventyp | `gml:LineString` mit `gml:posList` (srsDimension=3) |
| Topologie | Geschlossene Ringe (erster Punkt = letzter Punkt) |
| Traeger | `AbstractPhysicalSpace` → `Building`, `BuildingPart` |
| CRS | `urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH` |

### TIC-Erzeugung: Zwei Varianten

#### Variante 1: Flache TIC (ohne DGM — Standard)

Wenn kein Gelaendemodell verfuegbar ist, wird die TIC als flacher 3D-Ring bei Z = H_DGM
aus dem GroundSurface-Grundriss erzeugt:

```
Eingabe:  GroundSurface-Polygon mit Vertices V1..Vn
Ausgabe:  Geschlossener LineString mit Z = H_DGM

V1 (x1, y1, H_DGM) → V2 (x2, y2, H_DGM) → ... → Vn (xn, yn, H_DGM) → V1
```

Dies ist eine Approximation unter der Annahme eines flachen Gelaendes im Bereich des
Gebaeudegrundrisses.

#### Variante 2: Interpolierte TIC (mit DGM)

Wenn ein Digitales Gelaendemodell (DGM) vorhanden ist (ESRI ASCII Grid, GeoTIFF, oder
als Mosaik mehrerer Kacheln), wird die Z-Koordinate jedes TIC-Vertex durch **bilineare
Interpolation** aus dem DGM bestimmt:

```
Eingabe:  GroundSurface-Polygon mit Vertices V1..Vn + DGM-Raster
Ausgabe:  Geschlossener LineString mit Z = DGM(x, y)

V1 (x1, y1, DGM(x1,y1)) → V2 (x2, y2, DGM(x2,y2)) → ... → V1

Bilineare Interpolation:
  (col, row) = ((x - xllcorner) / cellsize - 0.5,
                (nrows - 1) - (y - yllcorner) / cellsize + 0.5)

  Z = (1-fx)(1-fy) * data[r0][c0]
    + (  fx)(1-fy) * data[r0][c1]
    + (1-fx)(  fy) * data[r1][c0]
    + (  fx)(  fy) * data[r1][c1]

  wobei fx/fy = fraktionale Anteile in der Rasterzelle
```

Die bilineare Interpolation liefert glatte Uebergaenge zwischen benachbarten Rasterzellen.
Liegt ein Vertex genau auf dem Rand des DGM-Rasters, wird der naeachste Rasterwert verwendet
(Nearest-Neighbor-Fallback). Liegt er ausserhalb des DGM, wird H_DGM als Fallback verwendet,
wie von Kolbe & Czerwinski (2006) empfohlen.

### Qualitaetskriterien nach Kolbe

Kolbe (2009) formuliert folgende Qualitaetskriterien fuer TICs, die in dieser Pipeline
beruecksichtigt werden:

| Kriterium | Umsetzung |
|---|---|
| **Geschlossene Ringe** | TIC-LineStrings werden immer als geschlossene Ringe erzeugt (V1 = Vn+1) |
| **3D-Koordinaten** | Alle Vertices haben volle 3D-Koordinaten (srsDimension=3) |
| **Konsistenz mit Gebaeude** | TIC verwendet die XY-Koordinaten der originalen GroundSurface |
| **DGM-Integration** | Optional: Z-Werte werden bilinear aus dem DGM interpoliert |
| **NODATA-Behandlung** | DGM-Luecken werden per Fallback auf H_DGM behandelt |
| **Multi-Ring** | Mehrere Grundrisspolygone erzeugen mehrere TIC-Ringe (MultiCurve) |

### Validierung gegen offizielle Dresden-TICs

Die von der Pipeline erzeugten TICs wurden gegen die offiziellen `lod2TerrainIntersectionCurve`-Daten
des **Geoportal Dresden** (LoD2-Stadtmodell, Kachel 33_416_5656) validiert. Dabei wurden 1720
Gebaeude per Centroid-Naehe gematcht und die Z-Werte der TIC-Vertices verglichen:

| Metrik | Wert |
|--------|------|
| Gematchte Gebaeude | 1720 (von 2148 unsere / 5127 Dresden) |
| **Mittlere Differenz AvgZ** | **-0,001 m** |
| Mittlerer Absolutbetrag | 0,191 m |
| Median (P50) | -0,006 m |
| 90%-Intervall (P5–P95) | -0,48 m bis +0,46 m |
| Anteil innerhalb ±0,5 m | 90,8% |
| Anteil innerhalb ±1,5 m | 97,3% |

**Ergebnis:** Die Z-Werte unserer TICs sind im Mittel praktisch identisch mit den offiziellen
Dresden-TICs (Differenz = -0,001 m). Die verbleibenden Abweichungen entstehen durch:

- **Vertex-Verdichtung**: Dresden erzeugt Ø 52 Vertices pro TIC (auf 1m-DGM-Raster verdichtet),
  unsere Pipeline verwendet Ø 14 Vertices (die originalen Grundriss-Eckpunkte). Dadurch bildet
  Dresden das Mikrorelief feiner ab, waehrend unsere TICs die Grundrissform exakt widerspiegeln.
- **Gebaeude-Matching**: Die Building-IDs unterscheiden sich zwischen den Datenquellen
  (DESNALK0pF… vs. DESNATPU1000…), daher erfolgte das Matching geometrisch per Centroid-Entfernung
  (Median: 1,44 m).
- **Ausreisser (>2 m)**: Betreffen ausschliesslich BuildingParts an Hanglagen — hier kann die
  unterschiedliche Vertex-Verdichtung groessere Differenzen verursachen.

### Referenzen

- **Kolbe, T. H. (2009)**. *Representing and Exchanging 3D City Models with CityGML.*
  In: Lee, J. & Zlatanova, S. (Eds.), 3D Geo-Information Sciences, Lecture Notes in
  Geoinformation and Cartography, Springer, pp. 15–31.
  DOI: [10.1007/978-3-540-87395-2_2](https://doi.org/10.1007/978-3-540-87395-2_2)

- **Kolbe, T. H. & Czerwinski, A. (2006)**. *Integration of DTM and 3D Building Models
  Using CityGML's TerrainIntersectionCurve.* Proceedings of the 2nd International Workshop
  on 3D Geo-Information, Berlin.

- **Kolbe, T. H., Gröger, G. & Plümer, L. (2005)**. *CityGML – Interoperable Access to
  3D City Models.* In: van Oosterom, P., Zlatanova, S. & Fendel, E. (Eds.), Geo-information
  for Disaster Management, Springer, pp. 883–899.
  DOI: [10.1007/3-540-27468-5_63](https://doi.org/10.1007/3-540-27468-5_63)

- **OGC (2021)**. *OGC City Geography Markup Language (CityGML) Part 1: Conceptual Model
  Standard.* OGC 20-010, Version 3.0.
  URL: [https://docs.ogc.org/is/20-010/20-010.html](https://docs.ogc.org/is/20-010/20-010.html)

- **Gröger, G. & Plümer, L. (2012)**. *CityGML – Interoperable semantic 3D city models.*
  ISPRS Journal of Photogrammetry and Remote Sensing, 71, pp. 12–33.
  DOI: [10.1016/j.isprsjprs.2012.04.004](https://doi.org/10.1016/j.isprsjprs.2012.04.004)

## DGM-Unterstuetzung (Multi-Format)

### Ueberblick

Die Pipeline unterstuetzt Digitale Gelaendemodelle in mehreren Formaten:

| Format | Dateiendung | Beschreibung |
|--------|-------------|---------------|
| ESRI ASCII Grid | `.asc` | Textbasiertes Rasterformat (Landesvermessungsaemter, GeoSN) |
| GeoTIFF | `.tif`, `.tiff` | Binaeres Rasterformat mit Georeferenzierung (haeufig bei DGM-Downloads) |
| ZIP-Archiv | `.zip` | Automatische Extraktion — enthaltene `.asc`/`.tif`-Dateien werden on-the-fly gelesen |
| Verzeichnis | Ordnerpfad | Alle DGM-Dateien (`.asc`, `.tif`, `.zip`) werden rekursiv gesammelt und als Mosaik kombiniert |

### Architektur

```
DgmProvider (Interface)
├── DgmReader        — ESRI ASCII Grid (.asc)
├── GeoTiffReader    — GeoTIFF (.tif / .tiff)
└── DgmMosaic        — Kombiniert mehrere Tiles zu einem virtuellen DGM

DgmLoader (Factory)
└── load(Path) → DgmProvider
    ├── .asc       → DgmReader
    ├── .tif/.tiff → GeoTiffReader
    ├── .zip       → liest .asc/.tif aus dem Archiv (ohne Entpacken)
    └── Verzeichnis → scannt rekursiv, laedt alle Tiles als DgmMosaic
```

`DgmLoader.load(Path)` erkennt das Format automatisch anhand der Dateiendung bzw.
des Dateityps (Verzeichnis) und gibt ein `DgmProvider`-Objekt zurueck.

### ESRI ASCII Grid (.asc)

Eigener Parser ohne externe Abhaengigkeiten.

```
ncols         1000
nrows         1000
xllcorner     416000.0
yllcorner     5656000.0
cellsize      1.0
NODATA_value  -9999
159.57 159.58 159.60 159.62 ...
159.55 159.56 159.59 159.61 ...
...
```

| Header-Feld | Beschreibung |
|---|---|
| `ncols` / `nrows` | Spalten- und Zeilenanzahl des Rasters |
| `xllcorner` / `yllcorner` | Linke untere Ecke (UTM-Koordinaten) |
| `xllcenter` / `yllcenter` | Alternativ: Mittelpunkt der linken unteren Zelle (wird automatisch in corner umgerechnet) |
| `cellsize` | Rasterweite in Metern (z.B. 1.0, 2.0, 5.0) |
| `NODATA_value` | Fehlwert (z.B. -9999) |

### GeoTIFF (.tif / .tiff)

GeoTIFF-Reader auf Basis von `javax.imageio` (Java 9+) — **keine externen Abhaengigkeiten**.

Unterstuetzte GeoTIFF-Tags:

| Tag | Nummer | Beschreibung |
|-----|--------|--------------|
| `ModelPixelScaleTag` | 33550 | Pixelgroesse in X/Y/Z |
| `ModelTiepointTag` | 33922 | Referenzpunkt (Pixel → Welt) |
| `ModelTransformationTag` | 34264 | Alternativ: 4×4 Transformationsmatrix |
| `GDAL_NODATA` | 42113 | NODATA-Wert als String |

Unterstuetzte Kompression: LZW, Deflate, PackBits, unkomprimiert.

Typische Daten (z.B. DGM1 Sachsen/Dresden):
- Kachelgroesse: 2000 × 2000 Pixel bei 1 m Aufloesung (= 2 × 2 km)
- Dateien als ZIP bereitgestellt: `dgm1_{easting}_{northing}_2_sn_tiff.zip`
- Jede ZIP-Datei enthaelt: `.tif`, `.tfw` (World-File), `.tif.aux.xml`, `_akt.csv`

### ZIP-Unterstuetzung

ZIP-Archive werden **on-the-fly** gelesen — kein manuelles Entpacken noetig.
`DgmLoader` oeffnet das ZIP, sucht die erste `.asc`- oder `.tif`-Datei darin und
liest sie direkt aus dem InputStream.

### Mosaik (Multi-Tile)

Wird ein **Verzeichnis** als DGM-Pfad angegeben, scannt `DgmLoader` rekursiv nach
allen unterstuetzten Dateien (`.asc`, `.tif`, `.tiff`, `.zip`) und kombiniert sie
zu einem virtuellen Mosaik (`DgmMosaic`).

Bei der Hoehenabfrage wird die passende Kachel anhand von `contains(x, y)` ermittelt.
Dies ermoeglicht z.B. die Nutzung aller 117 DGM1-Kacheln fuer Dresden als ein
durchgehendes Gelaendemodell.

```
Beispiel-Verzeichnis:
DGM/Dresden/
├── dgm1_33_330_5640_2_sn_tiff.zip
├── dgm1_33_330_5642_2_sn_tiff.zip
├── ...
└── dgm1_33_420_5664_2_sn_tiff.zip   (117 Kacheln)

Ausgabe:
  Scanne DGM-Verzeichnis: DGM/Dresden
  117 DGM-Dateien gefunden
  DGM-Mosaik geladen: 117 Tiles in 12345 ms
```

### Interpolation

Beide Reader (DgmReader und GeoTiffReader) verwenden **bilineare Interpolation**
zur Hoehenwertbestimmung:

```
Fuer einen Punkt P(x, y):

1. Rasterposition berechnen:
   col = (x - xllcorner) / cellsize - 0.5
   row = (nrows - 1) - (y - yllcorner) / cellsize + 0.5

2. Die 4 umgebenden Rasterzellen bestimmen:
   c0 = floor(col),  c1 = c0 + 1
   r0 = floor(row),  r1 = r0 + 1

3. Bilinear interpolieren:
   fx = col - c0,  fy = row - r0
   Z = (1-fx)(1-fy) · Z[r0,c0]
     + (  fx)(1-fy) · Z[r0,c1]
     + (1-fx)(  fy) · Z[r1,c0]
     + (  fx)(  fy) · Z[r1,c1]
```

### Robustheit

- **NODATA-Behandlung**: Rasterzellen mit NODATA-Wert werden uebersprungen; naechster
  gueltiger Nachbar wird verwendet
- **Rand-Fallback**: Fuer Punkte am Rasterrand wird Nearest-Neighbor statt bilineare
  Interpolation verwendet
- **Speichereffizienz**: Hoehenwerte als `float[][]` (nicht `double[][]`) fuer geringe
  Speicherbelastung bei grossen DGMs (2000×2000 Zellen = ~16 MB pro Tile)
- **Bounds-Check**: `contains(x, y)` prueft ob ein Punkt im DGM-Bereich liegt
- **Format-Erkennung**: `DgmLoader` erkennt das Format automatisch anhand der Dateiendung
- **Fehlertoleranz**: Unlesbare Dateien in einem Verzeichnis werden mit Warnung uebersprungen

### API

```java
// Einzelne Datei laden (Format wird automatisch erkannt)
DgmProvider dgm = DgmLoader.load(Path.of("dgm.asc"));       // ASCII Grid
DgmProvider dgm = DgmLoader.load(Path.of("dgm1.tif"));       // GeoTIFF
DgmProvider dgm = DgmLoader.load(Path.of("dgm1.zip"));       // ZIP (on-the-fly)

// Ganzes Verzeichnis als Mosaik laden
DgmProvider dgm = DgmLoader.load(Path.of("DGM/Dresden/"));   // Multi-Tile Mosaik

// Hoehe abfragen (bilineare Interpolation)
double z = dgm.getHeight(416290.843, 5657417.357);

// Bereich pruefen
boolean inRange = dgm.contains(416290.843, 5657417.357);

// Beschreibung (fuer Logging)
String info = dgm.describe();  // z.B. "GeoTIFF 2000x2000, pixelSize=1.00"
```

## Algorithmen und Berechnungsverfahren

### Sutherland-Hodgman Polygon-Clipping (cutWallPolygonAtZ)

**Problem:** Waende muessen an Geschossgrenzen horizontal geschnitten werden. Waende koennen
beliebige Formen haben: Rechtecke (90%), Fuenfecke/Giebel (3%), Sechsecke/Walm (4%),
Dreiecke (0.4%), und komplexere Formen mit bis zu 34 Eckpunkten (Gauben, Erker, L-Formen).

**Loesung:** Der Sutherland-Hodgman-Algorithmus teilt ein beliebiges Polygon an einer
horizontalen Ebene z=zCut in zwei Haelften. Der Algorithmus ist allgemein und funktioniert
unabhaengig von der Punktanzahl oder Form des Polygons.

**Sicherheitsmerkmale:**
- **Fitzelchen-Toleranz:** Schnitte werden nur ausgefuehrt wenn zCut mindestens `tolerance`
  (5cm) von den Polygon-Kanten entfernt ist → verhindert degenerierte Splitter-Polygone
- **Klassifikations-Epsilon:** Punkte innerhalb 1mm der Schnittebene werden als "auf der Ebene"
  behandelt → verhindert Numerik-Probleme bei fast-horizontalen Kanten
- **Z-Rundung:** Schnittpunkte werden auf mm-Genauigkeit gerundet → verhindert Floating-Point-Artefakte

### Newell's Method (calculateWallArea)

**Problem:** Die Flaeche eines 3D-Polygons berechnen. Die alte Methode (Breite × Hoehe)
funktionierte nur fuer Rechtecke (4 Punkte). Fuenfecke, Dreiecke, etc. erhielten FACEAREA=0.

**Loesung:** Newell's Method berechnet die Flaeche jedes planaren 3D-Polygons:

```
1. Flaechennormale N berechnen:
   N = Summe ueber alle Kanten (P_i x P_{i+1})
   → Kreuzprodukt-Summe aller aufeinanderfolgenden Eckpunkte

2. Flaeche = halbe Laenge von N:
   A = 0.5 * |N|
```

Fuer Rechtecke liefert Newell's Method das gleiche Ergebnis wie Breite × Hoehe.

### Wandnormale-Azimut (calculateWallNormalAzimuthFromPolygon)

**Problem:** Die Blickrichtung der Wand bestimmen (Kompass-Azimut der Aussenflaechen-Normalen).

**Loesung:** Dreistufige Strategie:
1. **Primaer:** Suche zwei aufeinanderfolgende Punkte am unteren Rand (minZ). Die Senkrechte
   auf diese "untere Kante" in der Horizontalebene ist die Wandnormale. Funktioniert fuer
   alle gaengigen Wandformen.
2. **Fallback:** Wenn keine untere Kante gefunden (z.B. Dreieck mit Einzelpunkt unten):
   Verwende die laengste horizontale Kante.
3. **Letzter Fallback:** Verwende die erste Kante des Polygons.

### Flachdach-Erkennung

**Problem:** Bei Flachdaechern liegt die CeilingSurface des obersten Geschosses auf derselben
Hoehe wie die RoofSurface → doppelte Geometrie, sinnlos.

**Loesung:** Berechne First-Hoehe (Max Z aller RoofSurface-Punkte) und Traufe-Hoehe
(Min Z aller RoofSurface-Punkte). Wenn die Differenz < 0.30m: Dach ist "flach" →
keine CeilingSurface am obersten Geschoss erzeugen.

```
firstZ  = max(alle RoofSurface Z-Werte)
traufeZ = min(alle RoofSurface Z-Werte)

isFlachdach = (firstZ - traufeZ) < 0.30m

Wenn isFlachdach UND oberstes Geschoss:
  → Kein CeilingSurface erzeugen (RoofSurface ist die Decke)
```

### Mischdach-Erkennung

**Problem:** Manche Gebaeude haben sowohl ein geneigtes Dach (Walm-/Satteldach) als auch
ein Flachdach auf niedrigerem Niveau. Die Traufe (traufeZ) liegt dann auf dem Flachdach-Level.
Die CeilingSurface des obersten Geschosses erstreckte sich bisher ueber den gesamten Grundriss
und ueberlagerte dabei das Flachdach. Im Testdatensatz sind **536 von 5101** Gebaeude/BuildingParts
(10.5%) davon betroffen.

```
FALSCH (vorher):                     RICHTIG (jetzt):

     /‾‾‾‾‾\                            /‾‾‾‾‾\
    / Walm   \   ________              / Walm   \   ________
   /  dach    \ |Flachdach|            /  dach    \ |Flachdach|
  /            \|         |           /            \|         |
  ==========================          ==============|  (kein  |
  |  CeilingSurface       |          |  Ceiling    ||  Ceiling|
  | (gesamter Grundriss!) |          | (nur unter  ||  hier!) |
  ==========================          | Walmdach)  ||         |
                                      ==============|=========|
```

**Algorithmus:**

```
1. Alle RoofSurface-Polygone sammeln
2. Fuer jedes Polygon: maxZ bestimmen
3. Klassifizieren:
   - "Flach bei Traufe": (maxZ - traufeZ) < 0.30m  → keine Decke noetig
   - "Geneigt/Erhoeht":  (maxZ - traufeZ) >= 0.30m → Decke noetig

4. Wenn ALLE flach → reines Flachdach → keine Decke (wie bisher)
5. Wenn KEINE flach → normales Dach → Decke aus Grundrisspolygon (wie bisher)
6. Wenn GEMISCHT:
   → CeilingSurface NUR aus projizierten geneigten Dachpolygonen erzeugen
   → Jedes geneigte Dachpolygon wird auf Z=traufeZ projiziert
   → Die Projektion ergibt die korrekte 2D-Grundflaeche unter dem geneigten Teil
   → Unter dem Flachdach: keine CeilingSurface (RoofSurface = Decke)
```

**Empirische Verteilung im Testdatensatz (536 Mischdach-Gebaeude):**

Typische Muster:
- Walmdach + Anbau mit Flachdach
- Hauptgebaeude mit Satteldach + Garage mit Flachdach
- Staffelgeschoss mit Flachdach unter hoeherem Dach

## Technische Details

### citygml4j Klassenhierarchie

Der Promoter nutzt die generischen Basisklassen von citygml4j:

```
AbstractCityObject
|-- AbstractSpace (Building, BuildingPart, Tunnel, Bridge...)
|   |-- getSolid(lod) / setSolid(lod, value)
|   |-- getMultiSurface(lod) / setMultiSurface(lod, value)
|   |-- getMultiCurve(lod) / setMultiCurve(lod, value)
|
|-- AbstractSpaceBoundary
    |-- AbstractThematicSurface (WallSurface, RoofSurface...)
        |-- getMultiSurface(lod) / setMultiSurface(lod, value)
```

### Metadaten-Attribute

Jedes hochgestufte Building erhaelt generische Attribute:

```xml
<!-- Nach Schritt 1: LoD2->LoD3 Hochstufung -->
<gen:stringAttribute name="lod2ToLod3Promotion">
  <gen:value>promoted=true; count=13; types=[Building.lod2Solid, ...]; timestamp=...</gen:value>
</gen:stringAttribute>

<!-- Nach Schritt 2: Keller hinzugefuegt -->
<bldg:storeysBelowGround>1</bldg:storeysBelowGround>  <!-- natives CityGML-Element -->
<gen:stringAttribute name="LoD3_Basement">
  <gen:value>generated</gen:value>
</gen:stringAttribute>

<!-- Nach Schritt 3: Geschosse erstellt -->
<gen:stringAttribute name="storeysGenerated">
  <gen:value>3</gen:value>
</gen:stringAttribute>
```

### CityGML-Version

Die Pipeline liest und schreibt CityGML 1.0 (Namespace `http://www.opengis.net/citygml/1.0`).

**Hinweis**: CityGML 1.0 unterstuetzt kein `BuildingStorey`-Element direkt. Stattdessen werden die Geschoss-Informationen als `FloorSurface` und `CeilingSurface` mit entsprechenden IDs gespeichert:
- `{buildingId}_storey{level}_floor_{polygonIndex}`
- `{buildingId}_storey{level}_ceiling_{polygonIndex}`

## Topologie-Bereinigung & geometrische Validitaet

Nachdem die Schritte 1–5 die LoD3-Geometrie erzeugt haben, laufen im Pipeline-Modus
zwei **formneutrale** Nachbearbeitungsschritte. „Formneutral" heisst: es wird **kein
bestehender Vertex verschoben** — die aus der Befliegung stammende Wandform bleibt
unveraendert. Ergaenzt werden ausschliesslich fehlende Verbindungspunkte, um die aus
unabhaengig erzeugten Flaechen (Wand/Boden/Decke/Keller) zusammengesetzte Huelle
wasserdicht (ISO 19107) zu machen. Ziel ist ein **geschlossenes `lod3Solid` pro
Gebaeude** — Voraussetzung fuer die Starkregen-/Fluss-Simulation.

### Schritt 7 — Junction-Conforming (`CityGmlUtils.conformJunctions`, Toleranz 5 mm)

Der **einzige** Nachbearbeitungsschritt, und er ist **streng formneutral** (an echter
Geometrie gemessen: **0** bestehende Vertices verschoben). An T-Stoessen — eine Wand
endet mitten auf der Kante einer anderen — fehlt dem laengeren Nachbarn der
Zwischenpunkt, sodass die Huelle formal nicht schliesst. Dieser Schritt fuegt den
fehlenden Vertex **auf** die bestehende Kante ein (Punkt liegt exakt auf der Geraden →
keine Formaenderung) und naeht so ausschliesslich **unsere eigenen** unabhaengig
erzeugten Zusatzflaechen (Geschosse, Keller, Boden-/Deckenslabs) zusammen. Messung an
der 658er-Kachel: **0 Vertices bewegt, 1.822 Punkte auf bestehenden Kanten eingefuegt**;
das senkt `NOT_CLOSED` von 83 auf 11 offene Huellen.

> **Vertex-Welding wurde bewusst ENTFERNT.** Das frühere 5-mm-Welding verschmolz nahe
> Vertices und verschob dabei ~0,3 % der Punkte um bis zu 5 mm — also echtes
> Geometrie-Reshaping. Da ein nachgelagerter **Healer** solche mm-Naehte ohnehin
> schliesst, gehoert das nicht in dieses LoD3-Update. Wichtiger Befund: das Entfernen
> aendert die Zahl der **von uns** gebrochenen Gebaeude **nicht** (22 mit wie ohne
> Welding); Welding hatte nur zusaetzlich 9 **bereits in der Quelle** offene Gebaeude
> mitgeheilt — genau die Aufgabe des Healers.

> **Warum keine eigenen BuildingParts fuer Anbauten?** Fuer die Simulation wird ein
> einziges geschlossenes Solid je Gebaeude benoetigt. Anbauten als separate
> BuildingParts wuerden zwar `BUILDINGPARTS_OVERLAP` (601) vermeiden, aber das
> Ein-Solid-Ziel verletzen. Stattdessen wird der Anbau in dieselbe Huelle integriert
> und per Conforming wasserdicht angeschlossen.

### Validierung (val3dity 2.6 / CityDoctor2)

Gepueft wird ueber `citygml-tools to-cityjson` → `val3dity` (ISO-19107-Solid-Validitaet).
Ergebnis am Sachsen-Testdatensatz (**658 Gebaeude**):

| Stand | valide Gebaeude | NOT_CLOSED (302) | Self-Int (104) | Geometrie bewegt |
|-------|-----------------|------------------|----------------|------------------|
| Ausgangs-LoD2 (Obergrenze) | 607 (92,2 %) | 15 | 1 | — |
| Multi-Piece-Schnitt (ohne Nachbearbeitung) | 533 (81,0 %) | 83 | 1 | 0 mm |
| **+ Junction-Conforming (final)** | **586 (89,1 %)** | **11** | **1** | **0 mm** |
| *(verworfen: zusaetzliches 5-mm-Welding)* | *595 (90,4 %)* | *2* | *3* | *243 Pkt ≤5 mm* |

Der finale Stand ist **586 (89,1 %) bei null Geometriebewegung**. Der Multi-Piece-Schnitt
(jede Wand korrekt unterteilt, Oeffnungswaende in Einzelstuecke getrennt) haelt Self-Int
und Shell-Self-Intersection niedrig; Conforming schliesst formneutral die Naehte unserer
eigenen Zusaetze. Von den 11 verbleibenden offenen Huellen sind **8 aus der Quelle
geerbt** (die selbst 15 offene hat), nur **2 stammen aus unserer Pipeline** — und wir
schliessen umgekehrt **7** der quell-offenen. Die letzten mm-Naehte bleiben dem Healer.

**Von uns eingefuehrte Fehler** (gemessen: Gebaeude, die in der Quelle valide sind, bei
uns aber nicht): **22** — davon `303` T-Stoesse an Anbau-Naehten (11), `601` (5, siehe
unten Tooling-Hinweis), `206` Fenster-in-Nische (3), Rest Einzelfaelle.

> **Hinweis `601 BUILDINGPARTS_OVERLAP`:** Betrifft 5 Mehr-Part-Gebaeude, ist aber
> **kein Defekt unseres CityGML** — die Ausgabe ist konform (Standard-LoD3: `lod3Solid`
> + `boundedBy`-Flaechen mit gemeinsamen, per xlink referenzierten Polygonen). Erst die
> `citygml-tools`→CityJSON-Konvertierung erzeugt eine Doppel-Darstellung (Solid +
> MultiSurface), die val3dity als Ueberlapp flaggt. Tooling-Artefakt der Pruefkette.

**Schritt 7a — Faltungs-Schutz beim Wandschnitt:** `cutWallAtMultipleZ` prueft jedes
erzeugte Segment mit `CityGmlUtils.ringSelfIntersects`. Wuerde der Sutherland-Hodgman-
Schnitt eine zur Schnittebene konkave/gebogene Wand zu einem self-touching Ring falten,
wird der Schnitt komplett verworfen und die **valide Original-Wand ungeschnitten**
behalten (formneutral). Das senkt Self-Int 24→15. Trade-off (an val3dity gemessen):
eine ganz gehaltene Wand ueber zwei Geschosse kollidiert mit der Geschoss-Bodenplatte an
der Etagengrenze (Platten-Kante mitten auf der Wandflaeche → NON_MANIFOLD 303: 9→13,
307: 1→3). Netto +3 valide Gebaeude. Ein zusaetzlich getesteter Weld-Pinch-Schutz war
netto neutral und wurde wieder entfernt.

**Verbleibende Fehler** (63 Gebaeude) und ihre Ursache:

| Code | Fehler | Anzahl | Ursache |
|------|--------|--------|---------|
| 601 | BUILDINGPARTS_OVERLAP | 25 | groesstenteils aus LoD2 geerbt |
| 104 | RING_SELF_INTERSECTION | 15 | Rest gefalteter Wandschnitte, die der Faltungs-Schutz (noch) nicht abfaengt |
| 303 | NON_MANIFOLD_CASE | 13 | ganz gehaltene Waende ↔ Geschoss-Bodenplatte (Platten-Kante mitten auf Wandflaeche) + Anbau-Stufen |
| 203/204 | NON_PLANAR | 7 | aus LoD2 geerbt (Befliegungsgenauigkeit) |
| 307 | POLYGON_WRONG_ORIENTATION | 3 | Einzelfaelle an ungeschnittenen Waenden |
| 302 | SHELL_NOT_CLOSED | 2 | Rest-Naehte |
| 206 | INNER_RING_OUTSIDE | 2 | Einzelfaelle |

**Ursache der Self-Intersections (an echter Geometrie verifiziert, urspr. 24):** Sie sind
**nicht** aus den Quelldaten geerbt — die LoD2-Eingabe hat nur 1 Self-Intersection von
775 Primitiven. Sie entstehen in **unserem** Wandschnitt `cutWallPolygonAtZ`
(Sutherland-Hodgman). Die betroffenen Waende sind **flach und planar** (Ebenen-Abweichung
≤1,4 mm; 21/24 senkrecht) — **nicht** gebogen. Sie haben aber einen **nicht-konvexen**
Umriss (gestufte Ober-/Unterkante, viele Ecken; inputPts 11–25), sodass die horizontale
Schnittlinie den Umriss an >2 Punkten kreuzt. Der klassische Sutherland-Hodgman verbindet
dann die getrennten Unter-Stuecke zu **einem** self-touching Ring. Verifiziert per
Instrumentierung: bei **15 von 16** kaputten Waenden ist die **Eingangswand sauber** und
erst der Schnitt bricht sie (nur 1 Upstream-Fall). Zusaetzlich: 23/24 Ringe tragen die
Faltungssignatur (≥3 Vertices auf einer Z-Ebene), und die Zahl ist ueber alle
Ausgabe-Staende identisch — Welding/Conforming erzeugen sie also weder noch beheben sie.

**Umgesetzt (Schritt 7a):** der oben beschriebene Faltungs-Schutz — gefaltete Schnitte
werden verworfen, die Wand bleibt valide ungeschnitten. Das behebt 9 der 24 Faelle
(Self-Int 24→15) ohne Formaenderung.

**Noch offen (die verbleibenden 15):** Der saubere Weg ist **kein** Rekonstruieren
fehlender Quelldaten, sondern die zur Schnittebene konkaven Waende beim Schnitt
**korrekt zu trennen** statt zu falten. Die dafuer vorbereitete Mehrstueck-Zerlegung
`splitWallByZ` (CityGmlUtils) trennt die Stuecke sauber, ist aber noch nicht in der
Pipeline aktiv: allein aktiviert legt sie die offene **vertikale Stufen-Flaeche**
zwischen den getrennten Stuecken frei (→ NON_MANIFOLD). Zudem muss ein solcher Schnitt
zur **Geschoss-Bodenplatte** passen, sonst entsteht dort statt 104 ein 303. Offen ist
daher, den Split mit dem Erzeugen der verbindenden Stufen-Flaeche **und** der Platten-
Anpassung zu koppeln. Planaritaet (203/204) und der geerbte Anteil von 601 sind bewusst
nicht adressiert, da sie aus den Eingangsdaten stammen.

> **Hinweis zur Windows-Beta von val3dity 2.6.0b0:** `--report` kann mit `0xC0000409`
> abstuerzen; die Auswertung liest daher die `SUMMARY`-Zeile aus stdout (valide Zahl +
> Fehlercodes). Prueflauf per `scratchpad/validate.ps1 <gml>` (GML → CityJSON → val3dity).

### CityDoctor2: `SE_POLYGON_WITHOUT_SURFACE` ist ein Mapper-Bug im Tool, kein Fehler unsererseits (2026-08-18)

CityDoctor2 meldet bei den semantischen Pruefungen auf **jedem** von uns erzeugten Fenster-,
Tuer- und Balkon-Polygon einen `SE_POLYGON_WITHOUT_SURFACE`-Fehler. Ursache verifiziert per
direktem Lesen des CityDoctor2-Quellcodes (`D:\Tools\citydoctor2-3.18.2`, Version 3.18.2):

- Die Pruefung selbst (`PolygonWithoutSurfaceCheck.java`) meldet jedes Polygon, dessen interne
  `partOfSurface`-Referenz `null` ist.
- Im eigenen CityGML-Parser (`SurfaceMapper.java`, `Citygml3FeatureMapper.java`) wird diese
  Referenz fuer Wand/Dach/Boden/Decke korrekt gesetzt (`p.setPartOfSurface(bs)` fuer jedes
  Polygon einer `BoundarySurface`). Fuer die Geometrie eines `Opening` (Fenster/Tuer, in
  `bldg:opening` eingebettet) wird dieser Aufruf **an keiner Stelle im gesamten Mapper**
  gemacht — die Geometrie wird eingelesen, aber nie mit ihrer Wand verknuepft.
- Fuer `BuildingInstallation` (Balkon, `lod3Geometry`) existiert zwar ein passender zweiter
  Mechanismus (`setPartOfInstallation()`), der auch korrekt aufgerufen wird — nur fragt die
  Pruefung selbst ausschliesslich `getPartOfSurface()` ab, nie `getPartOfInstallation()`.

**Empirisch bestaetigt** (nicht nur am Quellcode): der offizielle, externe FZK-Haus-
Referenzdatensatz des KIT (`CityDoctorModel/src/test/resources/FZK_haus.gml`, 15 MB LoD4,
nicht von uns erzeugt) liefert beim Durchlauf durch die echte CityDoctor2-CLI **14.207**
`SE_POLYGON_WITHOUT_SURFACE`-Fehler. Das ist keine Nische unserer Ausgabe, sondern eine
breite, strukturelle Luecke im citygml4j-3-Mapper von CityDoctor2 (betrifft dort auch
Interieur-/Implicit-Geometrie, nicht nur Oeffnungen/Installationen).

**Schema-Validitaet ist davon unberuehrt:** `citygml-tools validate` (echte XSD-Pruefung)
bleibt durchgehend "valid". `bldg:opening`+`Window`/`Door` und `BuildingInstallation` mit
`lod3Geometry` sind beides Standard-CityGML-1.0-Muster — `SE_POLYGON_WITHOUT_SURFACE` ist
eine CityDoctor-eigene semantische Zusatzpruefung mit einer Luecke, keine Aussage ueber
Schema-Konformitaet.

### val3dity: `601 BUILDINGPARTS_OVERLAP` bei Mehrteil-Gebaeuden ist ein Nef-Erosions-Effekt, kein reales Volumen-Overlap (2026-08-18)

Ergaenzung zum obigen Tooling-Hinweis, jetzt mechanistisch am val3dity-Quellcode verifiziert
(`github.com/tudelft3d/val3dity`, lokal geklont): `CityObject::validate_building()`
(`CityObject.cpp`) ruft fuer jedes Gebaeude mit >1 Solid je LoD (Building + BuildingParts)
`do_primitives_interior_overlap()` (`validate_prim_toporel.cpp`) auf — das baut aus **jedem**
Solid ein CGAL-`Nef_polyhedron` und testet paarweise, ob sich die **Innenraeume** exakt
schneiden. Ohne `--overlap_tol` (unser bisheriger Standardlauf) geschieht das **ohne jede
Tolerenz** — eine hauchduenne numerische Beruehrung an einer zwischen zwei BuildingParts
**geteilten** Wand (unser Standard-Mehrteil-Muster: gemeinsame, xlink-referenzierte
Grenzflaeche statt eigener Waende) reicht der exakten Nef-Arithmetik bereits als
"ueberlappend" — unabhaengig davon, ob irgendwo wirklich Volumen doppelt belegt ist.

`--overlap_tol` existiert bei val3dity genau fuer diesen Fall: bei `overlap_tol > 0` wird
jedes Solid vor dem Test um den angegebenen Betrag nach innen erodiert (`erode_nef_polyhedron`),
was exakt anliegende/gemeinsame Grenzflaechen aus dem Vergleich herausnimmt, echte
Volumen-Ueberlappungen (die viel groesser als ein paar mm/cm sind) aber weiterhin erkennt.

**Verifiziert an `DESNALK0pF001hmm`** (das Gebaeude, das CityDoctor bereits als
ueberlappungsfrei bestaetigt hatte): ohne Toleranz 1× `601`; mit `--overlap_tol 0.01`
(1 cm) **VALID**, 0 Fehler, 100 % valide Primitive. Deckt sich mit dem CityDoctor-Befund
(kein echtes Overlap) und erklaert ihn jetzt mechanistisch statt nur durch Ausschlussverfahren.

**Breiter verifiziert:** die 25 Gebaeude mit den meisten BuildingParts (3–15 Teile je
Gebaeude) aus dem Sachsen-Testdatensatz wurden gezielt extrahiert (`grep` auf
`Part_<ID>_<N>`-Praefixe) und per Bisektion einzeln/gruppenweise mit `--overlap_tol 0.01`
gegengetestet. **13 der 25** liessen sich so konkret verifizieren (u. a. eine 7er- und eine
3er-Gruppe komplett): jedes davon wird mit Toleranz `601`-frei, ohne dass ein anderer
Fehlercode neu auftaucht — durchgehend dieselbe "geteilte-Wand"-Signatur wie bei `hmm`.

**Aber: `--overlap_tol` selbst ist bei uns nicht durchgehend stabil.** Beim vollen
3.801-Gebaeude-Lauf mit `--overlap_tol 0.01` haengt val3dity minutenlang ohne Ergebnis
(21+ min CPU-Zeit, kein Abschluss — abgebrochen). Per Bisektion auf ein einzelnes Gebaeude
eingegrenzt: **`DESNALK0q80047Qg`** (14 BuildingParts) laesst val3dity reproduzierbar mit
`CGAL error: assertion violation! ... Convex_decomposition_3/SM_walls.h:440, wrong handle`
abstuerzen — ein Robustheitsproblem in val3dities eigener CGAL-Nef-Erosion bei sehr
vielteiligen/facettenreichen Solids, keine Aussage ueber unsere Geometrie. Fuer dieses eine
Gebaeude (zeigt ohne Toleranz 1× `601`) laesst sich die Ueberlapp-Hypothese mit dieser
Methode also **nicht** verifizieren; alle anderen 12 direkt getesteten Mehrteil-Gebaeude
bestaetigen sie sauber.

**Praxis-Empfehlung:** val3dity-Laeufe auf Mehrteil-Gebaeuden (bei uns der Normalfall) mit
einer kleinen `--overlap_tol` (1–2 cm) fahren, aber **nur auf handhabbaren Teilmengen**
(Einzelgebaeude oder kleine Batches) — auf der vollen Kachel ist die Option in der
aktuellen val3dity-2.6.0b0-Windows-Beta praktisch nicht nutzbar (haengt/stuerzt ab). Ohne
diese Option ist die reine `601`-Zahl **kein** verlaessliches Qualitaetsmass fuer
Mehrteil-Gebaeude in unserer Pipeline.

### Bugfix: `SE_BS_NOT_GROUND` — Kellerboden-Normale zeigte nicht nach unten (2026-08-19)

CityDoctors `IsGroundCheck` verlangt fuer `GroundSurface`-Polygone eine nach unten zeigende
Normale (`normal.z < 0`). Unser Kellerboden (`BasementGenerator`, `BA_Ground_*`) wurde direkt
aus der promoten Grundpolygon-Punktreihenfolge erzeugt, ohne die Richtung zu erzwingen — bei
mind. einem Gebaeude (`iaq`, neue Kachel) zeigte sie nach oben.

**Fix:** neuer wiederverwendbarer Helfer `CityGmlUtils.orientForNormalZ(ring, wantUpward)`
(Newell-Methode wie bereits `BalconyGenerator.orientUpward`, jetzt zentral), angewendet auf
den Kellerboden-Punktezug vor `createPolygon`.

**Wichtige Nebenwirkung entdeckt (empirisch bestaetigt, kein Bug):** `StoreyGenerator` liest
sein Grundpolygon fuer alle Geschosse eines Gebaeudes ueber `CityGmlUtils.collectGroundPolygons`
direkt aus den Gebaeude-Boundaries — und zwar **nachdem** `BasementGenerator` die
urspruengliche LoD2-GroundSurface bereits durch seine eigene ersetzt hat. Die Umkehrung des
Kellerboden-Umlaufs vererbt sich dadurch automatisch auf die Grundform, aus der alle
Geschossboeden/-decken dieses Gebaeudes abgeleitet werden. Das aendert bei betroffenen
Gebaeuden die Verteilung zwischen `SE_BS_NOT_FLOOR`/`SE_BS_NOT_CEILING` (welche der beiden
Seiten "falsch" ist, kippt), aber nicht deren Summe — siehe unten, Abschnitt zu Boden/Decke-
Normalen (offene Design-Entscheidung).

**Verifiziert:** Einzelgebaeude `iaq` und volle 14-Testgebaeude-Menge (`SE_BS_NOT_GROUND`
1→0, `SE_BS_NOT_FLOOR`/`NOT_CEILING`-Summe unveraendert 114, alle anderen Fehlercodes exakt
identisch); volle 3.801-Gebaeude-Kachel schema-valide, alle Pipeline-Statistiken (Boeden,
Decken, Fenster, Balkone etc.) exakt identisch zum Stand vor dem Fix (reine Punkt-Umordnung,
keine Flaechen-/Zaehlaenderung).

**Offen (siehe `SE_BS_NOT_FLOOR`/`SE_BS_NOT_CEILING`):** Boden und Decke eines Geschosses
teilen sich per XLink dieselbe Geometrie (siehe Schritt 3) und haben daher zwangslaeufig
dieselbe Normalenrichtung — CityDoctor verlangt aber engegengesetzte Richtungen fuer Boden
(oben) und Decke (unten). Nachweis: `LinkedPolygon.calculateNormalNormalized()` in
CityDoctor2 delegiert immer an das Original-Polygon, es gibt keine Unterstuetzung fuer eine
umgekehrte XLink-Orientierung bei einem NACKTEN href — siehe Loesung unten.

### Bugfix: `SE_BS_NOT_FLOOR`/`SE_BS_NOT_CEILING` behoben mit `gml:OrientableSurface` (2026-08-19)

Geloest ohne die XLink-Vorteile (garantiert nahtlos, kleinere Datei) aufzugeben: CityGML 1.0
kennt genau fuer diesen Fall `gml:OrientableSurface orientation="-"` — eine XLink-Referenz mit
umgekehrter Normale, ohne die Geometrie zu duplizieren. Empirisch am CityDoctor2-Report bestaetigt
(nicht nur laut Spezifikation): ein reales Boden/Decke-Paar manuell auf `OrientableSurface`
umgestellt, `SE_BS_NOT_FLOOR` ging exakt fuer dieses eine Polygon von 2 auf 1 zurueck.

**Umsetzung** (zwei Teile, damit deterministisch statt fallabhaengig):
1. Jede **eigenstaendig deklarierte** Decke (StoreyGenerator: normale Geschossdecke UND die
   Mischdach-Decke aus projizierten geneigten Dachflaechen; BasementGenerator: Kellerdecke) wird
   per neuem Helfer `CityGmlUtils.orientForNormalZ(punkte, wantUpward)` (Newell-Methode, wie
   bereits `BalconyGenerator.orientUpward`, jetzt zentral in `CityGmlUtils`) fest auf Normale
   nach UNTEN erzwungen.
2. Jeder Boden ohne eigene Geometrie (XLink auf die vorherige Decke) referenziert sie ueber den
   neuen `CityGmlUtils.createReversedXLinkMultiSurfaceProperty` (`OrientableSurface
   orientation="-"`) statt eines nackten hrefs — dadurch garantiert nach OBEN, unabhaengig von
   der (beliebigen) natuerlichen Umlaufrichtung des geteilten Grundpolygons. Der allererste Boden
   eines Gebaeudes (keine vorherige Decke) wird stattdessen direkt per `orientForNormalZ(...,
   true)` erzwungen.

**Verifiziert:** volle 14-Testgebaeude-Menge — `SE_BS_NOT_FLOOR`/`SE_BS_NOT_CEILING`/
`SE_BS_NOT_GROUND` vollstaendig auf 0 (vorher 71/43/1), alle anderen Fehlercodes exakt
unveraendert (`GE_S_NOT_CLOSED`=6, `SE_POLYGON_WITHOUT_SURFACE`=678, `GE_S_NON_MANIFOLD_EDGE`=3).
Volle 3.801-Gebaeude-Kachel schema-valide.

### Bugfix: Obergeschosse komplett uebersprungen bei Mischdach mit niedrigerem Anbau-Flachdach (2026-08-19)

**Problem** (Nutzer-Fund an Gebaeude `hWM`): bei einem Mischdach-Gebaeude mit einem niedrigeren
Anbau-Flachdach wurden nicht nur (korrekterweise) keine Decken ueber dem Flachdach-Bereich
erzeugt (siehe "Mischdach-Erkennung" oben, funktioniert), sondern ein ganzes oberes Geschoss des
HOEHEREN Hauptteils fehlte komplett — kein Boden, keine Decke, keine Fenster. Sichtbar am
tatsaechlichen 3D-Modell mit ausgeblendetem Dach: 1.OG "komisch geschnitten", 2.OG gar nicht
gebaut, obwohl die Waende dort klar vorhanden sind.

**Ursache:** die Zahl der Geschosse, die UeBERHAUPT Boden/Decke bekommen (`slabStoreys`), wird
durch `slabsTraufeZ` begrenzt — abgeleitet aus `rawMinRoofZ`, dem GLOBALEN Minimum ueber ALLE
RoofSurface-Polygone des Gebaeudes (inkl. kleiner Anbau-Flachdaecher), nicht aus der dominanten
(groessten/Haupt-)Dachflaeche wie `traufeZ` selbst. Ein niedrigeres Anbau-Flachdach zieht dadurch
die Slab-Grenze fuer das GESAMTE Gebaeude auf seine eigene, niedrigere Hoehe herunter — nicht nur
dessen eigene Decke wird uebersprungen (das waere richtig), sondern alle Geschosse DARUEBER
werden komplett aus der Erzeugungsschleife entfernt. Ein bereits vorhandener Korrekturmechanismus
(`slopedRawMinRoofZ` statt `rawMinRoofZ` verwenden) griff nur "nahe Erdgeschoss" (<2m) — bei
`hWM` liegt das Anbau-Flachdach aber 4,7m ueber dem Erdgeschossboden, also ausserhalb dieser
Schwelle.

**Fix:** die Bedingung "nahe Erdgeschoss" aus der bestehenden Korrektur entfernt — sie greift
jetzt immer, wenn ein echtes Mischdach-Missverhaeltnis vorliegt (geneigte Hauptdachflaeche
existiert UND liegt hoeher als das globale Minimum), unabhaengig vom Abstand zum Erdgeschoss.
Nutzt ausschliesslich bereits vorhandene, fuer die dominante Traufe (`traufeZ`) bereits bewaehrte
Werte — kein neuer Algorithmus.

**Verifiziert:** `hWM` isoliert (3 statt 2 Boeden, alle Fenster/Waende jetzt korrekt); betrifft auf
der vollen Kachel deutlich mehr als nur dieses eine Gebaeude (Boeden 6.154→6.454, Decken
4.613→6.668 nach Fix); volle Kachel schema-valide.

### Bugfix: `computeEdgeLimits` verlor Kanten-Zuordnung bei geknickter Wandbasis (2026-08-19)

> **UEBERHOLT (2026-08-20):** siehe Abschnitt "Anbau-Zuschnitt: JTS-basierte Polygon-Differenz" —
> `computeEdgeLimits` existiert nicht mehr, dieser Fix ist mit der Methode zusammen entfallen.

**Problem** (Nutzer-Fund an Gebaeude `gjj`): ein Gebaeude mit zwei unterschiedlichen Flachdaechern
bekam nur 1 statt der erwarteten 2 sichtbaren Geschosse — die Kanten-basierte Hoehengrenze (siehe
"Anbau-Kerben-Entfernung" oben) fiel fuer alle 8 Grundpolygon-Kanten einheitlich auf denselben, zu
niedrigen Wert.

**Ursache:** `computeEdgeLimits` wurde mit den bereits in Geschoss-Stuecke GESCHNITTENEN
Wandsegmenten aufgerufen (Sammlung erfolgte nach der Schnitt-Schleife). Hat eine `WallSurface` im
Original eine "geknickte" Basis (mehr als 2 Punkte auf Bodenhoehe, weil eine Wand mehrere
Grundpolygon-Kanten auf einmal abdeckt), geht dieser innere Knick-Punkt beim Schneiden in die
oberen Geschoss-Stuecke (z.B. `_UF_1_1`, `_UF_2_1`) verloren — nur das ungeschnittene GF-Stueck
behaelt ihn. Die Paar-basierte Kanten-zu-Wand-Zuordnung in `computeEdgeLimits` fand dadurch fuer
die hoeheren Wandstuecke keinen Treffer mehr, sodass nur noch die (niedrigere) GF-Wand pro Kante
matchte.

**Fix:** Schnappschuss der ungeschnittenen Original-Waende (`originalWalls`, per
`CityGmlUtils.collectWallSurfaces(target)`) VOR Beginn der Schnitt-Schleife angelegt und fuer den
`computeEdgeLimits`-Aufruf verwendet statt der Nachher-Sammlung.

**Verifiziert:** `gjj` isoliert (2→3 Geschosse); 14-Testgebaeude-Menge und volle 3.801-Gebaeude-
Kachel schema-valide, val3dity-Fehlerprofil byte-identisch zur Baseline
(102=6/104=5/201=2/204=31/302=38/303=13/306=2/307=8/601=322).

### Bugfix: Isolierte 1-Kanten-Kerbe erzeugte schwebende Stockwerk-Ecke (2026-08-19)

> **UEBERHOLT (2026-08-20):** siehe Abschnitt "Anbau-Zuschnitt: JTS-basierte Polygon-Differenz" —
> `computeActiveSubPolygon` existiert nicht mehr, dieser Fix ist mit der Methode zusammen entfallen.

**Problem** (Nutzer-Fund an Gebaeude `g4s`): ein Geschoss ragte an einer Ecke minimal ueber die
eigentliche Gebaeudekontur hinaus — eine kleine "schwebende" Ecke, die nicht zur Wandgeometrie
passte.

**Ursache:** die Vertex-Entfernungsregel der Kerben-Entfernung (`computeActiveSubPolygon`)
entfernt einen Vertex nur, wenn BEIDE angrenzenden Kanten inaktiv sind (Kante bereits "abgelaufen"
laut Hoehengrenze). Fuer eine isolierte einzelne inaktive Kante (Laenge-1-Lauf, auf beiden Seiten
von aktiven Kanten umgeben) hat aber KEIN Endpunkt zwei inaktive Nachbarkanten — beide Endpunkte
bleiben erhalten, die eigentlich abgelaufene Kante bleibt unveraendert im Ring stehen. Ergebnis:
ein kleiner, ungeschnittener Vorsprung genau an dieser Stelle — exakt das vom Nutzer im Screenshot
markierte Symptom.

**Fix:** zusaetzliche Bedingung in der Ergebnis-Schleife: fuer den Endpunkt eines isolierten
Laenge-1-Laufs (vorige Kante inaktiv, aktuelle Kante aktiv, UND die Kante davor bereits aktiv —
also eindeutig ein isolierter Einzel-Lauf) wird der Endpunkt zusaetzlich verworfen, sodass der Ring
direkt vom Vorgaenger- zum Nachfolger-Vertex durchverbunden wird und die Kerbe vollstaendig
entfaellt.

**Verifiziert:** `g4s` isoliert — Vertex-Zahl der betroffenen `UF_2`-Decke exakt um 1 reduziert
(27→26 Punkte, passend zur erwarteten Wirkung fuer genau einen isolierten Lauf); 14-Testgebaeude-
Menge und volle 3.801-Gebaeude-Kachel schema-valide, val3dity-Fehlerprofil byte-identisch zur
Baseline (102=6/104=5/201=2/204=31/302=38/303=13/306=2/307=8/601=322).

### Bugfix: Dach-Kanten-Abdeckung verfehlte Anbau bei winzigem Wand-Ruecksprung (2026-08-19)

> **UEBERHOLT (2026-08-20):** siehe Abschnitt "Anbau-Zuschnitt: JTS-basierte Polygon-Differenz" —
> `pointNearOrInPolygon2D` existiert nicht mehr, dieser Fix ist mit der Methode zusammen entfallen.
> Trotz dieses Fixes blieb bei `g4s` noch eine schwebende Restflaeche (zweisegmentige Anbau-
> Kontur, die eine einzelne gerade Schliesskante strukturell nicht abbilden kann) — der eigentliche
> Anlass fuer die vollstaendige Ablösung.

**Problem** (Nutzer-Fund an Gebaeude `g4s`, nach dem obigen Fix weiterhin sichtbar): eine
schwebende Deckenflaeche blieb ueber einem Flachdach-Anbau bestehen — aehnlich dem `iaq`-Symptom,
diesmal aber bei genau dem Anbau-Kerben-Entfernung-Fall, den der Mechanismus eigentlich abdecken
soll.

**Ursache:** das Dach des Anbaus (`Face_0003H5B_0_4`, 4 Eckpunkte A-B-C-D) beruehrt den
Grundpolygon-Ring nur an 3 der 4 Ecken (A, B, D) — an der vierten Ecke (C) macht die Wand einen
winzigen ~13-15cm-Ruecksprung, sodass der Ring dort zwei fast deckungsgleiche Zwischenpunkte hat.
Fuer die beiden dadurch entstehenden, sehr kurzen Kanten liegt der Kantenmittelpunkt (der bisher
einzige Abfrage-Punkt fuer die Dach-Abdeckung) hauchduenn (7-21cm) ausserhalb des Dachpolygons —
der strikte `pointInPolygon2D`-Containment-Test verfehlt das knapp. Diese beiden Kanten blieben
dadurch faelschlich "aktiv" (hoch), obwohl sie eindeutig zum Anbau gehoeren — nur die eine echte
isolierte Nachbarkante (siehe vorheriger Fix) wurde erkannt, der Rest des Anbaus nicht.

**Fix:** neuer Helfer `CityGmlUtils.pointNearOrInPolygon2D(px, py, poly, buffer)` — wie
`pointInPolygon2D`, aber zusaetzlich "getroffen" wenn der Punkt bis auf einen Puffer nah am
Polygonrand liegt (kuerzeste Distanz zu jedem Kantensegment). In `computeEdgeLimits` fuer die
Dach-Abdeckungspruefung verwendet, mit derselben Toleranz wie die bestehende Wand-Kanten-Zuordnung
(`XY_EDGE_TOLERANCE`=0,50m) — konsistent mit der bereits etablierten Snapping-Toleranz-Philosophie
des Projekts (kleine Zentimeter-Abweichungen zwischen Wand- und Dachpolygon derselben realen Kante
sind im LoD2-Quelldatensatz keine Seltenheit).

**Verifiziert:** `g4s` isoliert — `UF_2`-Deckenflaeche schrumpft von 212,83 auf 202,10 m² (−10,7 m²,
passend zur Anbau-Dachflaeche von 8,7 m²); 14-Testgebaeude-Menge (84→84 Boeden/131→131 Decken,
unveraendert in der Zaehlung, nur Flaechen kleiner) und volle 3.801-Gebaeude-Kachel schema-valide
(Boeden 6.480→6.477, Decken 6.702→6.699 — kleine, gezielte Reduktion, keine breite Verschiebung);
val3dity-Fehlerprofil weiterhin byte-identisch zur Baseline.

### Ablösung: Anbau-Zuschnitt durch echte 2D-Polygon-Differenz statt Kanten-Matching (JTS, 2026-08-20)

**Warum ein vollstaendiger Umbau statt eines weiteren Patches:** trotz drei aufeinanderfolgender
Fixes an der Kanten-Kerben-Entfernung (oben) fand der Nutzer zwei weitere reale, kaputte Faelle und
bat ausdruecklich um "die wirklich gute, perfekte Loesung" statt eines vierten Patches:

- **`DESNALK0pF001g4s`:** die Anbau-Dachflaeche `Face_0003H5B_0_4` hat 4 Ecken A-B-C-D; nur A, B, D
  liegen auf dem Grundriss-Ring, Eckpunkt C (417950,792 / 5657225,204) NICHT. Die korrekte
  Schliessgrenze braucht also ZWEI Segmente (ueber C), aber `computeActiveSubPolygon` konnte
  strukturell nur EINE gerade Schliesskante zwischen zwei Ring-Vertices erzeugen — sie schnitt
  daher zwangslaeufig durch eine noch stehende Wand oder liess ein Rest-Dreieck uebrig. Beweisbar
  kein Toleranz-Problem, sondern eine strukturelle Grenze des Ein-Segment-Ansatzes.
- **`DESNALK0pF001imo`:** Anbau-Dach bei Z=235,2804, Geschossdecke/-boden bei Z=235,30 — nur 2cm
  Differenz, innerhalb der bestehenden `CUT_TOLERANCE`(5cm)-Toleranz, wurde daher als "noch aktiv"
  behandelt. Volle 120,92 m² Deckenflaeche lag praktisch direkt auf dem Anbau-Dach.

**Neuer Algorithmus** (pro Geschoss-Hoehe z, pro Grundpolygon):

```
excluded(z) = Vereinigung ueber alle RoofSurface-Polygone R:
                  2D-Fussabdruck des Teils von R mit R.z <= z + CUT_TOLERANCE
                  (per CityGmlUtils.splitWallByZ(R, z, tolerance).lower() — dieselbe Methode, die
                   schon den Wandschnitt an einer Z-Ebene beliebig konkav zerlegt, jetzt auf ein
                   Dachpolygon statt eine Wand angewendet)
slab(z)     = Grundriss-Footprint MINUS excluded(z)     [JTS Polygon.difference()]
```

Kein Wand-Hoehen-Matching mehr noetig: ein LoD2-Gebaeude ist ein geschlossenes Solid aus
Ground+Wall+Roof, die Dachflaechen kacheln bereits den gesamten Grundriss — `footprint −
(Dachanteile ≤ z)` IST der tatsaechliche horizontale Querschnitt. `difference` (nicht
`intersection`) gewaehlt: ein fehlendes/falsch klassifiziertes Dach degradiert dann zu "nicht
clippen" (heutiges Alt-Verhalten als Fallback), nicht zu "Decke verschwindet ganz".

**Neue Abhaengigkeit:** `org.locationtech.jts:jts-core:1.20.0` (Maven Central, EDL/EPL-Lizenz, rein
Java, keine eigenen Abhaengigkeiten). Bewusste Abkehr von der urspruenglichen Entscheidung gegen
eine Clipping-Bibliothek (siehe oben, "Warum kein einfacher MAX→MIN-Fix") — die drei aufeinander-
folgenden Patch-Runden haben gezeigt, dass eine Handrollung der 2D-Boolean-Geometrie fuer beliebig
komplexe Anbau-Konturen strukturell nicht robust zu bekommen ist; JTS ist der Industriestandard
fuer genau dieses Problem auf der JVM.

**Neue Helfer in `CityGmlUtils.java`** (Abschnitt "Slab-Zuschnitt bei Anbauten (JTS)", direkt nach
dem `splitWallByZ`/`isRealPiece`-Block, da dieselben Bausteine wiederverwendet werden):
- `SlabPiece` (record): ein Teilstueck einer Geschossflaeche — offener Aussenring + (seltene)
  offene Innenringe (Loecher).
- `clipSlabAtZ(groundPts, roofPolygons, z, tolerance)`: liefert `List<SlabPiece>` auf Hoehe z
  (leer = an dieser Hoehe traegt hier nichts (mehr)). Zwei Fast-Paths erhalten das "0 Overhead im
  Normalfall"-Verhalten: kein Dach mit `minZ <= z+tolerance` → Ring unveraendert; Ausschluss-Union
  schneidet das Grundpolygon nicht → Ring unveraendert (kein JTS-Aufruf noetig).
- `calculateNetArea2D(SlabPiece)`, `createPolygonWithHoles(exterior, interiors)` (wiederverwendet
  `createPolygon`/`createLinearRing`, analog zum bestehenden Fenster-/Tueroeffnungs-Muster).
- Privat: `roofAreaBelowZ`, `toJts`/`toJtsRing` (Punktliste → JTS-Polygon), `toSlabPieces`/
  `toOpenRing` (JTS-Ergebnis → `SlabPiece`s, kleinere Teilstuecke/Loecher < `MIN_SLAB_AREA`=0,5m²
  als Zuschnitt-Artefakte verworfen). JTS-Typen werden im ganzen Block voll qualifiziert
  (`org.locationtech.jts.geom....`), da der Kurzname `Polygon` schon an den citygml4j-Typ gebunden
  ist.

**`StoreyGenerator.java`:** `computeEdgeLimits`, `computeActiveSubPolygon`, die
`XY_EDGE_TOLERANCE`-Konstante und der `originalWalls`-Schnappschuss (nur fuer `computeEdgeLimits`
gebraucht) vollstaendig entfernt. Die Floor/Ceiling-Schleife ruft pro Geschoss × Grundpolygon
`CityGmlUtils.clipSlabAtZ(...)` auf und erzeugt **pro zurueckgegebenem Teilstueck** eine eigene
Floor-/CeilingSurface (Face-/Slab-Id-Suffix `_2`, `_3`, ... nur wenn `pieces.size() > 1` —
Standardfall bleibt id-stabil). Neuer privater Helfer `buildSlabPolygon(SlabPiece, wantUpward)`
kapselt Normalen-Erzwingung (`orientForNormalZ`, unveraendert wiederverwendet — die Methode leitet
die Windung selbst aus der Newell-Normalen ab, ist also unabhaengig von JTS' Ring-Windungs-
Konvention) + Loch-Behandlung (Innenringe bekommen `!wantUpward`, wie bei Fenster-/Tueroeffnungen).

Die Boden↔Decke-XLink-Verkettung (`previousCeilingSlabIds`) wechselt von `Map<Integer,String>` zu
`Map<Integer,List<String>>` (Key bleibt `polyIdx`, Value jetzt eine Liste in Teilstueck-Reihenfolge)
— bei Groessen-Mismatch zur vorherigen Deckenliste (kann nur bei der BA-Decke passieren) bekommt
der Boden inline-Geometrie statt XLink, bleibt trotzdem korrekt. Der `stoppedPolygons`-Fast-Path
entfaellt ersatzlos: der Ausschluss ist monoton in z, "einmal leer bleibt leer" ist damit
automatisch (jeder `clipSlabAtZ`-Aufruf ist ohnehin durch die beiden Fast-Paths oben guenstig).

**Bewusst NICHT angefasst** (Abgrenzung): die Mischdach-Boden-Artefakt-Korrektur (`slabsTraufeZ`/
`slabsAreLimited`, begrenzt WIE VIELE Geschosse ueberhaupt Slabs bekommen, gebaeudeweit) und die
Mischdach-Decke aus `slopedRoofPolygons` (oberstes Geschoss) — beide orthogonal zur Form EINES
Slabs, laufen unveraendert weiter. Ein Hauptdach kann laut `slabsTraufeZ`-Logik ohnehin nur mit der
OBERSTEN Decke kollidieren, die bereits durch einen bestehenden Guard uebersprungen wird — ein Dach,
das mit einer NICHT-obersten Decke kollidiert, ist per Konstruktion ein niedriger Anbau, also genau
das, was jetzt ausgeschnitten wird.

**Weggefallene Sicherheitsnetze — bewusst, nicht vergessen:** der Faltungs-Schutz (self-touching-
Check) und der Laengen-Schutz (`longestRun > n/2`, siehe "Nachtrag" oben) betrafen ausschliesslich
die alte Ein-Segment-Schliesskante; JTS' `difference()` kennt dieses Problem nicht (beliebig
komplexe, auch mehrteilige Ergebnisse sind ein normaler Fall, siehe `SlabPiece`-Liste). Das bedeutet
insbesondere: `DESNALK0pF001iMM` (siehe "Nachtrag" oben, bisher bewusst ungeschnitten belassen, da
sein laengster abgelaufener Lauf mehr als die Haelfte des Rings ausmachte) wird jetzt **tatsaechlich
korrekt geschnitten** statt wie bisher als Kompromiss unveraendert durchgereicht — eine echte,
sichtbare Verbesserung, aber auch die groesste Verhaltensaenderung dieses Umbaus.

**Verifiziert:**
- `imo` isoliert: `UF_1_Ceiling`/`UF_2_Floor` (Z=235,30) 120,92 m² → **95,68 m²** — exakt passend
  zur bereits korrekten Mischdach-Decke (95,68 m²), der Schwellwert-Fall ist behoben.
- `g4s` isoliert: die neue `UF_2`-Deckenkontur enthaelt jetzt **beide** vorher fehlenden Eckpunkte
  (417950,792/5657225,204 und 417949,39/5657228,606) direkt im `posList` — die zweisegmentige
  Anbau-Kontur wird jetzt exakt nachgezeichnet, keine Rest-Flaeche, kein Schnitt durch eine Wand.
- `fYq` (Ursprungsfall 2026-08-12): unveraendert (97,877 m², < 0,001 m² Abweichung zur Baseline vor
  diesem Umbau — reine Gleitkomma-Rauschen aus dem anderen Rechenweg).
- `iMM` (Laengen-Schutz-Regressionsfall): Boden/Decke von Polygon 2 bei UF_1 sinken von 452,04 auf
  449,86 m² (−2,18 m², < 0,5 % — ein kleiner, plausibler echter Zuschnitt, keine grosse Verwerfung);
  **braucht trotzdem eine visuelle Bestaetigung durch den Nutzer**, da eine Flaechenzahl allein die
  architektonische Korrektheit nicht vollstaendig beweist.
- Alle vier Einzelgebaeude zusammen: schema-valide, keine `TopologyException`-Fallbacks im Log.
- 14-Testgebaeude-Menge: 62 Geschosse/1.244 Wandsegmente/84 Boeden/131 Decken — Zahlen unveraendert
  zum Stand vor diesem Umbau; schema-valide.
- Volle 3.801-Gebaeude-Kachel: Geschosse/Wandsegmente exakt unveraendert (6.524/66.875 — die
  Storey-Einteilung selbst wird durch diesen Umbau nicht beruehrt); Boeden 6.477→6.478, Decken
  6.699→6.700 (minimale, plausible Verschiebung); Laufzeit unveraendert (~18s); **val3dity-
  Fehlerprofil byte-identisch zur Baseline** (102=6/104=5/201=2/204=31/302=38/303=13/306=2/307=8/
  601=322) trotz des vollstaendigen Mechanismus-Austauschs; keine `TopologyException`-Fallbacks.

## Geplante Erweiterungen

| Schritt | Funktion | Status |
|---------|----------|--------|
| 1 | LoD2 → LoD3 Geometrie | ✅ Fertig |
| 2 | Keller | ✅ Fertig |
| 3 | Geschosse (Floor/Ceiling/Wandschnitt) | ✅ Fertig |
| 2a | GroundSurface-Ersetzung + TIC | ✅ Fertig |
| 2b | DGM-Reader (ASC + GeoTIFF + ZIP + Mosaik, bilineare Interpolation) | ✅ Fertig |
| 3a | Multi-Piece-Wandschnitt (`splitWallByZ`, Oeffnungswaende in Einzelstuecke) | ✅ Fertig |
| 3b | Flachdach-Erkennung (keine doppelte Decke) | ✅ Fertig |
| 3c | Newell's Method (3D-Flaechenberechnung) | ✅ Fertig |
| 3d | Mischdach-Erkennung (Ceiling nur unter geneigtem Dach) | ✅ Fertig |
| 3e | Flachdach-Fitzelchen (Merge-Limit 4.0m) | ✅ Fertig |
| — | Single-Pass-Pipeline (1× lesen, 5 Schritte in-memory, 1× schreiben) | ✅ Fertig |
| 4 | Tueren (DoorGenerator) | ✅ Fertig |
| 5 | Fenster (WindowGenerator) | ✅ Fertig |
| 5a | Kellerfenster ab Kellerboden (BA floor fix) | ✅ Fertig |
| 5b | BuildingPart-Duplizierung verhindern (coveredByPart) | ✅ Fertig |
| 5c | Dachfenster (RO.window.XXX) | 📋 TODO |
| 3f | Flaechentreue-Fallback (`isFaithfulSplit`, sicherer Schnitt bei Oeffnung+Mischdach → kein 306) | ✅ Fertig |
| 6 | Junction-Conforming (T-Naht-Vertices, streng formneutral, 0 mm bewegt) | ✅ Fertig |
| — | Vertex-Welding ENTFERNT (verschob Vertices ≤5 mm → Aufgabe des Healers) | ✅ Entfernt |
| 7 | Oeffnungs-Kontur-Check (Tuer+Fenster via `openingInsideWall2D`; 206/201 → 0) | ✅ Fertig |
| 8 | Balkone/Terrassen (`BalconyGenerator`) | ✅ Fertig — seit 2026-08-10 in Pipeline verdrahtet, seit 2026-08-11 als `BuildingInstallation`, seit 2026-08-12 zweiphasig um die Fenster herum (Redesign 3: 630→1.075 Balkone); an 14 Testgebäuden + voller 3.801er-Kachel val3dity- und XSD-verifiziert (0 zusätzliche Fehler, schema-valide) |
| 3g | Fliegende Geschossdecke bei BuildingPart-losen Anbauten (siehe [Bugfix](#bugfix-fliegendes-stockwerk-bei-anbauten-ohne-eigenes-buildingpart-2026-08-12)) | ✅ Fertig (2026-08-12) — Anbau-Kerben-Entfernung pro Grundpolygon-Kante, val3dity-neutral verifiziert |
| 5d | Doppelte/gestapelte Kellerfenster-Reihen (`WindowGenerator`, BA hart auf 1 Reihe begrenzt) | ✅ Fertig (2026-08-11) |

### TODO: Dachfenster (Schritt 5c)

Die JSON-Baukörpermodule enthalten bereits RO-Parameter (z.B. `RO.window.WiLen`,
`RO.window.WiHe`, `RO.window.VDistFlWi`, `RO.shape.RiHe` = Firsthöhe).
Die Implementierung ist zurückgestellt und als eigenständiger Schritt (5c) geplant.

Geplante Logik:
- Dachflächen (`RoofSurface`) nach Neigung und Azimut analysieren
- Firsthöhe (`RO.shape.RiHe`) aus JSON, Traufhöhe aus Geometrie bestimmen
- Dachfenster als `Opening`/`Window` auf schrägen Flächen platzieren
- Begrenzung auf maximal zulässigen Flächenanteil (WWR analog zu Wandfenstern)

## Schritt 6: Balkon-Generator (`BalconyGenerator`)

Platziert Balkone **zweiphasig um den `WindowGenerator` herum** (seit "Redesign 3",
2026-08-12 — siehe unten): Phase 1 platziert den führenden `Ga`-Lauf einer Wand unabhängig
von Fenstern (verankert über `HDistWaGa`), Phase 2 platziert restliche `Ga`-Token eines
Musters (z.B. `"GaWiGaWi"`) danach gegen die dann echten Fensterpositionen, verankert über
`HDistWiGa`. Jeder Balkon besteht aus Deck und Brüstung (je eine `BuildingInstallation`,
siehe "Redesign 2" unten) und einer Zugangsöffnung (`DoorSurface`, analog zur
Tür-/Fenster-Öffnungslogik aus Schritt 4/5). **Kompiliert, an den 14 echten Testgebäuden und
an der vollen 3.801-Gebäude-Kachel verifiziert (1.075 Balkone, 0 zusätzliche val3dity-Fehler
gegenüber der Pipeline ohne Balkone, schema-valide per `citygml-tools validate` — siehe
"Redesign 3" unten). Seit 2026-08-10 in `Lod2ToLod3Pipeline` registriert, seit 2026-08-11 als
`BuildingInstallation` statt RoofSurface/WallSurface modelliert, seit 2026-08-12 zweiphasig
um die Fenster herum (Schritte 5a/5b/5c) statt rein nachgelagert.** Die "Offenen Punkte"
unten (nicht datenbelegte Positionierungs-Annahmen) bleiben unabhängig davon bestehen — sie
betreffen die semantische Interpretation einzelner GA-Parameter, nicht die
Geometrie-Validität.

### Datengrundlage

Der Parameterblock `GA` (Gallery) ist in `ModuleParameters.Gallery` bereits vollständig
abgebildet und per PDF-Spezifikation (`Benennung_Parameter_Baukoerpermodule.pdf`,
Tabelle 3/4) verifiziert: `GA` = Balkon, nicht `UT` (= Hausanschlüsse/Versorgungsschächte
— siehe Korrektur in [../LoD3_Konzept.md](../LoD3_Konzept.md), das diesen Fehler noch
enthielt). **`GaPa` und `HDistWiGa` stehen in der PDF-Tabelle gar nicht** — ihre Bedeutung
war nie spec-verifiziert (siehe unten). Von 33 echten Baukörpermodulen haben 12 tatsächlich
befüllte GA-Werte (u.a. `ME3_4`, `ME6`, `ME7_4`, `MR5_4`, `MR6_4`, `MRG3_4`/`4_4`/`7_4`,
`MRO3_4`/`4_4`/`7_4`, `EE3_4`); die restlichen haben den Block, aber alle Felder `null`.

### Geometrie-Prinzip (pro einzelnem Balkon)

```
Draufsicht (eine Wand, ein Balkon):

  Hauswand ─────P1──────────P2───────── Hauswand      P1-P2 liegt auf der Wand,
                │  Tuer      │                          KEINE eigene Flaeche hier
                │            │
                P4───────────P3    ← Deck (BuildingInstallation), GaWid tief

  Seitenansicht:
                          ┌───┐  ← Brueestung (BuildingInstallation), Hoehe GaHe
                Tuer ─┐   │   │
   Hauswand ══════════╪═══╪═══╪══════   ← Deck-Z = Tuerschwelle (5cm ueber Wand-Fusspunkt)
```

Deck und Brüstung werden seit dem Redesign vom 2026-08-11 als **`BuildingInstallation`**
modelliert (siehe "Redesign 2" unten) — sie hängen direkt am Building/BuildingPart über
`outerBuildingInstallation`, unabhängig von `boundedBy`/`lod3Solid`. Ein Ausschluss-Hack
aus der Solid-Hülle ist dadurch nicht mehr nötig: `BuildingInstallation`-Objekte tauchen in
`target.getBoundaries()` gar nicht auf. Die Zugangsöffnung selbst bleibt Teil der Hülle
(schließt das Wandloch), exakt wie eine normale Tür — sie wird weiterhin als `DoorSurface`
modelliert, siehe "Offene Punkte" Punkt 0.

### Entwicklungsgeschichte: drei Fixes nach Sichttests an echten Gebäuden

**Fix 1 (Kollisionsprüfung) und Fix 2 (Eligibilität über `WindowPreference`)** kamen nach
einem Screenshot-Review am 14-Gebäude-Testfall: die Erstversion ("jede lange genug
GF/UF-Wand bekommt unabhängig einen Balkon") erzeugte **77 Balkone**, bis zu **45 an einem
einzigen Gebäude**, und legte Balkontüren über bereits platzierte Fenster. Fix 2 machte
Wände mit `WindowPreference=NONE` (Party-Wall/Nachbarbebauung) balkon-unfähig und
begrenzte zunächst auf einen Balkon pro Geschoss-Tag — dieses "ein Balkon pro Etage" wurde
später durch das musterbasierte Modell (siehe unten) abgelöst.

**Fix 3 (Auswärts-Normale):** Nach dem Fix der Fenster-Kollision zeigte sich an echten
Gebäuden: Balkontür saß korrekt in der Wand, aber Deck/Brüstung fehlten sichtbar — sie
waren von der Gebäudehülle verdeckt. Ursache: die Auswärts-Normale wurde per fester
90-Grad-Rotation der Wand-Unterkante berechnet (dieselbe Formel wie `CityGmlUtils`'
`NORMAL_AZI`); diese Formel liefert nur bei konsistenter Ringwicklung die Außenrichtung,
und genau das ist im Sachsen-LoD2-Quelldatensatz **nicht garantiert** (Original-Wände und
generierte Kellerwände sind unterschiedlich gewickelt, siehe `CityGmlUtils.isExteriorRingCCW`-
Javadoc). Verifiziert an echten Koordinaten (Gebäude `000C6Y7`, Wände 1 und 3): die Formel
zeigte dort exakt 180 Grad falsch — der Schwerpunkt-Check ergab `dot=-7.14` (sollte positiv
sein). Fix: die Kandidaten-Normale wird gegen den groben Gebäude-Schwerpunkt
(`computeFootprintCentroid`) geprüft und bei negativem Skalarprodukt umgedreht, statt der
Wicklung blind zu vertrauen.

### Redesign: GaPa-musterbasierte Fenster-Slot-Ersetzung

Bei der Umsetzung von Fix 2 stellte sich die Frage, ob "ein Balkon pro Etage" wirklich
richtig ist. Direkte Prüfung der PDF-Spezifikation ergab: `GaPa` steht dort gar nicht.
Ein Blick in die echten JSON-Module zeigt aber unzweideutig mehrere `Ga`-Token pro Muster:

```
ME6.json:     GaPa="GaWiGaWiGaWi"   (3 Balkone auf einer Wand)
MRG7_4.json:  GaPa="GaWiGaWiWiGaWi" (3 Balkone)
MR6_4.json:   GaPa="WiGaGaWi"       (2 direkt benachbarte Balkone)
MRO4_4.json:  GaPa="GaWiWiGaWi"     (2 Balkone)
```

"Ein Balkon pro Etage" war zu restriktiv. Statt einer schnellen "zähle `Ga`-Token, platziere
unabhängig"-Korrektur wurde das Modell grundlegend neu gebaut:

1. Pro Wand werden die bereits vom `WindowGenerator` platzierten `WindowSurface`-Öffnungen
   links→rechts sortiert. Das `GaPa`-Muster wird 1:1 mit dieser Liste verzippt: Token `i`
   entscheidet über Fenster Nr. `i`. `"Wi"` → Fenster bleibt; `"Ga"` → wird durch einen
   Balkon ersetzt. Jede eligible Wand wird jetzt unabhängig verarbeitet (wie beim
   `WindowGenerator`) — keine "nur die längste Wand pro Etage"-Gruppierung mehr.
2. Zusammenhängende `Ga`-Läufe (z.B. die beiden mittleren Token in `"WiGaGaWi"`) werden als
   **ein Block** behandelt, nicht unabhängig je auf ihr eigenes Fenster zentriert — das
   würde bei typischen Fensterabständen (~1,85 m) und Balkonbreiten (2,5–5 m) fast immer
   überlappen. Belegt an `MR6_4.json`: `HDistGaGa=0,01` m — praktisch null, zwei Balkone
   stoßen fast nahtlos aneinander. Ein Lauf der Länge `k` wird als Block der Breite
   `k·GaLen+(k-1)·HDistGaGa` platziert, zentriert auf den Mittelpunkt der ersetzten
   Fenstergruppe.
3. Jeder einzelne Balkon eines Laufs wird **einzeln** gegen die echte Wandkontur geprüft
   (nicht der Block als Ganzes) — passt einer nicht (Balkonbreite oft größer als die
   ersetzte Fensterbreite), wird nur dieser übersprungen, die anderen des Laufs werden
   trotzdem versucht.
4. Fenster außerhalb des Laufs, die `"Wi"` bleiben sollen, werden per `HDistWiGa`-
   Mindestabstand vor Überlappung geschützt — sie werden **nicht** entfernt, anders als im
   alten Kollisions-Modell.

### Redesign 2: Deck/Brüstung als `BuildingInstallation` statt RoofSurface/WallSurface (2026-08-11)

Auf Nutzer-Vorgabe hin direkt an der CityGML-1.0-XSD und der citygml4j-3.2.7-API
nachgeprüft (nicht nur aus Dokumentation zitiert):

- `BuildingInstallationType` (Datei `citygml/1.0/building.xsd` im citygml4j-Jar) erweitert
  `AbstractCityObjectType` und hat ausschließlich `class`/`function`/`usage` sowie
  `lod2Geometry`/`lod3Geometry`/`lod4Geometry` (`gml:GeometryPropertyType` — beliebige
  Geometrie). **Kein** `boundedBy`, keine WallSurface/RoofSurface-Zerlegung — das ist eine
  2.0+-Erweiterung, in 1.0 nicht vorhanden. Die Dokumentation der XSD nennt Balkone
  explizit als Beispiel.
- `outerBuildingInstallation` (unbounded) ist ein eigenständiges Element auf
  `AbstractBuildingType`, direkt neben `boundedBy`.
- `AbstractCityObjectType` hat den generischen Attribut-Hook — `BuildingInstallation`
  kann also `gen:stringAttribute` tragen, genau wie `WallSurface`/`RoofSurface`.
- citygml4j-API (per `javap` verifiziert): `AbstractBuilding.getBuildingInstallations()`
  liefert `List<BuildingInstallationProperty>` (schreibt als `outerBuildingInstallation`
  für v1.0-Output). Die Geometrie hängt an
  `buildingInstallation.getDeprecatedProperties().setLod3Geometry(new GeometryProperty<>(...))`
  — in citygml4j als "deprecated" markiert, weil 2.0/3.0 stattdessen `boundedBy` nutzen,
  aber fuer das 1.0-Ziel dieser Pipeline der korrekte Pfad.

**Granularität (Nutzer-Entscheidung):** 1 `BuildingInstallation` fürs Deck (ein Polygon)
und 1 `BuildingInstallation` für die gesamte Brüstung (`gml:MultiSurface` mit den 3
Seiten-Polygonen, FACEAREA = Summe) — statt wie vorher 1 Deck-Fläche + 3 einzelne
Brüstungs-Wandsegmente. Ergebnis: 2 statt 4 Flächen pro Balkon, näher am realen Objekt
"eine Brüstung".

**Bonus-Vereinfachung:** Weil `BuildingInstallation`-Objekte nie in `target.getBoundaries()`
landen, ist der alte `isBalconySurface()`-Ausschluss in `CityGmlUtils.rebuildSolidShell()`
und `collectShellExteriorRings()` (für `conformJunctions`) ersatzlos entfallen — echte
Vereinfachung, nicht nur Umbenennung.

**Verifikation:** Voller Neubau + Lauf gegen die 3.801-Gebäude-Kachel lieferte exakt
dieselben 630 Balkone/626 Wände/630 ersetzte Fenster wie vor dem Umbau (Platzierungslogik
unverändert, nur der Feature-Typ am Ende hat sich geändert). `citygml-tools validate`
bestätigt: **schema-valide** — sowohl ein einzelnes Gebäude (`DESNALK0pF0007iT`, per
`subset` isoliert) als auch die volle Kachel. val3dity-Vergleich mit/ohne Balkone (nach
temporärer, sofort wieder zurückgesetzter Deaktivierung des Balkon-Schritts in der Pipeline
für einen fairen A/B-Test): **exakt identische** 3.427/3.801 valide Buildings (90,2%),
fehlerscharf dieselbe Verteilung; alle 1.260 neuen `BuildingInstallation`-Objekte (630 Deck
+ 630 Brüstung) sind zu 100% valide. 0 zusätzliche Fehler durch den Umbau bestätigt.

### Redesign 3: Zweiphasige, unabhängige Balkon-Platzierung (2026-08-12)

**Root Cause:** Nutzer-Beobachtung, dass 630 Balkone auf 3.801 Gebäude wenig wirkten. Analyse
(temporäre Instrumentierung, wieder entfernt) ergab: von 869 übersprungenen Balkon-Versuchen
waren **619 (71 %) Wände, auf denen der `WindowGenerator` schlicht kein Fenster platziert
hatte** — der alte Ersetzungs-Ansatz (Redesign, siehe oben) kann nur ersetzen, was schon da
ist. **437 dieser 619 Wände waren rein längenmäßig lang genug** für den Balkon ihres Moduls
(z.B. 12,4 m Wand bei `GaLen`=2,8 m). Zusätzlich verifiziert: alle 7 tatsächlich in der Kachel
genutzten GA-Module (`ME3_4`, `ME7_4`, `EE3_4`, `MR5_4`, `MRG3_4`, `MRO3_4`, `MRO7_4`) haben
`Ga` als allererstes `GaPa`-Token und einen echten (nicht-`NaN`) `HDistWaGa`-Wert — ein bisher
komplett ungelesener, aber in der PDF-Spec dokumentierter Anker-Parameter
("Horizontaler Abstand Wand zum Balkon").

**Neues Modell (zwei Phasen, bracket den `WindowGenerator`):**

1. **Phase 1** (`placeLeadingBalconies`, läuft VOR den Fenstern): platziert nur den
   führenden zusammenhängenden `Ga`-Lauf einer Wand, verankert bei `HDistWaGa` ab
   Wandanfang, mit `HDistGaGa`-Abstand innerhalb des Laufs (unverändertes Blocklayout-Prinzip
   aus Redesign 1), geklemmt gegen `HDistMinWaGa` am rechten Wandrand. Schreibt die belegte
   Wandspanne als `GaReservedSpan`-Attribut (inkl. `HDistWiGa`-Puffer auf beiden Seiten, da
   `WindowGenerator` keine Gallery-Parameter kennt).
2. **`WindowGenerator`** (Schritt 5b): unverändert "so viele Fenster wie passen" — die
   generische `extractFreeSections`-Ausschlusslogik (bisher nur für Haustüren) wurde um
   `GaReservedSpan` erweitert; dieselbe Sortier-und-Lücken-Berechnung, keine neue Formel.
3. **Phase 2** (`placeRemainingPatternBalconies`, läuft NACH den Fenstern): nur relevant,
   wenn `GaPa` nach dem führenden Lauf noch weitere `Ga`-Token enthält (in dieser Kachel nur
   `MRO7_4`, `"GaWiGaWi"`, 3 Gebäude) — für 809 von 812 Kandidaten-Gebäuden ein reiner No-Op.
   Verankert **nicht mehr zentriert**, sondern exakt `HDistWiGa` rechts vom letzten
   überlebenden Nachbarfenster (`blockStart = slots[i-1].uHi() + HDistWiGa`) — die alte
   "zentriere auf ersetztes Fenster"-Heuristik ignorierte `HDistGaGa`/`HDistWiGa` faktisch
   (nur nachträgliche Ausschluss-Prüfung, keine Positionierungsvorgabe).

**Verworfene Alternative (ernsthaft geprüft): ein einziger vereinter Layout-Durchlauf**
(Ga- und Wi-Läufe gemeinsam von links nach rechts, jeweils mit fester Element-Anzahl aus dem
Muster-Token-Count). Verworfen, weil das die Fenster-Anzahl von "so viele wie an der echten
Wandlänge passen" auf "exakt die Muster-Token-Anzahl" umstellen müsste (Henne-Ei-Problem: die
Breite eines Wi-Laufs muss feststehen, um den nächsten Ga-Lauf zu positionieren) — riskanter,
da die JSON-Muster vermutlich auf eine Referenz-Wandlänge kalibriert sind und reale Wände
davon abweichen (siehe die 437 langen Wände oben). Der Zwei-Phasen-Ansatz trennt bewusst zwei
verschiedene Größen: Balkon-Position ist echt musterbestimmt (dafür gibt es keine unabhängige
Fit-Formel), Fenster-Anzahl bleibt "so viele wie passen".

**`HDistGaGa` vs. `HDistWiGa` — Abgrenzung (inferiert, nur 1 Datenpunkt je Fall):**
`HDistGaGa` gilt nur INNERHALB eines direkt zusammenhängenden `Ga`-Laufs (belegt an
`MR6_4.json`, `"WiGaGaWi"`, `HDistGaGa`=0,01 m — zwei fast nahtlos aneinanderstoßende
Balkone). Für durch Fenster GETRENNTE Läufe (z.B. `"GaWiWiGa"`) gibt es keine Referenzdaten;
`HDistWiGa` (Abstand zum jeweils nächsten Fenster auf beiden Seiten) ist die plausibelste
Lesart der Parameternamen und wurde so umgesetzt — explizit als Annahme gekennzeichnet.

**Verifikation:**
- 14 Testgebäude: 9 Balkone (vorher 8), 490 Fenster, 0 Fenster ersetzt (alle 9 kamen aus
  Phase 1 — keines der 14 nutzt `MRO7_4`), schema-valide, keine doppelten `gml:id`.
- Volle 3.801-Gebäude-Kachel: **1.075 Balkone** (vorher 630, **+70 %**), 1.075 Wände, nur 5
  Fenster durch Phase 2 ersetzt, 200 übersprungen (vorher 869) — schema-valide, keine
  doppelten `gml:id` (von >370.000 Elementen).
- **`MRO7_4`-Stichprobe** (Muster `"GaWiGaWi"`, 3 Gebäude in der Kachel): gezielt per
  `citygml-tools subset` extrahiert — mehrere Wände bekamen tatsächlich `Ga_1`- UND
  `Ga_2`-Decks. An Gebäude `K09Xf000Fn`, Wand `..._GF_3` koordinatengenau nachgerechnet:
  Reihenfolge auf der Wand ist Balkon 1 → Fenster → Balkon 2 (exakt wie das Muster vorgibt),
  Abstand Fenster→Balkon 2 beträgt **0,75 m** — exakt der `HDistWiGa`-Wert aus `MRO7_4.json`.
  Kein anderes Gebäude dieser 3 bekam an jeder Wand beide Balkone (z.B. `K0pF001gEY`: nur
  Einzel-Balkone) — plausibel, da Phase 2 abhängig von tatsächlich vorhandenen Fenstern ist.
- **val3dity-A/B-Vergleich** (volle Kachel, Balkon-Schritt temporär auskommentiert und
  danach wiederhergestellt, exakt dieselbe Methodik wie bei Redesign 2): ohne Balkone
  3.416/3.801 valide Buildings (89,9 %), mit den neuen 1.075 Balkonen 5.566/5.951 valide
  Features (93,5 %) — **fehlerscharf identische Verteilung** (102=6, 104=5, 201=2, 204=31,
  302=38, 303=13, 306=2, 307=8, 601=299) in beiden Läufen; alle 2.150 neuen
  `BuildingInstallation`-Objekte (1.075 Deck + 1.075 Brüstung) sind zu 100 % valide. **0
  zusätzliche val3dity-Fehler** durch die höhere Balkon-Zahl bestätigt.
- BA-Kellerfenster-Zahl (7.131, siehe Bugfix oben) bleibt exakt unverändert — Regressionscheck
  bestanden, da BA-Wände von Balkonen grundsätzlich ausgeschlossen sind.

### Rauchtest (14 echte Testgebäude, nach allen Fixes + Redesign)

> Dieser Lauf datiert vor "Redesign 3" (zweiphasige Platzierung, siehe oben) — die Zahlen
> hier (8 Balkone) beschreiben noch das reine Ersetzungs-Modell. Aktueller Stand: 9 Balkone,
> siehe "Redesign 3" → Verifikation oben.

| Kennzahl | Wert |
|---|---|
| Balkone gesamt | 8 |
| Wände mit Balkon | 8 |
| Wände übersprungen | 10 (davon 7: Muster will Balkon, Wand hat aber 0 Fenster) |
| Fenster durch Balkon ersetzt | 8 |
| Übersprungen (Türkonflikt) | 0 |
| Übersprungen (Lauf/Balkon zu breit für Wandkontur) | 0 |
| Übersprungen (Mindestabstand `HDistWiGa` zu Nachbarfenster) | 0 |
| Balkone pro Wand (Histogramm) | `{1=8}` |
| Doppelte `gml:id` | 0 (von 4394) |

Alle 8 Balkone dieses Testsets stammen aus dem `ME3`-Modul (`GaPa="GaWiWi"`, nur 1
`Ga`-Token) — kein Gebäude der 14 nutzt eines der Mehrfach-`Ga`-Module (`ME6`, `MRG7_4`,
`MR6_4`, `MRO4_4`). Der Mehrfach-Balkon-Pfad (`HDistGaGa`-Blocklayout) ist an den echten
`MR6_4`-Werten durchgerechnet und verifiziert, aber an DIESEM Testset nicht in Aktion zu
sehen — braucht einen Lauf gegen Gebäude, die eines dieser Module nutzen, um auch am
Endergebnis sichtbar zu werden.

Stichprobe (vor dem Redesign, weiterhin gültig als Beleg für die Fenster-Ersetzungs-
Mechanik): die Wand `Face_000C6Y7_0_3_GF_3` hatte 5 Fenster; genau das am weitesten links
liegende wurde entfernt, die übrigen blieben unverändert erhalten, die neue Balkontür wurde
mit korrektem Innenring ergänzt — kein verwaistes Loch, kein doppelt gezähltes Fenster.

### Serienlauf über die volle 3.801-Gebäude-Kachel + val3dity (2026-08-03)

> Dieser Lauf datiert vor dem BuildingInstallation-Redesign (siehe "Redesign 2" oben) UND vor
> der zweiphasigen Platzierung (siehe "Redesign 3" oben). Die Zeile "Türen = Decks =
> Brüstungen/3" beschreibt noch das alte 4-Flächen-Modell (1 Deck + 3 einzelne
> Brüstungs-Wandsegmente statt heute 1 Deck + 1 Brüstungs-MultiSurface), und die 630 Balkone
> sind seit Redesign 3 auf 1.075 gestiegen (siehe "Redesign 3" → Verifikation für die
> aktuellen Zahlen und den aktuellen val3dity-A/B-Vergleich).

Um sowohl den Mehrfach-Balkon-Pfad an echten Daten zu sehen als auch die val3dity-Lücke zu
schließen, lief die volle Kachel (`LoD2_33_416_5656_2_SN_BuildingPreferences.gml`, 3.801
Gebäude) durch Pipeline + `BalconyGenerator`:

| Kennzahl | Wert |
|---|---|
| Balkone gesamt | 630 |
| Wände mit Balkon | 626 |
| Balkone pro Wand (Histogramm) | `{0=130, 1=622, 2=4}` |
| Übersprungen (Lauf/Balkon zu breit für Wandkontur) | 70 |
| Übersprungen (Mindestabstand `HDistWiGa` zu Nachbarfenster) | 59 |
| Übersprungen (außerhalb Wandkontur, Tür-Check) | 1 |
| Übersprungen (Türkonflikt) | 0 |
| Wände mit Muster, aber ohne vorhandene Fenster | 619 |
| Doppelte `gml:id` | 0 (von 368.716) |
| Türen = Decks = Brüstungen/3 | 630 = 630 = 1890 |

**Mehrfach-Balkon-Pfad bestätigt:** 4 Wände bekamen tatsächlich 2 Balkone (`HDistGaGa`-
Blocklayout) — der bisher nur an `MR6_4.json`-Werten von Hand durchgerechnete Code-Pfad
läuft nachweislich auch an echten Gebäuden. Die neuen Schutzmechanismen (Punkt 3d/7 im
Redesign-Abschnitt oben) greifen ebenfalls sichtbar: 70× "Lauf passt nicht in die
Wandkontur", 59× "Mindestabstand zu Nachbarfenster verletzt" — beide vorher nur theoretisch
begründet, jetzt mit echten Häufigkeiten belegt.

**val3dity (via `citygml-tools to-cityjson` + `val3dity --ignore204`), Vergleich mit/ohne
Balkone auf identischer Kachel:**

| | Ohne Balkone | Mit Balkonen |
|---|---|---|
| Valide Features | 3.389/3.801 (89,2%) | 3.389/3.801 (89,2%) |
| Valide Primitive | 7.457/7.576 (98,4%) | 7.457/7.576 (98,4%) |
| Fehlerverteilung | 102=6, 104=5, 201=2, 302=40, 303=63, 306=2, 307=12, 601=304 | **identisch, Zahl für Zahl** |

Die Balkon-Geometrie erzeugt **null zusätzliche val3dity-Fehler** — jeder invalide Fall
existiert bereits in der Pipeline-Ausgabe ohne Balkone. Erwartbar, da Deck/Brüstung bewusst
aus der `lod3Solid`-Hülle ausgeschlossen sind (siehe "Warum Deck/Brüstung NICHT Teil der
lod3Solid-Hülle sind" oben): val3dity prüft sie gar nicht als Teil der Solid-Validität
(Primitiv-Anzahl in beiden Läufen exakt 7.576). Der 89,2%-Wert liegt nahtlos beim
dokumentierten Baseline von 89,1% (README, Schritt 6).

### Offene Punkte (siehe auch Klassen-Javadoc)

0. **Zugangsöffnung bleibt `DoorSurface`**, obwohl die PDF-Spec die GA-Block-Felder
   `WiLen`/`WiHe`/`HDistWaWi` identisch zu den allgemeinen Fenster-Parametern benennt.
   Gestützt durch `FD.GalleryDoor` in `ModuleParameters.FacadeDetails` — ein eigener
   Material-Slot für genau dieses Element, getrennt von `FD.Window` und `FD.Door` — spricht
   für ein drittes, türartiges Element.
1. **(Seit Redesign 3 überholt)** Phase 1 verankert den führenden Lauf bei `HDistWaGa` ab
   Wandanfang (kein Zentrieren mehr). Phase 2 verankert restliche Läufe bei `HDistWiGa` ab
   dem letzten überlebenden Nachbarfenster. Nur der (in echten Daten nicht erreichte)
   Fallback-Zweig von Phase 2 — kein überlebendes Fenster vor dem Lauf — zentriert weiterhin
   auf die ersetzte Fenstergruppe.
2. `HDistWiGa` hat seit Redesign 3 eine **doppelte Rolle**: (a) Positionierungs-Offset in
   Phase 2 (`blockStart = letztes Fenster.uHi + HDistWiGa`) und im `GaReservedSpan`-Puffer
   von Phase 1, (b) weiterhin Mindestabstand-Prüfung (`hasWindowClearanceConflict`) gegen
   Nachbarfenster außerhalb des jeweiligen Laufs. Beide Rollen nutzen denselben Wert — echte
   Werte 0,1–1,5 m passen zu beidem.
3. `DistWiGa` = Versatz der Zugangsöffnung vom linken Rand des jeweils eigenen
   Balkon-Fußabdrucks, geklemmt (echte Werte 0,0–0,8 m, immer deutlich unter `GaLen`
   2,5–3,7 m).
4. Fehlt `GaPa` (aber `GaLen`/`GaWid` gültig): Fallback bleibt 1 Token (`"Ga"`), platziert
   dadurch deterministisch über Phase 1 bei `HDistWaGa` ab Wandanfang.
5. **(Seit Redesign 3 überholt)** `HDistWaGa`/`HDistMinWaGa` werden jetzt wieder gelesen
   (Phase 1: Anker bzw. Rand-Klemmung) — anders als in Redesign 1 beschrieben, wo sie
   bewusst ungenutzt blieben. `HDistWaWi` (GA-Block-Feld, nicht zu verwechseln mit dem
   gleichnamigen Fenster-Parameter) bleibt weiterhin ungelesen.
6. **(Seit Redesign 3 überholt für den führenden Lauf)** Phase 1 braucht kein einziges
   bestehendes Fenster mehr — das war genau der Grund für Redesign 3 (619 von 869 Skips
   waren fensterlose, aber lang genug Wände). Gilt weiterhin für Phase 2: ein restlicher
   `Ga`-Lauf ohne überlebendes Fenster in der jeweiligen Position bleibt unerfüllt
   (`galleryTokensUnfulfilled`).
7. Keine Deckendicke (kein GA-Parameter dafür vorhanden) — Deck ist eine einzelne Fläche,
   wie auch Floor/CeilingSurface keine eigene Dicke haben.
8. `HDistGaGa` gilt nur innerhalb eines direkt zusammenhängenden `Ga`-Laufs; für durch
   Fenster getrennte Läufe (z.B. `"GaWiWiGa"`) bestimmt stattdessen `HDistWiGa` den Abstand
   — beide Interpretationen an je nur 1 realem Datenpunkt verifizierbar (siehe Redesign 3).

~~9. Kein val3dity-Lauf, keine Serienverarbeitung über alle 3801 Gebäude.~~ — erledigt,
siehe "Redesign 3" → Verifikation oben: 1.075 Balkone, 0 zusätzliche val3dity-Fehler
gegenüber der Pipeline ohne Balkone.

## Beispiel-Ausgabe (Pipeline)

```
============================================================
  LoD2 -> LoD3 Konvertierungs-Pipeline (Single-Pass)
============================================================
Input:  D:\...\LoD2_33_416_5656_2_SN_BuildingPreferences.gml
JSON:   D:\...\Baukoerpermodule_json
Output: D:\...\output
DGM:    D:\...\DGM\Dresden
BoundedBy-Envelope uebernommen

============================================================
                  Pipeline abgeschlossen
============================================================
Verarbeitungszeit: 20.115 s
Ausgabedatei: D:\...\output\LoD3_33_416_5656_2_SN.gml

Schritt 1 — Promotion:  3801 Gebaeude, 67079 Geometrien hochgestuft, 175676 Namen
Schritt 2 — Keller:     2148 Keller, 2171 GS ersetzt, 2148 TICs
Schritt 3 — Geschosse:  5719 Geschosse, 59287 Wandsegmente, 5768 Boeden, 6642 Decken
Schritt 4 — Tueren:     1340 Tueren, 1321 Waende modifiziert, 1181 uebersprungen
Schritt 5 — Fenster:    44655 Fenster, 20448 Waende, 65659 uebersprungen, 360 Giebel-Drops, 17 WWR-Warn
Schritt 5 — Skip:       WP0/null=15788, coveredByPart=5752, tooShort=32351, tooLow=8974, noFit=372, noParams=1813
```

Dieser Log stammt aus einem Lauf VOR der Balkon-Integration (mit DGM, siehe DGM-Zeile
oben) und dient hier weiterhin als Referenz für Schritte 1–5. **Verifikation der
Balkon-Integration (2026-08-10, gleicher Eingabedatensatz, ohne DGM):** Die Pipeline
druckt seither zusätzlich eine `Schritt 6 — Balkone`-Zeile —

```
Schritt 6 — Balkone:    630 Balkone, 626 Waende, 630 Fenster ersetzt, 869 uebersprungen
```

— exakt identisch zu den 630 Balkonen (626 Wände, 630 ersetzte Fenster) aus dem
Standalone-Lauf oben ("Serienlauf über die volle 3.801-Gebäude-Kachel + val3dity"). Die
Ausgabedatei enthält dazu 630 `LOD3_BalconyDoor`, 630 `LOD3_BalconyDeck` und 630
`LOD3_BalconyRailing`-`BuildingInstallation`-Objekte (je 1 pro Balkon, die Brüstung selbst
als `gml:MultiSurface` aus 3 Polygonen; Zahlen per `grep -c` gegenprüft) — die Verdrahtung
reproduziert die Standalone-Ergebnisse 1:1, keine Verhaltensänderung durch die Integration.

> **Stand nach Redesign 3 (2026-08-12):** Die Zeile heißt jetzt `Schritt 5a+5c — Balkone`
> (Balkone laufen zweiphasig um `Schritt 5b — Fenster` herum, siehe "Redesign 3" oben) und
> zeigt auf derselben Kachel `1075 Balkone, 1075 Waende, 5 Fenster ersetzt, 200
> uebersprungen`. Das Grundformat der Log-Zeile ist unverändert.

## Anforderungen

- Java 21+
- Maven 3.6+
- citygml4j 3.2.7 (wird automatisch heruntergeladen)

## Build

```bash
mvn clean package
```

Erzeugt drei JAR-Dateien im `target/` Verzeichnis.

---

## Ideen & Verbesserungspotenzial

### CityGML-Versions-Upgrade

Die Pipeline schreibt aktuell **CityGML 1.0** (`http://www.opengis.net/citygml/1.0`).
citygml4j 3.2.7 unterstützt aber drei Versionen:

```java
public enum CityGMLVersion {
    v1_0,   // CityGML 1.0 (aktuell verwendet)
    v2_0,   // CityGML 2.0
    v3_0    // CityGML 3.0
}
```

Die Bibliothek nutzt intern ein **CityGML-3.0-natives Objektmodell** — die Java-API ist 
versions-agnostisch. Der `CityGMLVersion`-Enum steuert nur die XML-Serialisierung.
Das bedeutet: der gleiche Java-Code kann wahlweise CityGML 1.0, 2.0 oder 3.0 schreiben.

---

#### Option A: Upgrade auf CityGML 2.0

**Aufwand: Minimal (3 Zeilen ändern)**

In genau 2 Dateien muss `CityGMLVersion.v1_0` durch `CityGMLVersion.v2_0` ersetzt werden:

| Datei | Beschreibung |
|-------|-------------|
| `CityGmlUtils.java` (processGmlFile) | Zentrale GML-Schreiblogik fuer alle Standalone-Generatoren |
| `Lod2ToLod3Pipeline.java` | Pipeline-Modus (Single-Pass) |

> **Hinweis:** Seit dem Refactoring nutzen alle Generatoren die gemeinsame Methode
> `CityGmlUtils.processGmlFile()` zum Lesen/Schreiben — daher genuegt eine einzige
> Aenderung fuer alle Standalone-Modi.

**Was passiert automatisch (ohne Codeänderung):**
- Alle Namespaces werden auf `*/2.0` umgestellt (`building/2.0`, `generics/2.0`, etc.)
- XML-Prefixes (`bldg:`, `gen:`, `gml:`) bleiben gleich
- LoD-Geometrie (`lod2MultiSurface`, `lod3MultiSurface`) bleibt identisch
- Generic Attributes (`StringAttribute`) funktionieren identisch  
- TerrainIntersectionCurve, Solid, MultiSurface — alles identisch
- `.withDefaultPrefixes()` wählt automatisch die richtigen Namespace-URIs

**Risiko:** Sehr gering — citygml4j abstrahiert alle Unterschiede.

##### Mehrwert von CityGML 2.0 gegenüber 1.0

CityGML 2.0 (OGC 12-019, veröffentlicht 2012) ist **keine reine Namespace-Umbenennung**,
sondern bringt einige konkrete Neuerungen, die für unseren LoD2→LoD3-Anwendungsfall
relevant sind:

**1. `relativeToTerrain` — Lage zum Gelände (hoch relevant!)**

CityGML 2.0 führt das standardisierte Attribut `relativeToTerrain` auf jedem `_CityObject`
ein. Dieses Attribut existiert in CityGML 1.0 **nicht**. Die möglichen Werte sind:

| Wert | Bedeutung |
|------|-----------|
| `entirelyAboveTerrain` | Gebäude liegt vollständig über Gelände |
| `substantiallyAboveTerrain` | Gebäude liegt im Wesentlichen über Gelände |
| `substantiallyAboveAndBelowTerrain` | Gebäude hat wesentliche Teile ober- und unterhalb |
| `substantiallyBelowTerrain` | Gebäude liegt im Wesentlichen unter Gelände |
| `entirelyBelowTerrain` | Gebäude liegt vollständig unter Gelände |

**Relevanz für unsere Pipeline:** Der `BasementGenerator` weiß bereits, welche Gebäude
ein Kellergeschoss erhalten. Diese Information könnte automatisch als `relativeToTerrain`
gesetzt werden:
- Gebäude **mit** Keller → `substantiallyAboveAndBelowTerrain`
- Gebäude **ohne** Keller → `entirelyAboveTerrain` (bzw. `substantiallyAboveTerrain`)

In CityGML 1.0 kann diese Information nur über GenericAttributes abgebildet werden — in
CityGML 2.0 ist sie standardisiert und damit für alle Downstream-Systeme (FME, 3DCityDB,
QGIS, etc.) ohne Sonderkonfiguration lesbar.

```java
// citygml4j API (bereits verfügbar):
import org.citygml4j.core.model.core.RelativeToTerrain;

building.setRelativeToTerrain(RelativeToTerrain.SUBSTANTIALLY_ABOVE_AND_BELOW_TERRAIN);
```

**Analog existiert `relativeToWater`** (Lage zum Gewässer) mit denselben Abstufungen.
Für den Starkregenzwilling potenziell interessant, falls Überflutungsszenarien
kategorisiert werden sollen.

**2. `OuterFloorSurface` / `OuterCeilingSurface` (relevant für Balkone/Überhänge)**

CityGML 1.0 kennt nur `FloorSurface` und `CeilingSurface` — beides sind implizit
**Innen**flächen (Geschossböden und -decken). CityGML 2.0 ergänzt:
- **`OuterFloorSurface`** — sichtbare Oberseite eines nach außen ragenden Bauteils
  (z.B. die begehbare Oberfläche eines Balkons, Terrasse auf Flachdach)
- **`OuterCeilingSurface`** — sichtbare Unterseite eines Überstands
  (z.B. Unterseite eines Balkons von unten betrachtet, Vordach-Unterseite)

**Relevanz:** Wenn die Pipeline in Zukunft um Balkone, Terrassen oder Vordächer erweitert
wird, stellt CityGML 2.0 die korrekten Oberflächentypen bereit. In CityGML 1.0 müssten
solche Flächen als generische `WallSurface` oder `RoofSurface` approximiert werden.

```java
// citygml4j API (bereits verfügbar):
import org.citygml4j.core.model.construction.OuterFloorSurface;
import org.citygml4j.core.model.construction.OuterCeilingSurface;

// Balkonflächen korrekt modellieren:
OuterFloorSurface balkonOben = new OuterFloorSurface();  // begehbare Fläche
OuterCeilingSurface balkonUnten = new OuterCeilingSurface();  // Unterseite
```

**3. `GenericAttributeSet` — Gruppierung von Attributen**

CityGML 1.0 erlaubt nur flache, einzelne GenericAttributes (`StringAttribute`,
`DoubleAttribute`, etc.) auf einem CityObject. CityGML 2.0 ergänzt `GenericAttributeSet`,
mit dem man zusammengehörige Attribute in einer benannten Gruppe bündeln kann.

**Relevanz:** Der `StoreyGenerator` erzeugt aktuell mehrere flache GenericAttributes
pro Geschoss (z.B. Geschosshöhe, Geschossnummer, Nutzung). Mit `GenericAttributeSet`
könnten diese sinnvoll gruppiert werden:

```xml
<!-- CityGML 1.0 (aktuell): flache Attribute -->
<gen:stringAttribute name="Geschoss_1_Nutzung">
  <gen:value>Wohnen</gen:value>
</gen:stringAttribute>
<gen:doubleAttribute name="Geschoss_1_Hoehe">
  <gen:value>2.8</gen:value>
</gen:doubleAttribute>

<!-- CityGML 2.0: gruppierte Attribute (besser strukturiert) -->
<gen:genericAttributeSet name="Geschoss_1">
  <gen:stringAttribute name="Nutzung">
    <gen:value>Wohnen</gen:value>
  </gen:stringAttribute>
  <gen:doubleAttribute name="Hoehe">
    <gen:value>2.8</gen:value>
  </gen:doubleAttribute>
</gen:genericAttributeSet>
```

```java
// citygml4j API:
GenericAttributeSet geschossSet = new GenericAttributeSet("Geschoss_1", List.of(
    new AbstractGenericAttributeProperty(new StringAttribute("Nutzung", "Wohnen")),
    new AbstractGenericAttributeProperty(new DoubleAttribute("Hoehe", 2.8))
));
```

**4. Bridge- und Tunnel-Modul (nicht relevant)**

CityGML 2.0 ergänzt eigenständige Module für Brücken und Tunnel. Für die LoD2→LoD3-
Gebäude-Pipeline sind diese nicht relevant.

##### Zusammenfassung CityGML 2.0 Mehrwert

| Feature | CityGML 1.0 | CityGML 2.0 | Relevanz für Pipeline |
|---------|-------------|-------------|----------------------|
| `relativeToTerrain` | ❌ nicht vorhanden | ✅ standardisiert | **Hoch** — Keller-Info standardisiert |
| `relativeToWater` | ❌ nicht vorhanden | ✅ standardisiert | Mittel — Starkregenbezug |
| `OuterFloorSurface` | ❌ nicht vorhanden | ✅ eigener Typ | Mittel — für Balkone/Terrassen |
| `OuterCeilingSurface` | ❌ nicht vorhanden | ✅ eigener Typ | Mittel — für Vordächer/Überhänge |
| `GenericAttributeSet` | ❌ nur flache Attribute | ✅ gruppierbar | Niedrig — sauberer aber optional |
| Bridge/Tunnel | ❌ nicht vorhanden | ✅ eigene Module | Keine — nicht im Scope |
| LoD-Geometrie | LoD 0–4 | LoD 0–4 | Identisch |
| BoundarySurfaces | Wall/Roof/Ground/Floor/Ceiling/Interior/Closure | + OuterFloor/OuterCeiling | Erweiterung |

**Fazit CityGML 2.0:** Entgegen dem ersten Eindruck bietet CityGML 2.0 durchaus
**konkreten Mehrwert** für unseren Anwendungsfall:

1. **`relativeToTerrain`** ist die wichtigste Neuerung — sie erlaubt es, die ohnehin
   vorhandene Keller-Information standardisiert zu kodieren, ohne auf GenericAttributes
   zurückzugreifen. Für den Starkregenzwilling ist die Frage "liegt das Gebäude
   wesentlich unter Gelände?" unmittelbar relevant.
2. **`OuterFloorSurface`/`OuterCeilingSurface`** werden relevant, sobald Balkone und
   Vordächer modelliert werden.
3. Der **Migrationsaufwand bleibt bei 3 Zeilen** — und die Nutzung der neuen Features
   kann schrittweise erfolgen (z.B. erst nur `relativeToTerrain` setzen, später
   OuterFloor/OuterCeiling bei Balkon-Erweiterung).

**Empfehlung:** CityGML 2.0 als Zwischenschritt ist sinnvoll, wenn man kurzfristig
`relativeToTerrain` nutzen möchte, ohne direkt auf CityGML 3.0 umzusteigen. Der Aufwand
ist minimal, der Mehrwert für Downstream-Systeme und Interoperabilität real.

---

#### Option B: Upgrade auf CityGML 3.0

**Aufwand: 3 Zeilen für die Minimalversion, aber CityGML 3.0 bietet erhebliches
Verbesserungspotenzial das ggf. mit umgesetzt werden sollte.**

##### Minimales Upgrade (3 Zeilen)

Wie bei 2.0 — in denselben 3 Dateien `v1_0` durch `v3_0` ersetzen. Das funktioniert,
weil citygml4j intern bereits das CityGML-3.0-Objektmodell verwendet und beim Lesen von
CityGML-1.0-Dateien automatisch auf das 3.0-Modell mappt.

**Was passiert automatisch:**
- Namespaces auf `*/3.0` umgestellt
- LoD-Konzept: CityGML 3.0 kennt nur noch **LoD 0–3** (LoD4 entfallen!)
  - `lod2MultiSurface` / `lod3MultiSurface` → bleiben erhalten ✅
  - `lod2Solid` / `lod3Solid` → bleiben erhalten ✅  
  - `lod3TerrainIntersectionCurve` → bleibt erhalten ✅
- Generic Attributes → funktionieren identisch ✅
- BuildingPart → bleibt erhalten (CityGML 3.0 hat weiterhin `consistsOfBuildingPart`) ✅
- XLinks → funktionieren identisch ✅

**Was in CityGML 3.0 deprecated/entfallen ist:**
- **LoD4** ist komplett entfallen — stattdessen wird Innenraum über `BuildingRoom` modelliert
- Die alte `Opening`-Klasse (CityGML 1.0/2.0: `bldg:opening` → `bldg:Window`/`bldg:Door`)
  wurde durch das **FillingSurface/FillingElement-Konzept** ersetzt (siehe unten)
- `lod4Solid`, `lod4MultiSurface`, `lod4MultiCurve` → nur noch über `DeprecatedProperties`

##### Direkt von 1.0 auf 3.0?

**Ja, ohne Umweg über 2.0.** citygml4j mappt intern direkt von jedem Format auf das
3.0-Modell. Der Zwischenschritt über 2.0 wäre unnötig und bringt keinen Vorteil.
Empfehlung: Direkt auf 3.0 gehen, wenn man upgradet.

---

### Neue Möglichkeiten mit CityGML 3.0

CityGML 3.0 hat das Building-Modul grundlegend umstrukturiert. Das eröffnet für
die Pipeline erhebliches Verbesserungspotenzial:

#### 1. Storey (Geschosse als First-Class-Objekte)

**Aktuell (CityGML 1.0):** Geschosse werden als `FloorSurface`/`CeilingSurface` mit
Generic-Attributen (`Geschoss=EG`, `Geschoss=1.OG`) repräsentiert. Es gibt kein natives
`BuildingStorey`-Element.

**CityGML 3.0:** Das `Storey`-Objekt ist ein vollwertiges Element im Building-Modul:

```java
// citygml4j API (bereits verfügbar in 3.2.7!)
import org.citygml4j.core.model.building.Storey;
import org.citygml4j.core.model.building.StoreyProperty;

Storey storey = new Storey();
storey.setId("Building_123_EG");
storey.setSortKey(0.0);                    // Sortierung: UG=-1, EG=0, 1.OG=1, ...
storey.setClassifier(new Code("EG"));      // Geschoss-Bezeichnung

// Geschoss kennt eigene Grenzen (Floor, Ceiling, Wände)
storey.addBoundary(new AbstractSpaceBoundaryProperty(floorSurface));
storey.addBoundary(new AbstractSpaceBoundaryProperty(ceilingSurface));
storey.addBoundary(new AbstractSpaceBoundaryProperty(wallSegment));

// Geschoss kann BuildingInstallations enthalten
storey.getBuildingInstallations().add(new BuildingInstallationProperty(treppe));

// Geschoss dem Gebäude zuordnen
building.getBuildingSubdivisions().add(new AbstractBuildingSubdivisionProperty(storey));
```

**Vorteile:**
- Geschosse sind als eigene Objekte abfragbar (z.B. "zeige mir alle EG-Grundrisse")
- `sortKey` ermöglicht automatische Sortierung der Geschosse
- Jedes Geschoss kann eigene Geometrie, Installationen und Räume haben
- Kein Workaround über Generic Attributes mehr nötig

**Aufwand:** ca. 1–2 Tage im `StoreyGenerator` — statt `FloorSurface`+`CeilingSurface`
mit `Geschoss`-Attribut werden echte `Storey`-Objekte erzeugt und über
`buildingSubdivisions` dem Gebäude zugeordnet.

#### 2. BuildingRoom (Raum-Modellierung)

**Aktuell:** Nicht vorhanden.

**CityGML 3.0:** Räume können als eigenständige Objekte modelliert werden:

```java
import org.citygml4j.core.model.building.BuildingRoom;
import org.citygml4j.core.model.building.BuildingRoomProperty;
import org.citygml4j.core.model.building.RoomHeight;

BuildingRoom room = new BuildingRoom();
room.setId("Building_123_EG_Room_1");
room.setClassifier(new Code("habitation"));
room.getRoomHeights().add(new RoomHeightProperty(
    new RoomHeight(/* status, lowReference, highReference, value */)
));

// Raum hat eigene Grenzen
room.addBoundary(floorSurface);
room.addBoundary(ceilingSurface);
room.addBoundary(interiorWallSurface);  // Neu in CityGML 3.0!
room.addBoundary(doorSurface);          // Tür als Raumgrenze

// Raum dem Geschoss zuordnen
storey.getBuildingRooms().add(new BuildingRoomProperty(room));
```

**Nutzen:** Relevant wenn Innenraum-Modellierung gewünscht ist (z.B. für
Energiesimulation, Facility Management). Der `ModuleParameters`-Loader hat bereits
einen `Interior`-Abschnitt in den JSON-Dateien.

**Aufwand:** ca. 3–5 Tage als neuer Pipeline-Schritt.

#### 3. InteriorWallSurface (Innenwände)

**Aktuell:** Nicht vorhanden.

**CityGML 3.0:** Innenwände sind ein eigener Oberflächentyp:

```java
import org.citygml4j.core.model.construction.InteriorWallSurface;

InteriorWallSurface interiorWall = new InteriorWallSurface();
interiorWall.setLod3MultiSurface(multiSurfaceProperty);
```

**Nutzen:** Innenwände unterteilen Räume und sind für Starkregensimulation relevant
(Strömungspfade im Gebäude).

#### 4. Fenster & Türen (FillingSurface-Konzept)

In CityGML 1.0/2.0 werden Fenster und Türen als `Opening`-Elemente modelliert, die
direkt an der `WallSurface` hängen. CityGML 3.0 führt ein neues Konzept ein:

**CityGML 1.0/2.0 (alt):**
```xml
<bldg:WallSurface>
  <bldg:opening>
    <bldg:Window gml:id="win_1">
      <bldg:lod3MultiSurface>...</bldg:lod3MultiSurface>
    </bldg:Window>
  </bldg:opening>
</bldg:WallSurface>
```

**CityGML 3.0 (neu) — zwei Ebenen:**

1. **FillingElement** (`Door`, `Window`) — das physische Objekt (kann z.B. Attribute
   wie Material, U-Wert haben):
```java
import org.citygml4j.core.model.construction.Window;
import org.citygml4j.core.model.construction.Door;

// Window/Door sind FillingElements (physische Objekte)
Window window = new Window();
window.setId("Building_123_Win_1");
window.setClassifier(new Code("isolierverglasung"));

Door door = new Door();
door.setId("Building_123_Door_1");
door.getAddresses().add(addressProperty);  // Tür kann Adresse haben!
```

2. **FillingSurface** (`WindowSurface`, `DoorSurface`) — die geometrische Grenze
   einer ConstructionSurface (Wand):
```java
import org.citygml4j.core.model.construction.WindowSurface;
import org.citygml4j.core.model.construction.DoorSurface;

// WindowSurface/DoorSurface sind FillingSurfaces (Loch in der Wand)
WindowSurface winSurface = new WindowSurface();
winSurface.setLod3MultiSurface(windowGeometry);

// FillingSurfaces werden der WallSurface zugeordnet
wallSurface.getFillingSurfaces().add(
    new AbstractFillingSurfaceProperty(winSurface)
);
```

**Klassenhierarchie in citygml4j 3.2.7:**
```
AbstractConstructionSurface (WallSurface, RoofSurface, GroundSurface, ...)
  └─ getFillingSurfaces() → List<AbstractFillingSurfaceProperty>
       ├─ WindowSurface   (Loch in der Wand → Fentergeometrie)
       └─ DoorSurface     (Loch in der Wand → Türgeometrie)

AbstractFillingElement (extends AbstractOccupiedSpace)
  ├─ Window   (physisches Fenster-Objekt mit Attributen)
  └─ Door     (physische Tür mit Adresse, Attributen)
```

**Nutzen für die Pipeline:**
- Schritt 4 (Fenster) und Schritt 5 (Türen) sollten direkt mit dem CityGML-3.0-Konzept
  implementiert werden → `WindowSurface` an `WallSurface.getFillingSurfaces()` hängen
- Bei CityGML 1.0/2.0-Ausgabe mappt citygml4j automatisch zurück auf das alte
  `Opening`-Konzept
- Vorteil: Ein Code, der alle Versionen bedient

**Aufwand:** Kein Mehraufwand bei der Implementierung von Schritt 4/5 — die citygml4j-API
ist ohnehin CityGML-3.0-nativ.

#### 5. BuildingConstructiveElement

**CityGML 3.0 exklusiv:** Konstruktive Elemente wie Stützen, Träger, Fundamente:

```java
import org.citygml4j.core.model.building.BuildingConstructiveElement;

BuildingConstructiveElement stuetze = new BuildingConstructiveElement();
stuetze.setClassifier(new Code("column"));
stuetze.setLod3MultiSurface(columnGeometry);
building.getBuildingConstructiveElements().add(
    new BuildingConstructiveElementProperty(stuetze)
);
```

**Nutzen:** Relevant für detaillierte Gebäudemodelle (BIM-Integration).

#### 6. Elevation (Höhenbezugspunkte)

**CityGML 3.0 exklusiv:** Gebäude und Geschosse können explizite Höhenbezugspunkte haben:

```java
import org.citygml4j.core.model.construction.Elevation;

// Höhenbezugspunkte am Gebäude (z.B. für H_DGM)
Elevation elev = new Elevation();
elev.setElevationReference(new Code("lowestGroundPoint"));
elev.setElevationValue(new DirectPosition(List.of(113.88)));
building.getElevations().add(new ElevationProperty(elev));
```

**Nutzen:** Der `BasementGenerator` berechnet bereits `H_DGM` (Geländehöhe am Gebäude).
Mit CityGML 3.0 könnte diese Information als standardisiertes `Elevation`-Objekt statt
als Generic Attribute gespeichert werden.

#### 7. storeyHeightsAboveGround / storeyHeightsBelowGround

**CityGML 3.0 (auch 2.0):** Gebäude können die Höhen der einzelnen Geschosse als 
geordnete Liste speichern:

```java
// Geschosshöhen als MeasureOrNilReasonList
MeasureOrNilReasonList heights = new MeasureOrNilReasonList();
heights.setValue(List.of("2.8", "2.6", "2.6", "2.4"));  // EG, 1.OG, 2.OG, DG
building.setStoreyHeightsAboveGround(heights);

MeasureOrNilReasonList belowHeights = new MeasureOrNilReasonList();
belowHeights.setValue(List.of("2.5"));  // Keller
building.setStoreyHeightsBelowGround(belowHeights);
```

**Nutzen:** Die Pipeline berechnet diese Werte bereits aus den Baukörpermodulen
(`GF.roomHeight`, `BA.height`). Aktuell werden sie nur intern verwendet — mit CityGML 3.0
könnten sie standardisiert am Gebäude-Objekt gespeichert werden.

**Aufwand:** ca. 0,5 Tage — die Werte existieren bereits, müssen nur als Properties
gesetzt werden.

---

### Upgrade-Empfehlung

| Pfad | Aufwand | Mehrwert |
|------|---------|----------|
| **1.0 → 2.0** | 5 Minuten (3 Zeilen) | Kein funktionaler Mehrwert |
| **1.0 → 3.0 (minimal)** | 5 Minuten (3 Zeilen) | Modernes Format, zukunftssicher |
| **1.0 → 3.0 + Storey** | 1–2 Tage | Echte Geschoss-Objekte statt Workarounds |
| **1.0 → 3.0 + Storey + Elevation + Heights** | 2–3 Tage | Vollständiges semantisches Modell |
| **1.0 → 3.0 + Storey + Room** | 4–6 Tage | Innenraum-Modellierung |

**Empfohlener Upgrade-Pfad:** Direkt 1.0 → 3.0. Kein Umweg über 2.0 nötig.

**Empfohlener Zeitpunkt:** Vor Implementierung von Schritt 4 (Fenster) und Schritt 5
(Türen), da diese direkt mit dem neuen `FillingSurface`-Konzept implementiert werden
sollten.

**Abwärtskompatibilität:** Die Pipeline kann mit CityGML 3.0 *auch weiterhin
CityGML-1.0-Dateien lesen*. Nur die Ausgabe ändert sich. citygml4j mappt automatisch
zwischen den Versionen.
