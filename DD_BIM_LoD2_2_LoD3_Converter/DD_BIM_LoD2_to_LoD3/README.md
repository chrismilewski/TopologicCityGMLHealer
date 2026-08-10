# LoD2 → LoD3 Konvertierungspipeline

Konvertiert CityGML-Gebäude von **LoD2 auf LoD3** – mit Keller, Geschossen, Türen und Fenstern.
Eingabe: CityGML-Datei + optionales DGM. Ausgabe: CityGML 1.0 (LoD3).

> **Vollständige technische Dokumentation:** [Doku.md](Doku.md)

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

---

## Schnellstart

### Vollständige Pipeline

```sh
java -jar target/lod2-zu-lod3-pipeline.jar  input.gml  Baukörpermodule_json/  output/  [dgm-pfad]
```

Oder mit Maven direkt aus dem Quellcode:

```sh
mvn exec:java \
  -Dexec.mainClass=de.mpsc.lod2tolod3.Lod2ToLod3Pipeline \
  -Dexec.args="input.gml Baukörpermodule_json/ output/"
```

### Einzelne Schritte

Jeder Schritt kann auch standalone aufgerufen werden:

```sh
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.Lod2ToLod3Promoter   input.gml  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BasementGenerator    input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.StoreyGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.DoorGenerator        input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.WindowGenerator      input.gml  jsonDir/  [output.gml]
java -cp target/lod2-zu-lod3-pipeline.jar  de.mpsc.lod2tolod3.BalconyGenerator     input.gml  jsonDir/  [output.gml]
```

`BalconyGenerator` ist seit 2026-08-10 als Schritt 6 fester Teil der Pipeline (läuft nach
den Fenstern); der Standalone-Aufruf oben bleibt zusätzlich für isolierte Tests möglich —
siehe Status-Tabelle unten.

---

## Pipeline-Ablauf

```
CityGML LoD2
    │
    ▼  Schritt 1 – Lod2ToLod3Promoter
    │  LoD2-Geometrie auf LoD3 hochstufen
    │  (WallSurface / RoofSurface / GroundSurface aus Solid extrahieren)
    │
    ▼  Schritt 2 – BasementGenerator
    │  Keller unterhalb der Geländeoberfläche modellieren (DGM-gestützt)
    │  Schnittlinie Gebäudehülle ↔ Geländefläche per Sutherland-Hodgman
    │
    ▼  Schritt 3 – StoreyGenerator
    │  Wände in Geschosse aufteilen (EG / OG / DG / UG)
    │  Horizontales Clipping mit automatischer Geschosshöhenberechnung
    │
    ▼  Schritt 4 – DoorGenerator
    │  Türen auf EG-Außenwände platzieren (TIC-basierte Positionierung)
    │
    ▼  Schritt 5 – WindowGenerator
    │  Fenster auf alle Geschoss-Außenwände platzieren (TIC-Methode)
    │
    ▼  Schritt 6 – BalconyGenerator
    │  Balkone ersetzen gezielt Fenster-Slots aus Schritt 5 gemäß GaPa-Muster
    │
    ▼  Schritt 7 – Junction-Conforming (streng formneutral)
    │  T-Naht-Vertices auf bestehende Kanten einfügen → wasserdichtes lod3Solid
    │
    ▼
CityGML LoD3
```

**Single-Pass-Architektur:** Die Eingabedatei wird genau einmal gelesen, alle
Schritte werden pro Gebäude im Speicher ausgeführt, das Ergebnis einmal geschrieben –
keine Zwischendateien.

**Streng formneutrale Nachbearbeitung (Schritt 6):** Nach dem Einbau von Kellern,
Geschossen, Türen und Fenstern fügt das Junction-Conforming fehlende T-Naht-Vertices
*auf bestehende Kanten* ein — gemessen werden dabei **0 bestehende Vertices bewegt**.
Es näht ausschließlich die eigenen Zusatzflächen zusammen; das Healing der
Quellgeometrie (mm-Nähte, Planarität) verbleibt beim nachgelagerten Healer. Damit
steigt die val3dity-Validität von 81,0 % auf **89,1 %** bei einer Quell-Obergrenze von
92,2 % (Details siehe [Doku.md](Doku.md)).

---

## Baukörpermodule (JSON)

Für jedes Gebäude wird eine JSON-Datei (`{gml:id}.json`) oder ein Fallback
(`_default.json`) aus dem `jsonDir`-Verzeichnis geladen:

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
| `.tif` / `.tiff` | GeoTIFF (javax.imageio) |
| `.zip` | ZIP-Archiv mit `.asc`-Dateien |
| Verzeichnis | Automatisches Mosaik aus mehreren Kacheln |

Format wird automatisch erkannt (`DgmLoader`-Factory).

---

## Implementierungsstatus

