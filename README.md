# Guide Overlay

**Current release: 2.0**

**OSRS guides as a checklist inside RuneLite — no more alt-tabbing to the wiki.**

Pick a guide, and it appears in your sidebar as a checklist. The plugin
checks steps off as you complete them, shows you where to go, and gets
your bank ready for each section. Every feature below can be turned on or
off in the settings.

<p align="center">
  <img src="https://raw.githubusercontent.com/RunelitePlugin/guide-overlay/main/docs/sidebar-checklist.png" alt="The guide as a sidebar checklist: progress bar, section navigation, completed steps struck through, and an item grid with green borders for items you have" width="260">
</p>

## Built-in guides

- **B0aty HCIM Guide V3**
- **BRUHsailer Ironman Guide**
- Add your own: paste any OSRS wiki guide link, or import from a file

## Checklist

- Checkboxes for every step, with progress bars per section and overall
- Long guide paragraphs are split into one action per step, so nothing
  gets cut off and every action is its own checkbox
- Steps that reference a video guide get a ▶ button (and a right-click
  option) that opens the YouTube/Streamable link in your browser; sections
  with their own video guide show a "▶ Section video guide" link under
  their steps, plus a ▶ on the section header
- Progress saves automatically and keeps across sessions
- Search your guide, or jump straight to your next unchecked step
- When a finished bank auto-collapses, the next one opens and scrolls into
  view by itself
- Starting mid-guide? One click syncs the checklist to your account's
  actual quest log and skills — or right-click any step to mark everything
  before it complete / clear everything after it to rewind
- Every bulk change (sync, catch-up, rewind, bank-wide marks) can be
  undone with one click
- Optional steps you don't want? Right-click "skip" excludes a step from
  progress without pretending you did it
- Separate progress per character — if your hardcore dies, the new one starts fresh
- Back up your progress to the clipboard and restore it anywhere

### Using the guide on multiple computers

Progress lives in RuneLite's own configuration, so RuneLite's account sync
carries it between PCs:

1. Sign into your **RuneLite account** (the profiles panel on the client's
   login screen / sidebar) on each computer
2. Per-character progress (the default) syncs automatically with the account
3. For the plugin's settings and shared (non-per-character) progress too,
   enable cloud sync on your configuration profile (the cloud icon in the
   profiles panel)
4. On a new computer the guide **text** still needs its one-click import —
   guides are never downloaded without your confirmation, so the sidebar
   simply offers the import button; your synced checkmarks apply the moment
   it loads

The clipboard progress code (above) remains as a manual fallback that works
without a RuneLite account.

## Auto-completion

Travel steps check themselves off when you arrive. A step that only says to go
somewhere completes on arrival; a step that also asks you to talk to someone,
buy something or finish a quest never does, because being in the right place
does not prove the rest was done.

Presence checks count items you are **wearing** as well as carrying, so an
equipped Dramen staff or Ardougne cloak satisfies a step that needs one.

- Steps check themselves off when the game confirms them: quests
  started/finished, skill levels reached, items collected
- Compound rows such as “teleport to Varrock, then talk to Aubury” keep one
  checkbox but run as ordered internal phases. Arrival advances the travel phase;
  the row stays open until its remaining task is actually confirmed or checked.
- Ambiguous, optional, task-first, and unresolved sequences stay manual rather
  than being split by guesswork. No generated phase changes bank counts, step
  keys, progress imports, or section percentages.
- Unticking a step manually always wins — it stays unticked

## Step navigation

- Small ◀ ▶ arrow buttons check off your current step or un-check the last
  one — attached at the top of the on-screen box, free-floating anywhere
  (Alt+drag), or hidden, your choice
- Moving forward or backward hands guidance over cleanly: the old step's
  arrow, route and tracking clear immediately and the newly current step's
  destination takes over, with a rewound step starting again from its first
  waypoint
- "Jump to next unchecked step" centers that step in the sidebar
- Optional keybinds for next/previous step, unbound by default and fully
  configurable

## Finding your way

