# Guide Overlay 1.5.0

Guide Overlay 1.5.0 completes the advanced location-guidance phase begun in
1.4.0. It preserves existing guide IDs, bank IDs, step keys, progress codes,
checklist completion rules, and location/import formats.

## Destination-aware hiding

- Location suppression is attached to the resolved destination, not only the
  visible step.
- Consecutive steps at the same place stay hidden while the player remains in
  that area.
- Suppression carryover ends after the player actually leaves the configurable
  leave radius. A later return to Tithe Farm or another previously hidden place
  therefore resolves and guides normally.
- The original step remains hidden for the user's current visit, including when
  the user hides a long route while still far from its endpoint.
- A materially different destination, manual waypoint change, guide reset, or
  explicit restore re-enables guidance.

## Ordered waypoints

- One step can contain an ordered route instead of only one final point.
- Automatic advancement requires the player to remain inside the active
  waypoint radius for the configured confirmation period (two game ticks by
  default).
- Passing near a waypoint for one tick does not skip it.
- Plane mismatches do not count as arrival.
- Previous and next waypoint controls are available in attached and floating
  navigation controls and overlay menus.
- Reaching the final waypoint does not complete the checklist step by itself.
- Active waypoint positions can optionally persist across restarts.

## Custom locations

The Location tools menu can now:

- replace a step destination with the player's current tile;
- extend the current automatic/custom route with the player's current tile;
- use the centered world-map position as a surface destination or waypoint;
- rename, reorder, or remove custom waypoints;
- restore automatic resolution;
- import/export profile-scoped custom-location JSON.

Appending to an automatically resolved route copies the current route before
adding the manual point, so existing stops are not silently discarded. Imports
are capped and validate guide IDs, stable step keys, coordinate ranges, planes,
labels, and waypoint counts.

## Guidance display controls

- All guidance
- World-map marker only
- Shortest Path only
- Nearby scene/minimap guidance only
- All guidance except the compass
- Optional distance-aware mode with hysteresis
- Five-minute global snooze
- Per-destination hide/show control

## Resolution metadata

Every destination now carries:

- a stable semantic identity;
- source (manual, named place, transport, stored entity, inherited context);
- confidence level;
- arrival radius and confirmation requirements.

The HUD/sidebar can show source and confidence, and low-confidence destinations
can be hidden.

## Quest Helper coexistence

Guide Overlay detects whether Quest Helper is active through RuneLite's normal
plugin manager. When enabled in settings, Guide Overlay suppresses duplicate
scene highlights for quest-stage steps while retaining its checklist, map
marker, and Shortest Path route. No reflection or Quest Helper internal API is
used.

## Location audit

The Location tools menu can export a Markdown audit containing:

- guide, episode, bank, and stable step key;
- unresolved reason;
- extracted unknown destination or transport candidate;
- original step text;
- low-confidence resolved waypoints;
- partially resolved routes whose final stop remains unknown.

The audit refreshes against NPC locations learned or imported since the guide
was loaded.

## Location-plan caching

Plans are cached by:

- guide object and guide ID;
- resolver version;
- custom-location revision;
- NPC-location database revision.

Guide text is not reparsed every game tick. Runtime work uses the active cached
plan and live scene/entity state.

## Compatibility

This release does not intentionally alter:

- wiki/bank parsing;
- bank dropdown behavior;
- guide, bank, or step identifiers;
- saved or exported progress;
- Notes-to-bank and split-key migrations;
- auto-completion conditions;
- semantic cyan/red/amber text colors;
- bank checklist and bank-tag behavior;
- Shortest Path message format;
- snapshot and backup formats.

## Safety

The plugin remains display/tracking only. It does not generate mouse input,
keyboard input, gameplay menu actions, packets, or direct game-protocol traffic.
