# Guide Overlay 1.4.0 — Consolidated Location, Transport, and Semantic Guidance Update

## Release summary

Guide Overlay 1.4.0 consolidates the work previously developed across the
1.3.1 location-pin patch, the 1.3.2 transport/location expansion, and the
1.3.3 semantic-color patch into one release.

The release version in `build.gradle` is now:

```gradle
version = '1.4.0'
```

This is a feature consolidation release. It does not change persisted step
keys, guide bank IDs, progress-code formats, or the location database formats
introduced by the prior patches.

---

## What is implemented in 1.4.0

### 1. Named-place location guidance

Guide steps can now resolve a named place even when the instruction does not
name a specific NPC or object.

Examples include:

- Tithe Farm
- towns and common banks
- guilds
- minigames
- quest entrances
- Kourend locations
- Varlamore locations
- raids and activity lobbies

The named-place resource is:

```text
src/main/resources/com/hcimguide/place-locations-seed.json
```

The current dataset validates:

- **114 named locations**
- valid RuneLite world coordinates and planes
- unique normalized aliases

The location points are intended to be useful arrival areas, entrances,
landings, or lobbies. They are not presented as exact interior boss tiles when
an activity is instanced or changes with game state.

### 2. Safe same-area location inheritance

Ordinary follow-up instructions can inherit the last reliable destination
inside the same guide bank.

This supports sequences such as:

1. Travel to Tithe Farm.
2. Buy seeds.
3. Fill watering cans.
4. Begin another round.

The planner deliberately stops inheritance when:

- the guide moves into another bank;
- a later movement instruction names an unresolved destination;
- an unresolved NPC or item target appears;
- the new instruction would otherwise retain a stale or backward-pointing
  location.

The implementation prefers no pin over a confidently incorrect pin.

### 3. Per-step location-guide toggle

A crosshair control can hide or restore the location guidance associated with
the current step.

Hiding a step location suppresses:

- the native hint arrow;
- the distant compass arrow;
- the world-map marker;
- the Shortest Path destination;
- route recommendation text;
- the location-based target outline.

The toggle is available with attached controls, floating controls, and from the
overlay context menu.

#### Current limitation

Suppression is currently keyed to the step. It resets when the step changes.
The proposed destination-aware carryover behavior—keeping a location hidden
when the next step resolves to the same place—is documented in the included
future implementation brief but is **not yet implemented in 1.4.0**.

### 4. Separate transport resolver

Transport shorthand is handled separately from ordinary named-place matching.
This prevents item names and arbitrary three-letter words from becoming false
location matches.

Production files:

```text
src/main/java/com/hcimguide/TransportResolver.java
src/main/resources/com/hcimguide/transport-destinations.json
```

The current transport dataset validates:

- **83 transport destinations**
- **49 fixed-coordinate fairy-ring codes**

Supported categories include:

- contextual fairy-ring codes;
- ring of dueling destinations;
- games necklace destinations;
- amulet of glory destinations;
- combat bracelet destinations;
- necklace of passage destinations;
- Digsite pendant destinations;
- Camulet destinations;
- Ectophial and Chronicle travel;
- teleport crystals;
- minigame teleports;
- minecarts;
- boats and ships;
- charter routes;
- Quetzals;
- portals;
- gliders;
- canoes;
- special access items such as giantsoul and chasm travel.

The resolver is contextual.

These instructions can resolve as transport:

```text
Fairy ring -> BKR
Use a Digsite pendant to the Digsite
Take the Quetzal to Tal Teklan
Boat to Port Khazard
Minigame teleport to Tempoross
```

These remain normal preparation or inventory text:

```text
Withdraw a Digsite pendant
Bring a games necklace
Bank the teleport crystal
```

When a step contains an intermediate teleport followed by a final named
location, the planner prefers the final endpoint. This prevents the route from
pointing backward to the teleport arrival after the player has continued
onward.

### 5. Expanded destination coverage

The added place and transport data broaden guidance for locations such as:

- Mage Training Arena
- Soul Wars
- Tempoross Cove
- Last Man Standing
- Blast Furnace
- Ranging Guild
- Port Khazard
- Temple of Ikov
- Varrock Sewers
- Dwarven Mine
- Smoke Dungeon
- Draynor Manor
- Nature Grotto
- Haunted Mine
- Rellekka
- Jatizso
- Lletya
- Port Tyras
- Trollheim
- Mos Le'Harmless
- Harmony Island
- Arceuus Library
- Fortis Colosseum
- Theatre of Blood
- Tombs of Amascut
- Chambers of Xeric
- Moons of Peril
- Royal Titans access
- Yama access

Instanced or state-dependent activities use a stable entrance, lobby, transport
landing, or access area rather than an invented interior coordinate.

### 6. Semantic step colors

Guide text now uses three configurable semantic accent colors in both the side
panel and the on-screen HUD.

