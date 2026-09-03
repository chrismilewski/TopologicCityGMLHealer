# LoD2 → LoD3 Konvertierungspipeline

Konvertiert CityGML-Gebäude von **LoD2 auf LoD3** – mit Keller, Geschossen, Türen, Fenstern,
Balkonen und Dachfenstern. Eingabe: CityGML-Datei (oder ganzer Ordner mit Kacheln) + optionales
DGM. Ausgabe: CityGML 1.0 (LoD3).

Reines CMD-Tool — keine IDE nötig, läuft in jedem Terminal mit Java 21+ (`java -jar ...`).

> **Vollständige technische Dokumentation, Verifikationsläufe, Bugfix-Historie:** [Doku.md](Doku.md)

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Java | 21+ |
| Maven | 3.6+ |
| citygml4j | 3.2.7 |

---

## Build

```sh
mvn clean package -DskipTests
```

Ergebnis: `target/lod2-zu-lod3-pipeline.jar` (Main-Class `Lod2ToLod3Pipeline`).

---

## Schnellstart

### Einzelne Datei

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  input.gml  Baukörpermodule_json/  output/  [dgm-pfad]
```

Schreibt genau eine Ausgabedatei nach `output/`, dabei `LoD2_...` → `LoD3_...` umbenannt.

### Batch-Modus: ganzer Ordner mit Kacheln

Wird automatisch erkannt, wenn das erste Argument ein Ordner statt einer Datei ist:

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  inputFolder/  Baukörpermodule_json/  output/  [dgm-pfad]
```

Verarbeitet alle `.gml`-Dateien im Ordner nacheinander, legt unter `output/` einen neuen
Unterordner an (`LoD2_...` → `LoD3_...` umbenannt) und schreibt dort jede Kachel einzeln hinein.
Bricht eine einzelne Kachel ab, läuft der Rest weiter; am Ende steht eine Liste fehlgeschlagener
Dateien plus eine aufsummierte Gesamtstatistik über alle erfolgreichen Kacheln.

### Mit Maven direkt aus dem Quellcode

```sh
mvn exec:java -Dexec.mainClass=de.mpsc.lod2tolod3.Lod2ToLod3Pipeline \
  -Dexec.args="input.gml Baukörpermodule_json/ output/"
```

### Einzelne Schritte

Jeder Schritt kann auch standalone aufgerufen werden (gleiches Argument-Muster):

```sh
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.Lod2ToLod3Promoter   input.gml  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BasementGenerator    input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.StoreyGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.DoorGenerator        input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.WindowGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BalconyGenerator     input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.RoofWindowGenerator  input.gml  jsonDir/  [output.gml]
```

---

## Pipeline-Ablauf

```
CityGML LoD2
    │
    ▼  1  Lod2ToLod3Promoter        LoD2-Geometrie auf LoD3 hochstufen
    ▼  2  BasementGenerator         Keller unterhalb der Geländeoberfläche (DGM-gestützt)
    ▼  3  StoreyGenerator           Wände in Geschosse aufteilen (EG/OG/DG/UG)
    ▼  4  DoorGenerator             Türen auf EG-Außenwände platzieren
    ▼  5a BalconyGenerator Phase 1  Führender Balkon-Lauf je Wand (reserviert Wandspanne)
    ▼  5b WindowGenerator           Fenster auf alle Geschoss-Außenwände
    ▼  5c BalconyGenerator Phase 2  Restliche Balkon-Läufe gegen die echten Fenster
    ▼  5d RoofWindowGenerator       Dachflächenfenster auf geneigten Dachflächen
    ▼  4d DoorGenerator (Fallback)  Ersatztür für Gebäude mit Fenstern, aber ohne Tür
    ▼  6  Junction-Conforming       T-Naht-Vertices einfügen (streng formneutral)
    ▼  7  Pinch-Point-Aufspaltung   Selbstberührende Ringe in einfache Teilringe auftrennen
    │
    ▼
CityGML LoD3
```

**Single-Pass-Architektur:** jede Eingabedatei wird einmal gelesen, alle Schritte laufen pro
Gebäude im Speicher, das Ergebnis wird einmal geschrieben — keine Zwischendateien.

**Schritte 6+7 sind streng formneutral:** kein bestehender Vertex wird bewegt, nur fehlende
Punkte auf bestehende Kanten eingefügt bzw. selbstberührende Ringe sauber aufgetrennt. Das
Healing der Quellgeometrie (mm-Nähte, Planarität) bleibt beim nachgelagerten Healer.

---

## Baukörpermodule (JSON)

