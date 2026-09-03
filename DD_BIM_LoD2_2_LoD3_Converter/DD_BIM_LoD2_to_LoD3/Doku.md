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

### Batch-Modus (2026-09-02): ganzer Ordner mit Kacheln

Wird automatisch erkannt, wenn das erste Argument ein Verzeichnis statt einer Datei ist (kein
separates Flag noetig, analog zu `HealedReplaceWorkflow` in `sql2gml_neu`). Verarbeitet ALLE
`.gml`-Dateien im Ordner NACHEINANDER (sequenziell — siehe Begruendung unten) und legt unter dem
Output-Argument einen neuen Unterordner an, dessen Name wie bei den Dateien LoD2→LoD3 umbenannt
ist:

```bash
java -jar target/lod2-zu-lod3-pipeline.jar \
  CityGML_LoD2_260813/ \
  Baukoerpermodule_json/ \
  output/ \
  [dgm-pfad]
# → output/CityGML_LoD3_260813/LoD3_<Kachelname>.gml, je eine Datei pro Eingabe-Kachel
```

Dateiname UND Ordnername folgen derselben Umbenennungsregel: `LoD2_...` → `LoD3_...`, bei
Dateien zusaetzlich `_BuildingPreferences` entfernt (identisch zum bisherigen Einzeldatei-
Verhalten). Enthaelt der Eingabe-Ordnername kein `LoD2`, wird stattdessen `_LoD3` angehaengt.

**Bewusst sequenziell, nicht parallel:** `ModuleParametersLoader` cached JSON-Modulparameter
intern in einer einfachen (nicht thread-sicheren) `HashMap`, die bei jedem `getParameters()`-
Aufruf befuellt wird. Eine gemeinsame Instanz ueber parallel laufende Kacheln haette hier ein
echtes Race-Condition-Risiko (korrupte Map). Da eine einzelne ~150 MB-Kachel in der Praxis nur
~30-60s braucht, ist sequenzielle Verarbeitung fuer eine Kachelmenge in der Groessenordnung
"eine Stadt" (getestet: 98 Kacheln, 4,3 GB) mit grob 30-60 Minuten Gesamtlaufzeit voellig
ausreichend — der Aufwand fuer sicheren parallelen Umbau (z.B. eine eigene Loader-Instanz pro
Worker-Thread) wurde bewusst nicht betrieben.

Jede Kachel wird unabhaengig mit frischen Generator-/Statistik-Instanzen verarbeitet (keine
Kachel-uebergreifende Zustands-Kontamination); ein DGM (falls angegeben) wird EINMAL fuer den
ganzen Batch geladen und fuer jede Kachel wiederverwendet (unterstuetzt bereits Verzeichnis-
Mosaike ueber mehrere Kacheln hinweg). Schlaegt eine einzelne Kachel fehl (Exception), wird sie
geloggt und uebersprungen, der Batch laeuft mit den restlichen Kacheln weiter — am Ende steht
eine Liste der fehlgeschlagenen Dateinamen. Nach dem letzten Tile wird zusaetzlich eine
aufsummierte Batch-Gesamtstatistik ueber alle erfolgreichen Kacheln ausgegeben (dieselbe
Aufschluesselung wie pro Einzeldatei, inkl. Skip-Gruende und Per-Geschoss-Aufschluesselung bei
Fenstern).

### Erster kompletter Dresden-Lauf (2026-09-02): 98 Kacheln, 141.670 Gebäude

Vollstaendiger Batch-Lauf ueber `CityGML_LoD2_260813_plusWAWUR/CityGML_LoD2_260813/` (98
Kacheln, 4,3 GB) mit dem vollen Dresden-DGM (117 GeoTIFF-Kacheln, `sqltest/DGM/Dresden/`) fuer
praezise TerrainIntersectionCurves. Erster Durchlauf: **97 von 98 Kacheln erfolgreich**, eine
(`33_408_5654`) brach komplett ab mit einem JTS `TopologyException: found non-noded
intersection` in `SlabClippingUtils.roofAreaBelowZ()` (Zeile 99, `union.union(jtsPoly)`) — ein
bekanntes JTS-Overlay-Robustheitsproblem bei zwei Liniensegmenten, die nur ~0,9mm auseinander
liegen (sub-mm-naher, aber nicht exakt deckungsgleicher Dachschnitt). Anders als die spaetere
JTS-Operation in derselben Methode (`clipSlabAtZ`s `footprint.difference(...)`, bereits per
try/catch abgesichert) hatte dieser fruehere Union-Aufruf GAR KEINE Absicherung — eine einzelne
Kante in einem von 141.670 Gebaeuden riss dadurch die GESAMTE Kachel (3.209 Gebaeude) mit.

**Fix:** `union.union(jtsPoly)` in `roofAreaBelowZ()` jetzt einzeln try/catch-abgesichert — bei
Fehlschlag wird nur dieses eine Dachstueck NICHT von der Ausschlussflaeche ausgenommen (Union
bleibt auf dem bisherigen Stand, Warnung geloggt), analog zum bestehenden Fallback-Muster in
`clipSlabAtZ`. Konservativ: die betroffene Slab-Flaeche an dieser einen Stelle bleibt dadurch
ggf. geringfuegig zu gross statt korrekt verkleinert — weit besser als der komplette
Kachel-Absturz.

**Verifikation:** (1) betroffene Kachel isoliert erneut verarbeitet — laeuft jetzt komplett
durch, genau 1 neue Warnung an exakt derselben Koordinate. (2) Kompletter Dresden-Lauf mit Fix
wiederholt: **98 von 98 Kacheln erfolgreich**, genau 1 Warnung im gesamten Lauf (dieselbe
Stelle). (3) Statistischer Vollvergleich statt erneutem 14-GB-Datei-Diff: jede einzelne
Kennzahl der Batch-Gesamtstatistik von Lauf 1 (97 Kacheln) PLUS die Kennzahlen der isolierten
Fix-Verifikation ergeben exakt die Kennzahlen von Lauf 2 (98 Kacheln) — z.B. 138.461 + 3.209 =
141.670 Gebaeude, 125.303 + 3.688 = 128.991 Tueren, 40 + 1 = 41 Pinch-Point-Aufspaltungen (alle
neun geprueften Kennzahlen exakt passend). Beweist rechnerisch: der Fix hat ausschliesslich die
eine betroffene Kachel veraendert, alle anderen 97 sind exakt unveraendert geblieben.

Ergebnis: `sqltest/output/CityGML_LoD3_260813/` — 98 Dateien, ~14 GB, 141.670 Gebaeude,
63.878 Keller, 236.940 Geschosse, 128.991 Tueren, 1.878.090 Fenster, 192.311 Balkone,
93.675 Dachfenster, 41 Pinch-Point-Aufspaltungen. Gesamtlaufzeit ~16 Minuten.

#### Validierung des kompletten Dresden-Laufs: val3dity + CityDoctor2 (2026-09-02)

Erste city-weite Validierung in diesem Projekt (bisher max. 1 Kachel, hier 98 — Faktor ~37).
Beide Tools jeweils per Batch-Skript ueber alle 98 Kacheln laufen lassen (val3dity ohne
`--overlap_tol`, die bei Mehrteil-Gebaeuden dokumentiert instabile Option, siehe oben — reine
`601`-Zahl daher wie gewohnt kein verlaessliches Qualitaetsmass fuer Mehrteil-Gebaeude).

**Ein echter Stolperstein unterwegs:** der erste CityDoctor2-Batch-Versuch scheiterte bei JEDER
Kachel mit `FileNotFoundException` auf die YAML-Konfigurationsdatei — Ursache: der Umlaut "ü" in
`Konfig_für Test.yml` wurde beim Verketten Bash-Tool → `powershell.exe`-Subprozess → `java.exe`
korrumpiert (Windows-Codepage-Problem ueber mehrere Prozess-Ebenen). Fix: Config auf einen
umlautfreien Dateinamen kopiert, Pfad im Batch-Skript darauf umgestellt — kein Problem der
Pipeline oder der Tools selbst, reine Batch-Skript-Infrastruktur.

**val3dity — 98/98 Kacheln, ~54 Minuten:**
- 526.292 Features, davon 517.671 valide (**98,36%**)
- 933.406 Primitive, davon 929.440 valide (**99,58%**)
- Fehlercodes (city-weit aufsummiert): 601 BUILDINGPARTS_OVERLAP 4.716 (siehe oben — groesstenteils
  Nef-Erosions-Artefakt bei geteilten Party-Walls, kein reales Overlap), 303 NON_MANIFOLD_CASE
  1.804, 204 NON_PLANAR_POLYGON_NORMALS_DEVIATION 791, 203 NON_PLANAR_POLYGON_DISTANCE_PLANE 501,
  307 POLYGON_WRONG_ORIENTATION 490, 302 SHELL_NOT_CLOSED 329, 201 INTERSECTION_RINGS 172,
  104 RING_SELF_INTERSECTION 123, 102 CONSECUTIVE_POINTS_SAME 115, 207 INNER_RINGS_NESTED 65,
  101 TOO_FEW_POINTS 25, 206 INNER_RING_OUTSIDE 20, 305 MULTIPLE_CONNECTED_COMPONENTS 18,
  306 SHELL_SELF_INTERSECTION 16, 205 POLYGON_INTERIOR_DISCONNECTED 2.

**CityDoctor2 — 98/98 Kacheln, ~76 Minuten** (mit Nutzer-Konfiguration, `-c`, korrektes
Arbeitsverzeichnis, `numberOfRoundingPlaces=3` bestaetigt in jedem Report):
- 141.670 Gebaeude geprueft (exakt deckungsgleich mit der Pipeline-eigenen Zaehlung — starke
  Konsistenzpruefung zwischen Generierung und Validierung)
- 130.673 fehlerfrei (**92,24%**), 10.997 mit mind. einem Fehler (7,76%)
- Fehlercodes (city-weit aufsummiert): GE_P_NON_PLANAR_POLYGON_DISTANCE_PLANE 15.796 (dominant,
  bekanntes Rauschen aus unabhaengig digitalisierten LoD2-Quellflaechen), GE_S_NON_MANIFOLD_EDGE
  1.124, GE_S_NOT_CLOSED 283, GE_R_CONSECUTIVE_POINTS_SAME 177, GE_R_SELF_INTERSECTION 140,
  GE_S_SELF_INTERSECTION 140, GE_P_INTERSECTING_RINGS 122, GE_R_TOO_FEW_POINTS 103,
  GE_S_NON_MANIFOLD_VERTEX 86, GE_P_INTERIOR_DISCONNECTED 74, GE_P_INNER_RINGS_NESTED 66,
  GE_P_HOLE_OUTSIDE 18, GE_S_MULTIPLE_CONNECTED_COMPONENTS 7, GE_S_ALL_POLYGONS_WRONG_ORIENTATION 1,
  1× nicht identifizierter "Unknown_error" bei `33_414_5664` (1 von 141.670 Gebaeuden, 0,0007% —
  nicht weiter untersucht).

**Einordnung:** beide Fehlerprofile sind in Art und relativer Groessenordnung konsistent mit den
bereits ausfuehrlich untersuchten Einzelkachel-Ergebnissen (siehe die vielen Bugfix-Abschnitte
oben) — kein neues, city-weit auftretendes Fehlerbild entdeckt. Die dominanten Kategorien
(BUILDINGPARTS_OVERLAP, NON_PLANAR_POLYGON_DISTANCE_PLANE) sind beide bereits mechanistisch
erklaert (Nef-Erosion bzw. LoD2-Quelldaten-Digitalisierungsrauschen), nicht neu entdeckt. Diese
city-weite Zahlenbasis ist die erste ihrer Art in diesem Projekt und dient ab jetzt als
Referenzpunkt fuer kuenftige Vollstadt-Laeufe.

**Verifiziert (2026-09-02):** (1) Einzeldatei-Modus nach dem main()-Umbau weiterhin
byte-identisch zum Vortagesstand (Diff nur laufspezifischer Zeitstempel). (2) Batch-Modus
liefert fuer dieselbe Kachel exakt dasselbe Ergebnis wie der Einzeldatei-Modus (Diff nur
Zeitstempel) — geprueft an einem 2-Kacheln-Testordner (33_412_5656 + 33_416_5656). Ein erster
Testlauf verglich faelschlich zwei UNTERSCHIEDLICHE Quelldateien fuer "416_5656" (eine aus
`sqltest/`, eine aus `LOD2_citygml/CityGML_LoD2_260813_plusWAWUR/`, tatsaechlich zwei
verschiedene Datensaetze) und zeigte fast 10 Mio. Diff-Zeilen — nach Korrektur des Testaufbaus
(dieselbe Quelldatei fuer beide Vergleichsseiten) war das Ergebnis sauber. Testfehler, kein
Code-Bug — Lehre: bei kuenftigen Batch-Tests immer denselben Quelldatei-Pfad fuer Vorher/
Nachher-Vergleiche verwenden, nicht "irgendeine Kopie derselben Kachel".

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

### Bugfix: Fenster/Tueren/Balkone hinter Anbau — Party-Wand-Erkennung auf Segment-Ebene (2026-08-20)

**Problem** (Nutzer-Fund an Gebaeude `DESNALK0pF001gHh`, Screenshot): ein Fenster wird fast
vollstaendig von einem angebauten Gebaeudeteil verdeckt gerendert. Verifiziert mit echten
Koordinaten: `Face_K0pF001gHh_1_6_GF_2` (BuildingPart 1, Hauptbau) ist eine geknickte Wand —
sie deckt zwei Grundriss-Kanten in einem WallSurface ab: (417127.227, 5656312.386) →
(417131.029, 5656315.19) → (417134.409, 5656317.683), perfekt kollinear, kein echter Winkel. Links
vom Knick (u≈0) sitzt eine Tuer, rechts davon (u≈4,7–6,8m) das verdeckte Fenster — und genau
dieser Abschnitt ist exakt deckungsgleich mit der eigenen, ungeknickten Wand
`Face_K0pF001gHh_0_1_GF_1` von BuildingPart 0 (dem Anbau). Echte Trennwand zwischen zwei
Gebaeudeteilen, keine Aussenwand.

**Ursache:** `CityGmlUtils.wallBottomMidKey` (der Party-Wand-Dedup fuer `WindowGenerator`/
`DoorGenerator`) mittelte ALLE Bodenpunkte einer Wand zu einem einzigen Punkt. Bei einer
geknickten Wand (3 Bodenpunkte) landet dieser Mittelpunkt weit weg vom Mittelpunkt der
ungeknickten Nachbarwand (2 Bodenpunkte) und matcht nicht — dieselbe Knick-Problematik wie beim
`gjj`-Bug im StoreyGenerator (siehe oben), nur in der Oeffnungslogik. `DoorGenerator` hatte
zusaetzlich einen unabhaengigen Pro-Tuer-Check (Tuermitte gegen Grundriss-Footprint,
`FOOTPRINT_SHARE_TOL`), aber nur Mittelpunkt-basiert und nur fuer GF gedacht. `WindowGenerator`
hatte GAR keinen Pro-Fenster-Fallback. `BalconyGenerator` hatte GAR keinen Deckungs-Check.

**Warum Wand-gegen-Wand statt Grundriss-gegen-Punkt:** Grundrisse sind reine 2D-Footprints ohne
Geschoss-Info. Ein einstoeckiger Anbau bleibt in der Draufsicht auf JEDER Hoehe kollinear zum
Hauptbau — ein reiner Footprint-Test wuerde ein echt freiliegendes Obergeschoss-Fenster (das ueber
die Anbau-Traufe hinausragt) faelschlich als "verdeckt" markieren. Wand-gegen-Wand, gefiltert nach
`zMin`-Uebereinstimmung (= gleiches Geschoss), ist der einzige ueber GF/UF/BA hinweg korrekte
Ansatz.

**Fix:** neue Helfer in `CityGmlUtils.java` — `collectAllWallSurfaces(Building)` (alle Waende
ueber alle Targets), `computeCoveredSpans(thisWall, allWallsOfBuilding, edgeStart, dirX, dirY,
wallLength, thisZMin)` (Deckungs-Bereiche entlang der eigenen Unterkante, prueft ALLE Bodenpunkte
der anderen Wand, damit auch eine geknickte ANDERE Wand korrekt erfasst wird — nicht nur Start/
Ende), `overlapsAnySpan`, `isFullyCovered`. Ist die GESAMTE Wand betroffen, wird sie wie bisher
komplett uebersprungen (`SkipReason.COVERED_BY_PART` bzw. `wallsSkippedCoveredByPart`); ist nur
ein Abschnitt betroffen, wird jedes Fenster/jede Tuer/jeder Balkon einzeln gegen die
Deckungs-Bereiche geprueft (`windowsDroppedCoveredByPart`, `doorsSkippedCovered`,
`balconiesSkippedCoveredByPart`) — nur die tatsaechlich (ganz oder teilweise) betroffenen
Oeffnungen fallen weg, unbetroffene auf demselben WallSurface bleiben erhalten. Der alte, wandweite
`wallBottomMidKey`-Dedup (`CityGmlUtils`) sowie `DoorGenerator`s Mittelpunkt-Footprint-Check
(`distPointToRingXY`/`distPointToSegmentXY`/`FOOTPRINT_SHARE_TOL`) wurden vollstaendig ersetzt und
geloescht — der neue Mechanismus deckt den alten Anwendungsfall (ganze Wand dupliziert) als
Spezialfall mit ab.

**Verifiziert:** `gHh` isoliert — das Fenster auf dem gemeinsamen Abschnitt ist aus dem
Wandpolygon verschwunden, die Tuer auf dem unbetroffenen Abschnitt DERSELBEN Wand bleibt
unveraendert erhalten; schema-valide. 14-Testgebaeude-Menge schema-valide (Fenster-Gesamtzahl
unveraendert — `gHh` ist nicht Teil dieser Menge). Volle 3.801-Gebaeude-Kachel: Fenster
35.543→35.391 (−152, −0,43 %), Balkone 1.352→1.295 (−57), Tueren unveraendert (2.733); Geschosse/
Wandsegmente exakt unveraendert (6.524/66.875 — dieser Fix ruehrt nicht an der Geschoss-Einteilung);
schema-valide; **val3dity-Fehlerprofil verbessert sich sogar** an zwei Solid-Shell-Kategorien
(302 SHELL_NOT_CLOSED 38→20, 601 BUILDINGPARTS_OVERLAP 322→316 — plausibel, da entfernte
Fenster-/Tueroeffnungen auf tatsaechlichen Trennwaenden genau solche Schalen-Defekte an dieser
Stelle verursacht haben koennen), alle anderen Kategorien exakt unveraendert
(102=6/104=5/201=2/204=31/303=13/306=2/307=8).

