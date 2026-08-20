# sql2gml - Dokumentation

## Übersicht

**sql2gml** ist ein Java-Tool, das CityGML-Dateien auf Basis einer SQLite-Datenbank aktualisiert. Es liest validierte und ggf. korrigierte Geometriedaten aus der Datenbank und schreibt sie zurück in die CityGML-Datei.

**Zwei Datenbank-Schema-Generationen, vier Workflows:**

| Workflow | DB-Schema | Strategie | Status |
|---|---|---|---|
| **HealedReplaceWorkflow** | neu (`SurfaceGeometries`+`PosLists`) | Kompletter Geometrie-Ersatz auf Building-Ebene, inkl. Solid+XLinks; neue/überholte BuildingParts werden automatisch angelegt/entfernt | ✅ **Haupt-Workflow** (Standard, Main-Class des JARs) |
| **PolygonOnlyReplaceWorkflow** | neu | Identisch zu HealedReplaceWorkflow, aber schreibt niemals `gml:TriangulatedSurface` (zerlegt in Einzel-Polygone) | Variante für Validatoren ohne TIN-xlink-Unterstützung (z.B. CityDoctor 3.18.2) |
| `legacy.ReplaceWorkflow` | alt (`Polygons`+`LinearRings`) | Kompletter Geometrie-Ersatz, Vorgänger von HealedReplaceWorkflow | Legacy, nicht weiterentwickelt |
| `legacy.CompleteWorkflow` | alt | Selektives Koordinaten-Update einzelner Polygone mit IsValid-Kaskade und Polygon-Splitting | Legacy, nicht weiterentwickelt |

### Kernfunktionen HealedReplaceWorkflow (Haupt-Workflow)

Strategie: „Kompletter Replace auf Building-Ebene" wie beim alten `ReplaceWorkflow`, aber mit drei Neuerungen (siehe Klassen-Javadoc):

1. **Geometrie-Typen**: Eine Surface trägt genau EINE `SurfaceGeometry`, die entweder ein klassisches `gml:Polygon` (PosList-Index 0 = Außenring, >0 = Löcher) oder eine `gml:TriangulatedSurface` (jede PosList = ein unabhängiges `gml:Triangle` — Notlösung des Healers für nicht planarisierbare Flächen) ist.
2. **BuildingPart-Ziel, nur 1:1**: `PartIdGml` entscheidet über das Ziel jedes DB-Parts — `null` → Geometrie gehört direkt zum Building; existierende GML-Part-ID → In-place-Ersatz. Jede andere ID (Healer hat per Party-Wall-Merge/Split einen Solid erzeugt, der keinem Original-Part 1:1 entspricht) disqualifiziert das GESAMTE Building ueber das Solid-Merge-Gate (s.u.) — Healer-Merges/-Splits werden aktuell NICHT geschrieben, der Healer-Code dafuer ist noch nicht ausgereift genug (beobachtete Faelle mit sichtbar kaputter Geometrie).
3. **Valid-Gate (strenger als beim alten ReplaceWorkflow)**: Ein Building wird NUR ersetzt, wenn das Building UND alle seine Parts/Surfaces/Geometrien/PosLists in der DB vollständig valide sind, UND jeder DB-Part 1:1 einem bestehenden GML-Building/-Part entspricht (Solid-Merge-Gate). Sonst bleibt die Original-Geometrie unverändert — kein teilweiser Ersatz, keine Hüllen-Lücke.

Weitere Eigenschaften (wie beim alten ReplaceWorkflow): Attribut-Erhalt, Unverändert-Garantie für Buildings ohne DB-Eintrag, Header-Fix, Auto-Batch aus `CityGmlFiles`. Zusätzlich: ausführliche TIN-Statistik (wie viele Flächen je Typ — Ground/Wall/Roof — trianguliert sind; triangulierte WÄNDE werden gesondert gewarnt, weil sie eine spätere LoD3-Fensterableitung praktisch unmöglich machen).

### PolygonOnlyReplaceWorkflow

Dünner Wrapper um `HealedReplaceWorkflow` (`setGeometryMode(ALWAYS_POLYGON)` vor `main()`). Grund: `gml:TriangulatedSurface` ist zwar CityGML-1.0-konform (Substitutionskette `TriangulatedSurface → Surface → _Surface`, von citygml-tools bestätigt), aber CityDoctor 3.18.2 kann eine per xlink referenzierte TIN nicht auflösen und meldet fälschlich `GE_S_NOT_CLOSED`. Die Geometrie ist bei beiden Modi punktgenau identisch — es entfällt nur die TIN-Verpackung.

### Legacy-Workflows

Siehe Abschnitt „Legacy" unten — funktionsgleich zur damaligen Doku, arbeiten aber auf dem alten DB-Schema (`Polygons`+`LinearRings` statt `SurfaceGeometries`+`PosLists`) und werden nicht weiterentwickelt.

---

## Technologien

| Technologie | Version | Zweck |
|-------------|---------|-------|
| **Java** | 21 LTS | Programmiersprache |
| **Maven** | 3.x | Build- und Dependency-Management |
| **citygml4j** | 3.2.7 | CityGML-Bibliothek (Lesen/Schreiben) |
| **SQLite JDBC** | 3.47.x | Datenbankzugriff |
| **Gson** | 2.11.x | JSON-Parsing für Attribute |
| **SLF4J Simple** | 2.0.x | Logging |

---

## Ausführung

### Voraussetzungen
- Java 21 installiert (JAVA_HOME gesetzt)
- Maven installiert
- SQLite-Datenbank mit validierten Daten vorhanden

### Kompilieren (Fat-JAR)
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
cd sql2gml_neu
mvn clean package -q
```

Erzeugt `target/sql2gml-complete.jar` (Fat-JAR mit allen Abhängigkeiten).

`java -jar` startet den **HealedReplaceWorkflow** (Haupt-Workflow, Main-Class des JARs).
`PolygonOnlyReplaceWorkflow` (keine TriangulatedSurface in der Ausgabe) und die beiden
Legacy-Workflows sind mit identischen Argumenten per `-cp` erreichbar:

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.PolygonOnlyReplaceWorkflow <Argumente>
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.ReplaceWorkflow <Argumente>
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.CompleteWorkflow <Argumente>
```

### Modus 1: Einzelne Datei
```powershell
java -jar target/sql2gml-complete.jar <input.gml> <database.db> [<output.gml>]
```
Ohne expliziten Output wird `<input>_new.gml` neben der Eingabe erzeugt.

**Beispiel:**
```powershell
java -jar target/sql2gml-complete.jar D:\data\LoD2_33_416_5656.gml D:\data\BuildingParts.db D:\output\LoD2_33_416_5656_updated.gml
```

### Modus 2: Batch-Verarbeitung (Ordner)
Verarbeitet alle `.gml` Dateien in einem Ordner (Output-Dateien erhalten Suffix `_new`):

```powershell
java -jar target/sql2gml-complete.jar <inputFolder> <database.db> [<outputFolder>]
```

### Modus 3: Auto-Batch (empfohlen für Kacheln)
Liest die Dateiliste aus der `CityGmlFiles`-Tabelle und verarbeitet nur Dateien mit Modifikationen:

```powershell
java -jar target/sql2gml-complete.jar <database.db> <inputFolder> <outputFolder> --auto
```

**Vorteile des Auto-Modus:**
- Liest Dateiliste direkt aus der Datenbank (`CityGmlFiles`-Tabelle)
- Überspringt Dateien ohne Modifikationen automatisch
- Ideal für große Kachel-Datensätze (z.B. Dresden mit 100+ Kacheln)
- DB-Index und CityGML-Context werden nur einmal pro Lauf geladen

---

## Datenbankstruktur (neues Healer-Schema, HealedReplaceWorkflow/PolygonOnlyReplaceWorkflow)

Die SQLite-Datenbank enthält die hierarchische Gebäudestruktur. Jede Tabelle hat `IsValid` (0/1) und `Log` Spalten:

```
CityGmlFiles
    └── Buildings (n)              IsValid, Log, Attributes (JSON)
            └── BuildingParts (n)  IsValid, Log, Attributes (JSON)
                    └── Surfaces (n)             IsValid, Log, Attributes (JSON: FACEAREA, NORMAL_AZI, ...)
                            └── SurfaceGeometries (genau 1 je Surface)  IsValid, Log, GeometryTypeId
                                    └── PosLists (n)   IsValid, Log, PosList, PosListIndex
```

