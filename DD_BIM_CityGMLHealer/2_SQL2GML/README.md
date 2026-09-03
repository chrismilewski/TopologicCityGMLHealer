# sql2gml

Java-Tool zum Zurückschreiben von validierten und reparierten CityGML-Geometrien aus einer SQLite-Datenbank (neues Healer-Schema) in CityGML 1.0 Dateien.

Die Datenbank enthält eine hierarchische Gebäudestruktur (Buildings → BuildingParts → Surfaces → SurfaceGeometries → PosLists) mit korrigierten Koordinaten und Validierungsprotokollen. Jede Surface trägt genau eine SurfaceGeometry, die entweder ein klassisches `gml:Polygon` (Außenring + Löcher) oder eine `gml:TriangulatedSurface` (Notlösung des Healers für nicht planarisierbare Flächen) ist.

Der Standard-Workflow ist der **HealedReplaceWorkflow** (Main-Class des JARs, gestartet per `java -jar`): Für jedes Gebäude, das UND dessen gesamte Unterhierarchie (BuildingParts/Surfaces/Geometrien/PosLists) in der DB vollständig valide sind, wird die **gesamte Geometrie komplett ersetzt** — Solid samt XLinks neu erzeugt, `gml:name` je Fläche deterministisch aus dem Flächentyp gesetzt (`LOD2_Wall`/`_Roof`/`_Ground`). Gebäude ohne DB-Eintrag oder mit irgendeinem invaliden Teil bleiben **unverändert** (kein teilweiser Ersatz, keine Hüllen-Lücken). Ein Solid-Merge-Gate lehnt zudem jedes Gebäude ab, bei dem entweder der Healer per Party-Wall-Merge/Split einen Solid ohne 1:1-Entsprechung zu einem bestehenden BuildingPart erzeugt hat, oder umgekehrt ein bestehender BuildingPart in der Healer-DB überhaupt keinen Eintrag mehr hat — der Healer-Code für Party-Wall-Fälle ist noch nicht ausgereift genug (beobachtete Fälle mit sichtbar kaputter Geometrie), solche Gebäude bleiben bis auf Weiteres unverändert statt einen Teil stillschweigend zu verlieren. Building-Attribute (measuredHeight, function, …) bleiben erhalten, DB-Logs werden als generische Attribute geschrieben.

Daneben existiert **PolygonOnlyReplaceWorkflow** — identischer Aufruf, schreibt aber niemals `gml:TriangulatedSurface`, sondern zerlegt trianguliert Flächen in einzelne `gml:Polygon` (ein Dreieck je Polygon). Grund: CityDoctor 3.18.2 kann eine per xlink referenzierte TIN nicht auflösen und meldet fälschlich `GE_S_NOT_CLOSED`, obwohl `TriangulatedSurface` CityGML-1.0-konform ist.

Die **älteren, per `java -cp … de.mpsc.sql2gml.legacy.<Klasse>` erreichbaren Workflows** (`ReplaceWorkflow`, `CompleteWorkflow`) arbeiten auf dem alten DB-Schema (Polygons + LinearRings statt SurfaceGeometries + PosLists) und werden nicht mehr weiterentwickelt — siehe Doku.md, Abschnitt „Legacy".

---

## Voraussetzungen

