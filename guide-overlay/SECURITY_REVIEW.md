# Guide Overlay security and rules review

Review date: 2026-07-15

## Overall result

The reviewed runtime code is an overlay/checklist plugin. It reads RuneLite client state and renders UI, highlights, map markers, a hint arrow, and optional integration messages. No code was found that generates mouse clicks or keypresses, invokes menu actions for the player, talks directly to the game protocol, uses native code, launches subprocesses, or uses dangerous Java reflection.

This is a source review, not an official Jagex or RuneLite approval. Final Plugin Hub acceptance still depends on RuneLite review and the then-current Jagex rules.

## Changes made in this revision

- Cached step-highlight scene scans and refresh them only after relevant scene/model/config changes, with a periodic safety refresh for NPC transformations.
- Fixed stale world-map marker tooltips when a new target uses the same tile.
- Added progress-code size, key-count, key-length, newline/NUL, and guide-ID validation.
- Filters imported progress to step keys that actually exist in the selected guide and aborts safely if the guide changes during import.
- Added guide-ID validation before constructing snapshot paths.
- Added size limits for stored guide files, location databases, clipboard imports, and network guide responses.
- Replaced direct snapshot/location writes with temp-file plus atomic-replace writes where the filesystem supports them.
- Restricted direct built-in guide downloads to HTTPS on `raw.githubusercontent.com`.
- Rolls back a newly created file-only guide entry when its first import fails.
- Changed the Plugin Hub submission helper so the GitHub token is not embedded in the Git remote URL or command-line arguments.
- Added `tools/audit.sh` for repeatable checks of prohibited APIs, credentials, shell syntax, and unpinned raw-data URLs.

## Checks performed

- Dependency-free static scan of all Java sources for generated input, subprocesses, JNI/native loading, dangerous reflection, sockets/protocol code, and credential patterns.
- Manual review of all network requests and file/clipboard boundaries.
- Pure-Java compilation with Java 11 compatibility and 25 executable checks covering URL allowlisting, parser behavior, HTML neutralization, stable keys, item parsing, deterministic progress export, round trips, corrupt payloads, oversized payloads, forged newline keys, and invalid guide IDs.
- Six additional stubbed boundary/file checks covering snapshot and backup writes, invalid/path-traversal guide IDs, and blank/oversized location imports.
- Java parser pass across the entire source tree. Full type resolution requires RuneLite/Gson/OkHttp dependencies.
- Shell syntax validation for `tools/submit.sh` and `tools/audit.sh`.

## Remaining release requirement

The two raw GitHub data URLs still use moving `main`/`master` branches in this working copy. Run `tools/submit.sh` (or pin both URLs manually to reviewed commit SHAs) before Plugin Hub submission. Do not submit a build whose remote data URLs can change without a plugin-code review.

## Build limitation in this environment

The normal Gradle test suite could not be executed here because the sandbox could not resolve/download the Gradle distribution or RuneLite dependencies. The source was still parsed, the dependency-free classes were compiled with `javac --release 11 -Xlint:all -Werror`, and the local checks passed. Run `./gradlew clean test` in a normal networked development environment before release.
