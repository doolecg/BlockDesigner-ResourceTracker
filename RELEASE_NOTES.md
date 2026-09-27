# Resource Tracker 1.1.0

Blocks you place in the game with the BlockCompanion mod now count as done. The Materials panel links the project to its build in the game, shows how many of each item are placed, and keeps up while you build.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

## New
- **Game progress** in the Materials panel: links the project to the build BlockCompanion has loaded from it (by project name, or by file name; the most recently updated if there are several). Pick another build or **Not linked**; the choice is saved for each project.
- **Placed counts as done:** each row shows how many are placed in the game, and an item with all of it placed is done by itself. What's left is needed − placed − gathered; the progress bar counts placed and gathered.
- **Live status:** "In game: 35% built · 9,313 blocks left · updated 5 s ago", refreshed every 2 seconds, or "not seen for …" when the game has stopped writing.
- **Copy list** and **Save CSV…** include the placed counts when the project is linked.

## Changed
- With a linked build, **Gathered** means what you have in hand and haven't placed yet, and **✓** fills in only what isn't placed. Without one, everything works as before.

---

# Resource Tracker 1.0.2

Kept up to date with BlockDesigner 0.4.23: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

## Changed
- Built against the BlockDesigner 0.4.23 plugin API.

---

# Resource Tracker 1.0.1

Kept up to date with BlockDesigner 0.4.22: built and tested against its plugin API. Nothing changes in how it works.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

## Changed
- Built against the BlockDesigner 0.4.22 plugin API.

---

# Resource Tracker 1.0.0

The first release: what a build needs, as the items you'd gather in survival, what you've gathered and what's left. It replaces the "Coming soon" Resource Tracker tab BlockDesigner used to have.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5).

## New
- **Materials panel** in the plugin's tab: every item the build needs, most first, as stacks and shulker boxes.
- **Where to count:** all visible layers, the selected layers, the active layer or the current selection; it recounts as you build.
- **Gathered counts** per item, typed as a number, stacks (`10s`), shulker boxes (`2sh`) or a sum, or ticked done; saved for each project.
- **Progress bar**, filter, hide done, and sorting by most left, most needed or name.
- **Copy list** (text) and **Save CSV…**; **Plugins › Copy materials list** too.
- **Item-accurate counts:** double slabs are two slabs, doors and beds one item for both halves, crops their seeds, wall torches torches, candles and sea pickles by the bunch, potted plants a pot and the plant.
- **Entities:** paintings, item frames (and what they hold), armor stands, boats, minecarts and end crystals count as items; mobs as spawn eggs if you tick **Mobs**.

---