| Werkzeug | Mindestversion | Download |
|----------|---------------|----------|
| **Java JDK** | 21 LTS | [Adoptium / Eclipse Temurin](https://adoptium.net/de/temurin/releases/?version=21) |
| **Apache Maven** | 3.6 | [maven.apache.org/download](https://maven.apache.org/download.cgi) |

Nach der Installation prüfen:

```powershell
java -version    # muss 21.x.x zeigen
mvn -version     # muss 3.6+ zeigen
```

---

## Bibliotheken

Alle Abhängigkeiten werden **automatisch von Maven heruntergeladen** — kein manuelles Installieren notwendig (Maven ist das Java-Äquivalent zu `pip` oder `npm`). Sie sind in `pom.xml` deklariert und werden beim ersten Build aus dem [Maven Central Repository](https://central.sonatype.com/) bezogen.

| Bibliothek | Version | Zweck |
|------------|---------|-------|
| **citygml4j-xml** | 3.2.7 | CityGML-Dateien lesen und schreiben (CityGML 1.0 / 2.0 / 3.0); stellt das vollständige CityGML-Objektmodell bereit (`Building`, `WallSurface`, `Polygon`, `TriangulatedSurface`, …) sowie `CityGMLReader`, `CityGMLChunkWriter` |
| **sqlite-jdbc** | 3.47.1.0 | JDBC-Treiber für SQLite-Datenbanken; ermöglicht den direkten Zugriff auf `.db`-Dateien ohne Datenbank-Server |
| **gson** | 2.11.0 | JSON-Parsing; liest die `Attributes`-Spalten (Building, Surface, …) als `Map<String, Object>` |
| **slf4j-simple** | 2.0.16 | Einfache Logging-Ausgabe auf der Konsole (kein Konfigurationsfile notwendig) |

---

## Installation und Build

```powershell
# 1. Repository klonen
git clone https://github.com/dd-bim/gml2sql2gml.git
cd gml2sql2gml/sql2gml_neu

# 2. JAVA_HOME setzen (Pfad ggf. anpassen)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# 3. Fat-JAR bauen — Maven lädt dabei alle Bibliotheken automatisch herunter
mvn clean package -q
```

Ergebnis: `target/sql2gml-complete.jar` — ein eigenständiges JAR mit allen Abhängigkeiten. Main-Class ist `HealedReplaceWorkflow`.

---

## Verwendung

### Modus 1 — Einzelne Datei

```powershell
java -jar target/sql2gml-complete.jar <input.gml> <database.db> [<output.gml>]
```

Ohne expliziten Output wird `<input>_new.gml` neben der Eingabe erzeugt.

Beispiel:

```powershell
java -jar target/sql2gml-complete.jar `
    D:\data\LoD2_33_416_5656.gml `
    D:\data\BuildingParts.db `
    D:\output\LoD2_33_416_5656_updated.gml
```

### Modus 2 — Batch (Ordner)

Verarbeitet alle `.gml`-Dateien in einem Ordner:

```powershell
java -jar target/sql2gml-complete.jar <inputFolder> <database.db> [<outputFolder>]
```

### Modus 3 — Auto-Batch (empfohlen für große Datensätze)

Liest die Dateiliste direkt aus der `CityGmlFiles`-Tabelle der Datenbank. Überspringt automatisch Kacheln ohne valide Geometrien:

```powershell
java -jar target/sql2gml-complete.jar <database.db> <inputFolder> <outputFolder> --auto
```

### PolygonOnlyReplaceWorkflow (keine TriangulatedSurface in der Ausgabe)

Identischer Aufruf zu allen drei Modi oben, nur mit anderer Main-Class:

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.PolygonOnlyReplaceWorkflow <input.gml> <database.db> [<output.gml>]
```

### Legacy-Workflows (altes DB-Schema, nicht weiterentwickelt)

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.ReplaceWorkflow <gleiche Argumente>
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.legacy.CompleteWorkflow <gleiche Argumente>
```

---

## Hilfs-Tools

### ExtractSst — Gebäude mit `sst`-Attribut extrahieren

Extrahiert nur Gebäude mit dem generischen Attribut `name="sst"`. Textstrom-basiert (`GmlMemberFilter`) — effizient auch für sehr große Kacheln.

```powershell
# Einzelne Datei (Output neben der Eingabe als _sst.gml)
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml>

# Expliziter Ausgabepfad
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <input.gml> <output.gml>

# Gesamten Ordner verarbeiten
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractSst <folder> [<outputFolder>]
```

### ExtractBuildings — Gebäude nach `gml:id` extrahieren

```powershell
java -cp target/sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> [ID2] ...
```

---

## Ausführliche Dokumentation

**→ [Doku.md](Doku.md)** — Vollständige Klassen-Dokumentation, Datenbankschema (neu + Legacy), Algorithmen und citygml4j-Codebeispiele.

## Lizenz

Projekt: `dd-bim/gml2sql2gml`
