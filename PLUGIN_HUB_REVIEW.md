# Guide Overlay — Plugin Hub review notes

Displays an OSRS wiki guide as an in-client checklist, highlights the items and
NPCs the current step needs, and points at the destination.

70 main source files. Input, clipboard, audio and network are confined to five files. The rest is parsing, UI and data.

Four scripts verify the claims below:

```bash
bash tools/audit-capabilities.sh    # each capability has exactly one owner
bash tools/audit.sh                 # prohibited mechanisms absent
python3 tools/check_transport_data.py
python3 tools/reviewer_checks.py    # asserts the claims in this document
```

`bash tools/pre-submit.sh` runs all of the above plus the test suite and build.

`audit-capabilities.sh` exits non-zero if a capability appears in a file that
does not own it.

---

## Capability owners

| File | Capability |
|---|---|
| `GuideInputHandler` | Registers `HotkeyListener`s for next/previous step; reads mouse position for the overlay's own buttons. Observes only. |
| `GuideExternalActions` | Clipboard read/write for progress codes; `LinkBrowser` for guide video links. |
| `ChimePlayer` | Playback of a generated tone via the client's `AudioPlayer`. |
| `GuideService` | HTTP GET for guide text; writes the guide snapshot to disk. |
| `LocationDbDownloader` | HTTP GET for the optional NPC location dataset. |

## Remaining files

| Area | Files |
|---|---|
| Core | `HcimGuidePlugin` (~7,000 lines) — events, step tracking, completion, overlay and panel coordination |
| Guide parsing | `WikitextParser`, `JsonGuideParser`, `ItemListParser`, `StepPhaseParser`, `TargetExtractor`, `ConditionParser`, `DialogSequenceParser` |
| Item resolution | `ItemReq`, `ItemAliases`, `ItemCategory`, `ItemIconResolver`, `StepItemOverrides`, `ConceptItems`, `InventorySnapshot` |
| Location | `PlaceDirectory`, `TransportResolver`, `StepLocationPlanner`, `StepLocationHint`, `NpcLocationStore`, `CustomLocationStore` |
| Overlays | `HudOverlay`, `TargetOverlay`, `DirectionArrowOverlay`, `StepNavOverlay`, `DialogOptionOverlay` — read state, do not mutate it |
| Panel | `HcimGuidePanel`, `ItemGridPanel`, `PanelLayout`, `PanelFonts`, `OverlayFonts` — Swing only |
| Storage | `ProgressCodec`, `ProgressKeyMigration`, `GuideRegistry`, `BankStockTracker` — via `ConfigManager` |
| Integration | `BankTagIntegration` (Bank Tags public API), `PathfinderIntegration` (`PluginMessage` carrying a destination) |

---

## Network

| Resource | Host | Trigger |
|---|---|---|
| Wiki guide chosen by the user | `oldschool.runescape.wiki/api.php` | User pastes a link and confirms |
| Built-in guide data | `raw.githubusercontent.com`, commit-pinned | User confirms one-time import |
| NPC location dataset | `raw.githubusercontent.com`, commit-pinned | User confirms optional download |

- GET only. Neither network file contains `.post()`, `.put()`, `.patch()` or
  `.method()`, so no request can carry a body.
- HTTPS only. Final response host re-checked after redirects.
- Caps: 16 MiB per guide; 64 MiB and 50,000 entries for the dataset. A hard
  per-call wall-clock timeout (60 s guide/wiki, 5 min dataset) bounds a server
  that trickles bytes.
- No authorization header, cookies, polling, telemetry or analytics.
- No request at startup — startup reads bundled local data.
- Calls are cancelled on shutdown. A response arriving after the plugin was
  disabled is discarded, not applied.

`GuideService.java`, `LocationDbDownloader.java`.

## File writes

Guide snapshot and one backup under the RuneLite data directory. Writes go to a
temp file and are moved into place. The only deletion is `Files.deleteIfExists`
on the plugin's own temp path after a failed write.

The side panel can also export progress, custom locations, and developer audit
reports, but only after the user opens a Save dialog and chooses the exact
destination. Those writes run on RuneLite's injected executor and never occur
at startup or on a timer.

`GuideService.java`, `NpcLocationStore.java`, `HcimGuidePanel.java`.

## Stored data

Completed and skipped step keys, active phase per step, custom map pins, a
per-guide teleport-stock note (`teleportBankStock`: item names and counts the
player banked, for the trip-ready check), and three UI flags: `selectedGuide`,
`importPrompted`, `fullDbPrompted`. All through `ConfigManager`. Progress is
RS-profile scoped. No credentials or chat content.

Every import path is bounded before allocation, and config JSON is parsed with a
streaming `JsonReader` rather than a generic collection type:

| Data | Limits |
|---|---|
| Completed / skipped / phase state | 8 MiB text, 50,000 entries, 512-char keys |
| Guide registry | 64 KiB, 64 user guides, validated IDs, titles and wiki pages |
| Custom pins | 128 KiB per write, 64 guides, 20,000 steps, 16 waypoints per step |
| Progress clipboard code | 8 MiB encoded, 4 MiB decoded, 50,000 keys |
| Guide model | 25,000 steps, 500 episodes, 500 sections per episode |
| NPC location store | 32 MiB, 50,000 entries, coordinates validated |