A coloured arrow marks the target NPC or destination tile while it is on screen.
It is drawn by the plugin rather than using the game's own hint arrow, so it
cannot be replaced by the game or another plugin, and its colour, transparency
and size can be set. The game's native hint arrow is available as an option if
you prefer it.

Opening the world map starts it centred on the current destination. It is
never moved while you are panning it, and the behaviour can be turned off.

The world map marker stays visible even when the destination is outside the
area you are looking at: the icon pins to the nearest edge of the map, and
clicking it pans straight there.

<p align="center"><img src="https://raw.githubusercontent.com/RunelitePlugin/guide-overlay/main/docs/scene-highlight.png" alt="The step's target NPC highlighted with a glowing outline, with a walking path drawn to it" width="520"></p>

- Pin a step and its NPC or named destination gets a persistent colored arrow
  drawn by Guide Overlay, plus a glowing NPC outline when nearby. Arrow color,
  opacity and size are configurable. The game's native hint arrow is available
  as an optional fallback, but is off by default because the game or another
  plugin can replace it.
- Named places such as Tithe Farm, banks, guilds, towns, minigames, and common
  quest areas are recognized automatically, so a step that just says "go to the
  Grand Exchange" still gets an arrow; consecutive actions in the same area keep
  pointing at the right spot
- Transport shorthand is resolved separately from ordinary place names: fairy-ring
  codes, jewelry teleports, minigame teleports, minecarts, boats, Quetzals,
  charters, portals, and similar travel instructions can point at their arrival
  location without treating every item name or three-letter word as a destination
- Teleport and transport instructions are cyan in both the sidebar and on-screen
  HUD by default, with a configurable color and an off switch
- The compass points at the destination most recently handed to Shortest Path
  on every located step, including destinations inside the loaded scene — the
  hand-off also drives the compass when Shortest Path itself isn't installed.
  With no hand-off target it falls back to the current far destination, and
  with nothing to track it can point at your next step automatically. For a
  moving NPC the compass follows the handed-off tile, so it can sit a tile or
  two behind the live NPC that the scene arrow tracks.
- Steps can contain ordered waypoints. The plugin advances automatically after
  you remain inside the active waypoint's arrival radius for two game ticks,
  and the HUD shows a quiet "Location 1/2" counter so multi-stop routes are
  visible at a glance
- Explicit travel-only steps can also check themselves off at the final waypoint.
  This is intentionally conservative: compound instructions, NPC interactions,
  unresolved or inherited destinations, parent summary rows, and steps with a
  separate item/quest/skill condition are never completed from location alone.
- The crosshair button hides or restores the location guide for the step you
  are on. The hide applies to exactly that step and clears automatically when
  the guide moves on — hidden routes still advance and complete normally
- Use **Location tools** to save the current tile or world-map center as a custom
  destination, add/reorder/rename/remove waypoints, restore automatic resolution,
  and import or export profile-scoped custom pins
- Every current step with a resolved location shows guidance without needing a
  pin. In-scene destinations get the scene arrow; when Shortest Path is
  installed and drawing, its path and the compass share one requested
  destination tile.
- Location source and confidence can be appended to the sidebar's status line.
  Low-confidence destinations can optionally be hidden from precise markers
  (off by default — the compass still points either way), and display modes
  can show all guidance, map only, Shortest Path only, nearby markers only,
  or everything except the compass
- If a step's location can't be pinned down, you can export a list of those
  steps to see exactly which ones need a manual pin and why
- Compass looks are configurable: full dial or bare arrow, solid triangle
  or an arrow with a tail
- The world map marks where your next step wants you to go
- NPCs, ground items, AND scene objects (ladders, altars, doors, furnaces...)
  your current section needs are highlighted automatically
- Steps that note chat choices — "(2,1)" — get the right dialogue option
  outlined in the chat box as each menu appears (you still click it), and
  only while you're talking to the NPC (or using the object) the step names —
  chatting with anyone else never draws a box

## Fastest route