| Meaning | Default | Hex |
| --- | --- | --- |
| Teleport or transport action | Cyan | `#50DCFF` |
| Explicit danger or item-loss warning | Coral red | `#FF6B6B` |
| Preparation, equipment, charges, or prerequisite | Amber | `#FFC857` |

Lavender quest-boundary coloring is intentionally not included.

#### Color priority

The display precedence is:

1. skipped-step styling;
2. completed/dimmed styling;
3. danger red;
4. preparation amber;
5. transport cyan;
6. normal step text.

Examples:

```text
Teleport to the Wilderness and do not die
```

Displays red because danger takes priority over transport.

```text
Withdraw a games necklace, food, and stamina potion
```

Displays amber because it is preparation, not an active teleport.

```text
Use the games necklace to teleport to Wintertodt
```

Displays cyan because it is an active route change.

```text
Buy death runes
```

Displays amber, not red. The classifier does not treat the word `death` alone
as a danger warning.

The dependency-light classifier is:

```text
src/main/java/com/hcimguide/StepTextSemantic.java
```

Each category has an independent toggle and color picker in the side-panel
configuration.

### 7. README integration documentation

The README now explains that Guide Overlay remains usable by itself and lists
optional companion plugins.

| Plugin | Required | Benefit when enabled | Behavior when absent |
| --- | ---: | --- | --- |
| Shortest Path | No | Draws a walking route to the active destination | Native arrows, compass, and world-map marker remain available |
| Quest Helper | No | Supplies detailed quest-stage guidance and quest-specific markers | Guide Overlay continues with its own checklist and locations |

Shortest Path is the only direct third-party message integration currently
implemented. Quest Helper is documented as a complementary plugin; Guide
Overlay does not read its internals.

The README also distinguishes guide-recommended activity plugins from true
Guide Overlay dependencies.

---

## Resolution architecture

The location responsibilities are intentionally separated.

| Component | Responsibility |
| --- | --- |
| `PlaceDirectory` | Matches ordinary named destinations |
| `TransportResolver` | Parses contextual transport shorthand and arrival points |
| `StepLocationPlanner` | Chooses the safest endpoint and controls inheritance |
| `StepLocationHint` | Carries the selected single-step destination |
| `TeleportDirectory` | Recommends transports the player appears to possess |
| `PathfinderIntegration` | Sends the selected point to Shortest Path |
| Native overlays | Show hint arrows, compass, map markers, and target highlighting |

This separation prevents the general place matcher from assuming every
teleport item, acronym, or generic transport phrase is itself a destination.

---

## Main production files added

```text
src/main/java/com/hcimguide/PlaceDirectory.java
src/main/java/com/hcimguide/StepLocationHint.java
src/main/java/com/hcimguide/StepLocationPlanner.java
src/main/java/com/hcimguide/TransportResolver.java
src/main/java/com/hcimguide/StepTextSemantic.java
src/main/resources/com/hcimguide/place-locations-seed.json
src/main/resources/com/hcimguide/transport-destinations.json
tools/check_transport_data.py
```

## Main production files modified

```text
README.md
build.gradle
src/main/java/com/hcimguide/HcimGuideConfig.java
src/main/java/com/hcimguide/HcimGuidePanel.java
src/main/java/com/hcimguide/HcimGuidePlugin.java
src/main/java/com/hcimguide/HudOverlay.java
src/main/java/com/hcimguide/StepNavOverlay.java
```

## Tests added

```text
src/test/java/com/hcimguide/PlaceDirectoryTest.java
src/test/java/com/hcimguide/StepLocationPlannerTest.java
src/test/java/com/hcimguide/StepNavOverlayTest.java
src/test/java/com/hcimguide/TransportResolverTest.java
src/test/java/com/hcimguide/StepTextSemanticTest.java
```

---

## Compatibility guarantees

The 1.4.0 changes are designed not to alter:

- wiki guide parsing;
- bank parsing and bank dropdown behavior;
- stable step keys;
- saved progress;
- imported/exported progress codes;
- Notes-to-bank progress migration;
- guide IDs;
- checklist completion rules;
- manual untick behavior;
- NPC/object/ground-item highlighting;
- bank checklist behavior;
- item requirement parsing;
- bank-tag behavior;
- route recommendation behavior;
- Shortest Path message format;
- snapshot and backup formats.

The semantic-color update is presentation/configuration only. The named-place
and transport data are additional resolution layers and do not change the guide
text or persisted progress identity.

---

## Validation performed for the consolidated release

### Passed

