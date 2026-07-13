# Guide Overlay (RuneLite plugin)

Puts wiki guides — the [B0aty HCIM Guide V3](https://oldschool.runescape.wiki/w/Guide:B0aty_HCIM_Guide_V3)
built in, any other OSRS-wiki guide via *Add guide from wiki link* — in a RuneLite side
panel so you never have to alt-tab: organized by **chapter → bank/section**, with
clickable checkboxes that persist between sessions, plus Quest-Helper-style
**NPC target tracking** (hint arrow, outline highlight, cross-map direction arrow
and world map marker).

## Features

- **One-time import, locally stored** — the guide is imported ONCE (⋮ menu →
  *Import guide from wiki*, or *Import guide from file* for future guides) and stored
  in `~/.runelite/hcim-guide/`. The plugin never contacts the network on its own, so
  wiki edits or vandalism can never change your guide or reset your progress behind
  your back. Each import backs up the previous snapshot (*Restore previous import*
  undoes a bad one). New guide versions ship rarely (~yearly), so updating is a
  deliberate two-click action, not a background process.
- **Checkboxes per step**, progress bars per bank / episode / whole guide. Progress is
  saved in your RuneLite config.
- **Right-click a bank header** for bulk actions: *Mark bank complete*, *Clear bank*,
  and *Mark everything before this bank complete* (great if you're already mid-guide).
- **Jump to next unchecked step** button.
- **Search** filters steps in the selected episode.
- **Multiple guides, grab-and-go**: a dropdown lists your guides. The B0aty guide is
  built in; paste any `oldschool.runescape.wiki` link via ⋮ → *Add guide from wiki
  link* to add more. Pages that don't use the Episode/Bank format fall back to a
  generic chapter/section parser, so most bullet-list guides just work. Each guide
  keeps its own isolated progress. On first run you get a one-click prompt to import
  the built-in guide (no download ever happens without that click).
- **Target tracking**: steps like "Talk to Father Urhney …" get a ⌖ button. Pin it and
  when that NPC is in the loaded area you get the in-game hint arrow, a colored outline
  and the name drawn above them.
- **Cross-map direction arrow**: when the pinned NPC is far away, a compass overlay
  points toward their last known location with the tile distance, and the spot is
  marked on the world map. Locations come from a small bundled seed plus **learning**:
  whenever a guide NPC is actually seen in-game its exact position is recorded
  (bounded to the guide's own target names, stored locally), so the database
  self-corrects as you play — no guessed coordinates ever override observed ones.
- **Auto-completion** (⚡ badge): steps the plugin can verify against game state check
  themselves off — quest started/finished (via the quest log), skill targets like
  "Train … to 5 Agility", and item steps ("Withdraw: …", "Collect 3x Logs") once the
  items are in your inventory. Only the first bank that still has unchecked steps is
  evaluated, so future duplicate steps never fire early. Manual control always wins:
  untick an auto-completed step and it stays unticked. When a step auto-completes, the
  next trackable step is pinned automatically (both behaviors have config toggles).
- **Item icon grids**: Withdraw/Collect steps show an inventory-style grid of real item
  sprites (resolved via RuneLite's item database), green-bordered when the item is in
  your inventory, red when missing — so you can see at a glance if your loadout matches
  the guide before leaving the bank.
- **Active-bank highlighting**: every NPC referenced by an unchecked step in your
  current bank gets a blue outline with their name, and ground items those steps need
  (the kitchen Knife, Leather Boots on the table, ...) get a green tile highlight —
  no pinning required. The pinned target still gets the stronger outline + hint arrow.
- **On-screen step overlay**: a small movable HUD panel shows your active bank and its
  next unchecked steps (count configurable), so the sidebar doesn't need to stay open.
- **Fully customizable UI**: config sections cover overlay font (or follow the client
  default), HUD width and step count, compass size/opacity/distance-text, panel step
  text size, all highlight colors, and per-feature toggles. Every overlay (HUD,
  compass, highlights) is movable anywhere on screen with **Alt+drag** — the standard
  RuneLite mechanism.
- **Full location database, one click**: ⋮ menu → *Download full location database*
  fetches the full-game NPC spawn dataset (one time, user-confirmed, a few MB) from
  [mejrs/data_osrs](https://github.com/mejrs/data_osrs) — the community dataset behind
  the OSRS wiki's interactive map — so the compass can point at ANY named NPC
  immediately. You're offered this once right after your first guide import. Observed
  positions always take precedence, so live play keeps self-correcting. Alternatives
  for offline setups: bundle `npc-locations-full.json` into resources before building
  (generate it with `tools/convert_spawns.py`), or paste a converted dataset via
  *Import NPC locations from clipboard*. The bundled seed (~111 hand-verified entries
  including permanent item spawns like the Lumbridge kitchen Knife) covers the early
  guide even before any download.
- **Item-spawn pointing**: single-item Collect/Take steps are pinnable too — the
  compass and world map marker point at the item's known spawn, not just NPCs.
- **Per-character progress**: each RuneScape character keeps its own checklist
  (RSProfile-scoped). If the hardcore dies and you restart, the new character starts
  fresh while the old one's progress is preserved. First login seeds from any existing
  shared progress so enabling it never appears to wipe anything. Toggleable.
- **Progress backup**: ⋮ menu → *Export progress to clipboard* produces a compact text
  code (a few KB even for the full guide); *Import progress from clipboard* restores it
  — survives reinstalls and transfers between computers.

## Running it (development client)

1. Install a JDK (11 or newer) — [Adoptium Temurin](https://adoptium.net) works well —
   and [IntelliJ IDEA Community](https://www.jetbrains.com/idea/download/).
2. Open this folder in IntelliJ (`File → Open`, pick the `build.gradle`). Let Gradle sync.
3. Find `src/test/java/com/hcimguide/HcimGuidePluginTest` and run its `main` method.
   Add `-ea` to VM options if IntelliJ doesn't already.
4. A development RuneLite client starts. Log in, enable **Guide Overlay** in the
   plugin list if needed, and click the checklist icon in the sidebar.

Or from a terminal:

```
./gradlew build          # compiles + runs the parser unit tests
```

## Plugin Hub

When you're happy with it, submitting to the Plugin Hub means: push this repo to GitHub,
then PR a short manifest to [runelite/plugin-hub](https://github.com/runelite/plugin-hub)
pointing at your repo/commit. `runelite-plugin.properties`, `icon.png` and the BSD-2
license are already in place. The `author` field is a project pseudonym; set it to
any handle before submission (it does not need to be a real name). Also pin the
location-database `SOURCE_URL` to a commit SHA first (see the comment in
`LocationDbDownloader.java`). Note for review: guide text is fetched from the OSRS wiki
(CC BY-NC-SA); the plugin credits the source and fetches rather than bundles it.

## Notes

- Checkbox state is keyed to the step's text. Because the guide only changes when YOU
  import a new snapshot, reworded steps can only reset at that moment — never behind
  your back — and *Restore previous import* undoes it.
- For generic (non-Episode) guides, chapters are numbered by their order on the page,
  so re-importing after a wiki editor reorders top-level sections can reset progress
  in the moved chapters — the import confirmation dialog is your checkpoint.
- The parser is deliberately tolerant: headings that aren't "Episode N"/"Bank N" become
  their own sections, and anything that isn't a bullet list is ignored.