Unterschied zum alten Schema (siehe „Legacy" unten): `Polygons`+`LinearRings` wurden durch
`SurfaceGeometries`+`PosLists` ersetzt. Eine Surface hat jetzt genau EINE Geometrie (nicht
mehr potenziell mehrere Polygone), die per `GeometryTypeId` entweder ein klassisches Polygon
oder eine Dreiecksvermaschung (TIN) ist.

### IsValid-Semantik

| IsValid | Bedeutung | Verarbeitung im Tool |
|---------|-----------|---------------------|
| `1` | Element (und beim Building: die GESAMTE Unterhierarchie) korrekt/valide | Geometrie aus DB übernehmen |
| `0` | Unheilbarer Fehler irgendwo in der Hierarchie | Original-CityGML für das GANZE Building beibehalten |

**Log wird immer in die DB geschrieben**, aber `HealedReplaceWorkflow` liest/schreibt nur
den Building- und BuildingPart-Log als generisches Attribut (`Log`/`Log_Part`) — anders als
beim alten, feingranularen Legacy-Log pro Polygon/Ring (siehe „Legacy" unten).

### Valid-Gate (striktes All-or-Nothing, NEU ggü. Legacy)

Anders als die alte IsValid-Kaskade (die einzelne Ebenen überspringen konnte, siehe
„Legacy" unten) prüft `HealedReplaceWorkflow.isFullyValid()` die **gesamte** Hierarchie
eines Buildings, bevor irgendetwas ersetzt wird:

```
Building.isValid()
  UND für JEDEN BuildingPart:   part.isValid()
  UND für JEDE Surface:         surface.isValid()
  UND deren SurfaceGeometry:    geometry != null, geometry.isValid(), mind. 1 PosList
  UND für JEDE PosList:         posList.isValid()
  UND mindestens 1 BuildingPart vorhanden
  UND Solid-Merge-Gate:         JEDER DB-Part mit gesetzter PartIdGml referenziert ein
                                 BEREITS bestehendes GML-BuildingPart (1:1) — keine vom
                                 Healer neu erzeugten Party-Wall-Merge/Split-Solids
```

Ist IRGENDEIN Teil invalide, bleibt das **gesamte** Building unverändert (Original-Geometrie
komplett erhalten) — es gibt keinen Teil-Ersatz mehr. Grund: ein teilweiser Ersatz könnte
eine Lücke in der Hülle hinterlassen (`SHELL_NOT_CLOSED`), wenn ausgerechnet die fehlende
Fläche gebraucht würde, um die Hülle zu schließen.

**Solid-Merge-Gate (2026-08-20):** der Healer erzeugt bei einem Party-Wall-Merge (mehrere
Original-BuildingParts zu einem Solid verschmolzen) oder -Split (ein Teil in mehrere Solids
aufgespalten) eine `PartIdGml`, die zu keinem bestehenden GML-BuildingPart passt. Frueher
wurde dafuer automatisch ein NEUES BuildingPart angelegt (siehe Git-Historie). Das ist
bewusst deaktiviert: der Healer-Code fuer diesen Fall ist noch nicht ausgereift genug und hat
sichtbar kaputte Geometrie produziert (fehlende Waende, schwebendes Dach). Bis der
Healer-Code dafuer reif ist, disqualifiziert jede solche PartIdGml das gesamte Building —
Original-Geometrie bleibt vollstaendig erhalten, wie bei jedem anderen Valid-Gate-Fehlschlag.

### Tabellen

#### CityGmlFiles
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| Filename | TEXT | Dateiname der CityGML-Kachel |

#### Buildings
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| BuildingIdGml | TEXT | gml:id des Buildings |
| FileId | INTEGER | Referenz auf CityGmlFiles |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### BuildingParts
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| PartIdGml | TEXT | `null`/leer → Geometrie gehört zum Building; existierende GML-Part-ID → In-place-Ersatz; sonst → neues BuildingPart (bereits vollqualifizierte Ziel-Id `{BuildingIdGml}_{PartIndex}_Part`) |
| BuildingId | INTEGER | Referenz auf Buildings |
| Attributes | TEXT | JSON mit Attributen |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### Surfaces
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| SurfaceIdGml | TEXT | gml:id der Surface |
| SurfaceTypeId | INTEGER | 0=None (→WallSurface-Fallback), 1=Ground, 2=Wall, 3=Roof |
| BuildingPartId | INTEGER | Referenz auf BuildingParts |
| Attributes | TEXT | JSON mit berechneten Attributen (FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL) |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### SurfaceGeometries
Genau EIN Eintrag je Surface (mehrere werden geloggt und alle bis auf den ersten verworfen).

| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| Id | INTEGER | Primary Key |
| SurfaceId | INTEGER | Referenz auf Surfaces |
| GeometryIdGml | TEXT | gml:id der Geometrie (Polygon bzw. TriangulatedSurface) |
| GeometryTypeId | INTEGER | **0 = Polygon**, **1 = TriangulatedSurface (TIN)** |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

#### PosLists
| Spalte | Typ | Beschreibung |
|--------|-----|--------------|
| SurfaceGeometryId | INTEGER | Referenz auf SurfaceGeometries |
| PosListIndex | INTEGER | Bei Polygon: 0 = Außenring, 1+ = Löcher. Bei TIN: jede PosList = ein unabhängiges Dreieck (4 Punkte, geschlossen) |
| PosList | TEXT | Koordinaten als Leerzeichen-getrennte Liste (x y z x y z ...), geschlossener Ring (erster == letzter Punkt) |
| IsValid | INTEGER | 0 oder 1 |
| Log | TEXT | Validierungsprotokoll |

---

## Projektstruktur

```
sql2gml_neu/
├── pom.xml                          # Maven-Konfiguration (inkl. maven-shade-plugin für Fat-JAR)
├── Doku.md                          # Diese Dokumentation
├── README.md                        # Kurzanleitung
└── src/main/java/de/mpsc/sql2gml/
    ├── HealedReplaceWorkflow.java   # HAUPT-Workflow, neues Schema (Main-Class)
    ├── PolygonOnlyReplaceWorkflow.java  # Variante ohne TriangulatedSurface (CityDoctor-Workaround)
    ├── DbReader.java                # Datenbankzugriff, neues Schema
    ├── GmlMemberFilter.java         # Gemeinsamer Streaming-Filter (Basis für ExtractSst/ExtractBuildings)
    ├── ExtractSst.java              # Gebäude mit sst-Attribut extrahieren
    ├── ExtractBuildings.java        # Gebäude nach gml:id extrahieren
    ├── model/                       # Datenmodell, neues Schema
    │   ├── Building.java
    │   ├── BuildingPart.java
    │   ├── Surface.java
    │   ├── SurfaceGeometry.java     # Ersetzt Polygon.java (1 Geometrie je Surface, Polygon ODER TIN)
    │   └── PosList.java             # Ersetzt LinearRing.java
    └── legacy/                      # Altes Schema, nicht weiterentwickelt (siehe Abschnitt „Legacy")
        ├── ReplaceWorkflow.java
        ├── CompleteWorkflow.java
        ├── DatabaseReader.java
        └── model/
            ├── Building.java
            ├── BuildingPart.java
            ├── Surface.java
            ├── Polygon.java
            └── LinearRing.java
```

---

## Hilfs-Tools

### ExtractSst — Gebäude mit sst-Attribut extrahieren

Extrahiert nur Gebäude, die das generische Attribut `name="sst"` besitzen, aus einer CityGML-Datei. Arbeitet textstrom-basiert (`BufferedReader`/`BufferedWriter`) und kann daher auch sehr große Kacheln effizient verarbeiten, ohne den gesamten CityGML-Objektbaum im Speicher aufzubauen.

#### Verwendung

```powershell
# Einzelne Datei — Output wird als _sst.gml neben der Eingabe erzeugt
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml>

# Einzelne Datei mit explizitem Ausgabepfad
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <output.gml>

# Einzelne Datei mit Ausgabeordner (Dateiname wird automatisch erzeugt)
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <outputFolder>

# Ordner-Modus — alle .gml Dateien im Ordner verarbeiten
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder>

# Ordner-Modus mit Ausgabeordner
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> <outputFolder>
```

#### Funktionsweise

1. Liest die CityGML-Datei zeilenweise
2. Sammelt jeden `<core:cityObjectMember>`-Block
3. Prüft ob der Block `name="sst"` enthält
4. Schreibt nur passende Blöcke in die Ausgabe (Header/Footer werden immer übernommen)

#### Beispiel

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst `
    "D:\data\LoD2_33_416_5656_2_SN.gml" `
    "D:\output\LoD2_33_416_5656_2_SN_sst.gml"
```

```
--- LoD2_33_416_5656_2_SN.gml ---
  Total buildings: 3801
  SST buildings:   1452
  Output: D:\output\LoD2_33_416_5656_2_SN_sst.gml
```

### ExtractBuildings — Gebäude nach gml:id extrahieren

Extrahiert bestimmte Gebäude anhand ihrer `gml:id` aus einer CityGML-Datei. Ebenfalls textstrom-basiert.

#### Verwendung

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> [ID2] ...
```

#### Beispiel

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings `
    "D:\data\LoD2_33_416_5656_2_SN.gml" `
    "D:\output\7buildings.gml" `
    DESNALK0pF001g4s DESNALK0pF001gGp DESNALK0pF001gjj
```

---

## Klassen im Detail

### HealedReplaceWorkflow.java — HAUPT-Workflow

Die Standard-Hauptklasse (Main-Class des Fat-JARs), für das neue Healer-Schema
(`SurfaceGeometries`+`PosLists` statt `Polygons`+`LinearRings`). Strategie: „Kompletter
Replace auf Building-Ebene" wie beim alten `legacy.ReplaceWorkflow` — aber mit drei
Neuerungen (siehe Übersicht oben): Geometrie-Typen (Polygon/TIN je Surface), ein
Solid-Merge-Gate das Party-Wall-Merge/Split-Ergebnisse des Healers noch NICHT akzeptiert
(dessen Code dafuer ist noch nicht ausgereift), und ein strengeres All-or-Nothing-Valid-Gate.

#### Ablauf pro Building (`replaceBuilding`)

```
┌─────────────────────────────────────────────────────────────────────┐
│                     HealedReplaceWorkflow                           │
├─────────────────────────────────────────────────────────────────────┤
│  Step 1: Datenbank lesen (EINMAL pro Lauf)                          │
│    → DbReader lädt alle Buildings hierarchisch                      │
│    → Index: BuildingIdGml → DB-Building                             │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: CityGML verarbeiten (Streaming, Chunk-weise, KEIN          │
│          ObjectWalker — einfache while-Schleife mit instanceof)     │
│    → boundedBy-Envelope aus dem Original übernehmen                 │
│    → Pro Building:                                                  │
│       • Nicht in DB: unverändert durchreichen                       │
│       • In DB, aber NICHT vollständig valide (Valid-Gate): Original │
│         komplett erhalten, Warnung loggen                           │
│       • In DB UND vollständig valide (inkl. Solid-Merge-Gate):       │
│           1. DB-Attribute + Log als generische Attribute schreiben  │
│           2. Je DB-BuildingPart Ziel bestimmen (PartIdGml: null/     │
│              existierend — s.u.) und dessen Boundaries neu aufbauen  │
│              (Surfaces → SurfaceGeometry → Polygon ODER TIN)        │
│           3. Ueberholte GML-Parts entfernen (Healer hat sie in der   │
│              DB weggelassen, ohne sie in etwas Neues zu mergen)      │
│           4. lod2Solid JE ZIEL komplett neu aus Geometrie-IDs        │
│              (xlink:href) — fehlt Geometrie, wird das Solid entfernt│
│    → Header-Fix (Namespaces/schemaLocation aus der Eingabe)         │
└─────────────────────────────────────────────────────────────────────┘
```

**Was erhalten bleibt:** Building-eigene CityGML-Attribute (measuredHeight, function,
Adresse, …) — nur die Geometrie und die DB-Attribute werden ersetzt.

**Ergebnis-Statistik:** Features read, Buildings replaced/unchanged/kept-invalid, Superseded
parts removed, Surfaces written (davon TIN), sowie eine Aufschlüsselung der Triangulierung
je Flächentyp (Ground/Wall/Roof) — triangulierte WÄNDE werden gesondert als Building-Liste
ausgegeben (siehe unten).

#### BuildingPart-Zielbestimmung (Kernstück, `replaceBuilding`)

| `PartIdGml` | Ziel | Bedeutung |
|---|---|---|
| `null`/leer | das Building selbst | Geometrie gehört direkt zum Building |
| existierende GML-Part-ID | das bestehende `BuildingPart` (in-place) | 1:1-Fall, keine Aufspaltung/Merge für dieses Teil |

Jede andere ID (Healer hat per Party-Wall-Merge/Split einen Solid erzeugt, der keinem
Original-Part 1:1 entspricht) darf diesen Punkt gar nicht erst erreichen — das Solid-Merge-
Gate in `isFullyValid` sortiert das gesamte Building vorher aus. Erreicht der Code diesen
Zweig trotzdem (Gate/Logik-Widerspruch), wird der betroffene DB-Part defensiv uebersprungen
und ein Fehler geloggt, statt einen unreifen Healer-Merge-Solid zu schreiben.

Alte GML-Parts, in die NICHT geschrieben wurde, werden anschließend entfernt — das betrifft
jetzt nur noch den Fall, dass der Healer einen Part ganz aus der DB weggelassen hat, ohne ihn
in einen neuen Part zu mergen.

#### Valid-Gate (`isFullyValid`)

Siehe Abschnitt „Datenbankstruktur" oben — ALLE Ebenen (Building, jeder Part, jede
Surface, jede Geometrie, jede PosList) müssen valide sein, UND das Solid-Merge-Gate muss
bestehen (jeder DB-Part mit gesetzter PartIdGml muss ein bestehendes GML-BuildingPart 1:1
referenzieren), sonst bleibt das gesamte Building unverändert.

#### GeometryMode (`buildGeometries`)

| Modus | GeometryTypeId=1 (TIN) wird geschrieben als | Verwendet von |
|---|---|---|
| `AS_IN_DATABASE` (Standard) | `gml:TriangulatedSurface`, 1:1 zur DB | `HealedReplaceWorkflow` |
| `ALWAYS_POLYGON` | N einzelne `gml:Polygon` (ein Dreieck je Polygon, eigene `gml:id`, alle einzeln im Solid referenziert) | `PolygonOnlyReplaceWorkflow` |

Die DB-Spalte `GeometryTypes` bleibt in beiden Fällen unangetastet — der Modus steuert
ausschließlich die GML-Repräsentation. `setGeometryMode()` ist ein globaler, statischer
Schalter, der VOR `main()` gesetzt werden muss (siehe `PolygonOnlyReplaceWorkflow`).

#### RunMode Enum

Erkennt den Ausführungsmodus anhand der Kommandozeilen-Argumente (identisch zum alten
`legacy.ReplaceWorkflow`):

| Wert | Erkennung | Bedeutung |
|------|-----------|-----------|
| `SINGLE_FILE` | `args.length >= 2` und `args[0]` ist eine Datei | Einzelne GML-Datei verarbeiten |
| `BATCH_FOLDER` | `args.length >= 2` und `args[0]` ist ein Ordner | Alle GML-Dateien in einem Ordner |
| `AUTO_BATCH` | `args.length >= 4` und `args[3] == "--auto"` | Dateiliste aus DB, nur geänderte Kacheln |

Die Erkennung erfolgt in `RunMode.detect(String[] args)` — AUTO_BATCH hat Vorrang vor BATCH_FOLDER.

### PolygonOnlyReplaceWorkflow.java

30-Zeilen-Wrapper: ruft `HealedReplaceWorkflow.setGeometryMode(ALWAYS_POLYGON)` und
delegiert dann an `HealedReplaceWorkflow.main(args)`. Grund: `gml:TriangulatedSurface` ist
CityGML-1.0-konform (Substitutionskette `TriangulatedSurface → Surface → _Surface`, von
citygml-tools bestätigt), aber CityDoctor 3.18.2 kann eine per xlink referenzierte TIN
nicht auflösen und meldet fälschlich `GE_S_NOT_CLOSED`. Die Geometrie ist bei beiden
Modi punktgenau identisch.

### DbReader.java

Liest die hierarchische Datenstruktur aus der SQLite-Datenbank (neues Schema). Jede
Tabelle wird genau EINMAL komplett gelesen und im Speicher über die Fremdschlüssel
zusammengesetzt (kein N+1-Query-Muster).

#### Wichtige Methoden

| Methode | Beschreibung |
|---------|--------------|
| `readAllBuildings()` | Liest komplette Hierarchie: Buildings → BuildingParts → Surfaces → SurfaceGeometries (genau 1 je Surface) → PosLists |
| `getCityGmlFiles()` | Liest alle Dateinamen aus `CityGmlFiles`-Tabelle (Map: FileId → Filename) |
| `hasModificationsForFile(fileId)` | Prüft per JOIN über die ganze Hierarchie, ob mindestens eine `SurfaceGeometry` mit `IsValid=1` für eine Datei existiert |

Unterschiede zum alten `legacy.DatabaseReader`: `Polygons`+`LinearRings` wurden durch
`SurfaceGeometries`+`PosLists` ersetzt; `RepairActions`/`QualityIssues` sind reine
Diagnose-Tabellen und werden hier nicht gelesen.

#### JSON-Attribute

Die `Attributes`-Spalte in Buildings, BuildingParts und Surfaces enthält JSON (gleiches
Format wie im alten Schema):

```json
{
    "FACEAREA": 12.345,
    "NORMAL_AZI": 180.0,
    "NORMAL_H": 0.0,
    "Z_Max": 125.5,
    "Z_Min": 120.0,
    "Z_MAX_ASL": 225.5,
    "Z_MIN_ASL": 220.0
}
```

Diese werden mit Gson in `Map<String, Object>` geparst.

### GmlMemberFilter.java

Gemeinsamer, zeilenbasierter Streaming-Filter — Basis für `ExtractSst` und
`ExtractBuildings` (vorher hatte jede Klasse ihre eigene Kopie dieser Logik). Kopiert den
Datei-Rahmen (alles außerhalb eines `cityObjectMember`) unverändert und schreibt jeden
`<core:cityObjectMember>…</core:cityObjectMember>`-Block nur, wenn ein übergebenes
`Predicate<String>` (erhält den vollständigen Blocktext) `true` liefert. Setzt voraus,
dass öffnendes und schließendes Member-Tag je auf einer eigenen Zeile stehen (passt zum
GDI-DE-/FME-Format dieses Projekts). `filter()` liefert ein `Result(totalMembers,
writtenMembers)` für die Fortschrittsausgabe.

### Model-Klassen (neues Schema)

Alle Model-Klassen haben `valid` (boolean) und `log` (String) Felder.

| Klasse | Felder | Beschreibung |
|--------|--------|--------------|
| `Building` | id, buildingIdGml, fileId, attributes, valid, log, buildingParts | Gebäude mit Referenz auf CityGML-Datei |
| `BuildingPart` | id, buildingId, partIdGml, attributes, valid, log, surfaces | Gebäudeteil |
| `Surface` | id, surfaceIdGml, surfaceTypeId, attributes, valid, log, geometry | Oberfläche (Wall/Roof/Ground); **genau EINE** `SurfaceGeometry` (nicht mehr eine Liste von Polygonen) |
| `SurfaceGeometry` | id, surfaceId, geometryIdGml, geometryTypeId, valid, log, posLists | Ersetzt `Polygon`. `geometryTypeId`: `TYPE_POLYGON=0`, `TYPE_TRIANGULATED_SURFACE=1`; `isTriangulatedSurface()` |
| `PosList` | surfaceGeometryId, posListIndex, posList, valid, log | Ersetzt `LinearRing`. Bei Polygon: Index 0=Außenring, >0=Loch. Bei TIN: jede PosList ein unabhängiges Dreieck |

`PosList.getPosListAsArray()` konvertiert den PosList-String in ein `double[]` Array
(unverändert zur alten `LinearRing.getPosListAsArray()`).

---

## Legacy (altes DB-Schema, nicht weiterentwickelt)

Die folgenden Klassen (Package `de.mpsc.sql2gml.legacy`) arbeiten auf dem alten
DB-Schema (`Polygons`+`LinearRings`, siehe historische Tabellenstruktur weiter unten in
diesem Abschnitt) und werden **nicht mehr weiterentwickelt** — sie bleiben nur als
Fallback/Referenz erhalten. Die Beschreibung entspricht dem Stand, zu dem sie noch der
Haupt-Workflow waren; Klassennamen sind ohne `legacy.`-Präfix belassen, wie im Code selbst.

### ReplaceWorkflow.java (`legacy.ReplaceWorkflow`)

Der damalige Standard-Workflow. Strategie: **„Kompletter Replace auf Building-Ebene"** — statt einzelne Polygone zu patchen, wird die gesamte Geometrie eines Gebäudes durch den DB-Stand ersetzt. `HealedReplaceWorkflow` ist der direkte Nachfolger (siehe oben).

#### Ablauf pro Building (`replaceBuilding`)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ReplaceWorkflow                               │
├─────────────────────────────────────────────────────────────────────┤
│  Step 1: Datenbank lesen (EINMAL pro Lauf)                          │
│    → DatabaseReader lädt alle Buildings hierarchisch                │
│    → Index: BuildingIdGml → DB-Building                             │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: CityGML verarbeiten (Streaming, Chunk-weise)               │
│    → boundedBy-Envelope aus dem Original übernehmen                 │
│    → Pro Building:                                                  │
│       • In DB (IsValid=1):                                          │
│           1. DB-Attribute + Log als generische Attribute schreiben  │
│           2. ALLE GML-Boundaries löschen, neue aus DB einfügen      │
│              (Surfaces → Polygone → LinearRings, Typ: Ground/       │
│               Wall/Roof über SurfaceTypeId)                         │
│           3. lod2Solid komplett neu aus DB-Polygon-IDs (xlink:href) │
│           4. Keine DB-Polygone → Boundaries leer + Solid entfernt   │
│              (keine kaputten XLinks)                                │
│       • Nicht in DB: unverändert durchreichen                       │
│    → Header-Fix (Namespaces/schemaLocation aus der Eingabe)         │
└─────────────────────────────────────────────────────────────────────┘
```

**Was erhalten bleibt:** Building-eigene CityGML-Attribute (measuredHeight, function, Adresse, generische Attribute des Buildings) — nur die Geometrie und die DB-Attribute werden ersetzt.

**Ergebnis-Statistik:** `Features read / Buildings replaced / Buildings unchanged / Buildings no DB polygons`.

#### RunMode Enum (identisch für beide Legacy-Workflows)

Erkennt den Ausführungsmodus anhand der Kommandozeilen-Argumente:

| Wert | Erkennung | Bedeutung |
|------|-----------|-----------|
| `SINGLE_FILE` | `args.length >= 2` und `args[0]` ist eine Datei | Einzelne GML-Datei verarbeiten |
| `BATCH_FOLDER` | `args.length >= 2` und `args[0]` ist ein Ordner | Alle GML-Dateien in einem Ordner |
| `AUTO_BATCH` | `args.length >= 4` und `args[3] == "--auto"` | Dateiliste aus DB, nur geänderte Kacheln |

Die Erkennung erfolgt in `RunMode.detect(String[] args)` — AUTO_BATCH hat Vorrang vor BATCH_FOLDER.

### CompleteWorkflow.java (`legacy.CompleteWorkflow`)

> **Hinweis:** CompleteWorkflow ist der älteste, selektive Update-Ansatz. Aufruf:
> `java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.CompleteWorkflow <Argumente>`.

#### Ablauf

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CompleteWorkflow                              │
├─────────────────────────────────────────────────────────────────────┤
│  Step 1: Datenbank lesen                                            │
│    → DatabaseReader lädt alle Buildings hierarchisch                │
│    → Erstellt Polygon-Index nach gml:id (Map<String, Polygon>)      │
│    → Erstellt Surface-Index (Map<Polygon, Surface>) für O(1)-Lookup │
│    → Zählt: valid / invalid                                         │
├─────────────────────────────────────────────────────────────────────┤
│  Step 2: CityGML verarbeiten                                        │
│    → Liest original boundedBy vom CityModel (ohne Chunking)         │
│    → Iteriert über alle Features mit CityGMLReader (mit Chunking)   │
│    → ObjectWalker besucht jedes Polygon:                            │
│       • Nicht in DB: Keine Änderung                                 │
│       • IsValid=0: Original beibehalten + Log als Attribut          │
│       • IsValid=1: Koordinaten aus DB übernehmen +                  │
│                     Surface-Attribute aktualisieren                  │
│       • Splitting: Neue Polygone in MultiSurface einfügen           │
│       • Log wird immer übertragen (Polygon, Ring, Surface-Ebene)    │
│    → ObjectWalker besucht jedes Building:                           │
│       • Building-Log + DB-Attribute hinzufügen                      │
│       • BuildingPart-Logs hinzufügen                                │
│       • IsValid=0 → kein super.visit, Original-Geometrie bleibt    │
│    → 2. Pass: CompositeSurface-Referenzen für neue Polygone         │
│       • Programmatische citygml4j-Navigation (nicht ObjectWalker)   │
│       • Building.getLod2Solid() + Building.getBuildingParts()        │
│       • Fügt xlink:href für jedes neue Polygon in die Shell ein     │
│    → 3. Pass: Entfernung ungültiger Polygone und Surfaces           │
│       • Polygone mit IsValid=0 (z.B. Tesselated) aus MultiSurface  │
│       • Surfaces mit IsValid=0 als ganze BoundarySurface            │
│       • Leer gewordene BoundarySurfaces (alle Polygone entfernt)    │
│       • xlink:href-Bereinigung im CompositeSurface (lod2Solid)      │
│       • Removal-Log als Attribute am Building (Removal_1, ...)      │
│    → fixHeader: Original-Header wird beibehalten (FME-Kompatibilität)│
│    → Schreibt Feature in Output-GML                                 │
├─────────────────────────────────────────────────────────────────────┤
│  Output: Neue CityGML 1.0 mit aktualisierten Daten                  │
└─────────────────────────────────────────────────────────────────────┘
```

#### Wichtige Methoden

| Methode | Beschreibung |
|---------|--------------|
| `main(String[] args)` | Einstiegspunkt, parst Argumente, wählt Modus (Single/Batch/Auto) |
| `runSingleFile()` | Verarbeitet eine einzelne GML-Datei |
| `runBatchMode()` | Verarbeitet alle GML-Dateien in einem Ordner |
| `runBatchFromDatabase()` | Liest Dateiliste aus DB, verarbeitet nur Dateien mit Modifikationen |
| `configureWriter()` | Konfiguriert `CityGMLChunkWriter` mit Namespace-Prefixes, Schema-Locations und `boundedBy` |
| `findParentSurface()` | Navigiert via `Child`-Interface zum Parent-Surface (WallSurface, RoofSurface, etc.) |
| `addLogToParentSurface()` | Sammelt Logs von Polygon-, Ring- und Surface-Ebene und schreibt sie als Attribute |
| `updateLinearRingCoordinates()` | Setzt neue Koordinaten in einem GML-LinearRing |
| `addGenericAttributes()` | Fügt Attribute hinzu oder aktualisiert bestehende (duplikat-sicher) |
| `findRingByIndex()` | Findet LinearRing nach Index in der DB-Polygon-Liste |
| `addNewRefsToSolid()` | Fügt xlink:href-Referenzen für neue Polygone in die Shell des lod2Solid ein |
| `findParentMultiSurface()` | Navigiert via `Child`-Interface aufwärts zur parent MultiSurface |
| `addNewPolygonToMultiSurface()` | Erzeugt neues GML-Polygon aus DB-Daten und fügt es in die MultiSurface ein |
| `createGmlLinearRing()` | Erzeugt einen GML-LinearRing aus DB-Daten (mit posList, srsDimension=3) |
| `removeSurfacesFromBuilding()` | Entfernt BoundarySurfaces mit IsValid=0 (inkl. xlink:href-Bereinigung) |
| `removeEmptySurfaces()` | Entfernt BoundarySurfaces, deren MultiSurface nach Polygon-Entfernung leer ist |
| `fixHeader()` | Ersetzt den citygml4j-generierten Header durch den Original-Header (FME-Kompatibilität) |

#### Log-Übertragung

Logs werden auf der Parent-Surface (WallSurface/RoofSurface/GroundSurface) als `gen:stringAttribute` geschrieben:

| Log-Quelle | Attribut-Name | Häufigkeit |
|------------|---------------|------------|
| Polygon | `Log_<PolygonIdGml>` | Pro Polygon |
| LinearRing | `Log_<PolygonIdGml>_exterior` / `Log_<PolygonIdGml>_interior_1` | Pro Ring |
| Surface | `Log_Surface_<SurfaceIdGml>` | Einmal pro Surface (dedupliziert) |

Building-Ebene:

| Log-Quelle | Attribut-Name |
|------------|---------------|
| Building | `Log` |
| BuildingPart | `Log_<PartIdGml>` |

#### Surface-Attribut-Aktualisierung

Für `IsValid=1`-Polygone werden folgende Attribute aus der DB auf der Parent-Surface aktualisiert:

```
FACEAREA, NORMAL_AZI, NORMAL_H, Z_Max, Z_Min, Z_MAX_ASL, Z_MIN_ASL
```

Dabei werden existierende Attribute mit gleichem Namen **aktualisiert** (nicht dupliziert).

#### Polygon-/Surface-Entfernung

Polygone und Surfaces mit `IsValid=0` werden aus der GML entfernt. Der Ablauf ist dreistufig:

1. **Polygon-Entfernung**: Polygone mit `IsValid=0` (z.B. Log enthält "Tesselated") werden aus ihrer `MultiSurface` entfernt (das `surfaceMember`-Element wird gelöscht).

2. **Surface-Entfernung**: Surfaces mit `IsValid=0` werden als ganze `BoundarySurface` (WallSurface, RoofSurface, etc.) aus dem Building/BuildingPart entfernt.

3. **Leere-Surface-Bereinigung**: BoundarySurfaces, deren `MultiSurface` nach individueller Polygon-Entfernung leer wurde (alle Polygone einzeln entfernt), werden ebenfalls entfernt.

In allen drei Fällen werden die zugehörigen `xlink:href`-Referenzen im `CompositeSurface` (`lod2Solid`) automatisch bereinigt.

#### Removal-Log

Entfernungen werden als `gen:stringAttribute` am **Building** protokolliert:

| Attribut-Name | Inhalt | Beispiel |
|---------------|--------|----------|
| `Removal_1` | Surface/Polygon + Grund | `Surface UUID_abc entfernt: Tesselated` |
| `Removal_2` | nächste Entfernung | `Polygon UUID_xyz entfernt: Tesselated` |
| `Removal_3` | LinearRing-Log | `LinearRing UUID_xyz_exterior entfernt: Polygon nicht mehr vorhanden` |
| … | fortlaufend nummeriert | |

#### Anchor-lose Merged Surfaces

Beim Polygon-Splitting können neue Surfaces entstehen, die **kein** Original-Polygon als Anker besitzen (alle Polygone der Surface sind neu, z.B. durch Zusammenführung). In diesem Fall wird kein bestehendes Polygon im ObjectWalker besucht, an dem die neuen Polygone angefügt werden könnten.

**Lösung**: Beim Index-Aufbau werden solche "anchor-losen" Surfaces identifiziert. Wenn ein beliebiges Polygon desselben Buildings/BuildingParts im ObjectWalker besucht wird, werden die neuen Polygone der anchor-losen Surface in dessen `MultiSurface` eingefügt (da sie zum gleichen Parent-Building gehören).

#### Header-Korrektur (fixHeader)

citygml4j schreibt beim Output einen eigenen XML-Header mit reduzierten Namespace-Deklarationen. FME und andere Werkzeuge erwarten jedoch den vollständigen Original-Header (inkl. `tex:`, `sch:`, `gml:boundedBy`, etc.).

`fixHeader()` ersetzt nach dem Schreiben den generierten Header durch den Original-Header der Eingabedatei. Alles vor dem ersten `<core:cityObjectMember>` wird 1:1 aus der Originaldatei übernommen.

#### Polygon-Splitting (NewPolygon)

Wenn ein Polygon in der DB geteilt wurde (Log enthält `"NewPolygon"`), werden die neuen Polygone:

1. **In der MultiSurface erstellt**: Neues `gml:Polygon` mit `gml:id`, Exterior-Ring und ggf. Interior-Rings aus der DB. Wird als neues `surfaceMember` in die bestehende `MultiSurface` der Surface eingefügt.

2. **Im CompositeSurface referenziert**: Im `lod2Solid` des Buildings (bzw. BuildingParts) wird ein `xlink:href` auf das neue Polygon eingefügt. Dazu wird programmatisch navigiert:

```
Building.getLod2Solid() → SolidProperty → Solid → Shell → surfaceMembers
Building.getBuildingParts() → BuildingPart.getLod2Solid() → ... (gleicher Pfad)
```

**Wichtig**: Der `ObjectWalker` traversiert die `lod2Solid`-Geometrie bei CityGML 1.0 Daten **nicht**. Daher erfolgt die CompositeSurface-Aktualisierung in einem separaten programmatischen Schritt nach dem ObjectWalker-Durchlauf.

**Wichtig**: BuildingParts werden über `Building.getBuildingParts()` erreicht (direkte citygml4j API), **nicht** über `DeprecatedPropertiesOfAbstractBuilding.getConsistsOfBuildingParts()`. Letzteres wird von citygml4j beim Lesen von CityGML 1.0 nicht befüllt.

---

### Innere Klassen in CompleteWorkflow

#### ProcessingStats

Mutable Counter-Klasse, die während der Entfernungsphase aggregierte Zahlen hält.

| Feld | Typ | Bedeutung |
|------|-----|-----------|
| `removedPolygons` | `int` | Anzahl bisher entfernter Polygone (IsValid=0 oder leer) |
| `removedSurfaces` | `int` | Anzahl bisher entfernter BoundarySurfaces |

Wird als Parameter an `removeSurfacesFromBuilding()` und `removeEmptySurfaces()` übergeben, damit beide Methoden gemeinsam auf denselben Zähler addieren können.

---

#### PolygonIndex

Baut beim Start aus der hierarchischen DB-Struktur (Liste von `Building`-Objekten) mehrere Maps auf, die während des ObjectWalker-Durchlaufs O(1)-Zugriff ermöglichen.

##### Felder

| Feld | Typ | Inhalt |
|------|-----|--------|
| `polygonIndex` | `Map<String, Polygon>` | Polygon-gml:id → DB-Polygon |
| `surfaceByPolygon` | `Map<Polygon, Surface>` | DB-Polygon → DB-Surface |
| `buildingByPolygon` | `Map<Polygon, Building>` | DB-Polygon → DB-Building |
| `newPolygonsByParentPolygon` | `Map<String, List<Polygon>>` | Anker-Polygon-gml:id → neue Split-Polygone |
| `newPolygonsByBuilding` | `Map<String, List<Polygon>>` | Building-gml:id → anchor-lose neue Polygone |
| `polygonsToRemove` | `Set<String>` | gml:ids von Polygonen mit IsValid=0 |
| `surfacesToRemove` | `Set<String>` | gml:ids von Surfaces mit IsValid=0 |
| `removedPolygonIds` | `Set<String>` | gml:ids erfolgreich entfernter Polygone (für xlink-Bereinigung) |
| `surfaceAttributesByPolygon` | `Map<String, Map<String, Object>>` | Polygon-gml:id → Surface-Attribute (FACEAREA, etc.) |
| `surfaceIdByPolygon` | `Map<String, String>` | Polygon-gml:id → Surface-gml:id |
| `anchorlessNewPolygonBuildings` | `Set<String>` | Building-gml:ids mit anchor-losen neuen Polygonen |

##### buildFromDatabase()

Iteriert über alle Buildings/BuildingParts/Surfaces/Polygons mit IsValid-Kaskade:

1. Überspringt Building, BuildingPart oder Surface wenn `IsValid=0`
2. Ruft `categorizePolygon()` für jedes Polygon auf

##### categorizePolygon(Polygon, Surface, String buildingGmlId)

Drei-Wege-Klassifizierung mit Guard Clauses:

| Bedingung | Aktion |
|-----------|--------|
| `polygon.getLog()` enthält `"NewPolygon"` | Neues Split-Polygon — in `newPolygonsByParentPolygon` oder `newPolygonsByBuilding` (anchor-los) |
| `!polygon.isValid()` | Ungültiges Polygon — in `polygonsToRemove` eintragen |
| sonst | Gültiges Polygon — in `polygonIndex`, `surfaceByPolygon`, `surfaceAttributesByPolygon` usw. eintragen |

Bei anchor-losen neuen Polygonen (kein gültig zugehöriges Original-Polygon existiert) wird der Building-gml:id in `anchorlessNewPolygonBuildings` eingetragen.

---

#### GmlUpdateWalker

`ObjectWalker`-Implementierung. Besucht alle `Polygon`- und `Building`-Objekte im CityGML-Objektbaum.

##### Felder

| Feld | Typ | Bedeutung |
|------|-----|-----------|
| `idx` | `PolygonIndex` | Lookup-Strukturen aus der DB |
| `updatedPolygons` | `int` | Zähler: aktualisierte Polygone |
| `updatedRings` | `int` | Zähler: aktualisierte Ringe |
| `processedBuildings` | `int` | Zähler: verarbeitete Buildings |
| `visitedPolygons` | `int` | Zähler: alle besuchten Polygone |
| `skippedBuildings` | `int` | Zähler: übersprungene Buildings (IsValid=0) |
| `createdPolygons` | `int` | Zähler: neu erzeugte Split-Polygone |
| `currentBuildingInDb` | `boolean` | `true` wenn das aktuell besuchte Building in der DB vorkommt |
| `writtenSurfaceLogs` | `Set<String>` | dedupliziert Log-Writes pro Surface-gml:id |
| `processedSurfacesForNewPolygons` | `Set<String>` | verhindert doppeltes Einfügen anchor-loser Polygone |
| `polygonsToRemove` | `Map<String, List<String>>` | Building-gml:id → Liste zu entfernender Polygon-gml:ids |
| `removedPolygonIdsForXlink` | `Set<String>` | gml:ids bereits entfernter Polygone (für xlink-Bereinigung) |
| `SURFACE_ATTRIBUTE_KEYS` | `static final String[]` | `{"FACEAREA","NORMAL_AZI","NORMAL_H","Z_Max","Z_Min","Z_MAX_ASL","Z_MIN_ASL"}` |

##### visit(Polygon)

5-Zeilen-Dispatcher:

```java
if (!idx.polygonIndex.containsKey(gmlId)) {
    handleUnmatchedPolygon(polygon, gmlId);
    return;
}
handleMatchedPolygon(polygon, gmlId);
```

##### handleUnmatchedPolygon(Polygon, String gmlId)

Wird für Polygone aufgerufen, die **nicht** im `polygonIndex` sind (also kein gültiges Update aus der DB haben).

Logik mit Guard Clauses:

1. Falls das Polygon-gml:id in `polygonsToRemove` des Walker: merkt das Polygon zur Entfernung vor
2. Falls das Building (`currentBuildingInDb`) anchor-lose neue Polygone hat und diese Surface noch nicht verarbeitet wurde: fügt die neuen Polygone in die MultiSurface des aktuellen Polygons ein (`addNewPolygonToMultiSurface`), setzt `processedSurfacesForNewPolygons`
3. Falls `currentBuildingInDb = false`: kein DB-Eintrag für dieses Building, Polygon wird unverändert durchgelassen

##### handleMatchedPolygon(Polygon, String gmlId)

Wird für Polygone aufgerufen, die im `polygonIndex` gefunden wurden.

1. **Koordinaten-Update**: Ruft `updateLinearRingCoordinates()` für jeden Ring auf
2. **Attribute-Update**: Iteriert `SURFACE_ATTRIBUTE_KEYS` und aktualisiert Attribute auf der Parent-Surface (duplikat-sicher via `addGenericAttributes()`)
3. **Log-Übertragung**: Ruft `addLogToParentSurface()` auf
4. **Split-Polygone**: Falls das Polygon als Anker für neue Polygone dient (`newPolygonsByParentPolygon`), fügt diese in die MultiSurface ein

##### visit(Building)

- Schlägt das Building anhand seiner `gml:id` in der DB nach, setzt `currentBuildingInDb`
- Wenn `IsValid=0`: schreibt Building-Log als Attribut, kehrt **ohne** `super.visit()` zurück (Original-Geometrie bleibt)
- Wenn `IsValid=1`: schreibt Building-Log, BuildingPart-Logs und Building-Attribute aus JSON, ruft `super.visit()` für Kinder auf

---

### DatabaseReader.java (`legacy.DatabaseReader`)

Liest die hierarchische Datenstruktur aus der SQLite-Datenbank (altes Schema). Nachfolger: `DbReader` (siehe oben, neues Schema).

#### Wichtige Methoden

| Methode | Beschreibung |
|---------|--------------|
| `readAllBuildings()` | Liest komplette Hierarchie: Buildings → BuildingParts → Surfaces → Polygons → LinearRings |
| `getCityGmlFiles()` | Liest alle Dateinamen aus `CityGmlFiles`-Tabelle (Map: FileId → Filename) |
| `hasModificationsForFile(fileId)` | Prüft ob mindestens ein Polygon mit `IsValid=1` für eine Datei existiert |
| `readBuildingPartsForBuilding()` | Liest BuildingParts für ein spezifisches Building |
| `readSurfacesForBuildingPart()` | Liest Surfaces für einen BuildingPart (inkl. JSON-Attribute) |
| `readPolygonsForSurface()` | Liest Polygons mit IsValid und Log |
| `readLinearRingsForPolygon()` | Liest LinearRings mit PosList, IsValid und Log |

#### Altes Datenbankschema (Polygons + LinearRings)

```
CityGmlFiles
    └── Buildings (n)              IsValid, Log, Attributes (JSON)
            └── BuildingParts (n)  IsValid, Log, Attributes (JSON)
                    └── Surfaces (n)       IsValid, Log, Attributes (JSON: FACEAREA, NORMAL_AZI, ...)
                            └── Polygons (n)       IsValid, Log
                                    └── LinearRings (n)    IsValid, Log, PosList
```

Eine Surface konnte MEHRERE Polygone tragen (im neuen Schema: genau eine SurfaceGeometry).

##### IsValid-Kaskade (Legacy — Unterschied zum neuen All-or-Nothing-Valid-Gate)

Die IsValid-Prüfung erfolgte **top-down** und konnte einzelne Ebenen überspringen, statt (wie im neuen Schema) das gesamte Building bei irgendeinem invaliden Teil zu verwerfen:

```
Building (IsValid=0?) → Gesamtes Building überspringen (Original-GML beibehalten)
  └── BuildingPart (IsValid=0?) → Part und alle Surfaces/Polygone überspringen
        └── Surface (IsValid=0?) → Surface und alle Polygone überspringen
              └── Polygon → Koordinaten aus DB übernehmen / neue Polygone erstellen
```

#### JSON-Attribute

Die `Attributes`-Spalte in Buildings, BuildingParts und Surfaces enthält JSON:

```json
{
    "FACEAREA": 12.345,
    "NORMAL_AZI": 180.0,
    "NORMAL_H": 0.0,
    "Z_Max": 125.5,
    "Z_Min": 120.0,
    "Z_MAX_ASL": 225.5,
    "Z_MIN_ASL": 220.0
}
```

Diese werden mit Gson in `Map<String, Object>` geparst.

---

### Model-Klassen (`legacy.model`, altes Schema)

Alle Model-Klassen haben `valid` (boolean) und `log` (String) Felder.

| Klasse | Felder | Beschreibung |
|--------|--------|--------------|
| `Building` | id, buildingIdGml, fileId, attributes, valid, log, buildingParts | Gebäude mit Referenz auf CityGML-Datei |
| `BuildingPart` | id, buildingId, partIdGml, attributes, valid, log, surfaces | Gebäudeteil |
| `Surface` | id, surfaceIdGml, attributes, valid, log, polygons | Oberfläche (Wall/Roof/Ground) |
| `Polygon` | id, surfaceId, polygonIdGml, valid, log, linearRings | Polygon |
| `LinearRing` | polygonId, ringIndex, posList, valid, log | Ring mit Koordinaten |

`LinearRing.getPosListAsArray()` konvertiert den PosList-String in ein `double[]` Array.

---

## citygml4j - Bibliothek

### Architektur

```
┌──────────────────────────────────────────────────────────────────────┐
│  CityGMLContext                                                      │
│    ├── createCityGMLInputFactory()  → CityGMLReader                  │
│    └── createCityGMLOutputFactory() → CityGMLChunkWriter             │
└──────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│  CityGML Object Model                                                │
│    Building                                                          │
│      ├── boundedBy → WallSurface / RoofSurface / GroundSurface       │
│      │                 └── lod2MultiSurface → MultiSurface           │
│      │                                          └── surfaceMember    │
│      │                                                └── Polygon    │
│      │                                                     └── exterior / interior
│      │                                                          └── LinearRing
│      │                                                                └── posList
│      ├── lod2Solid → SolidProperty → Solid → Shell (CompositeSurface)│
│      │                                         └── surfaceMember     │
│      │                                              └── xlink:href   │
│      └── getBuildingParts() → BuildingPart (gleiche Struktur)        │
└──────────────────────────────────────────────────────────────────────┘
```

### Wichtige Klassen

| Klasse | Beschreibung |
|--------|--------------|
| `CityGMLContext` | Factory für Reader/Writer |
| `CityGMLReader` | Liest CityGML-Dateien als Java-Objekte |
| `CityGMLChunkWriter` | Schreibt CityGML-Objekte in eine Datei |
| `ObjectWalker` | Visitor-Pattern zum Durchlaufen des Objektbaums |
| `AbstractFeature` | Basisklasse für alle CityGML-Features |
| `AbstractCityObject` | Basisklasse mit `getGenericAttributes()` |
| `Child` | Interface mit `getParent()` für Navigation im Objektbaum |
| `Building` | Gebäude-Objekt mit `getLod2Solid()`, `getBuildingParts()` |
| `BuildingPart` | Gebäudeteil mit eigenem `getLod2Solid()` |
| `WallSurface`, `RoofSurface`, `GroundSurface` | Surface-Typen |
| `MultiSurface` | Aggregat von Polygonen (lod2MultiSurface) |
| `SolidProperty` | Wrapper für Solid (lod2Solid) |
| `Solid` | Geometrie-Volumen mit `getExterior()` → Shell |
| `Shell` | CompositeSurface mit `getSurfaceMembers()` (xlink:href-Liste) |
| `SurfaceProperty` | Referenz auf Surface/Polygon, hat `setHref()` für xlink |
| `Polygon` | Einzelnes Polygon |
| `LinearRing` | Ring aus Koordinaten |
| `DirectPositionList` | Koordinaten-Liste |
| `StringAttribute` | Generisches String-Attribut |

### ObjectWalker Pattern

```java
feature.accept(new ObjectWalker() {
    @Override
    public void visit(Polygon polygon) {
        String gmlId = polygon.getId();
        // Polygon verarbeiten
        super.visit(polygon);  // Kinder besuchen
    }
    
    @Override
    public void visit(Building building) {
        // Building verarbeiten
        super.visit(building);
    }
});
```

> **Hinweis:** Dieses Pattern wird ausschließlich von den Legacy-Workflows
> (`legacy.CompleteWorkflow`) verwendet. `HealedReplaceWorkflow` liest stattdessen mit
> einer einfachen `while (reader.hasNext())`-Schleife über die Chunk-Features und
> unterscheidet Feature-Typen per `instanceof Building` — kein `ObjectWalker`, kein
> Visitor. Grund: Der Replace-Ansatz ersetzt beim Treffer die komplette Boundary/Solid
> eines Buildings auf einmal, es muss also nicht mehr in die Tiefe (Polygon-Ebene)
> traversiert werden wie beim selektiven Patch-Ansatz von `CompleteWorkflow`.

### Parent-Navigation via Child-Interface

```java
// Vom Polygon zur Parent-Surface navigieren
Object current = polygon;
while (current instanceof Child child) {
    current = child.getParent();
    if (current instanceof AbstractCityObject cityObject) {
        // WallSurface, RoofSurface, etc. gefunden
    }
}
```

### Koordinaten setzen

```java
double[] dbCoords = dbRing.getPosListAsArray();
List<Double> coordList = new ArrayList<>(dbCoords.length);
for (double coord : dbCoords) {
    coordList.add(coord);
}
gmlRing.getControlPoints().getPosList().setValue(coordList);
```

### lod2Solid / CompositeSurface navigieren

Der `ObjectWalker` traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie **nicht** (kein `visit(Solid)` oder `visit(CompositeSurface)` wird aufgerufen). Der Zugriff erfolgt programmatisch über die citygml4j API. **Dieses Muster ist weiterhin aktuell** — `HealedReplaceWorkflow.rebuildSolid()` baut den Solid für jedes Ziel (Building oder BuildingPart) exakt nach diesem Shell/Solid/SurfaceProperty-xlink-Schema neu auf, unabhängig vom ObjectWalker-Verzicht an anderer Stelle:

```java
// Building → lod2Solid → Solid → Shell → surfaceMembers
SolidProperty solidProp = building.getLod2Solid();
Solid solid = (Solid) solidProp.getObject();
Shell shell = solid.getExterior().getObject();
List<SurfaceProperty> refs = shell.getSurfaceMembers();  // xlink:href-Einträge

// Neue Referenz hinzufügen
SurfaceProperty newRef = new SurfaceProperty();
newRef.setHref("#neuePolygonId");
refs.add(newRef);

// BuildingParts über Building.getBuildingParts() (NICHT deprecated properties!)
for (var partProp : building.getBuildingParts()) {
    BuildingPart part = partProp.getObject();
    SolidProperty partSolid = part.getLod2Solid();  // eigener lod2Solid
    // ... gleiche Navigation wie oben
}
```

### Attribute duplikat-sicher hinzufügen

```java
List<AbstractGenericAttributeProperty> attrs = cityObject.getGenericAttributes();
for (AbstractGenericAttributeProperty prop : attrs) {
    if (prop.getObject() instanceof StringAttribute existing && name.equals(existing.getName())) {
        existing.setValue(newValue);  // Existierenden Wert aktualisieren
        return;
    }
}
// Neues Attribut hinzufügen
attrs.add(new AbstractGenericAttributeProperty(new StringAttribute(name, value)));
```

---

## Wichtige Hinweise

### maven-shade-plugin: ServicesResourceTransformer

Das Fat-JAR wird mit `maven-shade-plugin` gebaut. **Kritisch** ist der `ServicesResourceTransformer`:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

Ohne diesen Transformer werden die `META-INF/services`-Dateien nicht korrekt zusammengeführt. Das führt dazu, dass citygml4j die GML 3.1.1 Adapter nicht registriert und Geometrie-Elemente (MultiSurface, Polygon) als `null` geparst werden — obwohl das XML korrekt geladen wird.

### DefaultReferenceResolver NICHT verwenden

```java
// FALSCH — bricht inline-Geometrie:
// DefaultReferenceResolver.resolveReferences(feature);

// RICHTIG — ObjectWalker greift direkt auf die Geometrie zu
feature.accept(new ObjectWalker() { ... });
```

### CityGML 1.0 Output-Konfiguration

Der Writer wird mit den Schema-Locations des GDI-DE Repository konfiguriert und verwendet die Namespace-Prefixes der Original-Datei (inkl. `core:`, `tex:`, `sch:`, etc.).

### TIN / TriangulatedSurface: Schema-Konformität vs. Werkzeug-Kompatibilität

`gml:TriangulatedSurface` ist über die Substitutionskette `TriangulatedSurface → Surface →
_Surface` regulärer Bestandteil von GML 3.1.1/CityGML 1.0 (bestätigt durch `citygml-tools`,
das solche Dateien klaglos zu CityJSON konvertiert). `HealedReplaceWorkflow` schreibt sie
im `AS_IN_DATABASE`-Modus 1:1 so, wie der Healer sie in `SurfaceGeometry.GeometryTypeId=1`
abgelegt hat — direkt per xlink aus der Shell referenziert, exakt wie ein `gml:Polygon`.

**Bekannte Werkzeug-Lücke:** CityDoctor 3.18.2 kann eine per xlink referenzierte TIN nicht
auflösen und meldet fälschlich `GE_S_NOT_CLOSED`, obwohl die Geometrie tatsächlich
geschlossen ist (siehe Memory-Notiz „CityDoctor TIN false positive"). Für diesen Fall
existiert `PolygonOnlyReplaceWorkflow` (siehe oben) als reine Ausgabe-Variante — an der
zugrundeliegenden Geometrie ändert sich dabei nichts, nur die GML-Repräsentation
(N einzelne Polygone statt einer TriangulatedSurface).

---

## Bekannte Einschränkungen

1. **Interior Rings**: Werden unterstützt, aber weniger getestet als Exterior Rings.
2. **CityGML-Version**: Nur CityGML 1.0 wird unterstützt (Lesen und Schreiben).
3. **Attribut-Typen**: Aus der DB werden alle Attribute als `gen:stringAttribute` geschrieben. Numerische Typen in der DB werden zu Strings konvertiert.
4. **ObjectWalker und lod2Solid**: Der `ObjectWalker` von citygml4j traversiert bei CityGML 1.0 die `lod2Solid`-Geometrie nicht. CompositeSurface-Änderungen (z.B. neue xlink:href) müssen programmatisch über `Building.getLod2Solid()` erfolgen.
5. **BuildingParts-Zugriff**: `Building.getBuildingParts()` ist die korrekte API. `DeprecatedPropertiesOfAbstractBuilding.getConsistsOfBuildingParts()` wird bei CityGML 1.0 von citygml4j nicht befüllt.
6. **Triangulierte Wände blockieren nachgelagerte LoD3-Bearbeitung**: Wenn der Healer eine
   Wand nicht planarisieren konnte und sie deshalb als TIN ablegt (`GeometryTypeId=1`),
   kann eine nachgelagerte Pipeline (z.B. `LoD2_zu_LoD3` für Fenster-/Türeinbau) auf dieser
   Wand keine saubere, planare Öffnung mehr einschneiden — die Wandfläche ist kein
   einzelnes Polygon mehr, sondern N Dreiecke. Betroffene Wände sind über
   `Stats.buildingsWithTinWall` in der Konsolen-Ausgabe von `HealedReplaceWorkflow`
   identifizierbar.
7. **CityDoctor 3.18.2 TIN-xlink-Limitation**: Siehe „Wichtige Hinweise" oben — führt zu
   falsch-positiven `GE_S_NOT_CLOSED`-Meldungen bei per xlink referenzierten
   `TriangulatedSurface`-Elementen. Workaround: `PolygonOnlyReplaceWorkflow`.

---

## Beispiel-Workflow (historisch, Legacy)

> Die folgenden Zahlen stammen aus einem tatsächlichen, historischen Lauf von
> `legacy.CompleteWorkflow` auf dem alten DB-Schema. Es liegt für `HealedReplaceWorkflow`
> (neues Schema) noch kein vergleichbar dokumentierter Lauf vor — hier werden bewusst
> keine entsprechenden Zahlen erfunden.

### Szenario: Einzelne Kachel mit validierten Geometrien

**Datenbank-Inhalt (BuildingParts.db):**
- 1.487 Buildings, 1.735 BuildingParts, 14.771 Surfaces, 14.755 Polygons, 14.761 LinearRings
- 12.801 Polygone mit `IsValid=1` (Koordinaten korrigiert)
- 1.954 Polygone mit `IsValid=0` (nicht reparierbar, Log wird übertragen)

**Ausführung:**
```powershell
java -jar target/sql2gml-complete.jar
```

**Log-Ausgabe:**
```
=== Complete Workflow: DB → CityGML ===
--- Step 1: Reading Database ---
Read 1487 buildings
Created polygon index with 14755 entries
Polygon status: 12801 valid (update from DB), 1954 invalid (keep original, add Log)

--- Step 2: Processing CityGML ---
Reading and updating features...

--- Results ---
Features read: 3801
Polygons visited by GeometryWalker: 61978
Buildings processed: 3801
Polygons updated (isValid=1): 12801
Polygons kept original + Log added (isValid=0): 1954
Linear Rings updated: 12807
Polygons not in DB (unchanged): 47223

✓ SUCCESS: CityGML file created with updated coordinates!
```

**Ergebnis (Output-GML):**
- 12.801 Polygone mit korrigierten Koordinaten aus der DB
- 12.807 LinearRings aktualisiert (einige Polygone haben Interior-Rings)
- 1.954 Polygone beibehalten + Log als Attribut
- Surface-Attribute (FACEAREA etc.) aktualisiert (existierende ersetzt, keine Duplikate)
- 47.223 Polygone unverändert (nicht in DB enthalten)

---

## Weiterführende Links

- [citygml4j GitHub](https://github.com/citygml4j/citygml4j)
- [CityGML Standard](https://www.citygml.org/)
- [OGC CityGML 2.0](https://www.ogc.org/standards/citygml)
