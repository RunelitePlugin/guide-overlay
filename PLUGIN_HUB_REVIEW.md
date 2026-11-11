# Guide Overlay — Plugin Hub review notes

Displays an OSRS wiki guide as an in-client checklist, highlights the items and
NPCs the current step needs, and points at the destination.

72 main source files. Input, clipboard, audio and network are confined to six files. The rest is parsing, UI and data.

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
| `GuideInputHandler` | Registers `HotkeyListener`s for next/previous step; handles left-clicks on the overlay's own buttons and consumes those clicks so they do not also reach the game. Never generates input. |
| `GuideExternalActions` | Clipboard read/write for progress codes; `LinkBrowser` for guide video and screenshot links. |
| `ChimePlayer` | Playback of a generated tone via the client's `AudioPlayer`. |
| `GuideService` | HTTP GET for guide text; writes the guide snapshot to disk. |
| `LocationDbDownloader` | HTTP GET for the optional NPC location dataset. |
| `GuideImageLoader` | HTTP GET for guide screenshots. Opt-in, off by default. |

## Remaining files

| Area | Files |
|---|---|
| Core | `HcimGuidePlugin` (~7,000 lines) — events, step tracking, completion, overlay and panel coordination |
| Guide parsing | `WikitextParser`, `GuideImageLinks`, `JsonGuideParser`, `ItemListParser`, `StepPhaseParser`, `TargetExtractor`, `ConditionParser`, `DialogSequenceParser` |
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
| Wiki guide chosen by the user | `oldschool.runescape.wiki/api.php`, live page | User pastes a link in the "Add guide" dialog (that dialog is the confirmation) |
| Built-in B0aty HCIM guide | `oldschool.runescape.wiki/api.php`, live page | User confirms import |
| Built-in BRUHsailer guide data | `raw.githubusercontent.com`, commit-pinned | User confirms one-time import |
| NPC location dataset | `raw.githubusercontent.com`, commit-pinned | User confirms optional download |
| Guide screenshot (opt-in) | `i.ibb.co`, third party | "Show guide screenshots" enabled (off by default, warning dialog), then the user opens a section that has one |

Wiki guides, the built-in B0aty guide included, are deliberately NOT pinned to
a revision: an import reads the page as it stands at that moment, so the guide
follows its authors' edits. The wiki is community-edited, so what is imported
is whatever the page says when the user confirms. The text is then stored
locally and never refreshed on its own; a re-import is always a user action,
and the previous import is kept as a backup that can be restored. The two
GitHub resources are pinned to a full commit SHA and always return the same
content.

- GET only. Neither network file contains `.post()`, `.put()`, `.patch()`,
  `.delete()` or `.method()`, so no request can carry a body.
- HTTPS only. Redirects are not followed (a 3xx is reported as a failed
  download). The final response host is still re-checked, together with every
  earlier hop of the response.
- Caps: 16 MiB per guide; 64 MiB and 50,000 entries for the dataset. A hard
  per-call wall-clock timeout (60 s guide/wiki, 5 min dataset) bounds a server
  that trickles bytes.
- No authorization header, cookies, polling, telemetry or analytics.
- No request at startup — startup reads bundled local data.
- Calls are cancelled on shutdown. A response arriving after the plugin was
  disabled is discarded, not applied.

`GuideService.java`, `LocationDbDownloader.java`. The BRUHsailer URL literal is
declared in the built-in table in `GuideRegistry.java`; `GuideService.fetchUrl`
re-checks its scheme and host before requesting it.

### Guide screenshots

The B0aty guide shows each bank's inventory setup with
`{{Extimage|https://i.ibb.co/<id>/<file>.png|...}}`. The parser keeps those
URLs (at most 4 per section) as display metadata; step text and progress keys
are unaffected.

- By default nothing is fetched. A section shows a "Guide screenshot" link that
  opens the image in the user's browser through `GuideExternalActions`, exactly
  like a video link.
- With "Show guide screenshots" enabled (`loadGuideScreenshots`, default
  `false`, RuneLite `warning` dialog naming the third-party host and the IP
  disclosure), `GuideImageLoader` fetches the image when a section holding one
  is opened. Never at startup, never for a closed section.
- This is the only fetch whose URL comes from guide text, which is publicly
  editable. `GuideImageLinks.accepted` therefore passes one shape only:
  `https://i.ibb.co/<alphanumeric id>/<name>.(png|jpg|jpeg)` — no other scheme
  or host, no userinfo, port, query, fragment, percent-escape, `..` or extra
  path segment. The same check runs again in the loader and before a browser
  launch. A rejected URL is neither shown nor requested.
- GET only, no body, no auth or cookies, redirects not followed, response host
  re-checked, 20 s wall-clock timeout.
- The response is untrusted: 4 MiB cap while reading; must start with a PNG or
  JPEG signature; declared dimensions (4,096 per side, 8 megapixels) are
  checked from the header before any pixel is decoded; decoder failures are
  caught. Only a scaled thumbnail (panel width, at most 480 px tall) is kept.