### Bugfix: Fenster lag exakt an der Traufe an (GE_P_INTERIOR_DISCONNECTED, 2026-08-24)

**Problem** (Nutzer-Fund via CityDoctor2 an Gebaeude `DESNALK0pF001imo`, Polygon `Poly_00040DQ_0_6`):
in seltenen Faellen liegt die Oberkante eines Fensters exakt auf der Oberkante seiner Wand — der
Innenring (Fenster) beruehrt dann den Aussenring (Wand) an zwei Punkten, was CityDoctor2 als
`GE_P_INTERIOR_DISCONNECTED` meldet. **Ursache bestaetigt:** bei `imo`s `Face_00040DQ_0_6`
(Geschoss UF_2, oberstes Geschoss unter einem Walmdach) fehlt das Attribut `GeschossDeckeZ` (wird
nur bei einer normalen Zwischendecke gesetzt, nicht beim obersten, dachseitig begrenzten
Geschoss) — `usableHeight` entspricht dadurch der vollen Wandhoehe bis zur Traufe, ohne jeden
Sicherheitsabstand nach oben. Passen `vDistFloorWindow + windowHeight` (JSON-Parameter) zufällig
exakt in diese Hoehe, landet die Fensteroberkante exakt auf der Traufe. Tile-weite Suche (exakte
Koordinaten-Uebereinstimmung, < 2mm) fand **2 von ueber 43.500 Fenstern** — ein sehr seltener
Grenzfall, kein systematisches Problem.

**Erster Fix-Versuch (verworfen):** ein Sicherheitsabstand auf ALLEN vier Seiten des
Oeffnungs-Kontur-Checks `CityGmlUtils.openingInsideWall2D` — zunaechst global fuer alle drei
Generatoren, dann auf `WindowGenerator` eingeschraenkt. Beide Varianten behoben den gemeldeten
Fall, verursachten aber auf der vollen Kachel eine val3dity-Regression bei
`201 INTERSECTION_RINGS` (2→13 bzw. 2→8) durch eine **entlang der Balkon/Fenster-
Platzierungskette kaskadierende Verschiebung**: strengere Kriterien aendern, welche Fenster
ueberhaupt entstehen → das aendert `GaReservedSpan`/Abstandskonflikte fuer den nachgelagerten
`BalconyGenerator` (Phase 2) → andere Balkone/Fenster entstehen an anderer Stelle, darunter
mehrere mit eigenen (vorher nie erzeugten, daher nie aufgefallenen) Ring-Intersection-Problemen.

**Finaler Fix (Nutzer-Idee: Fenster bei Konflikt verschieben statt verwerfen):** zwei gezielte
Aenderungen statt einer breiten Toleranz:
1. `CityGmlUtils.openingInsideWallTopClearance2D` — wie `openingInsideWall2D`, aber mit 2cm
   Sicherheitsabstand **ausschliesslich an der Oberkante** (`vTop`), links/rechts/unten bleiben
   exakt wie zuvor. Der Testpunkt wird bewusst Richtung Kontur (nach oben) verschoben, nicht weg
   davon — nur so erkennt der Test eine zu nah anliegende Oberkante zuverlaessig (ein reiner
   Ray-Casting-Containment-Test ist an Randpunkten mehrdeutig und haette den Fall sonst
   faelschlich als "passt" durchgewunken). Da nur die Oberkante betroffen ist, bleiben echte
   Giebel-Abschnitte (schraege Seitenkanten, links/rechts) unangetastet.
2. `WindowGenerator.collectValidWindows`: schlaegt der (jetzt strengere) Kontur-Check fehl, wird
   VOR dem Verwerfen ein zweiter Versuch mit dem Fenster **5cm tiefer** (`WINDOW_TOP_NUDGE`)
   unternommen — Position (u-Offset) und Fensterbreite bleiben exakt gleich, nur die Z-Hoehe
   ruckt runter. Klappt auch das nicht, wird wie bisher verworfen (`gableWindowsDropped`), sonst
   zaehlt `windowsNudgedDown`.

**Warum keine Kaskade mehr:** `BalconyGenerator` kennt nur die HORIZONTALE Fensterposition
(u-Spanne fuer `GaReservedSpan`/Abstandskonflikte), nie die Z-Hoehe. Ein nach unten gerueckes
Fenster hat exakt dieselbe u-Spanne wie zuvor — fuer `BalconyGenerator` also nicht von einem
unveraenderten Fenster zu unterscheiden. Verifiziert: Tueren (2.733) und Balkone (1.295) auf der
vollen Kachel **byte-identisch** zur Baseline vor diesem Fix.

**Verifiziert:** `imo` isoliert — Fensterdecke jetzt bei Z=237,13 statt 237,18 (5cm tiefer, keine
Beruehrung mehr mit der Wandkontur); schema-valide. 14-Testgebaeude-Menge unveraendert (574
Fenster, 11 Balkone), schema-valide. Volle 3.801-Gebaeude-Kachel: Fenster 35.391→35.400 (+9,
genau die Anzahl der neu "geretteten" statt verworfenen Fenster — Giebel-Drops sanken passend um
9, von 423 auf 414), Tueren/Balkone/Geschosse/Wandsegmente exakt unveraendert; schema-valide;
**val3dity verbessert sich** — `201 INTERSECTION_RINGS` komplett auf 0 (vorher 2, beide betroffenen
Faelle behoben), `204 NON_PLANAR_POLYGON_NORMALS_DEVIATION` 31→29 — alle anderen Kategorien exakt
unveraendert (102=6/104=5/302=20/303=13/306=2/307=8/601=316).

### Bugfix: Wand-Mehrfachschnitt schlug bei "Anbau-Kerbe" fehl — JTS-Umbau (2026-08-24)

**Problem** (Nutzer-Fund an Gebaeude `DESNALK0pF001imo`): eine Wand mit Fenster blieb als eine
grosse, ungeschnittene Flaeche stehen, obwohl sie in ≥2 Geschossstuecke geteilt werden sollte —
sichtbar am Kontrast zur Rueckseite desselben Gebaeudes, wo die entsprechende Wand korrekt
geschnitten war. Nutzer-Vermutung: haengt mit einem Anbau zusammen, der an der Wand endet.

**Ursache bestaetigt** (Koordinaten + Code-Trace): `Face_00040DQ_0_8` fasst drei Grundriss-Kanten
A→B→C→D in einem WallSurface zusammen (kollinear — dieselbe "geknickte Wand aus mehreren
Grundriss-Kanten"-Konstellation wie beim `gjj`-StoreyGenerator-Bug und dem `gHh`-Party-Wand-Bug,
siehe oben). Die mittlere Kante B→C grenzt an einen Anbau (Flachdach bei Z=235,28): dort reicht die
Wand nur bis zur Anbau-Traufe, waehrend die aeusseren Kanten bis zur Haupttraufe (237,18)
durchgehen — das 2D-Profil der Wand hat dadurch eine Kerbe, die bis zum Boden reicht (kein
Rechteck). Der alte Mehrfachschnitt (`cutWallMultiPiece`/`extractZRuns`) erzeugte bei diesem Profil
ein geometrisch falsches Phantom-Stueck (Flaechenbilanz-Check `isFaithfulSplit` schlug fehl) →
Fallback auf `cutWallSinglePieceGuarded`, dessen einfacher 2-Stueck-Schnitt (`cutWallPolygonAtZ`)
nur Ebenen verarbeiten kann, die den Umriss GENAU 2× kreuzen. Bei Schnitten innerhalb der
Kerben-Zone (4 Kreuzungen) erkannte `ringSelfIntersects` das Problem und **ueberSprang den Schnitt
komplett**, statt ihn in mehrere Teilstuecke aufzuloesen — GF und UF_1 blieben verschmolzen.

**Fix:** dieselbe Baustelle wurde fuer Boden-/Deckenflaechen bereits robust mit JTS geloest
(`clipSlabAtZ`, siehe oben). Die Wand-Mehrfachschnitt-Logik war der letzte Ort mit der alten,
handgestrickten Ring-Clip-Logik. Neuer Helfer `CityGmlUtils.cutWallAtMultipleZJTS`: projiziert die
Wand ins wandeigene (u,v)-Profil (`findBottomEdge` + `projectWallTo2D`, dieselbe Basis wie
Fenster/Tuer/Balkon-Platzierung), baut daraus ein JTS-Polygon und schneidet es bandweise (ein
unabhaengiges `intersection()` je Hoehenband) — JTS zerlegt beliebig komplexe/konkave Profile
korrekt in (Multi-)Teilstuecke, ganz ohne Sonderfall-Erkennung "wie oft kreuzt die Ebene den
Umriss". Ersetzt in `StoreyGenerator` den alten `cutWallAtMultipleZ`-Aufruf; `cutWallMultiPiece`,
`isFaithfulSplit`, `cutWallSinglePieceGuarded` komplett geloescht (einziger Aufrufer ersetzt).
`cutWallPolygonAtZ`/`ringSelfIntersects` bleiben (weiterhin fuer den unabhaengigen
Keller-Trim-Einzelschnitt bzw. `splitWallByZ`/`roofAreaBelowZ` gebraucht).

**Zwei Nachbesserungen waehrend der Verifikation** (beide durch die volle Kachel + val3dity
aufgedeckt, nicht durch die Einzelgebaeude-Pruefung):
1. **Umlaufrichtung:** JTS legt bei `intersection()` keine feste Umlaufrichtung fest — ohne
   Korrektur zeigte die Normale mancher neuer Wandstuecke ins Gebaeude
   (val3dity `303 NON_MANIFOLD_CASE` 13→1467, `307 POLYGON_WRONG_ORIENTATION` 8→1468 auf der vollen
   Kachel!). Fix: Original-Umlaufrichtung der Wand vorab per Shoelace-Flaeche bestimmen, jedes
   JTS-Ergebnisstueck bei Bedarf zurueckdrehen.
2. **Koordinaten-Praezision an Schnittkanten:** der (u,v)-Rundweg (projizieren → JTS schneiden →
   ueber `edgeStart + u*dir` zurueckrechnen) erzeugte fuer unveraenderte Original-Ecken und fuer
   neue Schnittpunkte Koordinaten mit Sub-mm-Abweichung vom exakten Original-Wert — genug, um
   `302 SHELL_NOT_CLOSED` auf der Kachel deutlich zu erhoehen (20→392), weil Nachbarwaende/
   Boeden/Decken exakte Kantenuebereinstimmung erwarten. Fix in zwei Stufen: (a) Punkte, die einer
   Original-Ecke entsprechen, schnappen exakt auf deren Original-Koordinate zurueck (`
   findOriginalPoint`); (b) echte neue Schnittpunkte werden direkt auf der betroffenen
   Original-3D-Kante interpoliert (`interpolateOnOriginalEdge`, haengt nur von den beiden
   Kanten-Endpunkten ab — wie der alte Algorithmus es tat), statt ueber wandspezifische
   `edgeStart`/`dir`-Grössen zu gehen. Nach beiden Stufen: `302` wieder exakt bei 20.

**Verifiziert:** `imo` isoliert — `Face_00040DQ_0_8` erzeugt jetzt 4 Stuecke (`GF_5`, `GF_6`, je ein
"Bein" links/rechts der Anbau-Kerbe auf Erdgeschoss-Hoehe, `UF_1_5` — ein einzelnes, nicht-konvexes
Stueck, da die Kerbe 2cm unterhalb von UF_1s Decke wieder zusammenlaeuft, `UF_2_2`) statt 2; alle
anderen Waende im Gebaeude unveraendert (Wand-Dump-Vergleich: nur die laufenden Nummern-Suffixe
verschieben sich). `Face_00040DQ_0_6` (die schon vorher untersuchte, benachbarte Wand) bleibt
weiterhin unveraendert 1 Stueck — architektonisch korrekt (ihr Z-Bereich entspricht bereits exakt
einem Geschoss), kein Bug. 14-Testgebaeude-Menge schema-valide. Volle 3.801-Gebaeude-Kachel:
Wandsegmente 66.875→67.024 (+149, mehr korrekt geschnittene Waende systemweit), Fenster
35.400→35.425 (+25, neue nutzbare Wandflaeche), Tueren 2.733→2.768 (+35), Balkone 1.295→1.290 (−5)
— anders als bei den vorherigen Session-Fixes ist dieser Cascade-Effekt hier **erwuenscht**, da der
Fix die Geschoss-Struktur selbst korrigiert (mehr/besser geformte Waende → legitim andere
Oeffnungs-Platzierung); schema-valide; val3dity nach beiden Nachbesserungen **exakt auf Baseline**
(204=29/302=20/303=13/307=8/601=316; 102/104/306 innerhalb ±1, vorbestehende, unabhaengige
Einzelfaelle).

### Code-Audit: Dubletten/Dead Code bereinigt (2026-08-24)

Kurzer Codeaudit auf Dubletten/Dead Code/Optimierungspotenzial ueber `CityGmlUtils`,
`StoreyGenerator`, `WindowGenerator`, `DoorGenerator`, `BalconyGenerator`, `BasementGenerator`,
`ModuleParameters`. Gefunden und behoben:
- `WindowGenerator.resolveWallArea`/`BalconyGenerator.resolveCurrentWallArea` waren identische
  Implementierungen (FACEAREA-Attribut, sonst `calculateWallArea`-Fallback) — jetzt eine
  zentrale `CityGmlUtils.resolveWallArea(WallSurface, List<Point3D>)`, beide Aufrufer umgestellt.
- `CityGmlUtils.createXLinkMultiSurfaceProperty` war Dead Code (0 Aufrufer im gesamten Projekt,
  vermutlich Rest aus der Zeit vor der Floor/Ceiling-OrientableSurface-Umstellung — nur die
  reversed-Variante wird noch gebraucht) — geloescht.

Restliche Befunde (kein Bug, kein akuter Handlungsbedarf): `BalconyGenerator.spansOverlap`
dupliziert die innere Formel von `CityGmlUtils.overlapsAnySpan` fuer den 2-Werte-Fall (2 Zeilen,
geringer Nutzen einer Zentralisierung); sechs `CityGmlUtils`-Methoden
(`calculateEdgeLength2D`/`collectBoundariesByType`/`collectLod3Polygons`/`createLinearRing`/
`pointInPolygon2D`/`splitWallByZ`) sind `public`, werden aber nur intern in derselben Datei
aufgerufen — koennten auf `private` verengt werden, rein kosmetisch.

Verifiziert: Clean Build; `imo` isoliert liefert exakt dieselben Zahlen wie vor der Bereinigung
(10 Fenster, 42 Wandsegmente) — reine Strukturaenderung, kein Verhaltensunterschied.

### Bugfix: doppelte Fensterreihen + Balkone fehlten an Waenden mit `Innenwand="1"` (2026-08-25)

**Problem** (Nutzer-Fund an Gebaeude `DESNALK0pF001iqd`, Screenshot): eine hohe Wand unterm Dach
zeigte zwei uebereinanderliegende Fensterreihen statt einer; ausserdem bekam nur EINE Gebaeudeseite
Balkone, obwohl geometrisch mehrere Waende gepasst haetten.

**Ursache 1 (Fensterreihen):** `WindowGenerator.computeRowPositions` hatte den Ein-Reihen-Zwang nur
fuer `Geschoss="BA"` (`singleRowOnly = "BA".equals(ctx.geschoss())`) — bei GF/UF stapelte die
Schleife weitere Reihen, solange die Wandhoehe reichte, begrenzt nur durch den 60%-WWR-Grenzwert.
Bei einer hohen Wand mit ausreichend Flaeche reisst 2 Reihen den Grenzwert nicht.

**Fix 1:** `computeRowPositions` erzeugt jetzt fuer JEDES Geschoss immer genau eine Fensterreihe
(die alte BA-Sonderfall-Unterscheidung ist entfallen, die Mehrfachreihen-Trimm-Schleife fuer den
WWR-Check ebenfalls — bei konstant 1 Reihe war sie unerreichbarer Code). Der WWR-Check bleibt als
reine Warnung erhalten (eine einzelne Reihe kann bei kurzer/breiter Wand mit grossen Fenstern
weiterhin den Grenzwert reissen).