Shortest Path only removes the part of the route you have already walked when
it recalculates. **Path refresh distance** asks it to recalculate after you move
that many tiles (default 3), so the trailing edge keeps up. Lowering it is smoother but adds
noticeably more CPU work, and on long routes a very low value can make the path
lag further behind rather than less. Raising it costs almost nothing. Set it to
0 to refresh only when the step changes.

- The on-screen overlay suggests the quickest teleport to your next objective
- Suggestions are item-aware — it checks your runes and jewelry in your
  inventory, gear, and bank ("in bank" items are labeled). It does not yet
  model Magic level, spellbook, quest unlocks, or cooldowns, so treat a
  suggestion as "you have the items", not "guaranteed castable"
- Choose which kinds count: spells, teleport tablets, jewelry, other items
- Have the Shortest Path plugin installed? Guide Overlay sends it your
  destination so the walking path is drawn on the ground for you. The compass
  uses that same requested target, so the two always aim at one tile (if you
  set your own target inside Shortest Path, the guide's target resumes within
  a few seconds).


## Bank help

<p align="center"><img src="https://raw.githubusercontent.com/RunelitePlugin/guide-overlay/main/docs/bank-tab.png" alt="A bank tag tab showing only the items the current section needs, with quantity numbers on each" width="520"></p>

- Opening your bank shows a tab with just the items your current section needs,
  arranged in the order the guide uses them (toggleable)
- Finished steps drop off the tab automatically
- Item pictures under each step turn green when you have the item, red when missing
- A "✓ Trip ready" indicator (with its own distinct sound) confirms the
  moment everything the section needs is in your inventory
- Checking off a step plays a short success chime, and finishing a section
  prints a chat message and can chime too — every confirmation has its
  own toggle
- Sounds are generated by the plugin by default, so they work even with the
  game muted, and have their own volume slider. If you switch **Sound source**
  to **Game** instead, the game's master volume *and* sound effects volume must
  both be turned on or you will hear nothing

## On-screen overlay

Overlay text has its own font: 28 families including any font installed on your
computer, separate plain, bold and italic styles, and any size from 8 to 40. The
side panel has the same choices — family, style, 8–40px size, and a custom
installed-font field — independent of the overlay, applied to every piece of
panel text, and defaulting to Sans Serif, Plain, 10px. No font files are
bundled or downloaded, and a font your system does not have falls back to a
standard one.

<p align="center"><img src="https://raw.githubusercontent.com/RunelitePlugin/guide-overlay/main/docs/onscreen-overlay.png" alt="The on-screen box showing the current step, a Next steps list, and a compass arrow with tile distance to the destination" width="400"></p>

- A small movable box shows your current section, next steps, and route tip
- Showing more than one step? The current step gets its own box with a
  separate "Next steps" box underneath, so it's easy to focus (toggleable)
- The current step's item pictures can show right in the box (toggleable),
  so you see what to grab without opening the sidebar
- Shift+right-click the box for quick actions: step navigation, pinning the
  current tile, snoozing/restoring guidance, or toggling the current
  destination's location guide
- Everything repositions with Alt+drag and has size/font/color settings
- Overlay text has an independent 8–40px size control, plain/bold/italic
  styling, more than twenty cross-platform and common installed-font presets,
  plus a custom installed-font-family field with a safe Sans Serif fallback
- Side-panel text has the same font controls as the overlay — family presets,
  custom installed font, plain/bold/italic, 8–40px — with its own independent
  values (fresh default: Sans Serif, Plain, 10px)

## Interchangeable item groups

When a step says "Inventory of X" **and** also needs other items, the slot shows
the full 28 but counts as ready at 14, so the step can still complete once you
are carrying a sensible share of both. A step that asks only for an inventory of
something still needs all 28. The tooltip says when the two numbers differ.

Some guide instructions allow several valid items rather than one exact item.
Guide Overlay represents these as one grouped requirement instead of inventing
a specific loadout. Examples include:

- `3 lots of 7 random cooked food` → `3×7 Any cooked food` (21 total);
- `log or axe` → any listed log or usable axe;
- `Uncut gems` → the combined eligible uncut gems in the inventory;
- `Few food` → any supported cooked-food combination (on the curated steps
  that carry an override; elsewhere a vague quantity stays as written).

Inventory presence and trip-ready checks sum the eligible alternatives. The
sidebar/HUD uses one compact representative icon and lists the valid choices in
the tooltip. The managed Bank Tags tab includes the eligible underlying items,
so the player can choose what is actually available without being told every
option is mandatory.

When a step says `Inventory of X`, the icon and bank-facing quantity still show
28. If that same step also asks for another item, 14 of X is enough for the
have-it border, trip-ready state and item-condition auto-completion. A standalone
`Inventory of X` continues to require the full 28.

Some steps name a family rather than one exact item. "Pickaxe", "Axe", "Boots",
"Any ring", "Grimy herb" and "Food" accept any member of that family, and the
slot shows the lowest tier so the picture never implies gear the step does not
need. A phrase with no dose written, such as "Prayer Potion", accepts any dose;
an explicitly written "(4)" stays exact.

Phrases that name your own loadout, such as "Combat Gear" or "Range Gear", have
no fixed item list and show a crossed-swords marker instead. These are skipped
by the trip-ready check, since only you know what they mean for your account.

## Getting started

1. Install **Guide Overlay** from the RuneLite Plugin Hub
2. Click the scroll icon in the sidebar and confirm the one-time guide download
3. Play — right-click a step to sync the checklist to wherever you already are

### Optional plugins

**Nothing needs installing.** Guide Overlay is complete on its own: the
checklist, auto-completion, item highlighting, hint arrow, compass and world-map
markers all work with no other plugin. The bank tab uses one of RuneLite's own
built-in plugins, which is on by default.

One built-in RuneLite plugin is used if you want the bank tab:

| Plugin | Adds | Without it |
| --- | --- | --- |
| **Bank Tags** | The "guide-overlay" bank tab that shows the current step's items | Everything else works; the bank tab simply does not appear |

Bank Tags ships with RuneLite and is enabled by default, so there is usually
nothing to do. If the bank tab never appears, check it is switched on in the
plugin list. It is not a Plugin Hub download.

Two Plugin Hub plugins add things Guide Overlay cannot do by itself:

| Plugin | Adds | Without it |
| --- | --- | --- |
| **Shortest Path** | Draws a tile-by-tile walking route on the ground to the current destination | Hint arrow, compass and map marker still work, just no drawn path |
| **Quest Helper** | Detailed quest-stage instructions and quest markers | Guide Overlay uses its own locations and targets as normal |

Shortest Path is the only plugin Guide Overlay sends anything to: it passes the
current destination so the route can be drawn. Quest Helper is detected through
RuneLite's normal plugin manager, so Guide Overlay can stop drawing duplicate
scene highlights on quest steps without reading anything inside Quest Helper.

Guides sometimes recommend activity-specific helpers such as Mahogany Homes or
Kourend Library plugins. Those are suggestions from the guide's author, not
requirements of Guide Overlay, and their Hub names may change independently.

## Network use

The plugin connects to two places, and only to read:

- **GitHub** for the bundled guide data and the NPC location database. Both URLs
  are pinned to a fixed, reviewed commit, so the same content is fetched every
  time.
- **The OSRS wiki**, only when you add a guide by link, to read the page you
  asked for.

Nothing is uploaded. No account name, character data, inventory, location or
gameplay information is ever sent anywhere. The only thing a server learns is
which guide page you asked it for, the same as opening that page in a browser.

If you would rather make no network requests at all, use the import-from-file
option instead of adding a guide by link.

## Privacy & fair play

- Guides download **once**, only when you confirm — never automatically.
  Your guide and progress can't be changed behind your back
- Nothing about you or your account is ever sent anywhere
- The plugin only displays and tracks. It never clicks, moves, or acts for you

## License

BSD 2-Clause. Guide content belongs to its original authors.
