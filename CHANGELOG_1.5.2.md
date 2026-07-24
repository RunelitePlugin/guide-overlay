# Guide Overlay 1.5.2

Hardening release on top of 1.5.1, produced by a full independent review of
the 1.4.0–1.5.1 location-guidance work against the verified 1.3.0 base. No
guide IDs, bank IDs, step keys, progress codes, or storage formats changed.
Step-key output was verified byte-identical across both real guides (5,263
steps), and progress codes round-trip unchanged.

## Fixes

### Custom-location import crashed on the live client

`CustomLocationStore` called `JsonParser.parseString`, a gson method that
does not exist in the older gson bundled by the RuneLite client. Importing
custom pins would have thrown `NoSuchMethodError` on every attempt. The
store now uses the instance parser API, matching the rest of the codebase.

### Custom-pin edits re-parsed the whole store once per step

Rebuilding location plans called `getPlan` for every step of the guide, and
each call deserialized the entire stored custom-location blob. With a large
imported pin set this turned every pin edit into minutes of client-thread
work (and even an empty store paid a full planner run of roughly a second).
Plans and persisted waypoint indexes are now fetched in one parse per
rebuild, pin edits republish only the affected step's plan without running
the planner at all, and the full planner runs only on the executor.

### Multi-second freezes on profile switch and audit export

The full plan rebuild ran synchronously on the client thread during profile
changes, and the unresolved-location audit (plus the custom-pin export) was
generated and written to disk on the Swing thread. Profile-change rebuilds
now run on the executor, and file exports generate their content and write
off the Swing thread with a progress status.

### Waypoint rename could hit the wrong waypoint

The rename dialog captured a waypoint index before it opened; a reorder or
automatic advance while it was open could redirect the rename to a different
waypoint. The target is re-validated by label when the rename is applied,
and the edit is refused with a clear status if the waypoints changed.

### Low-confidence handling was inert

Nothing ever produced a LOW confidence value, so the "Hide low-confidence
pins" option and the audit's low-confidence table could never act. Inherited
destinations carried further than five consecutive steps are now classified
LOW - long chains genuinely are less trustworthy - making both features do
what they say.

### Smaller corrections

- The HUD destination line used a glyph (U+2316) missing from the RuneScape
  physical fonts, rendering as a box for non-sans font choices; it now uses
  an ASCII prefix.
- The plugin's own custom-location and waypoint-index config writes no
  longer trigger its generic config-change reaction.
- Custom-pin edits are refused at the login screen instead of silently
  writing into the global (all-characters) configuration scope, and an
  oversized stored blob is reported in the log instead of silently treated
  as empty.
- Waypoint arrival tracking is reset only on the client thread.
- The active-destination summary shown in the HUD and side panel is a
  per-tick snapshot, so Swing reads never touch client-thread state.
- Reorder/remove/restore pin actions report failures in the status line
  instead of failing silently.
- The suppression system's deliberate plane asymmetry (leave checks are
  horizontal-only; cross-identity equivalence still requires the same
  plane) is documented in the code.
- A stale unit test still pinned the pre-1.5.1 plane-sensitive carryover
  behavior; it now pins the 1.5.1 fix and adds a genuine walk-away-on-
  another-plane case.

## Verification performed

- 20 of 20 offline harness suites pass, plus LocationV15Test (48 checks).
- 55 standalone JUnit tests pass (including the location classes compiled
  against the WorldPoint stub).
- Step-key dumps byte-identical to 1.3.0/1.5.1 for the b0aty wikitext guide
  (2,920 steps) and the BRUHsailer JSON guide (2,343 steps).
- Full-source syntax parse clean; safety audit passes; no new network
  endpoints (location data ships as bundled resources only).

`./gradlew clean test` and `./gradlew build` must still be run locally
before shipping - the full JUnit suite requires the RuneLite API, which is
not reachable from this environment.