Für jedes Gebäude wird eine JSON-Datei (`{gml:id}.json`) oder ein Fallback (`_default.json`)
aus dem `jsonDir`-Verzeichnis geladen:

```json
{
  "GF": { "roomHeight": 2.8, "windowRatio": 0.25, "doorCount": 1 },
  "OG": { "roomHeight": 2.6, "windowRatio": 0.30 },
  "DG": { "roomHeight": 2.4, "windowRatio": 0.15 },
  "BA": { "height":    2.5, "windowRatio": 0.0  }
}
```

| Schlüssel | Bedeutung |
|---|---|
| `GF` | Erdgeschoss |
| `OG` | Obergeschoss(e) – wird bei mehreren Vollgeschossen wiederholt |
| `DG` | Dachgeschoss (wenn unter Traufe Restfläche vorhanden) |
| `BA` | Keller (Basement) |

---

## DGM-Unterstützung

| Format | Beschreibung |
|---|---|
| `.asc` | ESRI ASCII Grid (einzelne Kachel) |
| `.tif` / `.tiff` | GeoTIFF |
| `.zip` | ZIP-Archiv mit `.asc`-Dateien |
| Verzeichnis | Automatisches Mosaik aus mehreren Kacheln (z.B. ganze Stadt) |

Format wird automatisch erkannt (`DgmLoader`-Factory).

---

## Status

Alle 8 Pipeline-Schritte (1–7 inkl. 4d/5a–5d) sind fertig und produktiv im Einsatz. Letzter
kompletter Stadt-Lauf (Dresden, 98 Kacheln, 141.670 Gebäude): val3dity 98,36 % valide Features,
CityDoctor2 92,24 % fehlerfreie Gebäude. Verifikationshistorie, Einzelfixe und genaue Zahlen:
[Doku.md](Doku.md).

---

## Projektstruktur

```
src/main/java/de/mpsc/lod2tolod3/
├── Lod2ToLod3Pipeline.java         Haupt-Pipeline (Single-Pass + Batch-Modus)
├── Lod2ToLod3Promoter.java         Schritt 1: Geometrie-Promotion
├── AbstractGenerator.java          Gemeinsame Generator-Basis (Template-Method)
├── BasementGenerator.java          Schritt 2: Keller
├── StoreyGenerator.java            Schritt 3: Geschosse
├── DoorGenerator.java              Schritt 4 + 4d: Türen (inkl. Fallback-Türen)
├── WindowGenerator.java            Schritt 5b: Fenster
├── BalconyGenerator.java           Schritt 5a/5c: Balkone
├── RoofWindowGenerator.java        Schritt 5d: Dachflächenfenster
├── util/
│   ├── CityGmlUtils.java           Attribut-Helfer, GML-Datei-I/O, SRS
│   ├── GeometryUtils.java          Geometrie-Grundlagen (Basis-Klasse der meisten anderen)
│   ├── BuildingQueryUtils.java     Boundary-/Target-Sammlung, Dach-Z-Bereich
│   ├── WallCuttingUtils.java       Horizontaler Wand-Schnitt (Sutherland-Hodgman + JTS)
│   ├── SlabClippingUtils.java      Geschossflächen-Zuschnitt bei Anbauten (JTS)
│   ├── SolidShellUtils.java        TerrainIntersectionCurve + Solid-Shell-Neuaufbau
│   ├── JunctionConformingUtils.java T-Naht-Konformierung + Pinch-Point-Aufspaltung (Schritt 6+7)
│   ├── OpeningUtils.java           Fenster-/Tür-Platzierungsprüfung und -Erzeugung
│   ├── PartyWallCoverageUtils.java Wand-Deckung durch Nachbarbauteile (Anbau)
│   ├── Point3D.java                3D-Punkt-Klasse
│   ├── DgmLoader.java              DGM-Format-Erkennung (Factory)
│   ├── DgmReader.java              ESRI ASCII Grid Parser
│   ├── GeoTiffReader.java          GeoTIFF Parser
│   ├── DgmMosaic.java              Mosaik-Kombinator (mehrere Kacheln)
│   ├── DgmProvider.java            Interface (getHeight, contains, describe)
│   └── ModuleParametersLoader.java JSON-Parameter-Loader mit Cache
└── model/
    ├── ModuleParameters.java       Datenklasse für JSON-Baukörpermodule
    └── WindowPreference.java       Enum: NONE/NORMAL/ABOVE_NEIGHBOR
```

---

## CRS

`urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH` (Koordinatenreferenzsystem der Testdaten)

---

## Lizenz

Siehe [LICENSE](../../LICENSE) im Repository-Root (`TopologicCityGMLHealer/LICENSE`).