- static RuneLite safety audit;
- no generated mouse or keyboard input;
- no generated in-game actions;
- no subprocess execution;
- no JNI/native loading;
- no dangerous reflection;
- no direct socket or game-protocol access;
- no credential literals;
- runtime raw GitHub data URLs are commit-pinned;
- transport-data validation;
- **114 named locations validated**;
- **83 transport destinations validated**;
- **49 fixed fairy-ring codes validated**;
- shell syntax checks;
- source ZIP integrity checks;
- cumulative patch generation;
- semantic-classification focused checks performed during the previous patch;
- focused location-planner and transport-resolver checks performed during the
  previous patches.

### Full Gradle status

The full command was attempted:

```bash
./gradlew clean test --no-daemon
```

It could not start because the execution environment could not resolve:

```text
services.gradle.org
```

The failure occurred while downloading Gradle 8.14.3 and is not a Java compile
or unit-test failure. Run these commands locally before release:

```bash
./gradlew clean test
./gradlew build
```

---

## Current limitations

### Single destination per step

`StepLocationHint` currently represents one active location. A long instruction
containing several stops selects the safest final objective rather than showing
an ordered route.

### Per-step suppression

The location toggle currently resets when the step changes. It does not yet
carry suppression forward when the new step resolves to the same destination.

### No in-game custom-pin editor yet

Locations can be extended through JSON and code, but users cannot yet stand on
a tile or click the world map to save a custom location for a step.

### No automatic waypoint advancement yet

Previous/next waypoint controls and arrival-based waypoint advancement are
planned but are not included in the 1.4.0 source.

### Not every natural-language step has one correct tile

Some instructions describe:

- an entire quest stage;
- a multi-stop route;
- an instanced activity;
- a generic nearest bank;
- relative movement such as “go upstairs”;
- a state-dependent entrance.

The resolver continues to fail closed when it cannot establish a reliable
point.

---

## Included future implementation brief

The release package includes:

```text
GuideOverlay_location_guidance_master_brief.md
```

That document specifies the recommended next phase:

- custom in-game pin editing;
- world-map location selection;
- multi-waypoint steps;
- automatic waypoint advancement;
- previous/next waypoint controls;
- destination identity;
- destination-aware hide carryover;
- granular display modes;
- confidence/source indicators;
- cached location plans;
- optional Quest Helper coexistence;
- unresolved-location audit export.

These are roadmap items, not claims about the current 1.4.0 implementation.

---

## Recommended manual verification

Before publishing, verify in RuneLite:

1. Load the B0aty guide and confirm the bank dropdown still uses banks rather
   than episode-sized Notes sections.
2. Navigate to a Tithe Farm step and confirm named-place guidance appears.
3. Confirm nearby ordinary follow-up steps can inherit Tithe Farm inside the
   same bank.
4. Confirm a new bank does not inherit the prior bank's location.
5. Confirm an unresolved later travel destination clears the earlier location
   rather than pointing backward.
6. Confirm live NPC targets remain more precise than broad place pins.
7. Confirm `Fairy ring -> BKR` resolves while a bare unrelated three-letter word
   does not.
8. Confirm `Withdraw a Digsite pendant` is amber, not cyan.
9. Confirm `Use Digsite pendant to the Digsite` is cyan.
10. Confirm a Wilderness teleport warning is red.
11. Confirm completed and skipped styling overrides semantic colors.
12. Confirm all three semantic color toggles and color pickers refresh the
    sidebar and HUD.
13. Confirm the crosshair hides the arrow, compass, map marker, route text,
    outline, and Shortest Path target.
14. Test Shortest Path installed/enabled, installed/disabled, and absent.
15. Confirm plugin startup, guide switching, progress restore, snapshot restore,
    and shutdown remain normal.

---

## Package contents

The complete 1.4.0 release package contains:

```text
GuideOverlay-1.4.0-source.zip
GuideOverlay_1.4.0_FINAL.md
GuideOverlay-1.4.0-cumulative.patch
GuideOverlay_location_guidance_master_brief.md
validation/GuideOverlay-1.4.0-CHECK_RESULTS.txt
validation/GuideOverlay-1.4.0-audit.log
validation/GuideOverlay-1.4.0-data-check.log
validation/GuideOverlay-1.4.0-gradle.log
patch-history/guide-overlay_location-pins.patch
patch-history/guide-overlay-1.3.2-transport-resolver.patch
patch-history/guide-overlay-1.3.3-semantic-colors.patch
implementation-history/guide-overlay_location-pins_README.md
implementation-history/GuideOverlay_1.3.2_transport_locations.md
implementation-history/GuideOverlay_1.3.3_semantic_colors.md
SHA256SUMS.txt
```

The cumulative patch is generated from the most recent user-provided 1.3.0
source archive to the consolidated 1.4.0 source.

---

## Release conclusion

Version 1.4.0 is the correct label for this combined feature set because the
change is broader than a color-only 1.3.3 patch. It introduces a new location
resolution layer, a separate transport-data architecture, substantially
expanded destination coverage, a new semantic-classification system, new
configuration, new resources, new tests, and updated integration
documentation.