**Ursache 2 (Balkone):** `BalconyGenerator.isEligibleForBalcony` schloss Waende mit
`Innenwand="1"` von Balkonen aus. Laut eigener Doku (Abschnitt "Vorbedingungen (Gate-Checks)"
oben) ist `Innenwand="1"` aber nur ein **LoD4-Indikator** ("hier koennte spaeter eine Innenwand
abgehen") und sagt nichts ueber den aktuellen LoD3-Wandtyp aus — `WindowGenerator` ignoriert das
Attribut bewusst und behandelt solche Waende normal. Bestaetigt an `iqd`: `Face_001FAAR_0_6` und
`_0_8` haben `Innenwand="1"`, bekommen deshalb korrekt volle Fensterreihen vom `WindowGenerator`,
wurden aber vom `BalconyGenerator` faelschlich komplett uebersprungen — exakt die vom Nutzer
beobachtete "nur eine Gebaeudeseite hat Balkone".

**Fix 2:** die `Innenwand`-Pruefung in `isEligibleForBalcony` ersatzlos entfernt — `BalconyGenerator`
behandelt solche Waende jetzt wie `WindowGenerator` normal.

**Verifiziert:** `iqd` isoliert — Fenster 110→73 (UF_3 von 2 auf 1 Reihe, 39→14 Fenster dort),
Balkone 5→11 (Waende `0_6`/`0_8` bekommen jetzt ebenfalls Balkone auf allen passenden Geschossen,
`0_7` bleibt korrekt auf das oberste Geschoss beschraenkt — dort verhindert `WindowPreference=2`
(ABOVE_NEIGHBOR) die unteren Geschosse, unabhaengig von diesem Fix); schema-valide. 14-Testgebaeude-
Menge schema-valide. Volle 3.801-Gebaeude-Kachel (auf dem seit 2026-08-24 aktuellen Datensatz mit
ueberarbeiteten `sst`/`BuildingPreferences`-Attributen, siehe unten): Fenster 43.444→29.736 (−32%,
erwartet — die zweite Reihe entfaellt), Balkone 1.574→14.980 (**+851%**, `Innenwand="1"`-Waende
kommen jetzt tile-weit dazu, nicht nur bei `iqd`); schema-valide. val3dity: die meisten Kategorien
bleiben trotz der fast 5-fachen Feature-Zahl (BuildingInstallation-Paare pro neuem Balkon) nahezu
unveraendert (204=29→31, 302=23→23, 303=27→26, 306=1→1, 307=10→11, 601=321→321) — **aber
`201 INTERSECTION_RINGS` erscheint neu mit 3 Instanzen** (vorher 0). Versuch, die betroffenen
Gebaeude zu isolieren: Hypothese "Balkontuer beruehrt exakt die Wand-Oberkante" (dieselbe
Fehlerklasse wie beim Fenster-Traufe-Fix) per Koordinatenabgleich ueber die gesamte Kachel
widerlegt (0 Treffer) — `BalconyGenerator` nutzt fuer Tuer/Galerie-Span weiterhin nur das einfache
`openingInsideWall2D`, nie die Clearance-Variante, das bleibt ein moeglicher Verdaechtiger fuer
eine andere Beruehrungsart (z.B. seitlich), aber nicht bestaetigt. Die betroffenen 3 Primitive
liessen sich nicht isolieren, da `val3dity --report` in dieser Umgebung persistent mit Exit 127
abstuerzt (bestaetigt selbst bei einer 500-Feature-Datei — bekannte, ungeloeste Tooling-Einschraenkung,
siehe auch weiter oben). Bei 3 von ueber 33.700 Features ein sehr kleiner, aber unbestaetigter
Rest — als offener Punkt vorgemerkt, kein Revert (der Nutzen der Balkon-Korrektur ist eindeutig
groesser als dieser kleine, noch nicht lokalisierte Nebeneffekt).

### Bugfix: Balkone verdraengten Fenster komplett (EG-Ausschluss + Mindest-Pattern-Pruefung, 2026-08-26)

**Problem** (Nutzer-Screenshots): der `Innenwand`-Fix vom Vortag hatte Balkone tile-weit korrekt
auf viel mehr Waende ausgeweitet (+851%), aber zwei neue Probleme sichtbar gemacht: (1)
Erdgeschoss-Waende bekamen Balkone; (2) `BalconyGenerator`s Phase 1 (`placeLeadingBalconies`)
reservierte den fuehrenden Ga-Lauf **blind**, bevor ueberhaupt ein Fenster existierte — bei
schmalen Waenden blieb dann kein Platz mehr fuer das naechste Pattern-Element, die Wand wurde zur
reinen Balkon-Wand statt normal befenstert zu werden (Gebaeude mit mehr Balkonen als Fenstern,
mehrere kollidierende Balkone an Gebaeude-Knicken).

**Fix 1 (kein EG):** `isEligibleForBalcony` akzeptiert nur noch `geschoss.startsWith("UF_")` (GF
faellt raus) — theoretisch koennten Loggien im EG existieren, das ist aktuell aber nicht ueber
Parameter abfragbar. EG bekommt weiterhin ganz normal Tuer+Fenster ueber
`DoorGenerator`/`WindowGenerator`.

**Fix 2 (Mindest-Pattern-Pruefung):** in `placeLeadingBalconies`, direkt nach der bestehenden
Breiten-Pruefung, NEU: nach dem (geplanten) Ga-Lauf muss auf der Restwand noch mindestens 1
Fenster passen (`WindowGenerator.calculateWindowCount` wiederverwendet — paket-privat, dieselbe
Datei/Package, garantiert dieselbe Passform-Logik wie der `WindowGenerator` spaeter tatsaechlich
anwendet). **Wichtiger Befund:** `leadingGalleryRunLength` fasst alle fuehrenden aufeinander-
folgenden `"Ga"`-Token bereits zu einem Lauf zusammen — das Token direkt danach kann demnach nie
wieder `"Ga"` sein (waere es das, haette der gierige Lauf es schon eingesammelt). "GaGa als
Folge-Element pruefen" kommt fuer den fuehrenden Lauf strukturell also gar nicht vor, das einzig
relevante Folge-Element ist entweder `"Wi"` oder gar keins. Die Pruefung laeuft bewusst
**einheitlich fuer beide Faelle** (kein Sonderfall fuers fehlende Folge-Token): die
Mindestabstands-Parameter (`HDistMinWaGa`, `HDistWiGa`, Fensterbreite) sind fuer jedes Modul mit
gueltigen Gallery-Parametern vorhanden, unabhaengig davon ob `GaPa` explizit gesetzt ist (z.B.
`EE3_4.json`, wo `GaPa` fehlt und auf ein reines `"Ga"` zurueckfaellt) — passt Balkon +
Mindestabstand + Fensterbreite auf die Restwand, wird gebaut (der "fehlende" Fensterplatz wird
ohnehin ganz normal vom nachgelagerten `WindowGenerator` befuellt); passt es nicht, wird gar kein
Balkon reserviert und die Wand komplett normal durchgefenstert. Neuer Zaehler
`balconiesSkippedNoFollowUp`. Phase 2 (`placeRemainingPatternBalconies`) brauchte die Pruefung
nicht: sie ersetzt ausschliesslich bereits real platzierte Fenster durch Balkone und kann daher
nie mehr Balkone erzeugen als ohnehin Fenster da waren — das "Wand wird komplett verdraengt"-
Problem war strukturell auf Phase 1 beschraenkt.

**Kollidierende Balkone (dritte vom Nutzer genannte Restriktion) brauchten am Ende KEINEN
zusaetzlichen Code:** ein dedizierter Koordinaten-Scan ueber alle 3.082 Balkon-Decks der vollen
Kachel (paarweiser Bounding-Box-Vergleich, bewusst konservativ — mehr Kandidaten als eine echte
2D-Polygon-Ueberschneidung liefern wuerde) fand **0 Ueberlappungskandidaten**. Die Mindest-Pattern-
Pruefung hat die vom Nutzer gezeigte Ecken-Kollision offenbar bereits vollstaendig als Nebeneffekt
geloest (weniger, aber gezielter platzierte Balkone).

**Verifiziert:** `iqd` isoliert — EG-Balkon (`Face_001FAAR_0_3_GF_1_Ga_1_...`) verschwunden,
UF-Balkone unveraendert (10 statt 11, nur der EG-Fall entfaellt, alle verbleibenden hatten schon
vorher genug Platz fuer ein Folge-Fenster); Fenster 73→76; schema-valide. 14-Testgebaeude-Menge
schema-valide (Balkone 243→61, Fenster 356→480). Volle 3.793-Gebaeude-Kachel: Balkone
**14.980→3.082 (−79%)**, Fenster 29.736→40.336 (+36%, GF-Fenster allein 11.888 statt vorher
anteilig durch Balkone verdraengt); schema-valide; val3dity: **`201 INTERSECTION_RINGS` zurueck
auf 0** (war 3 seit dem `Innenwand`-Fix — bestaetigt, dass dieser Rest tatsaechlich von der
unkontrollierten Balkon-Verdraengung kam, nicht von einer separaten, ungeklaerten Ursache), alle
anderen Kategorien innerhalb ±1 der Baseline (102=7/104=4/204=30/302=22/303=27/306=1/307=10/601=314).

### Bugfix: Balkone nach innen statt nach aussen orientiert (2026-08-26)

**Problem** (Nutzer-Fund an Gebaeude `iMM`, Screenshots): an manchen Waenden zeigten Balkon-Decks
und -Bruestungen ins Gebaeudeinnere statt nach aussen — bei `iMM` an mehreren Waenden entlang eines
Gebaeudefluegels rund um einen Innenhof.

**Ursache bestaetigt:** `resolveWallGeometry` bestimmte die Auswaerts-Normale einer Wand bisher
ueber "weg vom Gebaeude-Schwerpunkt" (`computeFootprintCentroid` — Mittelwert aller
Wand-Bodenkanten-Endpunkte des ganzen Gebaeudes). Bei einem **nicht-konvexen** Grundriss (Innenhof,
mehrere Fluegel, wie bei `iMM`) kann "weg vom Schwerpunkt" fuer eine einzelne Wand systematisch die
FALSCHE Seite ergeben — der Schwerpunkt liegt dann naeher an einer Aussenwand als deren eigene
Innenseite, sodass die "weg davon"-Richtung nach innen zeigt statt nach aussen. Verifiziert mit
echten Koordinaten an zwei unabhaengigen Waenden (`Face_0003ZEV_0_16` und `Face_0003ZEV_0_47`, per
Ray-Casting/Schuhband-Formel gegen die tatsaechliche GroundSurface-Kontur geprueft): bei `0_47`
zeigte die alte Schwerpunkt-Methode nachweislich nach innen, bei `0_16` traf sie zufaellig (fuer
diese eine Wand) dieselbe Richtung wie die neue Methode — bestaetigt, dass der Bug nicht bei jeder
Wand auftritt, aber strukturell vorhanden ist.

**Fix:** die Auswaerts-Normale wird jetzt aus der **Umlaufrichtung des Wand-Polygons selbst**
abgeleitet (`isExteriorRingCCW`, bereits vorher fuer die Tueroeffnungs-Umlaufrichtung genutzt und
vertraut) statt aus einer gebaeudeweiten Schwerpunkt-Heuristik: `normX = extCCW ? dirY : -dirY;
normY = extCCW ? -dirX : dirX;` (Herleitung: fuer eine im (u,v)-Profil CCW gewundene Wand zeigt die
Auswaerts-Normale nach dem Kreuzprodukt der (u,v)-Basisvektoren in Richtung `(dirY,-dirX)`). Das ist
eine rein lokale Eigenschaft NUR dieser einen Wand und damit unabhaengig von der Grundriss-Form
(konvex, Innenhof, beliebig verwinkelt) immer korrekt — verifiziert an zwei geometrisch
unabhaengigen Waenden mit unterschiedlichen Referenz-Konturen. `computeFootprintCentroid` war
danach nirgendwo mehr gebraucht und wurde geloescht, ebenso der `footprintCentroid`-Parameter aus
`resolveWallGeometry`/`placeLeadingBalconies`/`placeRemainingPatternBalconies`.

**Verifiziert:** `iMM` isoliert — 5 von 23 Balkon-Waenden hatten tatsaechlich abweichende
Normalen zwischen alter und neuer Methode (per Debug-Vergleich beider Formeln nebeneinander
bestaetigt, nicht nur vermutet); Balkon-Anzahl unveraendert (23, reine Richtungs-Aenderung,
keine Eligibilitaets-Aenderung). 14-Testgebaeude-Menge und volle 3.793-Gebaeude-Kachel: Fenster/
Tueren/Balkone-Zahlen **exakt unveraendert** (40.336/3.048/3.082 — erwartet, die Wand-Auswahl
aendert sich nicht, nur die Deck-Richtung); schema-valide; val3dity **exakt identisch zur
Baseline** (102=7/104=4/204=30/302=22/303=27/306=1/307=10/601=314) — plausibel, da ein
gespiegeltes, weiterhin planares und nicht-selbstueberschneidendes Rechteck geometrisch genauso
gueltig ist wie das Original, nur auf der jeweils anderen Wandseite.

### Neue Standard-Testkachel (2026-08-24/25)

Ab 2026-08-24 gilt `sqltest/BuildingParts_neu_Kachel/neue_Prefs/LoD2_33_416_5656_2_SN_
BuildingPreferences.gml` (voll, 3793 Gebaeude) bzw. `.../14_geb/LoD2_33_416_5656_2_SN_
BuildingPreferences_neueKachel_14_Buildings.gml` (14er-Set) als Standard-Testdaten fuer alle
Verifikationen — die `sst`/`BuildingPreferences`-Attribute wurden ueberarbeitet, deutlich mehr
Gebaeude haben jetzt ein passendes Baukoerpermodul (Fenster 35.425→43.444, Tueren 2.768→3.048,
Balkone 1.290→1.574 auf dem unveraenderten Codestand vor den beiden Fixes oben — allein durch die
Datenaktualisierung, nicht durch Codeaenderungen).

### Bekannte Einschraenkung: manche Gebaeude haben Fenster aber keine Tuer (Quelldaten-Luecke)

**Problem** (Nutzer-Fund an Gebaeude `DESNALK0pF001giF`): ein Gebaeude hat Fenster, aber keine
einzige Tuer. **Ursache bestaetigt:** `DoorCount=0` ist fuer **jede** GF-Wand dieses Gebaeudes
bereits im LoD2-Quelldatensatz (`sqltest/LoD2_33_416_5656_2_SN_BuildingPreferences.gml`) so
gesetzt — nicht erst durch unsere Pipeline. `DoorGenerator` verarbeitet dieses Attribut korrekt
(0 = keine Tuer, wie dokumentiert). Tile-weite Suche: **26 von 3.801 Gebaeuden** (≈0,68 %) haben
Fenster, aber `DoorCount=0` auf jeder Wand — durchgehend dasselbe Muster, keine Mischfaelle mit
"echtem" Platzierungsfehler gefunden. Dies ist eine Datenluecke der vorgelagerten
Datenaufbereitung (fehlende Tuer-Zuordnung fuer diese 26 Gebaeude), kein Fehler in
`DoorGenerator`/`WindowGenerator` — analog zu den bereits gemeldeten CityDoctor2-Upstream-Bugs
(siehe `CityDoctor2_Bugreport.md`), sollte aber beim Datenersteller gemeldet statt in diesem
Konverter "repariert" werden (wir haben keine Information, WO eine Tuer sein sollte).

### Bugfix: falsche Traufhoehe durch dominantes Kehl-/Walm-Dachpolygon (2026-08-26)

**Problem** (Nutzer-Fund an Gebaeude `DESNALK0q80047bD`, mehrere Screenshots): an vielen senkrechten
Waenden im 1./2./3.OG fehlten scheinbar willkuerlich Fenster, obwohl sichtbar Platz vorhanden war.

**Zwei Mechanismen als korrekt bestaetigt** (kein Bug): `WindowPreference=2` (ABOVE_NEIGHBOR)
blockt Fenster unterhalb echter Nachbar-Bauteile — exakte Hoehen-Uebereinstimmung mit den
Nachbar-Parts verifiziert; ein hartes `TOO_SHORT`-Minimum (`hDistMin=2.25m` → 5.5m
Mindestwandbreite) lehnt schmale Waende (4.4–4.9m) korrekt ab, waehrend eine 5.81m breite Wand
aehnlicher Flaeche korrekt akzeptiert wird.

**Ursache (echter Bug) bestaetigt:** `CityGmlUtils.getRoofZRange` bestimmte die Traufhoehe fuer
geneigte Daecher bisher aus dem lokalen Minimum-Z des flaechengroessten RoofSurface-Polygons. Fuer
Part 3 (Walmdach-Hauptteil) ist die flaechengroesste Flaeche (`Face_K0q80047bD_3_17`, 86.00 m²) eine
komplexe Kehl-/Walm-Verschneidungsflaeche mit 9 unterschiedlichen Z-Niveaus (124.13 bis 132.96) —
ihr eigenes Minimum (124.13) liegt weit unter der echten Traufe, die von zwei fast gleich grossen
Nachbarflaechen uebereinstimmend gezeigt wird (`_3_51`, 65.09 m², Min 127.246; `_3_76`, 54.64 m²,
Min 127.220). Die zu niedrige Traufe deckelte `GeschossDeckeZ` fuer das oberste Geschoss (UF_3) zu
frueh, wodurch `WindowGenerator`s `usableHeight`-Check dort viele Fenster faelschlich mit `TOO_LOW`
verwarf.

**Fix:** `getRoofZRange` nimmt jetzt das **Maximum der lokalen Minima aller "grossen" geneigten
Dachflaechen** (Flaeche ≥ 50 % der groessten geneigten Flaeche, `MAJOR_FACET_AREA_RATIO`) statt
blind der flaechengroessten Einzelflaeche zu vertrauen. Bei nur 1–2 geneigten Flaechen (Normalfall)
aendert sich nichts (die "grosse Flaechen"-Menge ist dann identisch mit der bisherigen dominanten
Flaeche allein). Reiner Flachdach-Fall bleibt unveraendert.

**Verifiziert:** `DESNALK0q80047bD` isoliert — Traufe Part 3 124.13→127.246 (exakt der erwartete
Konsens-Wert), Geschossteilung bekommt dadurch ein zusaetzliches, vorher fehlendes UF_4
(GeschossDeckeZ 124.82→127.246 statt vorher bei UF_3 gedeckelt), 38 Fenster inkl. 11 neue in UF_4;
schema-valide. 14-Testgebaeude-Menge schema-valide. Volle 3.793-Gebaeude-Kachel: Fenster
40.336→40.781 (+445, +1,1 %, wie erwartet), Tueren unveraendert 3.048, Balkone 3.082→3.096
(minimaler Nebeneffekt durch neu entstandene Fensterplaetze); schema-valide; val3dity **keine
Regression** (102=7/104=4/204=30/302=22/303=27/306=1/307=10/601=313 — 601 sogar 314→313, eine
Ueberlappung weniger, plausibel da eine der beiden am Ueberlapp beteiligten Geometrien jetzt anders
zugeschnitten ist).

**Bekannte Einschraenkung (nicht Teil dieses Fixes):** einige Waende von Part 3 (z.B.
`Face_K0q80047bD_3_19/_39/_41/_66`, Z bis 130.56) reichen weiterhin hoeher als selbst die neue
Traufe von 127.246 — vermutlich ein Giebel-/Turmabschnitt mit einer zweiten, noch hoeheren
Eigen-Traufe innerhalb desselben BuildingParts. Diese Waende bleiben unveraendert "Orphan"-Waende
(keine Schnitt-Z faellt in ihren Bereich) und werden ueber den bestehenden Nearest-Storey-Fallback
dem obersten Geschoss zugeordnet — ein `traufeZ`-Wert pro BuildingPart ist fuer ein Dach mit
mehreren echten Traufhoehen strukturell zu einfach; falls das sichtbar wird, braucht es eine
grundsaetzlichere Ueberarbeitung (mehrere Traufhoehen pro Part), kein Thema dieses Fixes.

### Neues Feature: Fallback-Tuer fuer Gebaeude ohne Tuer (`DoorGenerator.processFallbackDoors`, 2026-08-27/28)

**Anlass** (Nutzer-Fund an Gebaeude `DESNALK0pF001iLp`, spaeter mit dem Referenzfall
`DESNALK0pF001giF` verifiziert): manche Gebaeude haben `DoorCount=0` auf JEDER Wand bereits in
den LoD2-Quelldaten — bestaetigte Luecke der vorgelagerten Adresspunkt-/ALKIS-Zuordnung (siehe
oben, "Bekannte Einschraenkung"), kein Pipeline-Fehler. Solche Gebaeude bekommen trotzdem korrekt
Fenster, aber gar keine Tuer — unrealistisch fuer ein bewohntes Gebaeude. Nutzer-Wunsch: Gebaeude
mit mindestens einem Fenster (Wand- oder Dachfenster) aber ohne jede Tuer bekommen genau eine
zusaetzliche Tuer.

**Design-Entscheidungen** (mit Nutzer abgestimmt): Wand-Auswahl per **breiteste EG-Wand zuerst**
(FACEAREA-sortiert), nicht `BU.EnDi` (Eingangsrichtung) — EnDi ist zwar bestaetigt
"EntranceDirection", aber eine reine 1-4-Seitennummerierung des idealisierten `BuLen`x`BuWid`-
Rechtecks aus der urspruenglichen novaFACTORY-Generierung, ohne dokumentierte Zuordnung zu echten
Wand-IDs/Richtungen unserer realen (unregelmaessigen) LoD2-Grundrisse — zu riskant zu erraten.
Wand- UND Dachfenster zaehlen gleichermassen als "hat Fenster".

**Implementierung:** neue Methode `DoorGenerator.processFallbackDoors`, als eigener Pipeline-
Schritt "Fallback-Tueren" **nach** Fenstern UND Dachfenstern verdrahtet (anders als der normale
Tuer-Schritt, der immer VOR den Fenstern laeuft) — erst dann steht fest, ob das Gebaeude Fenster
hat. `DoorGenerator.processWall` (bestehende, gepruefte Platzierungslogik) wird direkt
wiederverwendet, minimal erweitert um einen `extraExclusionSpans`-Parameter (leer am bestehenden
Aufrufer, kein Verhaltensunterschied im Normalfall) und einen Rueckgabewert (Anzahl platzierter
Tueren).

**Drei Bugs waehrend der Verifikation gefunden und behoben** (guter Beleg dafuer, warum die volle
Kachel + val3dity + CityDoctor2 nach JEDER Aenderung nochmal laufen muss, auch bei scheinbar
einfachen Erweiterungen):

1. **`GE_R_SELF_INTERSECTION`-Fehldiagnose:** erster Verdacht war eine Tuerkante exakt auf der
   Wandkontur (analog zum Fenster-Seitenkante-Fix). Fix angewendet
   (`openingInsideWallSideTopClearance2D` statt `openingInsideWall2D`, dieselbe bereits verifizierte
   Methode wiederverwendet), aber die betroffene Gebaeudeliste blieb identisch — der Fehler
   existierte nachweislich schon VOR dem Tuer-Feature (bestaetigt im Report der vorherigen
   Kachel-Baseline). Der Fix bleibt trotzdem drin (sinnvolle, risikoarme Haertung), war aber nicht
   die Ursache der eigentlichen val3dity-Regression (`201 INTERSECTION_RINGS`, 0→6).
2. **Wandkontur durchquert die Oeffnung** (der eigentliche Haupt-Bug): eine "M"-foermige Wand
   unter einem Satteldach mit Gaube (zwei Firstspitzen, ein Tal dazwischen, Gebaeude
   `DESNALK0q80046gq`) — eine Fallback-Tuer, die das Tal ueberspannt, besteht den reinen
   4-Eckpunkt-Containment-Test (beide Ecken liegen unterhalb der jeweils benachbarten Firstspitze),
   obwohl ihre Oberkante mitten durch das Tal der Wandkontur verlaeuft. Neue Funktion
   `CityGmlUtils.wallContourEntersOpening`: zweistufig — (a) liegt ein Wand-Eckpunkt selbst
   innerhalb des Oeffnungs-Rechtecks, (b) durchquert eine Wandkante (echte Segment-Schnitt-Pruefung,
   nicht nur Beruehrung) eine der 4 Rechteck-Kanten. Reduzierte die Faelle von 6 auf 5.
3. **Zwei Oeffnungen stossen an derselben u-Kante mit unterschiedlicher Hoehe aneinander**
   (Gebaeude `DESNALK0q80045BR`): die bestehende Fenster-Ausschluss-Pruefung
   (`computeExistingWindowSpans`/`extraExclusionSpans`) verglich nur den horizontalen u-Bereich,
   ignorierte die Hoehe komplett — eine Fallback-Tuer direkt neben einem (niedrigeren) Fenster mit
   exakt gleicher u-Kante aber ueberlappender Hoehe erzeugt teilweise deckungsgleiche Ringkanten.
   Fix: `computeExistingWindowSpans` liefert jetzt 2D-Rechtecke `{uMin,uMax,vMin,vMax}` statt
   reiner u-Spannen, neue Funktion `CityGmlUtils.overlapsAnyOpeningRect` prueft echten 2D-Konflikt
   (nicht getrennt in U ODER V, mit Sicherheitsabstand). Reduzierte die restlichen Faelle von 5
   auf 0.

**Verifiziert:** 25/25 Unit-Tests (5 neue: "M"-Wand-Tal-Erkennung inkl. Kontrollfall "besteht
4-Eckpunkt-Test", 2D-Rechteck-Konflikt inkl. "gleiche u-Kante aber sauber getrennte Hoehe darf
nicht blockieren"-Gegenprobe). `DESNALK0pF001giF` isoliert (bestaetigter DoorCount=0-ueberall-
Fall): 1 Fallback-Tuer, schema-valide, CityDoctor2 sauber bis auf den bekannten
`SE_POLYGON_WITHOUT_SURFACE`-Mapper-Fehlalarm. 14-Testgebaeude-Menge: unveraendert (kein
betroffenes Gebaeude enthalten), schema-valide. Volle 3.793-Gebaeude-Kachel: **142 Fallback-Tueren**
(3.048→3.190 Tueren gesamt inkl. der bereits vorher bestehenden 3.048), Fenster/Balkone/
Dachfenster **exakt unveraendert** (vollstaendig unabhaengiger, nachgelagerter Schritt);
schema-valide; val3dity und CityDoctor2 nach dem letzten Fix **exakt identisch zur Baseline vor
dem Feature** (val3dity: 79 invalide Primitive/390 invalide Features, 102=7/104=4/204=30/302=23/
303=13/306=1/307=7/601=314 — keine einzige neue Fehlerklasse; CityDoctor2:
GE_R_SELF_INTERSECTION=5/GE_S_NON_MANIFOLD_EDGE=19/GE_S_NON_MANIFOLD_VERTEX=1/GE_S_NOT_CLOSED=58/
GE_S_SELF_INTERSECTION=34, `GE_P_INTERSECTING_RINGS` vollstaendig verschwunden).

### Nachbesserung: Fallback-Tuer gab zu frueh auf + verdeckte Wand als Kandidat (2026-08-28)

**Problem 1** (Nutzer-Fund an Gebaeude `DESNALK0q80047Pm`): trotz vorhandener Fenster keine
Fallback-Tuer. **Ursache bestaetigt:** `processWall` berechnet fuer die Fallback-Tuer nur EINE
feste Standardposition (HDistDoWa ab Wandanfang bzw. zentriert) und gibt sofort auf, wenn genau
diese mit einem vorhandenen Fenster kollidiert — ALLE 4 GF-Waende dieses Gebaeudes waren
geometrisch geeignet (breit genug, `WindowPreference=1`), scheiterten aber jeweils nur an der
EINEN versuchten Position, obwohl daneben noch reichlich freier Platz gewesen waere.

**Problem 2** (Nutzer-Fund am bereits verifizierten Referenzgebaeude `DESNALK0pF001giF`): die
platzierte Fallback-Tuer landete auf einer Wand mit `WindowPreference=2` (ABOVE_NEIGHBOR,
`Z_Fenster_ASL=215.02` > eigene `Z_MAX_ASL=211.92` — die komplette Wand liegt unterhalb der
Schwelle) — diese Wand ist laut dem bereits an anderer Stelle bestaetigten ABOVE_NEIGHBOR-
Mechanismus (siehe "traufeZ"-Untersuchung) durch ein Nachbargebaeude (Reihenhaus/Doppelhaushaelfte)
verdeckt. Fuer Fenster wird das bereits korrekt beruecksichtigt (keine Fenster unterhalb
`Z_Fenster_ASL`), die Fallback-Tuer-Kandidatenauswahl pruefte `WindowPreference` bisher aber gar
nicht — eine Tuer in eine verdeckte Wand ist architektonisch falsch.

**Fix 1 — Mehrfach-Positionsversuch:** `processWall` bekommt einen neuen optionalen Parameter
`forcedOffset` (nur bei der Fallback-Tuer genutzt, `null` = unveraendertes Verhalten am
bestehenden Aufrufer). Scheitert die Standardposition an einem vorhandenen Fenster, berechnet
`processFallbackDoors` ueber die neue Methode `computeFreeUSections` alle freien Wandabschnitte
NEBEN den vorhandenen Fenstern (groesster zuerst, jeweils zentriert platziert) und probiert diese
der Reihe nach, bevor die ganze Wand verworfen wird.

**Fix 2 — WindowPreference-Filter:** die Kandidatenliste in `processFallbackDoors` nimmt nur noch
Waende mit `WindowPreference=NORMAL` auf (`WindowPreference.parse`, bereits vorhandenes Modell aus
`WindowGenerator`) — NONE (komplett verdeckt) und ABOVE_NEIGHBOR (im unteren, tuerrelevanten
Bereich verdeckt) werden von vornherein ausgeschlossen.

**Verifiziert:** 25/25 Unit-Tests weiterhin gruen (keine neuen Tests noetig, reine Erweiterung
bereits getesteter Bausteine). `DESNALK0q80047Pm` isoliert: Fallback-Tuer jetzt erfolgreich an der
breitesten Wand, schema-valide, CityDoctor2 sauber (nur bekannter Mapper-Fehlalarm).
`DESNALK0pF001giF` isoliert: Tuer wandert von der verdeckten Wand `..._GF_3` (WindowPreference=2)
zur naechstbreiten, normalen Wand `..._GF_1`, weiterhin schema-valide und CityDoctor2-sauber.
14-Testgebaeude-Menge unveraendert, schema-valide. Volle 3.793-Gebaeude-Kachel: Fallback-Tueren
142→**154** (+12, vom Mehrfach-Positionsversuch gerettete Gebaeude wie Pm — der
WindowPreference-Filter kann in Einzelfaellen auch Gebaeude von "hat Fallback-Tuer" auf "kein
Kandidat gefunden" umklappen, per Saldo tile-weit aber ein deutliches Plus); Fenster/Balkone/
Dachfenster exakt unveraendert; schema-valide; val3dity **exakt identisch zur Baseline**
(79 invalide Primitive/390 invalide Features, 102=7/104=4/204=30/302=23/303=13/306=1/307=7/601=314
— keine einzige neue Fehlerklasse).

### Bugfix: Selbstueberschneidung bei Geschossdecken-Zuschnitt nahe Grundriss-/Dach-Diskrepanz (GE_R_SELF_INTERSECTION, 2026-08-28)

**Nutzer-Fund** (Gebaeude `DESNALK0q8004709`, Screenshot mit einer aus der Fassade herausragenden
Linie, 3-4x ueber die Geschosse verteilt): Frage, ob dies aus den LoD2-Quelldaten stammt oder von
der Pipeline eingefuehrt wird. **Wichtiger Werkzeug-Fund waehrend der Untersuchung:** die
CityDoctor2-CLI ohne eigene `-c`-Konfiguration nutzt einen unvollstaendigen Default-Pruefplan (das
referenzierte Schematron `checkForSolid.xml` wird nur gefunden, wenn die CLI aus
`D:\Tools\CityDoctorGUI-3.18.2-win\` heraus mit der eigenen `Konfig_für Test.yml` des Nutzers
(`sqltest/output/Konfig_für Test.yml`, `-c`-Flag) aufgerufen wird) — der Default-Plan meldet fuer
viele Gebaeude ausschliesslich den bekannten `SE_POLYGON_WITHOUT_SURFACE`-Fehlalarm und uebersieht
echte Fehler. **Mit der korrekten Konfiguration bestaetigt:** LoD3-Ausgabe zeigt 4x
`GE_R_SELF_INTERSECTION` (0x in der LoD2-Quelle) — eindeutig von uns eingefuehrt.

**Ursache:** `CityGmlUtils.clipSlabAtZ` (JTS-Slab-Zuschnitt, Abschnitt "Slab-Zuschnitt bei
Anbauten (JTS)") schneidet das Grundpolygon
(`footprint`, aus der GroundSurface) mit `footprint.difference(excluded)`, wobei `excluded` die
Vereinigung aller Dachanteile unter der jeweiligen Geschosshoehe ist (aus den RoofSurface-Polygonen
abgeleitet). Grundriss- und Dachpolygone sind in den LoD2-Quelldaten unabhaengig voneinander
digitalisiert und treffen sich an gemeinsamen Gebaeudeecken oft nicht exakt — am betroffenen
Gebaeude lagen zwei eigentlich identische Eckpunkte (ein schmaler Pilaster-/Erker-Vorsprung an der
Fassade) real nur ca. 1,1 cm auseinander. Ohne Snapping erzeugt JTS' `difference()` dort statt
eines sauberen gemeinsamen Eckpunkts einen entarteten "Spike" (der Ring beruehrt sich selbst kurz
vor dem eigentlichen Schluss) — genau die aus der Fassade ragende Linie im Screenshot. Da
`clipSlabAtZ` pro Geschoss unabhaengig aufgerufen wird, trat der exakt gleiche Spike an derselben
(X,Y)-Ecke auf allen 4 betroffenen Geschossdecken auf (4 Fehler, eine Ursache).

**Fix (zweiter Anlauf — erster Versuch verworfen, siehe unten):** `excluded` wird vor der
Differenz-Operation um `SLAB_EXCLUSION_GROW_TOL` (2 cm) aufgeblaht (`excluded.buffer(...)`), sodass
eine eigentlich gemeinsame Ecke zuverlaessig vollstaendig abgedeckt ist, ohne dass irgendein
Eckpunkt tatsaechlich verschoben wird.

**Erster Versuch (verworfen — Nutzer-Fund, siehe "Nachbesserung" unten):** urspruenglich per
`org.locationtech.jts.operation.overlay.snap.GeometrySnapper.snap(footprint, excluded, tol)`
geloest (beide Geometrien vor der Differenz aufeinander einrasten). Behob den Spike, erzeugte aber
an einem anderen Gebaeude (`DESNALK0pF001hYt`) ein NEUES, schwebendes Geschossdeckenstueck —
GeometrySnapper verschiebt Punkte irgendwo im (oft komplexen, vielkantigen) Grundriss, nicht nur an
der betroffenen Ecke, und klemmte dabei eine schmale Verbindung zwischen zwei Raeumen ab. Auch
`GeometryFixer.fix(...)` auf das Differenz-ERGEBNIS angewendet wurde probiert und verworfen (behob
den Spike gar nicht). Der finale `buffer`-Ansatz aendert dagegen nur, WIE VIEL `excluded` entfernt,
nie WO die uebrigen Punkte liegen — rein lokal, keine Nebenwirkungen anderswo im Grundriss.

**Verifiziert:** 25/25 Unit-Tests gruen (ein bestehender Test mit synthetischer, exakt
uebereinstimmender Anbau-/Grundriss-Geometrie musste um den erwarteten 2cm-Puffer-Randverlust
angepasst werden, 50,0→49,8 m²). `DESNALK0q8004709` isoliert mit der echten CityDoctor2-
Konfiguration: `GE_R_SELF_INTERSECTION` 4→**0**. `DESNALK0pF001hYt` isoliert: Boeden/Decken wieder
16/11 wie im Ausgangszustand (kein schwebendes Stueck mehr). Volle 3.801-Gebaeude-Kachel:
schema-valide (`citygml-tools validate`), val3dity **byte-identisch** mit und ohne Fix auf
demselben Datenstand (102=6/104=4/204=30/302=20/303=13/306=1/307=8/601=309 — val3dity's eigene,
deutlich lockerere Toleranz erkennt diese Art Spike gar nicht, daher unveraendert; die eigentliche
Bestaetigung kommt aus dem direkten CityDoctor2-Vorher/Nachher-Vergleich am betroffenen Gebaeude).

**Bekannter, NICHT behobener Nebenbefund:** dieselbe CityDoctor2-Pruefung zeigt fuer
`DESNALK0q8004709` zusaetzlich 9x `GE_P_NON_PLANAR_POLYGON_DISTANCE_PLANE` (LoD2-Quelle: 5x).
Davon sind 7 unveraendert/erklaerbar (4 unveraendert uebernommene Original-Dachpolygone + 1
Original-Wandpolygon, das durch den Geschoss-Wandschnitt in 3 Hoehen-Stuecke zerlegt wird und daher
3x statt 1x gemeldet wird — eine bereits vorher nicht-plane Wand bleibt in jedem Teilstueck
nicht-plan). Die verbleibenden 2 (`Poly_0003ZPX_0_29`, `Poly_0003ZPX_0_10`, beide RoofSurfaces mit
Dachfenster-Ausschnitt) sind grenzwertig NEU: Abstand von der Ebene 1,47 mm bzw. 1,62 mm gegen
einen Schwellwert von 1,41 mm — nur 0,06–0,21 mm ueber der Grenze. Ursache: das Hinzufuegen des
Fensterloch-Innenrings verschiebt CityDoctor2's Ausgleichsebene (Least-Squares ueber alle Punkte
inkl. Loch) minimal, was bei ohnehin schon knapp unter der Schwelle liegenden Original-Daechern
ausreicht, um sie knapp darueber zu schieben. Da `RoofWindowGenerator` bei jedem Dachfenster
(802 tile-weit) diesen minimalen Effekt erzeugt, aber nur Daecher betrifft, die schon vorher
Millimeter von der Schwelle entfernt waren, ist dies ein sehr seltener Grenzfall (2 von ~800). Eine
vollstaendige Behebung wuerde erfordern, die Loch-Eckpunkte auf dieselbe Ausgleichsebene zu
projizieren, die CityDoctor2 intern berechnet (nicht exakt bekannt/reproduzierbar) — Aufwand/Nutzen
aktuell nicht gerechtfertigt, daher bewusst als bekannte Einschraenkung dokumentiert statt "gefixt".

### Nachbesserung: Fallback-Tuer ignorierte HDistDoWi (Mindestabstand zu vorhandenen Fenstern, 2026-08-28)

**Nutzer-Fund:** die Fallback-Tuer (siehe oben) hielt zwar geometrisch einen 2D-Konflikt mit
vorhandenen Fenstern ab (`overlapsAnyOpeningRect`, 2cm Sicherheitsabstand), beachtete aber nicht
den architektonisch vorgegebenen `HDistDoWi`-Parameter aus der JSON (`GF.window.HDistDoWi`,
"Horizontaler Abstand Tuer-Fenster") — denselben Wert, den `WindowGenerator.extractFreeSections`
bereits in umgekehrter Richtung nutzt, um neue Fenster von vorhandenen Tueren fernzuhalten.

**Fix:** `computeExistingWindowSpans` bekommt `hDistDoWi` (aus
`params.getGroundFloor().window.hDistDoorWindow`, 0 falls kein Fenster-Modul definiert) als
Parameter und schlaegt ihn als u-Puffer auf jede Fenster-Sperrspanne auf — analog und
symmetrisch zu `WindowGenerator`s Behandlung des Parameters in der Gegenrichtung. Nur die
u-Ausdehnung wird gepuffert, nicht die Hoehe (HDistDoWi ist rein horizontal).

**Erste Version (verworfen — Nutzer-Fund, siehe naechster Abschnitt):** kollidierte die Tuer mit
einem vorhandenen Fenster (auch unter Beruecksichtigung von HDistDoWi), wurde die Wand komplett
verworfen und die naechste Kandidatenwand probiert; blieb am Ende gar keine geeignete Wand uebrig,
bekam das Gebaeude gar keine Tuer. Am eigenen Referenzgebaeude `DESNALK0pF001giF`
(`HDistDoWi=2,0m` in dessen Modul) fuehrte das dazu, dass GAR KEINE Fallback-Tuer mehr gesetzt
wurde — tile-weit sank die Zahl der Fallback-Tueren von 29 auf 22 (7 Gebaeude ganz ohne Tuer).

### Nachbesserung: Tuer erzwungen statt Wand aufgegeben (2026-08-28)

**Nutzer-Fund:** die obige Konsequenz ("lieber keine Tuer als eine zu dicht am Fenster") war ein
Missverstaendnis meinerseits — ein bewohntes Gebaeude OHNE Tuer ist unrealistisch ("man kommt ja
nicht rein"). Die Tuer MUSS gesetzt werden, wenn DoorCount=0 aber Fenster vorhanden sind; die
Fenster-Abstandsregel (HDistDoWi) darf dabei nur bestimmen, WELCHE/WIE VIELE Fenster im Zweifel
weichen — bis zu allen, wenn noetig — nicht ob ueberhaupt eine Tuer kommt.

**Fix:** `processWall` bekommt einen neuen Parameter `forceWindowRemoval`. Kollidiert die
Standardposition mit einem Fenster: bei `false` (Normalfall, erste zwei Versuche in
`processFallbackDoors`) wird die Tuer wie bisher verworfen; als dritter, letzter Versuch pro Wand
wird `processWall` mit `forceWindowRemoval=true` aufgerufen — kollidierende Fenster werden jetzt
entfernt (Innenring aus dem Wandpolygon + FillingSurface-Eintrag, FACEAREA korrigiert), die Tuer
wird trotzdem gesetzt. Die Pruefreihenfolge in `processWall` wurde dafuer umgestellt: Anbau-
Verdeckung und Wandkontur-Checks laufen jetzt VOR dem Fenster-Konflikt-Check, damit im Force-Modus
nie ein Fenster entfernt wird, wenn die Position ohnehin aus einem anderen (nicht behebbaren)
Grund scheitert. Nur wenn buchstaeblich KEINE der Kandidatenwaende geometrisch passt (zu schmal,
komplett Anbau-verdeckt, Wandkontur durchquert die Oeffnung ueberall) bleibt ein Gebaeude ohne
Fallback-Tuer — das ist dann keine Fenster-Frage mehr.

**Verifiziert:** Build + 25/25 Unit-Tests gruen. `DESNALK0pF001giF` isoliert: bekommt wieder eine
Tuer (an Wand `..._GF_1`), dafuer wird 1 von deren 2 Fenstern entfernt; schema-valide,
CityDoctor2-sauber (nur der bereits in der LoD2-Quelle vorhandene 1 Planaritaetsfehler, keiner
neu). Volle 3.801-Gebaeude-Kachel: Fallback-Tueren wieder **29/29** (alle Kandidaten bekommen ihre
Tuer, 0× "kein geeigneter Wandkandidat"), dafuer 15 Fenster an 11 Gebaeuden entfernt; normale
Tueren (Schritt 4) unveraendert; schema-valide; val3dity zeigt gegenueber der Baseline nur eine
A/B-bestaetigt vom Feature UNABHAENGIGE Verschiebung bei `102 CONSECUTIVE_POINTS_SAME` (6→8,
identisch mit und ohne `forceWindowRemoval` reproduziert — 5 Gebaeude ohne jeden Bezug zu
Fallback-Tueren/-Fenstern, siehe "Bekannter, nicht behobener Nebenbefund" unten); alle anderen
Fehlerklassen exakt identisch zur Baseline (104=4/204=30/302=20/303=13/306=1/307=8/601=309).
Doku.md/GitHub-Zielordner/sqltest/output synchron.

### Bugfix + Untersuchung: `102 CONSECUTIVE_POINTS_SAME` — zwei verschiedene Ursachen (2026-08-28)

**Nutzer-Fund** (Gebaeude `DESNALK0pF001fWE`, Screenshot mit einer Punktreihe im obersten OG
oberhalb des Daches): "die Punkte kommen ja von uns rein?" — bestaetigt: JA. Zusaetzlich gefragt,
ob die 5 zuvor gemeldeten Gebaeude denselben Grund haben. **Antwort: NEIN, zwei verschiedene,
unabhaengige Ursachen** — siehe unten.

**Ursache 1 (fWE, GEFIXT):** `Poly_...UF_1_Ceiling_1`, eine `clipSlabAtZ`-Geschossdecke — dieselbe
Funktion wie beim `q8004709`-Selbstueberschneidungs-Fix oben. Der dortige `buffer()`-Fix nutzt die
JTS-Standardeinstellung `JOIN_ROUND`: eine Ecke wird durch mehrere kurze Kreisbogen-Segmente
angenaehert (Default 8 pro Viertelkreis) — bei 2cm Radius liegen deren Punkte nur 1-2mm
auseinander und wurden am realen Gebaeude prompt als eigener neuer Fehler erkannt. **Fix:**
`BufferOp.bufferOp(excluded, tol, params)` mit `BufferParameters.JOIN_BEVEL` statt der
Standardmethode — schneidet Ecken mit hoechstens einem zusaetzlichen, weit genug entfernten Punkt
gerade ab, kein Rundungs-Splitter, kein Spike-Risiko (anders als ein Mitre-Join). Zusaetzlich:
neue, groessere Toleranz `RING_DEDUP_TOL` (2mm statt der bisherigen `POINT_MERGE_TOL`=1mm) im
finalen Ring-Dedup in `createPolygon` — 1mm lag exakt an/unter CityDoctor2's eigener
`minVertexDistance` (0,00173m aus der Nutzer-Konfiguration), sodass Punkte, die knapp unter dieser
Schwelle lagen, unser eigenes Dedup faelschlich ueberlebten.

**Ursache 2 (5 weitere Gebaeude, NICHT gefixt — bewusste Entscheidung):** die betroffenen Polygone
sind normale WallSurfaces, keine `clipSlabAtZ`-Slabs. Ursache: `CityGmlUtils.conformJunctions`
(Schritt 7, T-Naht-Vertices) fuegt fuer jede Wandkante Punkte benachbarter Huellen-Ringe ein, die
auf dieser Kante liegen. Zwei UNABHAENGIGE Nachbarwaende koennen an derselben Stelle je einen
eigenen Eckpunkt haben, der (weil in den LoD2-Quelldaten separat digitalisiert) nur ca. 1mm
auseinanderliegt — beide werden als eigene Kandidaten erkannt und eingefuegt, es entstehen zwei
fast identische neue Punkte.

**Erster Fix-Versuch (verworfen):** den naeher liegenden der beiden Kandidaten beim Einfuegen
uebersprang (per 3D-Abstand zum zuletzt eingefuegten Punkt, `RING_DEDUP_TOL`). Behob
`102 CONSECUTIVE_POINTS_SAME` an allen 6 betroffenen Gebaeuden vollstaendig (0 Fastduplikate
tile-weit, per Text-Scan bestaetigt) — **aber per direktem A/B an genau diesen 6 Gebaeuden mit
CityDoctor2** bestaetigt: `GE_S_NOT_CLOSED` stieg dabei von 4 auf 10 (`GE_S_SELF_INTERSECTION`
und `GE_P_NON_PLANAR` blieben unveraendert bei je 2). **Ursache des Tauschgeschaefts:** der
uebersprungene Kandidat war der Anschlusspunkt fuer eine ANDERE Nachbarwand — fehlt er, bleibt
dort eine kleine Luecke in der Huelle. Ein Tausch von "harmlosem" `CONSECUTIVE_POINTS_SAME`
gegen das schwerwiegendere `SHELL_NOT_CLOSED` ist kein echter Fix, daher verworfen.

Erster Fix-Versuch damit zunaechst NICHT uebernommen, mit Verweis auf den bereits dokumentierten
Projekt-Grundsatz bei `conformJunctions` ("Vertex-Welding wurde bewusst ENTFERNT: es verschob
~0,3% der Vertices um bis zu 5mm... das Schliessen solcher mm-Naehte uebernimmt der nachgelagerte
Healer, nicht dieses LoD3-Update").

**Korrektur (2026-08-28, spaeter am Tag):** Nutzer stellte klar, dass dieser Grundsatz auf einer
falschen Annahme beruhte — der Healer laeuft laut Projektplan NUR ueber die LoD2-Ausgangsdaten,
NICHT ein zweites Mal nach der LoD3-Aufwertung. Es gibt also keinen nachgelagerten Schritt, der
von unserer Pipeline neu eingefuehrte Maengel noch bereinigt — solche Fehler sind IMMER unsere
eigene Verantwortung (siehe Memory `healer-runs-only-on-lod2`). Die Entscheidung, Ursache 2 offen
zu lassen, war damit hinfaellig.

**Finaler Fix — echtes, aber eng begrenztes Vertex-Welding:** neue `CityGmlUtils.
weldNearbyRingVertices(rings, RING_DEDUP_TOL)`, VOR der T-Naht-Einfuegung in `conformJunctions`
aufgerufen. Verschmilzt Eckpunkte VERSCHIEDENER Huellen-Ringe, die naeher als `RING_DEDUP_TOL`
(2mm) beieinander liegen, auf einen gemeinsamen Punkt — per Union-Find, damit auch Cluster aus
3+ nahen Punkten korrekt (nicht nur paarweise) zusammengefasst werden. Anders als das
urspruenglich entfernte generelle Vertex-Welding (bis zu 5mm, unklar begrenzt) ist dies bewusst
eng: nur 2mm Toleranz, nur innerhalb der ohnehin fuer die T-Naht-Pruefung gesammelten Huellen-
Ringe. Die bestehende (unveraenderte) Einfuege-Logik sieht dank der Vorab-Verschmelzung nur noch
EINEN Kandidaten pro realer Ecke — der bestehende "doppeltes t"-Check verhindert daher ganz
automatisch jede Doppel-Einfuegung, ohne dass irgendeine Nachbarwand ihren Anschlusspunkt verliert.

**Verifiziert:** 25/25 Unit-Tests gruen. Direkter A/B-Vergleich mit CityDoctor2 an genau den 11
betroffenen Gebaeuden (`fEA, fb7, fIN, g7H, gmt, hKN, hna, i8L, iWh, ixo, fWE`), sonst identischer
Code: `GE_R_CONSECUTIVE_POINTS_SAME` 52→**0**, alle anderen Fehlerklassen an denselben Gebaeuden
**exakt unveraendert** (`GE_S_SELF_INTERSECTION` 2→2, `GE_S_NOT_CLOSED` 4→4,
`GE_P_NON_PLANAR_POLYGON_DISTANCE_PLANE` 6→6) — diesmal ein sauberer Fix ohne Tauschgeschaeft.
Volle 3.801-Gebaeude-Kachel: schema-valide; Text-Scan auf Fastduplikat-Punkte (CityDoctor2-
Schwelle 0,00173m) zeigt **0 verbleibende Faelle** tile-weit; `q8004709`/`hYt` weiterhin
unveraendert (Selbstueberschneidung 0, keine schwebende Etage); Schritt-3/4-Zahlen (Boeden/Decken/
Tueren/Fallback-Tueren) exakt identisch zur Baseline. val3dity tile-weit sogar spuerbar
**verbessert** (75→**56** invalide Primitive): `102`=0 (weg), `104`=5 (unveraendert), `204`=25
(vorher 30 — das Welding schliesst offenbar auch anderswo vorher lockere mm-Naehte), `302`=11
(vorher 20, ebenfalls verbessert), `303`=13/`306`=1/`307`=8 (alle unveraendert). Einzige kleine
Verschiebung: `601 BUILDINGPARTS_OVERLAP` 309→313 Features (+4) — dieser Check ist nachweislich
nachbarschaftskontext-abhaengig (siehe frühere Untersuchungen in diesem Dokument) und auf
Mikro-Verschiebungen im mm-Bereich empfindlich; nicht weiter verfolgt. Doku.md/GitHub-Zielordner/
sqltest/output synchron.

### Systematischer LoD2-vs-LoD3-Vergleich: neue Fehlerkategorien (2026-08-31)

**Nutzer-Frage:** "holen wir uns im Vergleich zu vorher (Ausgangsdaten) noch irgendwo neue Fehler
rein?" — CityDoctor2 (Nutzer-Konfig) auf volle LoD2-Quelle vs. volle LoD3-Kachel verglichen
(je 3.801 Gebaeude), per-Gebaeude-Diff berechnet. Ergebnis: reale neue Fehler jenseits des
bisher Gefixten — `GE_R_SELF_INTERSECTION` 0→8, `GE_S_SELF_INTERSECTION` 1→12 (KEINE
Ueberschneidung mit der R-Level-Liste!), `GE_P_NON_PLANAR` netto +30 (~35 Gebaeude), `GE_S_
NON_MANIFOLD_EDGE` 1→3. `GE_S_NOT_CLOSED` sogar verbessert (9→8), keine Aktion noetig.

**GE_R_SELF_INTERSECTION — Root Causes gefunden, Ergebnis gemischt:**

1. **cutWallAtMultipleZJTS-Klasse** (`h55` 3x, `gqs` 1x, vermutlich auch `h37`, `09Xf000Fn`):
   die LoD2-Wand hat ein reales "gestuftes" Profil (dieselbe (u)-Position kommt bei mehreren
   Original-Hoehen vor — echte Quelldaten-Geometrie). Trifft eine Geschoss-Schnitthoehe zufaellig
   fast exakt (hier: 4mm) eine dieser Original-Hoehen, ist `wallJts.intersection(bandRect)` ein
   bekannter JTS-Robustheits-Grenzfall und liefert einen sich selbst beruehrenden Ring.
   Fix-Versuch (Schnitthoehe bei Koinzidenz wegruecken) behob h55+gqs vollstaendig, erzeugte aber
   an einem ANDEREN, unbeteiligten Gebaeude (`gO2`) eine neue `GE_S_NOT_CLOSED`-Luecke.
   **Verworfen — kein Fix im Einsatz, Root Cause aber dokumentiert fuer einen spaeteren, besseren
   Versuch.**
2. **conformJunctions-Klasse** (`hms`): eine Nachbarwand-Ecke liegt zufaellig exakt auf einer
   Kante DIESES Rings, an einer Stelle, die (nicht benachbart) bereits einem eigenen Punkt des
   Rings entspricht. Fix-Versuch (Kandidat ueberspringen, wenn er einem anderen Punkt DESSELBEN
   Rings entspricht) behob hms — aber identisches Muster wie der bereits am 28.08. verworfene
   erste conformJunctions-Fix: an anderer Stelle wieder `GE_S_NOT_CLOSED` + `GE_S_NON_MANIFOLD_
   EDGE` neu. **Verworfen.**
3. **clipSlabAtZ-Zyklusschluss-Klasse** (`hHr`, NEU gefunden, GEFIXT): eine Geschossdecke, deren
   Ring am zyklischen Schluss (letzter Punkt ~ erster Punkt) 2,2mm auseinanderliegt — knapp ueber
   `RING_DEDUP_TOL` (2mm). Eine pauschale Erhoehung von `RING_DEDUP_TOL` auf 3mm haette hHr
   behoben, aber an einer voellig unbeteiligten Wand in einem anderen Gebaeude (`i5d`) eine neue,
   grenzwertige `GE_P_NON_PLANAR`-Meldung ausgeloest (per A/B mit 2,3mm UND 3mm Toleranz identisch
   reproduziert — die Ursache ist die pauschale Geltung, nicht die genaue Toleranzgroesse).
   **Finaler Fix:** `createPolygon` bekommt eine private ueberladene Variante mit expliziter
   Toleranz; `createPolygonWithHoles` (ausschliesslich fuer clipSlabAtZ-Geschossdecken/-boeden
   verwendet) nutzt eine eigene, groessere `SLAB_RING_DEDUP_TOL` (3mm) NUR fuer den Aussenring
   dieser Slabs, waehrend alle anderen `createPolygon`-Aufrufer (Waende, Oeffnungen) weiter bei
   `RING_DEDUP_TOL` (2mm) bleiben. **Sauber verifiziert:** per-Gebaeude-Diff der vollen Kachel
   zeigt genau EINE Zeile Unterschied zur Baseline (`hHr`s Selbstueberschneidung weg), sonst
   tile-weit exakt null Veraenderung — kein Tauschgeschaeft.

**Wichtige, wiederholt bestaetigte Lektion:** in `conformJunctions`/`cutWallAtMultipleZJTS`
funktionieren "Kandidat/Schnitt ueberspringen"-Fixe fast nie sauber (zweimal an unterschiedlichen
Stellen mit demselben Tauschgeschaeft gescheitert). Ein pauschal erhoehter globaler Toleranzwert
kann ebenfalls an unbeteiligter Stelle Kollateralschaeden ausloesen — der rettende Unterschied
beim `hHr`-Fix war, die erhoehte Toleranz strikt auf den tatsaechlich betroffenen Aufrufer-
Kontext (Slabs) zu beschraenken, statt sie global zu setzen. Fuer kuenftige aehnliche Faelle:
**Toleranzerhoehungen so eng wie moeglich scopen (Aufrufer-spezifisch), nicht die zentrale
Utility-Funktion pauschal betreffen.**

**Verbleibend UNGEFIXT nach diesem ersten Durchgang (2026-08-31, vormittags):**
- `GE_R_SELF_INTERSECTION`: 8→7 (nur hHr behoben, war Klasse 3 = clipSlabAtZ-Zyklusschluss, nicht
  Teil der urspruenglichen 8 R-Level-Faelle sondern ein NEUER, beim Diagnostizieren gefundener
  Fall — die urspruenglichen 6 betroffenen Gebaeude/8 Instanzen bleiben unveraendert offen, da
  beide dafuer entwickelten Fixe verworfen wurden).
- `GE_S_SELF_INTERSECTION` (12 Instanzen, 11 Gebaeude) — komplett ununtersucht, CityDoctor2
  liefert dafuer keine Koordinaten (nur `type=SOLID` + `parent BuildingPart`).
- Grossteil der `GE_P_NON_PLANAR`-Zunahme (+30 netto) — nur ein Mechanismus bekannt
  (Dachfenster-Lochrand verschiebt die Ausgleichsebene minimal), erklaert vermutlich nicht alle
  Faelle.

### KRITISCHER Config-Fund: `numberOfRoundingPlaces` falsch (2026-08-31, nachmittags)

Beim Versuch, `GE_S_SELF_INTERSECTION` per Sichtpruefung mit dem Nutzer zu klaeren (CityDoctor2-
GUI, Fehler-Baum mit Polygon-Namen), zeigten mehrere Gebaeude in der GUI "valide", obwohl die CLI
mit derselben `-c Konfig_für Test.yml` einen Fehler meldete (`ivU`, `gEY`, `g0q`). Ursache: die
YAML hatte `numberOfRoundingPlaces: '8'` (Nanometer-Praezision), die tatsaechliche, vom Nutzer in
der GUI haendisch eingestellte und per Screenshot bestaetigte Konfiguration nutzt aber `3`
(Millimeter — CityGML-Quelldateien liegen ohnehin nur mit max. 3 Nachkommastellen vor).
**`sqltest/output/Konfig_für Test.yml` jetzt auf `3` korrigiert.**

Auswirkung: bei `GE_S_SELF_INTERSECTION` (Solid-Ebene, trianguationsbasiert) massiv — volle
Kachel meldete mit `8` 12 Instanzen/11 Gebaeude, mit korrektem `3` nur noch **5 Instanzen/5
Gebaeude**. 7 der 11 (`ivU, iB9, hWg, fMO, fHh, gEY, g0q`) waren reine Rundungs-Fehlalarme
(Sub-Millimeter-Trianguationsrauschen, das bei Nanometer-Rundung als "Fehler" erscheint, bei
Millimeter-Rundung korrekt verschwindet) — per direktem CLI/GUI-Abgleich an 7 Testgebaeuden
100% verifiziert. Andere Kategorien (`GE_R_SELF_INTERSECTION`, `GE_S_NON_MANIFOLD_EDGE`)
blieben beim Wechsel unveraendert — nur der trianguationslastige Solid-Self-Intersection-Check
reagierte so empfindlich. **Wichtige Nebenwirkung:** die LoD2-Baseline muss mit derselben
korrigierten Konfig neu geprueft werden (war ebenfalls mit `8` gelaufen) — dabei fiel zusaetzlich
auf, dass `GE_S_NOT_CLOSED` fälschlich als "9→8, verbessert" dokumentiert war; mit korrekter
Baseline ist es tatsaechlich **6→8, eine Verschlechterung** (siehe unten).

### GE_S_SELF_INTERSECTION: vollstaendig untersucht, drei Root-Cause-Klassen (2026-08-31)

Mit der korrigierten Konfig verbleiben 5 echte Instanzen: `hGc`, `fMQ`, `gnQ`, `iWh`, `j0t`.

1. **hGc** (1 Instanz): Dachfenster-Loch (`RoofWindowGenerator`) in einer nicht-konvexen, um eine
   Gaube herum gekerbten Dachflaeche (16 Randpunkte). Fensterposition nachweislich egal (auch ein
   sicher weit von der Kerbe platziertes Fenster loest den Fehler aus; komplettes Deaktivieren
   behebt ihn). CityDoctor2 trianguliert fuer den Solid-Check intern (eigene, zur Laufzeit
   generierte `CityDoctor_<timestamp>_N`-IDs — NICHT in unserer GML vorhanden, per grep
   bestaetigt); ein Loch in einer so komplexen Kontur macht daraus eine "constrained
   triangulation", die an der Gaubennaht offenbar ein Splitter-Dreieck erzeugt, obwohl unsere
   echten Randpunkte exakt mit der Gaube uebereinstimmen. val3dity findet dort nichts Ungueltiges.
   Vermutlich (nicht 100% sicher) ein CityDoctor2-Trianguations-Grenzfall, aehnlich dem bekannten
   TIN-Fehlalarm. Als generelle Verbesserung (unabhaengig vom eigentlichen Fehler) wurde
   `RoofWindowGenerator` um `openingInsideWallSideTopClearance2D` + `wallContourEntersOpening`
   erweitert (analog Tuer/Fenster) — per Volltile-Diff exakt nebenwirkungsfrei, behebt aber
   diesen spezifischen Fall nicht (positionsunabhaengig). NICHT weiter gefixt.
2. **fMQ** (1 Instanz, aber 12 Splitter-Stellen rund um das Gebaeude): ECHTES Artefakt, kein
   Trianguations-Fehlalarm. Ungleiche Traufhoehe zwischen Nachbarwaenden derselben Gebaeudeecke
   (z.B. 233,70 vs. 233,75 — 5cm, reale LoD2-Geometrie). Die hoehere Nachbarwand braucht ein
   zusaetzliches Geschoss, dessen Geschoss-Grenz-Vertex per T-Naht-Logik (`conformJunctions`)
   korrekterweise auch auf die niedrigere Nachbarwand eingetragen wird — erzeugt dort einen
   5cm-Splitter, den CityDoctor2 kaskadierend gegen mehrere Nachbarflaechen meldet. Gleiche
   Grundklasse wie die weiterhin offenen `h55`/`gqs`/`h37`/`09Xf000Fn`. NICHT gefixt.
3. **gnQ + iWh** (je 1 Instanz, gnQ mit 15 gemeldeten Paaren): schmale (10-25cm), aber sehr hohe
   (bis 16m, Keller bis 4.OG), lueckenlos und ueberlappungsfrei gestapelte Wandsegmente — bei gnQ
   bestaetigt exakt dieselben zwei XY-Eckpunkte in allen 6 Stuecken, Z-Bereiche schliessen
   nahtlos. Alle moeglichen Paare (auch nicht benachbarte) werden gemeldet — spricht fuer einen
   weiteren Trianguations-Stabilitaetsgrenzfall bei sehr schmalen/hohen, exakt fluchtenden
   Mehrfach-Scheiben. NICHT gefixt, NICHT abschliessend als Fehlalarm oder echt eingestuft.
4. **j0t**: erst durch die Config-Korrektur ueberhaupt sichtbar geworden. NACHTRAG (spaeter am
   Tag): identischer Mechanismus wie `hGc` bestaetigt (Dachfenster deaktivieren behebt es komplett,
   positionsunabhaengig; betroffene Dachflaechen mit 13 Aussenring-Punkten aehnlich komplex/
   gekerbt).

**Abschluss-Entscheidung (2026-08-31, Nutzer):** hGc/j0t (Dachfenster-Klasse) und gnQ/iWh
(Schmalwand-Klasse) werden als bekannte, akzeptierte CityDoctor2-Trianguations-Grenzfaelle
dokumentiert (analog zum TIN-Fehlalarm) — KEIN Code-Fix. Jeder denkbare generelle Fix
(Dachfenster auf komplex gekerbten Dachflaechen grundsaetzlich unterdruecken, schmale Waende
nicht mehr geschossweise teilen) waere eine echte Verhaltensaenderung mit realen Kosten (weniger
Dachfenster / andere Wandaufteilung), nur um einen mutmasslichen Tool-Trianguations-Fehlalarm zu
vermeiden — kein echter Geometriedefekt (Randpunkte stimmen an allen Nahtstellen exakt ueberein,
val3dity findet an keiner der 4 Stellen etwas Ungueltiges). **Damit ist `GE_S_SELF_INTERSECTION`
komplett abgeschlossen** — einzige verbleibende ECHTE, ungefixte Instanz in dieser Kategorie ist
`fMQ` (Teil der groesseren, weiterhin offenen T-Naht-Splitter-Familie mit h55/gqs/h37/09Xf000Fn,
siehe `GE_R_SELF_INTERSECTION` oben).

### Bugfix: Traufe-Verwurf-Schwelle inkonsistent mit Flachdach-Toleranz (GE_S_NOT_CLOSED, 2026-08-31)

Bei der Untersuchung der neu entdeckten `GE_S_NOT_CLOSED`-Faelle `fb7`, `ggO`, `hHL` (alle drei
per Config-Korrektur neu sichtbar geworden, siehe oben) fand sich ein gemeinsamer, klar
verstandener Mechanismus: `StoreyGenerator` klassifiziert ein Dach als "flach" (`isFlachdach`),
wenn First- und Traufhoehe um weniger als `FLAT_ROOF_TOLERANCE` (0,30m) auseinanderliegen — bei
`fb7` betrifft das ein Dach mit einem kleinen First-Detail nur 19cm ueber der Traufe, korrekt
noch als "flach" erkannt. Beim anschliessenden Wandschnitt (`cutWallAtMultipleZJTS`) wird bei
Flachdaechern jedes Wandstueck OBERHALB der Traufe verworfen — aber mit der viel engeren
`CUT_TOLERANCE` (0,05m) statt derselben `FLAT_ROOF_TOLERANCE`. Ergebnis: das legitime 19cm-First-
Wandstueck wird verworfen, obwohl die zugehoerigen (unveraenderten) Original-Dachflaechen dieses
Detail weiterhin erwarten → offene Kante im Solid (`GE_S_NOT_CLOSED`). Bestaetigt per direkter
`cutWallAtMultipleZJTS`-Instrumentierung: das First-Dreieck wird von JTS korrekt als eigenes
Bandstueck erzeugt, aber danach durch die zu enge Verwurf-Schwelle wieder entfernt.

**Fix:** beide betroffenen Verwurf-Schwellen (Sammelschleife + Pro-Segment-Schleife) in
`StoreyGenerator.processBuilding` von `traufeZ + CUT_TOLERANCE` auf `traufeZ +
FLAT_ROOF_TOLERANCE` geaendert — dieselbe Toleranz, die auch die Flachdach-Klassifikation selbst
verwendet, konsistent angewendet. Bewusst NUR diese zwei Stellen geaendert (nicht die
Cut-Entscheidung selbst, nicht andere `traufeZ`-Vergleiche wie Slab-Generierung), um den Eingriff
minimal zu halten.

**Verifikation:** fb7, ggO UND hHL isoliert alle drei sauber behoben (Root Cause war identisch
bei allen dreien). Volle 3.801-Gebaeude-Kachel per-Gebaeude-Diff: **genau 3 Zeilen** Unterschied
(fb7/ggO/hHL, `GE_S_NOT_CLOSED` jeweils weg), alle anderen Kategorien (`GE_R_SELF_INTERSECTION`=7,
`GE_S_SELF_INTERSECTION`=5, `GE_P_NON_PLANAR`=538, `GE_S_NON_MANIFOLD_EDGE`=3) exakt unveraendert
— kein Tauschgeschaeft. `GE_S_NOT_CLOSED` tile-weit 8→5. **Gefixt, synchronisiert, GitHub-
Zielordner gebaut, `sqltest/output` neu erzeugt.**

Der vierte urspruengliche `GE_S_NOT_CLOSED`-Neuzugang, `gJv`, ist ein ANDERER, bereits bekannter
Fall: zwei separate `BA_Ground`-Flaechen desselben Gebaeudes ueberlappen sich exakt an den
gemeldeten Fehlerkoordinaten — identisch zum bereits dokumentierten, vom Nutzer bewusst
zurueckgestellten "isolierte GroundSurface"-Fall (siehe `iaq`, Abschnitt "Keller-Basisgeschoss-
Generierung"). Nicht Teil dieses Fixes, bleibt bewusst offen.

**Vollstaendiger, config-korrigierter Fehlerbild-Vergleich LoD2 → LoD3 (Stand 2026-08-31, nach
diesem Fix):**

| Kategorie | LoD2 | LoD3 (vorher) | LoD3 (nach Traufe-Fix) |
|---|---|---|---|
| `GE_R_SELF_INTERSECTION` | 0 | 7 | 7 (unveraendert) |
| `GE_S_SELF_INTERSECTION` | 1 | 5 | 5 (unveraendert) |
| `GE_S_NOT_CLOSED` | 6 | 8 | **5** |
| `GE_S_NON_MANIFOLD_EDGE` | 1 | 3 | 3 (unveraendert) |
| `GE_P_NON_PLANAR_...` | 505 | 538 | 538 (unveraendert) |

**Verbleibend UNGEFIXT (Stand 2026-08-31, Sitzungsende):**
- `GE_R_SELF_INTERSECTION` (7 Instanzen: h55×3, gqs, h37, 09Xf000Fn, hms) — Root Cause bekannt
  (T-Naht-Splitter), drei Fix-Versuche verworfen (Tauschgeschaeft).
- `GE_S_SELF_INTERSECTION` (5: hGc, fMQ, gnQ, iWh, j0t) — **ABGESCHLOSSEN.** hGc+j0t und
  gnQ+iWh als CityDoctor2-Trianguations-Fehlalarm dokumentiert (Nutzer-Entscheidung, kein
  Code-Fix). Einzige verbleibende echte, ungefixte Instanz: `fMQ` (T-Naht-Splitter-Familie,
  s.o.).
- `GE_S_NOT_CLOSED` (5: `CpV`, `gGt`, `hjL` vorbestehend nicht unsere Schuld; `gmt` Symptom von
  Self-Intersection zu NotClosed gewechselt, weiterhin kaputt; `gJv` bekannter, zurueckgestellter
  Fall).
- `GE_S_NON_MANIFOLD_EDGE` (3: `hdQ`/`iMM` Symptom von NonManifoldVertex zu NonManifoldEdge
  gewechselt, weiterhin kaputt; `gJv` s.o.).
- `GE_P_NON_PLANAR` (505→538, +33 netto) — **VOLLSTAENDIG AUFGESCHLUESSELT** (2026-08-31,
  Sitzungsende, auf Nutzer-Wunsch zuerst Wandfaelle geprueft). Drei Ursachen erklaeren praktisch
  die gesamte Zunahme:
  1. **Vorbestehende Quelldaten-Ungenauigkeit** (Wand oder Dach bereits in den LoD2-Rohdaten
     leicht windschief — ein Eckpunkt liegt 1-2mm neben der Ebene der anderen drei, typischerweise
     durch ~1cm XY-Versatz zwischen oberer und unterer Kante ueber die Bauteilhoehe). Bei ALLEN
     8 geprueften Wandfaellen (`fbd`, `hxZ`, `iJE`, `i2s`, `02d60008Z`, `g0f`, `0q800468k`,
     `0007wF`) bestaetigt: dieselbe Original-Wand hatte in der LoD2-Baseline bereits GENAU DIESE
     Meldung — unser Geschossschnitt teilt die Wand nur in mehrere Stuecke auf, jedes Stueck erbt
     dieselbe winzige Verdrehung und wird deshalb EINZELN gezaehlt (1 Meldung -> 2-3 Meldungen).
     Keine neue Verdrehung wird eingefuehrt. Nutzer-Entscheidung: als erklaert dokumentieren, kein
     Fix (echte Neu-Einebnung waere eine Geometrieaenderung mit realem Seiteneffekt-Risiko, nur
     um Sub-3mm-Kleinstungenauigkeit zu vermeiden). Betrifft auch einen Teil der Nicht-Wand-Faelle
     (z.B. `iLc`, `0B740002q`, Teile von `q800468k`/`ie3`).
  2. **RoofWindowGenerator verschiebt die Ausgleichsebene** (bereits laenger bekannter
     Mechanismus, siehe [[slab-clip-self-intersection-snap-fix]]) — massiv bestaetigt: 27 von 41
     geprueften Nicht-Wand-Instanzen betroffen, u.a. ein kompletter 7er-Cluster (`02d60008S/N/R/
     K/L/b/Z`, alle mit identischer Original-Aussenkontur, nur durch 1-5 Dachfenster-Loecher
     jeweils ueber die Toleranz gedrueckt). Keine Aktion — dieselbe Klassifikation wie bei hGc/j0t
     (GE_S_SELF_INTERSECTION): reale, aber sehr kleine (Sub-mm) Verschiebung der CityDoctor2-
     Ausgleichsebene durch legitime Dachfenster, kein Geometriefehler.
  3. **T-Naht-Splitter (conformJunctions)** — DIESELBE Familie wie die offenen
     `GE_R_SELF_INTERSECTION`-Faelle (h55/gqs/h37/09Xf000Fn/fMQ/hms): ein T-Naht-Kandidat wird
     wenige mm bis ~1cm neben einem bereits vorhandenen Eckpunkt derselben Kontur eingefuegt und
     erzeugt dort statt einer Selbstueberschneidung nur einen winzigen Knick, der die Ebene
     verfehlt. Bestaetigt bei `h37` (Punktanzahl 7->8, neuer Punkt exakt 7mm unter einem
     bestehenden Eckpunkt derselben XY-Position), `ie3`, `q80047bD`, `flt` (alle mit zusaetzlichem
     Punkt gegenueber LoD2) und `q80044Y6` (mehrere neue Punkte). ~5 Instanzen. Bleibt ungefixt
     aus demselben Grund wie `GE_R_SELF_INTERSECTION` — Teil derselben, bereits mehrfach an
     Trade-offs gescheiterten Baustelle, keine separate Untersuchung noetig.

  **Fazit:** die urspruengliche Sorge "34 komplett unerklaerte Gebaeude" ist nach dieser
  Untersuchung nicht mehr zutreffend — praktisch die komplette Zunahme ist auf die drei
  o.g., bereits an anderer Stelle dokumentierten Mechanismen zurueckgefuehrt, keine neue,
  eigenstaendige Fehlerklasse gefunden.

### Vierter Fix-Versuch fuer T-Naht-Splitter, TEILWEISE erfolgreich: Ring-Aufspaltung am Pinch-Point (2026-08-31, spaet)

Nach den drei gescheiterten "Kandidat weglassen"-Versuchen (s.o., Abschnitt "Systematischer
LoD2-vs-LoD3-Vergleich") eine strukturell andere Idee: statt die T-Naht-Einfuegung zu verhindern,
den dadurch entstandenen selbstberuehrenden Ring NACHTRAEGLICH an der Beruehrungsstelle in zwei
einfache Teilringe aufspalten — beide bleiben Teil desselben `MultiSurface` (in CityGML voellig
normal, mehrere `Polygon`e pro Flaeche sind zulaessig). Keine Einfuegung wird dafuer weggelassen,
keine Verbindung geht verloren.

**Implementierung:** `CityGmlUtils.splitSelfTouchingRings(Building)`, neuer letzter
geometrieveraendernder Schritt NACH `conformJunctions` (muss danach laufen, der Pinch entsteht
erst dort — nachgelagerter Code darf sich ab hier NICHT mehr auf "genau ein Polygon pro Flaeche"
verlassen). Findet im Aussenring eines Polygons ein Kandidatenpaar (nicht benachbarter, (nahezu)
identischer Punkt), teilt den offenen Punktpfad an dieser Stelle in zwei Haelften auf, ordnet
vorhandene Innenringe (Fenster/Tueren) per 3D-Punkt-in-planarem-Ring-Test (dominante-Achse-
Projektion, wie `ringSelfIntersects`) der jeweils passenden Haelfte zu. Rekursiv (bis 5
Iterationen), falls mehrere verschachtelte Pinch-Points im selben Ring vorliegen.

**Zwei wichtige, beim Testen gefundene Randfaelle:**
1. Bei verschachtelten Pinch-Points (derselbe Punkt kommt an DREI+ Stellen vor) kann das erste
   gefundene Kandidatenpaar eine entartete (<3 Punkte) Teilflaeche erzeugen, obwohl ein ANDERES
   Paar im selben Ring einen gueltigen Schnitt liefern wuerde. Fix: naechstes Kandidatenpaar
   probieren statt aufzugeben, wenn eines entartet.
2. Ein reiner "Spike" (Weg geht zu einem Punkt raus und exakt wieder zurueck, z.B. A→B→C→B) hat
   IMMER einen zu kurzen Abstand zwischen den beiden Beruehrungs-Indizes — beide resultierenden
   Haelften wuerden auf <3 Punkte entarten. Strukturell NICHT per Zweiseiten-Aufspaltung loesbar.
   **Sicherung:** das Gesamtergebnis wird nur committet, wenn ALLE Teilstuecke (nach allen
   Rekursionsstufen) mit dem gruendlicheren `ringSelfIntersects`-Test vollstaendig sauber sind —
   sonst wird die GESAMTE Aufspaltung verworfen und das Original bleibt unveraendert (Ring bleibt
   wie bisher gemeldet, aber es entsteht KEIN neuer Fehler an anderer Stelle). Ohne diese
   Sicherung erzeugte ein Testlauf bei `h55` genau das befuerchtete Tauschgeschaeft (2 von 3
   Selbstueberschneidungen behoben, aber eine neue `GE_S_NOT_CLOSED` durch das uebrig gebliebene
   Spike-Stueck) — mit der Sicherung: sauber 2 von 3 behoben, keine neue Luecke.

**Ergebnis:** von den bekannten Problemfaellen liessen sich NUR 2 der 3 Instanzen bei `h55`
sauber aufspalten (echte, nicht verschachtelte Zweiseiten-Pinches). `gqs`, `h37`, `09Xf000Fn`,
`hms` sind alle reine Spikes (Punktabstand <3 zwischen den Beruehrungsstellen) und bleiben
unveraendert — fuer diese braeuchte es eine andere Technik (Spike-Detour entfernen), die aber
strukturell wieder dem bereits verworfenen "Weglassen"-Muster nahekommt und hier bewusst NICHT
versucht wurde. **Verifiziert:** volle 3.801-Gebaeude-Kachel per-Gebaeude-Diff zeigt GENAU EINE
Zeile Unterschied (`h55`: 3→1 Instanzen), alle anderen Kategorien exakt unveraendert
(`GE_S_SELF_INTERSECTION`=5, `GE_S_NOT_CLOSED`=5, `GE_P_NON_PLANAR`=538, `GE_S_NON_MANIFOLD_
EDGE`=3), Schema-valide, val3dity identisch (366/9409 Features, 53/16238 Primitive, exakt wie
vorher). `GE_R_SELF_INTERSECTION` tile-weit 7→5. **Geshippt, synchronisiert, GitHub-Zielordner
gebaut, `sqltest/output` neu erzeugt.**

**Verbleibend UNGEFIXT (Stand 2026-08-31, nach diesem Fix):** `h55` (1 verbleibende Instanz,
Spike), `gqs`, `h37`, `09Xf000Fn`, `hms` (alle Spikes) — 5 Instanzen insgesamt. Root Cause fuer
ALLE fuenf jetzt vollstaendig verstanden (Spike-Pattern), aber kein sicherer Fix ohne
Tauschgeschaeft gefunden.

**Konkrete Root-Cause-Herleitung an `gqs` + Korrektur einer Fehleinschaetzung (2026-08-31,
noch spaeter):** die T-Naht-Einfuegung sucht mit `tol=5mm` nach Kandidaten fuer jede Ringkante.
Weil die Geschoss-Schnitthoehe bei `gqs` zufaellig nur ~1mm von einer ECHTEN Original-Wandstufe
entfernt liegt (`cutWallAtMultipleZJTS` liefert dafuer bereits ein sauberes, aber sehr eng
gestuftes Rohstueck), findet die T-Naht-Suche einen Kandidaten, der SPAETER im selben Ring
ohnehin schon als eigener Punkt vorkommt, und traegt ihn zusaetzlich ein — der Spike entsteht.
Bestaetigt identisch bei `h55`/`h37`/`09Xf000Fn` (6-9mm Z-Differenz zwischen den beiden
Vorkommen); `hms` ist derselbe Spike-Typ mit exakt (nicht nur fast) gleicher Hoehe.

Erste Vermutung dazu (`weldNearbyRingVertices` kollabiere zwei 1mm-nahe Punkte und schaffe so
erst die kritische Kante) wurde per direktem A/B-Test (Verschmelzung per Schalter deaktiviert vs.
aktiviert, an denselben 5 Gebaeuden UND an der vollen Kachel) **widerlegt** — `GE_R_SELF_
INTERSECTION` war in beiden Faellen exakt identisch (5/5). Verschmelzung ist NICHT die Ursache.
Zusaetzlich bestaetigt: OHNE Verschmelzung wird es an anderer Stelle sogar schlechter
(`GE_S_NOT_CLOSED` 5→9, `GE_R_CONSECUTIVE_POINTS_SAME` 0→26) — Verschmelzung bleibt ein reiner
Gewinn, keine Mitursache dieser Spikes.

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
| 5c | Dachfenster (RO.window.XXX, `RoofWindowGenerator`) | ✅ Fertig (2026-08-27) |
| 3f | Wand-Mehrfachschnitt bei Oeffnung+Mischdach/Anbau-Kerben (JTS-Bandschnitt, `cutWallAtMultipleZJTS` → kein 306) | ✅ Fertig (2026-08-24, JTS-Umbau) |
| 6 | Junction-Conforming (T-Naht-Vertices, streng formneutral, 0 mm bewegt) | ✅ Fertig |
| — | Vertex-Welding ENTFERNT (verschob Vertices ≤5 mm → Aufgabe des Healers) | ✅ Entfernt |
| 7 | Oeffnungs-Kontur-Check (Tuer+Fenster via `openingInsideWall2D`; 206/201 → 0) | ✅ Fertig |
| 8 | Balkone/Terrassen (`BalconyGenerator`) | ✅ Fertig — seit 2026-08-10 in Pipeline verdrahtet, seit 2026-08-11 als `BuildingInstallation`, seit 2026-08-12 zweiphasig um die Fenster herum (Redesign 3: 630→1.075 Balkone); an 14 Testgebäuden + voller 3.801er-Kachel val3dity- und XSD-verifiziert (0 zusätzliche Fehler, schema-valide) |
| 3g | Fliegende Geschossdecke bei BuildingPart-losen Anbauten (siehe [Bugfix](#bugfix-fliegendes-stockwerk-bei-anbauten-ohne-eigenes-buildingpart-2026-08-12)) | ✅ Fertig (2026-08-12) — Anbau-Kerben-Entfernung pro Grundpolygon-Kante, val3dity-neutral verifiziert |
| 5d | Doppelte/gestapelte Fensterreihen (`WindowGenerator`, jede Wand hart auf 1 Reihe begrenzt) | ✅ Fertig (2026-08-11, auf alle Geschosse erweitert 2026-08-25) |

### Schritt 5c: Dachfenster (`RoofWindowGenerator`, 2026-08-27)

Eigenständiger neuer Generator (nicht in `WindowGenerator` integriert, um diesen nicht weiter
aufzublähen und weil die Geometrie einer geneigten Dachfläche fundamental anders ist als eine
senkrechte Wand). Platziert Dachflächenfenster (flach in der Dachschräge liegend, Velux-Stil,
keine Gauben) auf geneigten `RoofSurface`-Polygonen anhand des `RO.window`-Blocks der JSON-
Baukörpermodule — derselbe Parametersatz wie bei Wand-/Kellerfenstern
(`HDistWaWi`/`HDistMinWaWi`/`HDistWiWi`/`VDistFlWi`/`WiLen`/`WiHe`), da `RO.window` intern
denselben `WindowParams`-Typ nutzt.

**Vorab geklärt:** `RO.shape.Typ` ist NICHT ein Dachfenster-/Gauben-Typ, sondern die Dachform des
Gebäudes selbst (Satteldach/Flachdach/etc., `ModuleParameters.RoofShape.type`). Die weiteren
`RO.shape`-Neigungsparameter (`HSiTi`/`VSiTi`/`HFrTi`/`VFrTi`) dienten offenbar der prozeduralen
Dachform-Erzeugung im urspruenglichen novaFACTORY-Tool und sind fuer uns irrelevant, da die
Dachgeometrie bereits real aus dem LoD2-Datensatz vorliegt (kein Dach wird neu konstruiert). Scope
bleibt bewusst einfach: flache Dachflächenfenster in vorhandene geneigte `RoofSurface`-Polygone
einschneiden, keine Gauben-Baukörper. `RO.window` ist in ca. der Haelfte der geprueften Module
komplett leer (kein Dachfenster in diesem Baukoerpertyp) — `WindowParams.isValid()` filtert das
automatisch.

**Geometrische Kernentscheidung:** die von Wänden bekannte Konvention "u = entlang Unterkante, v =
Erstreckung von der Bezugskante weg" wird 1:1 auf die Dachfläche übertragen — bei der Wand ist v
zufällig Welt-Z (weil senkrecht), bei der Dachfläche ist v die Erstreckung **entlang der
Dachschräge** (Traufe → First), nicht Welt-Z. `VDistFlWi`/`WiHe` werden also entlang der Neigung
gemessen (an einer 45°-Testfläche numerisch verifiziert: ein Fenster mit `WiHe=1.45` hat exakt
1.45m 3D-Abstand zwischen Unter- und Oberkante, nicht nur 1.45m Z-Differenz). Außerdem: genau 1
Fensterreihe pro Dachfläche (Traufe→First), konsistent mit der Wand-Konvention seit dem
2026-08-25-Fix.

**Wiederverwendung:** `CityGmlUtils.findBottomEdge` (Traufkante = "Unterkante" einer Dachfläche,
identische Logik wie bei Wänden — komplexe/unregelmäßige Verschneidungsflächen ohne 2 Punkte auf
zMin, z.B. die aus dem traufeZ-Fix bekannte 86m²-Kehlfläche, werden dadurch automatisch
übersprungen, kein Sondercode nötig), `WindowGenerator.calculateWindowCount`/`calculateWindowOffsets`
(paket-privat, direkt wiederverwendbar), `CityGmlUtils.pointInPolygon2D`/`openingInsideWall2D`/
`openingInsideWallTopClearance2D` (bereits vollständig generisch), `CityGmlUtils.addOpeningToWall`
(trotz des Namens bereits generisch — nimmt 4 beliebige 3D-Eckpunkte entgegen, kein Duplikat
nötig). Neu: zwei kleine, eigenständige Utility-Methoden `CityGmlUtils.projectPlaneTo2D`/
`isRingCCWOnPlane` (Generalisierung von `projectWallTo2D`/`isExteriorRingCCW` mit echtem 3D-
"Aufwärts"-Vektor statt der Wand-Annahme v=Z — bewusst als NEUE Methoden, keine Änderung an den
bestehenden wand-spezifischen Funktionen, um jedes Regressionsrisiko am verifizierten Wand-Pfad
auszuschließen) sowie `computeUpSlopeVector` (Newell-Normale × Traufrichtung, Vorzeichen auf
First-Richtung normiert).

**Bugfix waehrend der Verifikation — `302 SHELL_NOT_CLOSED` (22→342 Primitive):** der erste Lauf
auf der vollen Kachel zeigte eine deutliche val3dity-Regression. Isoliert auf `DESNALK0q80047bD`
(1 Dachfläche, 2 Dachfenster) reproduziert. Ursachen-Analyse: `CityGmlUtils.rebuildSolidShell`
nimmt FillingSurfaces (Fenster/Türen) einer Öffnung explizit mit in die Solid-Shell auf — laut
eigenem Code-Kommentar "sonst GE_S_NOT_CLOSED am Lochrand" — aber dieser Mechanismus war hart auf
`instanceof WallSurface` verdrahtet, ohne Berücksichtigung von `RoofSurface`. Jedes durchs
Dachfenster geschnittene Loch blieb dadurch am Lochrand offen. **Verifiziert per Ausschlussverfahren**
(empirischer Flip-Test, um eine falsche Loch-Wicklung als Ursache auszuschließen: `!extCCW` beim
Lochschnitt ergab `208 ORIENTATION_RINGS_SAME` statt `302` — bestätigt, dass die urspruengliche
Wicklungsrichtung korrekt war und das Problem woanders lag). **Fix:** `rebuildSolidShell`s
FillingSurface-Sammlung generalisiert auf `WallSurface` UND `RoofSurface` (kein Umbau der
bestehenden Wand-Logik, nur ein zusätzlicher Zweig). Nach dem Fix: isoliertes Testgebäude 10/10
Primitive valide (vorher 9/10), volle Kachel wieder exakt auf der Baseline.

**Verifiziert:** 17/17 Unit-Tests (4 neue, geometrisch an einer 45°-Testfläche per Hand
vorverifiziert: `computeUpSlopeVector`, `projectPlaneTo2D`, CCW-Erkennung beider Richtungen).
`DESNALK0q80047bD` isoliert (Modul EE3, reale `RO.window`-Werte): 2 Dachfenster auf der sauberen
Walmdach-Teilfläche `_3_76` (54.6m², Teil des im traufeZ-Fix bestätigten Konsens-Clusters), 0
Fenster auf der bekannten 86m²-Kehlfläche `_3_17` (automatisch ausgeschlossen: zu kurze
Traufkante) — schema-valide, 10/10 val3dity-Primitive valide. 14-Testgebäude-Menge schema-valide.
Volle 3.793-Gebäude-Kachel: 1.400 Dachfenster auf 657 Dachflächen (2.116 übersprungen: 497
Flachdach, 776 keine Traufkante/Normale, 96 zu kurz entlang der Schräge, 640 Traufkante zu kurz,
107 außerhalb der Kontur); Wand-/Tür-/Fenster-/Balkon-Zahlen **exakt unverändert** (40.781/3.048/
3.096 — Dachfenster sind vollständig unabhängig vom Wandzustand); schema-valide; val3dity **exakt
identisch zur Baseline** (102=7/104=4/204=30/302=22/303=27/306=1/307=10/601=313).

### Bugfix: Fensterkante exakt auf Anbau-Kerbe der Wandkontur (GE_P_INTERIOR_DISCONNECTED, 2026-08-27)

**Problem** (Nutzer-Fund an Gebäude `DESNALK0pF001i5d`, Screenshot): CityDoctor2 meldet
`GE_P_INTERIOR_DISCONNECTED`. Der Nutzer vermutete richtig: eine Fensterecke berührt exakt die
Ecke, an der ein Flachdach-Anbau in die Hauptwand einschneidet.

**Ursache bestätigt** (CityDoctor2-CLI direkt auf das isolierte Gebäude angesetzt,
`de.hft.stuttgart.citydoctor2.CityDoctorValidationCLI` aus `D:\Tools\CityDoctorGUI-3.18.2-win\app`,
liefert einen XML-Report mit exakten Koordinaten): Wand `Face_00042S8_0_1_UF_1_1` (Innenwand="1",
neben einem Anbau) hat durch die bestehende Anbau-Kerben-Behandlung eine **nicht-konvexe**
Kontur mit einer echten Stufe (Kerbe bei u≈7,63, Höhe springt von Z=230,81 auf Z=231,35). Ein
Fenster-Kandidat (`Win_2`) wurde von `calculateWindowOffsets` so platziert, dass seine RECHTE Kante
exakt auf dieser Kerbe liegt (Koordinaten bis auf 1e-7m identisch). Der reine 4-Eckpunkt-
Ray-Casting-Test (`openingInsideWall2D`) ist an Randpunkten mehrdeutig und ließ den Kandidaten
faelschlich durch — dieselbe Fehlerklasse wie der bereits behobene "Fenster liegt exakt an der
Traufe an"-Bug (`openingInsideWallTopClearance2D`), hier aber an einer SEITENKANTE statt der
Oberkante.

**Tile-weite Prüfung:** CityDoctor2 auf der vollen 3.793-Gebäude-Kachel angesetzt (lief in ~7
Sekunden dank In-Memory-Fallback-DB) — **nur 1 Vorkommen tile-weit**, kein systematisches Muster.

**Fix:** neue Methode `CityGmlUtils.openingInsideWallSideTopClearance2D` — wie die bestehende
Traufe-Clearance, zusätzlich mit demselben 2cm-Sicherheitsabstand an LINKER und RECHTER Kante
(bewusst weiterhin OHNE Unterkante — bodenbündige Kellerfenster bleiben gültig). Da bei einem
"normalen" Fenster der Abstand zur Wandkante durch `HDistMinWaWi` (typischerweise 1–3m) ohnehin
weit über 2cm liegt, betrifft die Änderung ausschließlich echte Randfälle wie diesen — verifiziert
durch die tile-weite Zahlen unten. `WindowGenerator.collectValidWindows` nutzt diese neue Methode
jetzt als primäre Prüfung (Fallback-Nudge nach unten bleibt wie bisher nur vertikal — ein seitlich
kollidierendes Fenster wird verworfen statt seitlich verschoben, analog zum bestehenden
Giebel-Drop-Verhalten).

**Verifiziert:** 20/20 Unit-Tests (2 neue: Ablehnung einer Fensterkante exakt auf einer
Wand-internen Kerbe, Bestätigung dass ein Fenster mit echtem Abstand weiterhin passiert, an einer
synthetischen L-foermigen Testwand). `DESNALK0pF001i5d` isoliert: 19→18 Fenster (1 Giebel-Drop
mehr), CityDoctor2 bestätigt `GE_P_INTERIOR_DISCONNECTED` 1→0. 14-Testgebäude-Menge:
schema-valide, Fensterzahl unverändert (i5d nicht im 14er-Set enthalten). Volle Kachel:
40.781→40.776 Fenster (−5, plausibel: die restlichen 4 sind bislang unentdeckte, ähnlich knappe
Randfälle ohne CityDoctor2-Meldung, jetzt präventiv verworfen), Giebel-Drops 333→338 (+5, exakt
gegenläufig); schema-valide; val3dity **Gesamtzahlen exakt unverändert** (92/17.797 Primitive,
396/9.985 Features weiterhin invalide) — nur eine Verschiebung 302→303 um je 1 (dieselbe Wand,
siehe unten); CityDoctor2 tile-weit: `GE_P_INTERIOR_DISCONNECTED` 1→0.

**Nebenbefund (nicht Teil dieses Fixes):** nach dem Entfernen des kollidierenden Fensters meldet
CityDoctor2 für **dieselbe** Wand neu `GE_S_NOT_CLOSED` (vorher durch den schwerwiegenderen
Interior-Disconnected-Fehler offenbar maskiert) — passt zur val3dity-Verschiebung 302→303. Das
Entfernen eines Fensters kann geometrisch nur vereinfachen, nie einen neuen Fehler erzeugen; die
gestufte Wandkontur an dieser Stelle hat also vermutlich bereits vorher ein eigenes, noch nicht
untersuchtes Problem, unabhängig vom Fenster. Val3dity-Gesamtzahl bleibt unveraendert (derselbe
Primitive war vorher schon invalide), daher kein Rueckschritt — aber als offener Punkt fuer eine
spaetere, gezielte Untersuchung der Anbau-Kerben-Geometrie an dieser Wand vorgemerkt. **Update
2026-08-27: identifiziert und behoben, siehe unten** — exakt derselbe Mechanismus wie bei
`DESNALK0pF001iLp`.

### Bugfix: Keller-Überhang-Segment nicht verworfen (GE_S_NON_MANIFOLD_EDGE/GE_S_NOT_CLOSED, 2026-08-27)

**Problem** (Nutzer-Fund an Gebäude `DESNALK0pF001iLp`, betrifft laut Nutzer möglicherweise mehr
Gebäude): CityDoctor2 meldet an der Keller-/EG-Kante `GE_S_NON_MANIFOLD_EDGE` und `GE_S_NOT_CLOSED`
— der Nutzer beschrieb es treffend als "zwei Linien knapp übereinander".

**Neuer Werkzeug-Einsatz:** CityDoctor2-CLI (`de.hft.stuttgart.citydoctor2.CityDoctorValidationCLI`)
direkt auf den vollen 3.793-Gebäude-Bestand angesetzt (in ~7 Sekunden dank In-Memory-DB), um die
tile-weite Häufigkeit zu ermitteln, statt blind zu fixen: **79 von 3.793 Gebäuden (≈2,1 %)**
betroffen — genug, um eine echte, allgemeine Untersuchung zu rechtfertigen.

**Ursache bestätigt** (StoreyGenerator, Wand-Mehrfachschnitt): eine Original-Wand kann geringfügig
unter `egFloorZ` (Keller-/EG-Grenze) hinabreichen (hier exakt 0,10 m, `Face_00043QT_0_12` von
227,49 bis 230,87, `egFloorZ`=227,59). Der JTS-Bandschnitt (`cutWallAtMultipleZJTS`) erzeugt daraus
korrekt ein eigenes Segment für das Kellerband [227,49; 227,59]. Der Verwurf-Check dafür prüfte
bisher den **Mittelpunkt** des Segments gegen `egFloorZ − CUT_TOLERANCE` (227,59 − 0,05 = 227,54)
— bei genau 0,10 m Überhang liegt der Mittelpunkt (227,54) **exakt** auf dieser Schwelle, der
`<`-Vergleich schlägt knapp fehl, das Segment wird NICHT verworfen. Der anschließende
Nachtrimm-Fallback (`trimWallBelowEgFloor` → `cutWallPolygonAtZ`) erkennt dann, dass der
Schnittpunkt exakt auf der eigenen Obergrenze des Segments liegt ("nichts mehr zu schneiden") und
gibt `null` zurück — der Fallback behält daraufhin das komplette Kellerband als eigenständiges
GF-Wandstück, das sich mit der separat erzeugten Kellerwand (`BasementGenerator`) überlappt bzw.
eine offene Kante hinterlässt.

**Fix:** Verwurf-Kriterium von "Mittelpunkt unterhalb Schwelle" auf "**Oberkante** des Segments
überragt `egFloorZ` nicht um mehr als `CUT_TOLERANCE`" umgestellt (`segZ[1] <= egFloorZ +
CUT_TOLERANCE`) — an zwei Stellen in `StoreyGenerator.java` (Vorab-Ermittlung von `keptMaxTop` und
die eigentliche Verwurf-Entscheidung). Diese neue Schwelle ist **mathematisch exakt deckungsgleich**
mit `cutWallPolygonAtZ`s eigenem "zCut liegt an der Obergrenze"-Schutz (`zCut >= maxZ - tolerance`
⟺ `maxZ <= zCut + tolerance`, gleiche `CUT_TOLERANCE`) — jedes Segment, das den Nachtrimm-Fallback
zum Scheitern bringen würde, wird dadurch bereits vorher zuverlässig verworfen, unabhängig von der
genauen Größe des Überhangs (nicht nur bei exakt 0,10 m).

**Verifiziert:** 20/20 Unit-Tests weiterhin grün (keine neuen Tests nötig, reine Bugfix-Änderung
an bereits bestehender Logik). `DESNALK0pF001iLp` isoliert: alle bisherigen Warnungen
("reicht unter egFloorZ", "Tür passt nicht in Wand ... Wandhöhe 0.1") verschwunden,
Wandsegmente 16→8, CityDoctor2 bestätigt `GE_S_NON_MANIFOLD_EDGE`/`GE_S_NOT_CLOSED` je 1→0.
14-Testgebäude-Menge: unverändert (kein betroffenes Gebäude enthalten), schema-valide. Volle
Kachel: Wandsegmente 77.374→77.151 (−223, Kellerüberhang-Slivers tile-weit eliminiert), Türen
2 weniger übersprungen (44→32, einige zuvor fälschlich zu niedrige Wandstücke sind jetzt normale
Wände), Fenster/Balkone/Dachfenster unverändert; schema-valide; val3dity **echte Verbesserung**
(92→79 invalide Primitive, 396→390 invalide Features; 303 NON_MANIFOLD_CASE 26→13,
307 POLYGON_WRONG_ORIENTATION 10→7, 601 minimal +1 durch Nebeneffekt, alle anderen Kategorien
unverändert); CityDoctor2 tile-weit: `GE_S_NON_MANIFOLD_EDGE` 32→19, `GE_S_NOT_CLOSED` 71→58
(exakt 13 Gebäude vollständig behoben, 79→66 betroffene Gebäude), `GE_P_INTERIOR_DISCONNECTED`
bleibt bei 0 (Fix von oben haelt).

**Offener Punkt (nicht Teil dieses Fixes):** 66 Gebäude zeigen weiterhin `GE_S_NON_MANIFOLD_EDGE`/
`GE_S_NOT_CLOSED` — Stichprobe (`DESNALK0pF0007wF`) zeigt offene Kanten auf einem ANDEREN
Z-Niveau (224,01 statt einer egFloorZ-nahen Höhe), also vermutlich eine andere, noch nicht
diagnostizierte Ursache. Für eine spätere, separate Untersuchung vorgemerkt — CityDoctor2-CLI auf
die volle Kachel angesetzt liefert dafür bereits die betroffene Gebäudeliste
(`affected_buildings2.txt`-Muster, siehe Vorgehen oben).

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

## Unit-Tests (2026-08-24)

Seit dem Code-Audit vom 2026-08-24 gibt es neben der bisherigen manuellen Verifikationskette
(Einzelgebäude isolieren → 14er-Testset → volle 3.801-Gebäude-Kachel → `citygml-tools validate` →
val3dity) auch klassische JUnit-5-Tests für die trickyste, reine Geometrie-Logik in
`CityGmlUtils` — die Funktionen, die keine GML-Ein-/Ausgabe brauchen, sondern nur Koordinaten
rein und Teilstücke/Booleans raus liefern.

**Warum zusätzlich zur Kachel-Verifikation, nicht statt ihr:** ein Test läuft in Millisekunden
und schlägt sofort fehl, wenn eine Änderung eine der bereits gefundenen, nicht offensichtlichen
Fehlerursachen (geknickte Wände, Anbau-Kerben, Party-Wand-Deckung) wieder kaputt macht — noch
bevor man den mehrminütigen Weg über Kachel + val3dity geht. Was Tests **nicht** abdecken können:
Shell-Geschlossenheit, Koordinaten-Präzision zwischen benachbarten Flächen im fertigen GML,
val3dity-Kategorien insgesamt — das sind Effekte, die erst beim echten Zusammenbau der ganzen
Kachel auftreten (siehe die zwei Nachbesserungen beim JTS-Wand-Mehrfachschnitt-Fix oben). Die
volle Verifikationskette bleibt daher vor jedem Fix-Abschluss weiterhin Pflicht.

**Wo:** `src/test/java/de/mpsc/lod2tolod3/util/`, spiegelt die Paketstruktur von `src/main`
(Standard-Maven-Layout). Abhängigkeit: `org.junit.jupiter:junit-jupiter` (test-scoped in
`pom.xml`), von Maven Surefire automatisch erkannt — kein zusätzliches Plugin nötig.

**Ausführen:**
```bash
mvn test              # nur Tests
mvn clean package      # Tests laufen automatisch mit (nicht mehr -DskipTests wie bisher!)
```

**Vorhandene Tests:**

- `CityGmlUtilsWallCutTest` — testet `cutWallAtMultipleZJTS` (siehe Bugfix oben). Baut das
  Face_00040DQ_0_8-Profil aus `imo` mit runden Testkoordinaten nach (Wand mit Anbau-Kerbe, die
  bis zum Boden reicht) und prüft: (a) ein Schnitt innerhalb der Kerben-Zone zerfällt korrekt in
  mehrere Teilstücke statt stillschweigend übersprungen zu werden (der ursprüngliche Bug), (b) die
  Flächenbilanz aller Teilstücke stimmt exakt mit der Original-Wandfläche überein (deckt sowohl
  verlorene als auch doppelt gezählte Fläche auf — genau die Art Fehler, die zur
  Orientierungs-Regression während der Entwicklung geführt hat). Plus ein Kontrolltest für die
  einfache Rechteckwand (kein Kerben-Sonderfall), damit der JTS-Umbau den Normalfall nicht
  verändert.
- `CityGmlUtilsPartyWallTest` — testet `computeCoveredSpans`/`overlapsAnySpan`/`isFullyCovered`
  (siehe Bugfix "Fenster/Tueren/Balkone hinter Anbau" oben). Baut die gHh-Situation nach (geknickte
  Wand, ein Abschnitt deckt sich mit der Trennwand eines Anbaus) und prüft: (a) nur der
  tatsächlich verdeckte Abschnitt wird als verdeckt erkannt, der freie Abschnitt bleibt nutzbar,
  (b) volle Deckung wird korrekt von Teildeckung unterschieden, (c) eine Nachbarwand auf einem
  anderen Geschoss (abweichendes zMin) erzeugt keine Deckung — das ist der Grund, warum die
  Prüfung wandweise und nicht grundrissweise arbeitet (ein freiliegendes Obergeschossfenster über
  einem einstöckigen Anbau würde sonst fälschlich als verdeckt markiert).
- `CityGmlUtilsBottomEdgeTest` — testet `findBottomEdge`, die Grundlage praktisch jeder
  Öffnungs-Platzierung UND (seit 2026-08-24) des Wand-Mehrfachschnitts. Prüft: bei mehreren Punkten
  auf `zMin` (geknickte Wand mit Zwischenpunkt auf der Sohle) wird das Punktepaar mit dem größten
  2D-Abstand gewählt (volle Wandbreite), nicht das erste gefundene, kürzere Teilstück — genau der
  Mechanismus, der beim `gjj`-Bug fehlte.
- `CityGmlUtilsOpeningClearanceTest` — testet `openingInsideWallTopClearance2D` (siehe Bugfix
  "Fenster lag exakt an der Traufe an" oben). Prüft: ein Fenster, dessen Oberkante exakt auf der
  Wandkontur liegt, wird abgelehnt; eines mit echtem Abstand zur Oberkante bleibt zugelassen; ein
  bodenbündiges Fenster (Unterkante bei v=0) bleibt unbeeinflusst — der Clearance-Abstand gilt
  bewusst nur oben, nicht an den anderen drei Seiten (sonst würden echte Giebel-Abschnitte mit
  schrägen Seitenkanten mitbeeinflusst).
- `CityGmlUtilsSlabClipTest` — testet `clipSlabAtZ` (siehe Bugfix "Ablösung: Anbau-Zuschnitt durch
  echte 2D-Polygon-Differenz" oben, der Vorläufer des heutigen Wand-Mehrfachschnitt-Fixes). Baut
  einen Grundriss mit einem niedrigeren Anbau-Flachdach nach und prüft: eine Geschossdecke weit
  über der Anbau-Traufe wird korrekt um die Anbau-Grundfläche verkleinert (kein schwebender
  Deckenanteil über dem Anbau), eine Geschossdecke unterhalb der Anbau-Traufe bleibt dagegen
  unverändert über den vollen Grundriss.

Alle Testklassen sind bewusst als Regressionstests für konkrete, real gefundene Fehler geschrieben
(nicht als abstrakte Coverage-Übung) — jeder Testfall entspricht einem Screenshot/Bugreport aus
dieser Session.

---

## Code-Redundanz-Aufräumung: `CityGmlUtils.java` aufgeteilt (2026-09-01)

`CityGmlUtils.java` war über Monate organisch auf 2.186 Zeilen mit 13 thematisch klar
unterscheidbaren Blöcken gewachsen (Geometrie-Grundlagen, Gebäude-Abfragen, Wand-Schnitt,
Slab-Zuschnitt, TerrainIntersectionCurve, Solid-Shell-Rebuild, Junction-Conforming,
Pinch-Point-Aufspaltung, Öffnungen, Party-Wand-Deckung, GML-I/O, Attribut-Helfer). Reine
Verschiebung ohne Logikänderung, kein Verhalten sollte sich ändern — daher als **Phase A**
separat von jeder inhaltlichen Änderung durchgeführt und per Plan-Mode vom Nutzer abgesegnet.

**Neue Struktur** (alle im selben Paket `de.mpsc.lod2tolod3.util`, package-private Sichtbarkeit
reicht für Cross-Klassen-Aufrufe):

| Datei | Inhalt |
|---|---|
| `Point3D.java` | Die bisher in `CityGmlUtils` verschachtelte `Point3D`-Klasse, jetzt Top-Level |
| `GeometryUtils.java` | Geometrie-Grundlagen (Flächen, Kanten, Rundung, `createPolygon`, Selbstschnitt-Test, Wand-Attribut-Berechnung, Ebenen-Projektion, Ring-Punkte) — Basis-Klasse, von fast allen anderen genutzt |
| `BuildingQueryUtils.java` | Boundary-/Target-Sammlung, Dach-Z-Bereich |
| `WallCuttingUtils.java` | Sutherland-Hodgman-Einzelschnitt + JTS-Bandschnitt an mehreren Höhen |
| `SlabClippingUtils.java` | Geschossflächen-Zuschnitt bei Anbauten (JTS-Differenz) |
| `SolidShellUtils.java` | TerrainIntersectionCurve + Solid-Shell-Neuaufbau |
| `JunctionConformingUtils.java` | T-Naht-Konformierung + Pinch-Point-Aufspaltung (gehören eng zusammen, Aufspaltung läuft direkt nach Konformierung) |
| `OpeningUtils.java` | Fenster-/Tür-Platzierungsprüfungen und -Erzeugung |
| `PartyWallCoverageUtils.java` | Wand-Deckung durch Nachbarbauteile (Anbau) |
| `CityGmlUtils.java` (bleibt, 200 statt 2.186 Zeilen) | Attribut-Helfer, GML-Datei-I/O, SRS-Konstanten + `MultiSurfaceProperty`-Erzeugung |

Zwei Methoden mussten entgegen der reinen Zeilenbereich-Zuordnung des Plans bewusst mit ihrem
jeweiligen Abschnitt "mitwandern", weil sie nur dort gebraucht werden: `pointsOfRing` (physisch im
Öffnungen-Block, aber sowohl von der Pinch-Point-Aufspaltung als auch von den Öffnungen gebraucht
→ landet in `GeometryUtils`) und `segmentsProperlyIntersect` (Selbstschnitt-Helfer, zusätzlich von
`OpeningUtils.wallContourEntersOpening` gebraucht → package-private in `GeometryUtils`, nicht mehr
rein privat). `import static` wurde bewusst NICHT verwendet (Herkunft am Aufrufort bleibt über
`GeometryUtils.xxx(...)` sichtbar, wie zuvor `CityGmlUtils.xxx(...)`).

**Verifikation:**
1. `mvn clean package`: alle 25 Unit-Tests grün, keine Compile-Fehler (Java-Compiler als
   Sicherheitsnetz für übersehene Aufrufstellen genutzt — zwei Fehler tatsächlich so gefunden:
   ein falscher Klassenimport (`WallSurface` aus dem falschen Package) und eine
   Methodenreferenz `CityGmlUtils::calculateNetArea2D`, die das reine `CityGmlUtils.xxx`-Text-
   Ersetzungsskript wegen der `::`-statt-`.`-Syntax übersehen hatte).
2. Volle 3.801-Gebäude-Kachel einmal mit dem alten Code (Stand vor der Aufteilung, aus dem
   GitHub-Zielordner) und einmal mit dem neuen Code durchlaufen lassen, beide ~438 MB großen
   Ausgabedateien direkt per `diff` verglichen: von 15.228 Diff-Zeilen betraf **jede einzige**
   ausschließlich den lauf-spezifischen `timestamp=...`-Wert in der Promotion-Metadaten
   (`gen:value` der Hochstufungs-Herkunft, mit Wanduhrzeit gestempelt — ändert sich bei jedem Lauf
   unabhängig vom Code). Keine einzige Abweichung an Geometrie, Attributen oder Struktur — die
   Aufteilung ist damit als verhaltensneutral bestätigt, kein erneuter CityDoctor2/val3dity-Lauf
   nötig (siehe Plan-Begründung).
3. GitHub-Zielordner (`DD_BIM_LoD2_to_LoD3`) synchronisiert, dort ebenfalls `mvn clean package`
   grün mit allen 25 Tests.

Nebenbei sechs stale `{@link CityGmlUtils#...}`-Javadoc-Verweise in den Testklassen auf die
jeweils neue Zielklasse korrigiert (rein kosmetisch, keine Verhaltensänderung).

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