| # | Schritt | Klasse | Status |
|---|---|---|---|
| 1 | LoD2→LoD3 Promotion | `Lod2ToLod3Promoter` | ✅ Fertig |
| 2 | Keller | `BasementGenerator` | ✅ Fertig — `WindowPreference`-Zuordnung an Kellerwände 2026-08-03 gefixt (Mittelpunkt- statt Kollinearitätsprüfung ließ ~40% der Kellerwände ohne `WindowPreference`, dadurch fälschlich wie Party-Wand behandelt); siehe [Doku.md](Doku.md#schritt-2-keller-generator-basementgenerator) |
| 3 | Geschosse | `StoreyGenerator` | ✅ Fertig — 2026-08-04 Bugfix: Wände, die den Mehrfach-Schnitt umgehen (z.B. schräg zulaufende Walmdach-Innenwände), hingen sonst unterhalb `egFloorZ` und überlappten die Kellerwand (`NON_MANIFOLD_CASE`/`SHELL_NOT_CLOSED`, val3dity-verifiziert); jetzt hartes Nachtrimmen auf `egFloorZ` in allen drei betroffenen Codepfaden — siehe [Doku.md](Doku.md#schritt-3-geschoss-generator) |
| 4 | Türen | `DoorGenerator` | ✅ Fertig |
| 5 | Fenster | `WindowGenerator` | ✅ Fertig — Fensterblock wird seit 2026-08-03 immer auf den Wandabschnitt zentriert (vorher: `HDistWaWi`-verankert, Zentrierung nur als Fallback); siehe [Doku.md](Doku.md#fensterpositionen) |
| 6 | Balkone | `BalconyGenerator` | ✅ Fertig — seit 2026-08-10 in `Lod2ToLod3Pipeline` verdrahtet (nach Fenstern). Verifiziert an 14 echten Testgebäuden UND an der vollen 3.801-Gebäude-Kachel (630 Balkone, davon 4 Wände mit 2 Balkonen; 0 doppelte IDs). **val3dity (via `citygml-tools`+`val3dity --ignore204`): 89,2% valide Features, identisch zur Pipeline ohne Balkone — 0 zusätzliche Fehler.** Balkone ersetzen gezielt bestehende Fenster-Plätze gemäß der `GaPa`-Musterreihenfolge (mehrere Balkone pro Wand möglich, `HDistGaGa`-Blocklayout für benachbarte `Ga`-Token); Eligibilität über `WindowPreference` (0/1/2). Mehrere Positionierungs-Annahmen bleiben inferiert statt spec-belegt (u.a. `HDistWiGa`, `DistWiGa`) — Geometrie-Validität ist davon unabhängig bewiesen; siehe [Doku.md](Doku.md#schritt-8-balkon-generator-balconygenerator) und Javadoc der Klasse. |
| 7 | Junction-Conforming | `CityGmlUtils.conformJunctions` | ✅ Fertig |
| 8 | Dachfenster | – | 📋 TODO |

Getestet mit **3 801 Gebäuden** (Testdatensatz Sachsen LoD2), Laufzeit ca. **20 s**.
Geometrische Validität (val3dity, 658-Gebäude-Kachel): **89,1 %** valide Solids bei
**0 mm** Veränderung bestehender Geometrie; Ausgabe ist CityGML-1.0-schema-valide. Balkone
(Schritt 6) fügen auf der vollen 3.801-Gebäude-Kachel nachweislich **0 zusätzliche
val3dity-Fehler** hinzu (89,2 % valide Features, identisch mit/ohne Balkone).

---

## Projektstruktur

```
src/main/java/de/mpsc/lod2tolod3/
├── Lod2ToLod3Pipeline.java         Haupt-Pipeline (Single-Pass)
├── Lod2ToLod3Promoter.java         Schritt 1: Geometrie-Promotion
├── AbstractGenerator.java          Gemeinsame Generator-Basis (Template-Method)
├── BasementGenerator.java          Schritt 2: Keller
├── StoreyGenerator.java            Schritt 3: Geschosse
├── DoorGenerator.java              Schritt 4: Türen
├── WindowGenerator.java            Schritt 5: Fenster
├── BalconyGenerator.java           Schritt 6: Balkone (ersetzt Fenster-Slots gemäß GaPa-Muster)
├── util/
│   ├── CityGmlUtils.java           Shared Utilities (conformJunctions, splitWallByZ, …)
│   ├── DgmLoader.java              DGM-Format-Erkennung (Factory)
│   ├── DgmReader.java              ESRI ASCII Grid Parser
│   ├── GeoTiffReader.java          GeoTIFF Parser
│   ├── DgmMosaic.java              Mosaik-Kombinator (mehrere Kacheln)
│   ├── DgmProvider.java            Interface (getHeight, contains, describe)
│   └── ModuleParametersLoader.java JSON-Parameter-Loader mit Cache
└── model/
    ├── ModuleParameters.java       Datenklasse für JSON-Baukörpermodule
    └── WindowPreference.java       Enum: NONE/NORMAL/ABOVE_NEIGHBOR (Fenster- und Balkon-Eligibilität)
```

---

## CRS

`urn:adv:crs:ETRS89_UTM33*DE_DHHN2016_NH` (Koordinatenreferenzsystem der Testdaten)

---

## Lizenz

Siehe [LICENSE](../../LICENSE) im Repository-Root (`TopologicCityGMLHealer/LICENSE`).