Guide IDs are constrained to [a-z0-9-]{1,64}; they are used as filenames and config keys, so this prevents path traversal. Only the hardcoded built-in guides carry a remote URL — a user-added guide supplies a wiki page name, not a URL, so the set of reachable hosts is fixed at compile time.

`ProgressCodec.java`, `GuideRegistry.java`, `CustomLocationStore.java`.

## Audio

One tone on step or section completion, played through the client's own
`net.runelite.client.audio.AudioPlayer` — `ChimePlayer` is the only file
touching an audio API, and the plugin does not import `javax.sound` at all.
The tone is generated from raw samples and wrapped in an in-memory WAV header —
no audio file is bundled or read. Single-flight, runs on the injected executor,
no plugin-created thread, silent on failure. `AudioPlayer` buffers the clip
into a self-closing line and returns at playback start, so the executor is
never parked for the clip's duration.

## Input

Two configurable hotkeys and mouse position for the overlay's own buttons.
`GuideInputHandler` is the only file registering a listener. No `java.awt.Robot`,
no constructed `KeyEvent` or `MouseEvent`, no menu invocation or menu-entry
mutation anywhere in the plugin.

## Target arrow

`TargetOverlay` draws one polygon as an `ABOVE_SCENE` overlay. It does not call
`Client.setHintArrow` unless the native-arrow option is enabled; that option
defaults to off. The `Graphics2D` context is copied and disposed.

`DirectionArrowOverlay` reads the immutable `WorldPoint` snapshot from the last
successful Shortest Path hand-off. It does not run pathfinding, post messages,
or mutate plugin state while rendering. The snapshot is cleared by the same
`PathfinderIntegration.clear()` lifecycle path that removes the drawn path.

## Player state

Every `getLocalPlayer()` call reads only the world location (after a null
check). Used for arrival detection and arrow aiming. The player name is never read. No `getUsername`,
`getEmail`, `InetAddress`, `getHostName` or MAC address. `getAccountHash()`
appears once, compared against `-1L` to detect login completion; the value is
never stored, logged or transmitted.

## Automatic completion

A step completes when the game confirms it — quest state, skill level, items
held — or when the player reaches a travel step's destination.

Arrival completes a step only when the step is travel and nothing else. A step
that also requires talking, buying, killing or quest progress is never completed
by arriving. A checkbox untick wins and suppresses further automatic completion of
that step.

`ArrivalCompletionPolicy.java`. Covered by `ArrivalCompletionPolicyTest`,
`StepPhaseParserTest`, `StepPhaseCorpusTest`.

## Caches

| Cache | Bound | Cleared |
|---|---|---|
| `OverlayFonts` | 128 entries, access-ordered LRU; also serves `PanelFonts` | font setting change, shutdown |
| `ItemIconResolver` | keyed by the loaded guide's item names | `reset()` on shutdown |

Font caches hold `java.awt.Font` for families already registered with the JVM.
No font file is bundled, read, parsed or downloaded. A user-typed font name is
trimmed to 80 characters and is never logged.

## CPU

| Work | Limit |
|---|---|
| Auto-completion and route evaluation | every 4 game ticks, first incomplete bank only |
| Shortest Path hand-off | target-change dedup, 2-tile live-NPC deadband, 15-second recovery keepalive |
| Item definition scan | 500 definitions per client-thread chunk, one owner, generation-cancelled |
| Tracked item names | 100,000 hard cap per loaded guide |
| Scene highlighting | cached, dirty-flag driven |
| Object name cache | 2,048-entry LRU, cleared on shutdown |
| NPC lookup cache | 16-entry LRU |
| Panel search | 200 ms debounce, 400-row expansion budget, timer disposed on shutdown |

## Logging

INFO and WARN contain counts, booleans, setting values and fixed internal
labels only — no account hash, RuneScape profile key, player name, guide or
step text, inventory, equipment, location or progress contents. Unresolved
item names are DEBUG only.

---

## Absent

Verified by `tools/audit.sh`: generated keyboard or mouse input; menu invocation
or menu-entry mutation; packet or protocol code; `java.awt.Robot`;
`Runtime.exec` and `ProcessBuilder`; JNI and native library loading;
`Class.forName`, `setAccessible`, `MethodHandles`, `URLClassLoader` and other
dynamic class loading; Gson `TypeToken` and `java.lang.reflect` imports; raw
sockets; outgoing chat insertion or modification;
credential storage; upload of player or account data.

## Shutdown

`shutDown()` removes five overlays, unregisters input listeners and hotkeys,
removes the toolbar button, disposes the panel and its debounce timer, cancels
outstanding HTTP calls, clears Bank Tags managed data, clears the hint arrow and
Shortest Path handoff, clears scene tracking, and resets the item resolver and
the shared font cache (cleared through both the overlay and panel entry
points).

Async work carries a generation token taken at submission. Work from an earlier
activation is discarded rather than applied, so disabling and re-enabling during
a download or item scan cannot write into the new session.

## Build

Release `2.0`, Java 11, Java only, `build=standard`,
`runeLiteVersion = 'latest.release'`, BSD 2-Clause, 48×48 icon, no runtime
dependency outside RuneLite.
