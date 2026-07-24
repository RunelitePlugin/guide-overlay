# Guide Overlay 1.5.3

Focused release: section video guides made impossible to miss, and a
repository cleanup. No guide IDs, bank IDs, step keys, progress codes, or
storage formats changed. Earlier release notes are preserved in the git
history.

## Section video guides in the step list

Bank sections whose source guide places a video under them (for example
Bank 30's Brimhaven agility method video) previously surfaced that video
only as a small ▶ button on the collapsed section header and a right-click
menu entry - easy to miss while working through the open checklist. Every
section with a video now also shows an orange "▶ Section video guide" link
row directly beneath its steps, where the wiki page puts it.

Coverage was verified against the live guide snapshot: all 25 distinct
videos in the b0aty guide are surfaced - 20 episode videos, the Bank 30
section video, and 4 step-level videos (plus duplicate inline embeds of a
step's own video correctly not shown twice).

## Repository cleanup

Superseded per-release changelog files (1.4.0, 1.5.0, 1.5.1, 1.5.2) are
removed in favor of this single CHANGELOG.md; older notes live in the git
history. No source, resource, or tooling files were affected.

## Verification performed (differential)

- Video attribution probed against the real b0aty snapshot: 25 of 25
  distinct videos surfaced, zero lost to first-marker-wins or embed
  deduplication.
- VideoLinkTest and RealGuideTest suites pass; full-source syntax parse
  clean; step-key output untouched by construction (no parser changes).

`./gradlew clean test` and `./gradlew build` must still be run locally
before shipping.