- Cancelled and cleared on shutdown and when the setting is switched off. The
  plugin writes no image to disk (RuneLite's shared HTTP cache may keep the
  response, as for any request made through the client's `OkHttpClient`).
- Which image a section shows is whatever the guide page said when the user
  imported it - the same trust the guide's text already has. It changes only
  on a user-triggered re-import.

`GuideImageLinks.java`, `GuideImageLoader.java`, `GuideScreenshotTest`.

## File writes

Guide snapshot and one backup under the RuneLite data directory. Writes go to a
temp file and are moved into place. Importing text identical to the stored
snapshot changes nothing, so the backup always holds the previous *different*
import. The only deletions are `Files.deleteIfExists` on the plugin's own temp
path after a failed write, and on its own temp files (`<id>.txt…tmp`,
`<id>.bak…tmp`, `npc-locations.json…tmp`) that are more than ten minutes old -
leftovers of a client that died mid-save. Removing a guide from the list
deletes nothing: its snapshot and progress stay stored.

The side panel can also export progress, custom locations, and developer audit
reports, but only after the user opens a Save dialog and chooses the exact
destination. Those writes run on RuneLite's injected executor and never occur
at startup or on a timer.

`GuideService.java`, `NpcLocationStore.java`, `HcimGuidePanel.java`.

## Stored data

Completed and skipped step keys (`completedSteps.<id>`, `skippedSteps.<id>`),
active phase per step (`executionPhases.<id>`), custom map pins with one backup
copy (`customLocationsV1`, `customLocationsV1Backup`), the saved position
within multi-stop routes (`waypointIndexesV1`), the list of user-added guides
(`guides`) and of removed guides whose data is still stored (`guidesRemoved`),
a teleport-stock note (`teleportBankStock`: item names and counts the player
banked, for the trip-ready check), and three UI flags: `selectedGuide`,
`importPrompted`, `fullDbPrompted`. All through `ConfigManager`. Progress and
the teleport-stock note are RS-profile scoped. No credentials or chat content.

Every import path is bounded before it allocates per entry. How stored JSON is
read:

- Streaming `JsonReader`: completed / skipped / phase state, saved waypoint
  positions, the NPC location store and the downloaded dataset.
- Size-capped first, then bound to a fixed plugin-owned class: the guide
  registry (`Entry[]`) and the custom-pin blob (`Root`).
- Size-capped first, then walked as a JSON tree (`JsonParser` / `JsonObject`):
  the teleport-stock note, a pasted custom-pin import, guide JSON and the wiki
  API response.

Nothing is bound to `Map`, `List`, `Object` or a `TypeToken`. Limits:

| Data | Limits |
|---|---|
| Completed / skipped / phase state | 8 MiB text, 50,000 entries, 512-char keys |
| Guide registry | 64 KiB, 64 user guides, 64 removed-guide records, validated IDs, titles and wiki pages |
| Custom pins | 128 KiB per write (pins, their backup copy and saved waypoint positions alike), 64 guides, 20,000 steps, 16 waypoints per step; imported labels 120 chars, no angle brackets |
| Progress clipboard code | 8 MiB encoded, 4 MiB decoded, 50,000 key lines (counted before any is stored), 512-char lines |
| Guide model | 25,000 steps, 500 episodes, 500 sections per episode |
| NPC location store | 32 MiB, 50,000 entries per load, coordinates validated. Observed and imported positions are written first, so an oversized store loses bulk data, not what the player saw |

Guide IDs are constrained to [a-z0-9-]{1,64}; they are used as filenames and config keys, so this prevents path traversal. Only the hardcoded built-in guides carry a remote URL — a user-added guide supplies a wiki page name, not a URL, so the set of reachable hosts is fixed at compile time.

An id that still has stored data behind it is only ever given back to the same
source. Removing a guide records its id with its wiki page (or its name, for a
file-only guide); a guide added later reuses that id only when it is that same
page or name, and otherwise gets a fresh id - as it does when a snapshot file
exists under an id that no list accounts for. A new guide therefore never opens
on another page's stored text or inherits its ticks, and nothing is deleted to
achieve that.

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

Two configurable hotkeys (unbound by default) and left-clicks on the overlay's
own buttons. `GuideInputHandler` is the only file registering a listener, and
the only file that consumes an input event:

- the press, drag, release and click of a left-button gesture that began on
  one of the plugin's own on-screen buttons, so that click does not also reach
  the game underneath;
- the key press of a bound hotkey, which RuneLite's `HotkeyListener` consumes
  for every plugin that uses it.

Nothing else is consumed, and nothing is ever generated: no `java.awt.Robot`,
no constructed `KeyEvent` or `MouseEvent`, no menu invocation, and no change to
any game menu entry. The HUD and step-navigation overlays add their own
`RUNELITE_OVERLAY` entries through `Overlay.addMenuEntry` (the overlay's
right-click menu: next/previous step, location-guide and pin actions), and the
plugin subscribes to `MenuOptionClicked` read-only, to note which NPC or object
the player just clicked.

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
| `GuideImageLoader` | 12 thumbnails, access-ordered LRU; 256 failed URLs | setting switched off, shutdown |

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

Verified by the audit scripts (`tools/audit.sh`, `tools/audit-capabilities.sh`,
`tools/reviewer_checks.py`): generated keyboard or mouse input; menu invocation
or creation/replacement of game menu entries; packet or protocol code;
`java.awt.Robot`; `Runtime.exec` and `ProcessBuilder`; JNI and native library
loading; `Class.forName`, `setAccessible`, `MethodHandles`, `URLClassLoader` and
other dynamic class loading; Gson `TypeToken` and `java.lang.reflect` imports;
raw sockets; request bodies and non-GET verbs; followed redirects; fetchable
URLs outside the two allowed hosts or not commit-pinned on GitHub; a guide
screenshot fetch that is not opt-in, not gated at its one call site, or not
limited to validated `https://i.ibb.co/` image URLs.

Absent by review rather than by a script: outgoing chat insertion or
modification (the plugin only adds local game messages to the player's own chat
box); credential storage; upload of player or account data.

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
