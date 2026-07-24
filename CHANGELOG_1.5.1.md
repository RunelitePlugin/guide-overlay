# Guide Overlay 1.5.1

Maintenance release on top of 1.5.0. One default changed, four defects fixed.
No guide IDs, bank IDs, step keys, progress codes, or storage formats were
altered. Verified byte-identical parse output against 1.3.0 across 5,263 real
guide steps.

## Changed default

- The on-screen HUD now defaults to the Sans Small font instead of the
  RuneScape Small font. The side panel's own text-size options are unchanged
  and still offer larger sizes. Users who prefer the previous look can select
  Small under Overlay font.

## Fixes

### Suppression carryover ignored the player's plane

`LocationSuppressionState.hide` decided whether suppression could carry to a
following step using a check that required the player and the destination to be
on the same plane. Hiding a destination while standing on top of it but on a
different storey (upstairs in a bank, down in a dungeon) therefore disabled
carryover immediately, and the arrow reappeared on the next step rather than
staying hidden for the visit.

Eligibility now uses horizontal distance only. The leave check in
`updatePlayer` uses the same horizontal test, so climbing a staircase at the
destination is no longer read as having left it, while genuinely walking away
still ends carryover as before.

### Exactly-one-callback contract was not enforced

`LocationDbDownloader.download`, `GuideService.fetch`, and
`GuideService.fetchUrl` all document that exactly one of onSuccess/onError is
invoked. Nothing enforced it.

In the downloader specifically, the store merge and save ran inside the outer
try but outside the guard around onSuccess, so a failure while merging fell
through to the generic handler and reported "Parse failed" for a call whose
parse had succeeded and whose data had already been merged.

All three methods now carry an AtomicBoolean guard, and the downloader reports
a merge failure distinctly from a parse failure.

### Location audit could produce a ~90MB clipboard payload

`LocationAudit.toMarkdown` appended one table row per unresolved step, each
including the step's full text, with no bound. On a worst-case 25,000-step
guide with poor resolution this measured 90MB and took 12.6 seconds.

Each table is now capped at a 512KB character budget, checked by builder length
so the guard is constant-time per append. The summary counters above each table
remain exact, and a truncation notice states the true total. The same worst case
now produces 515KB in 99ms.

### Identity slug patterns were recompiled per hint

`StepLocationHint.defaultIdentity` used two `String.replaceAll` calls, each
compiling a fresh `Pattern`. Both are now precompiled statics, matching the
convention documented in `Names`.

## Verification performed

- 20 of 20 offline harness suites pass.
- 48 executable checks over the 1.5.0 location classes: waypoint arrival
  debouncing, suppression carryover, hint identity and equivalence, radius and
  tick clamping. Includes a regression check for the plane defect above.
- Step-key dumps for the b0aty wikitext guide (2,920 steps) and the BRUHsailer
  JSON guide (2,343 steps) are byte-identical to 1.3.0 output, so no stored
  progress is orphaned.
- Progress codes round-trip in both directions between 1.3.0 and this release.
- Audit cap measured before and after against a synthetic 25,000-step guide.

`./gradlew clean test` and `./gradlew build` have NOT been run for this release
and must be run locally before shipping. The full JUnit suite requires the
RuneLite API, which was not reachable from the environment where these fixes
were made.
