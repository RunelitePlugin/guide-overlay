# Guide Overlay

**OSRS guides as a checklist inside RuneLite — no more alt-tabbing to the wiki.**

Pick a guide, and it appears in your sidebar as a checklist. The plugin
checks steps off as you complete them, shows you where to go, and gets
your bank ready for each section. Every feature below can be turned on or
off in the settings.

## Built-in guides

- **B0aty HCIM Guide V3**
- **BRUHsailer Ironman Guide**
- Add your own: paste any OSRS wiki guide link, or import from a file

## Checklist

- Checkboxes for every step, with progress bars per section and overall
- Long guide paragraphs are split into one action per step, so nothing
  gets cut off and every action is its own checkbox
- Steps that reference a video guide get a ▶ button (and a right-click
  option) that opens the YouTube/Streamable link in your browser
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

- Steps check themselves off when the game confirms them: quests
  started/finished, skill levels reached, items collected
- Unticking a step manually always wins — it stays unticked

## Step navigation

- Small ◀ ▶ arrow buttons check off your current step or un-check the last
  one — attached under the on-screen box, free-floating anywhere (Alt+drag),
  or hidden, your choice
- Optional keybinds for next/previous step, unbound by default and fully
  configurable

## Finding your way

- Pin a step and its NPC gets the in-game hint arrow and a glowing outline
- Too far away? A compass arrow points toward them with the tile distance —
  and with nothing pinned it points at your next step's target automatically
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

- The on-screen overlay suggests the quickest teleport to your next objective
- Suggestions are item-aware — it checks your runes and jewelry in your
  inventory, gear, and bank ("in bank" items are labeled). It does not yet
  model Magic level, spellbook, quest unlocks, or cooldowns, so treat a
  suggestion as "you have the items", not "guaranteed castable"
- Choose which kinds count: spells, teleport tablets, jewelry, other items
- Have the Shortest Path plugin installed? Guide Overlay sends it your
  destination so the walking path is drawn on the ground for you

## Bank help

- Opening your bank shows a tab with just the items your current section needs,
  arranged in the order the guide uses them (toggleable)
- Finished steps drop off the tab automatically
- Item pictures under each step turn green when you have the item, red when missing
- A "✓ Trip ready" indicator (with its own distinct sound) confirms the
  moment everything the section needs is in your inventory
- Checking off a step plays a short success chime, and finishing a section
  prints a chat message and can chime too — every confirmation has its
  own toggle

## On-screen overlay

- A small movable box shows your current section, next steps, and route tip
- Showing more than one step? The current step gets its own box with a
  separate "Next steps" box underneath, so it's easy to focus (toggleable)
- The current step's item pictures can show right in the box (toggleable),
  so you see what to grab without opening the sidebar
- Shift+right-click the box for quick actions: next step, previous step,
  pin next target
- Everything repositions with Alt+drag and has size/font/color settings

## Getting started

1. Install **Guide Overlay** from the RuneLite Plugin Hub
2. Click the scroll icon in the sidebar and confirm the one-time guide download
3. Play — right-click a step to sync the checklist to wherever you already are

## Privacy & fair play

- Guides download **once**, only when you confirm — never automatically.
  Your guide and progress can't be changed behind your back
- Nothing about you or your account is ever sent anywhere
- The plugin only displays and tracks. It never clicks, moves, or acts for you

## Development checks

- Run `./gradlew clean test` before release.
- Run `./tools/audit.sh` for dependency-free safety checks.
- Pin both raw GitHub data URLs to reviewed commit SHAs before Plugin Hub
  submission; `tools/submit.sh` performs that pinning automatically.

## License

BSD 2-Clause. Guide content belongs to its original authors.
