# BlockCompanion Plugin 1.6.0

Resource Tracker is now called the **BlockCompanion Plugin**, after the Minecraft mod it links BlockDesigner to. It updates to the new name by itself, and everything you saved (what you've gathered, saved games, your settings and keys) stays.

**Needs BlockDesigner 0.4.26 or later** (plugin API 7).

## Changed
- **New name:** the tab, the Plugins window, its page in Settings and its messages say **BlockCompanion Plugin**. Games connected to it show that name too.
- **Install mod checks what it downloads:** the BlockCompanion jar must match the size and SHA-256 checksum GitHub publishes for it. If it doesn't, it's thrown away and your mods folder is left as it was.

---

# Resource Tracker 1.5.2

The BlockCompanion page's games list no longer draws its "no game running" message over the text below it.

**Needs BlockDesigner 0.4.26 or later** (plugin API 7).

## Fixed
- **Games list with no game running:** the "No BlockCompanion game running" message was squeezed into a box three rows tall, so its link icon sat on the box's edge and **Install mod…** hung over the hint below. It now gets the room it needs and the list comes back as soon as a game connects.

---

# Resource Tracker 1.5.1

When Install mod can't replace the old BlockCompanion jar, it now tells you which program is holding it.

**Needs BlockDesigner 0.4.26 or later** (plugin API 7). Update BlockDesigner to 0.4.27 as well: it no longer locks the mods of the game it takes its textures from, which was the usual reason Install mod failed.

## Changed
- Built against the BlockDesigner 0.4.27 plugin API.

## Fixed
- **"BlockCompanion jar is in use":** Install mod used to tell you to close the game even when no game was running. If the game is running, it still says so. Otherwise it names the jar and explains that another program has it open, and that BlockDesigner before 0.4.27 held it while taking textures from that game. Update BlockDesigner, or close it and delete the jar by hand.

---

# Resource Tracker 1.5.0

Edit a build from your game: pick **Edit in BlockDesigner** on a placement in BlockCompanion and it opens here as your project, and the game follows your changes.

**Needs BlockDesigner 0.4.26 or later** (plugin API 7). Older BlockDesigners keep 1.4.1 until BlockDesigner itself is updated.

## New
- **Edit in BlockDesigner:** a placement picked in the game (BlockCompanion 0.3.0 or later) opens here as your project, whether it's a BlockDesigner project, `.schem`, `.litematic` or `.nbt` (a schematic opens as a new project). If your project has unsaved changes you're asked first. BlockDesigner comes to the front, that game is ticked and **Live** turns on, so the placement follows your edits where it stands: same position, turn and mirroring.
- **What happened** shows under the status on the BlockCompanion page: "Opened Castle from Survival", or why it couldn't (for example the file didn't arrive whole, or you cancelled). The game is told too.

## Changed
- Built against the BlockDesigner 0.4.26 plugin API.

## Fixed
- **Games that listen on IPv6 now connect.** A game whose Java prefers IPv6 (BlockCompanion 0.2.0 and older) stayed on **Connecting…**; Resource Tracker now tries `::1` when `127.0.0.1` doesn't answer.

---

# Resource Tracker 1.4.1

Install mod now always gets the newest BlockCompanion from GitHub, picks the right jar for your game, and never leaves an old copy behind. The games list is taller.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 1.2.0 until BlockDesigner itself is updated.

## Changed
- **The newest BlockCompanion release:** Install mod takes the newest full release on GitHub (BlockCompanion 0.2.0 today), skipping drafts and pre-releases.
- **The right jar:** a game on a newer patch of a Minecraft version gets that line's jar (26.3.1 gets the 26.3 jar). You're only asked for the loader when the release has more than one for that version, so 26.2 installs Fabric straight away.
- **Install by hand:** you can pick the game folder or its `mods` folder. The game's Minecraft version and loader are read from the folder, and its jar is picked for you when they're known.
- **Your textures game:** a version that's only a guess shows a **?** (for example "26.2 Fabric?"), and a game BlockCompanion has seen in that folder gives its real version instead.
- **The games list is taller:** it shows at least three games and up to six before it scrolls.

## Fixed
- **Old BlockCompanion jars are always removed,** also when renamed, and a Paper server's `plugins/update` copy. If the game is running and holds the old jar, it's disabled, or you're told to close the game first. Two copies never stay behind.
- **Clearer errors:** "GitHub's rate limit is reached; try again at 14:05" instead of "GitHub answered 403", and a missing jar says what the release does have ("no NeoForge jar for 26.2; it has Fabric for 26.2").
- Picking a `mods` folder by hand no longer installs into `mods/mods`.

---

# Resource Tracker 1.4.0

The BlockCompanion page's game list now only shows games that are running, plus the ones you save, and Install mod goes straight to the game BlockDesigner takes its textures from.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 1.2.0 until BlockDesigner itself is updated.

## New
- **Save a game:** the save button on a game's row keeps it in the list after it disconnects, so you can tick it, see its version and use its textures while it's closed. Saved games are remembered between runs; press the button again to let it go.
- **Install mod… offers your textures game first:** the game BlockDesigner takes its textures from (**Settings › Minecraft assets**) is the first choice, with its Minecraft version and loader. That's the launcher instance picked there (Prism, MultiMC, CurseForge or Modrinth), or else the game folder of your resource packs, or the official launcher's `.minecraft`. If its loader can't be told, you're asked Fabric or NeoForge.

## Changed
- **Games leave the list when they disconnect** unless saved, instead of staying for two days.
- **Another game folder…** picks the jar for your textures' Minecraft version first.

---

# Resource Tracker 1.3.0

Materials and BlockCompanion are now two pages of Resource Tracker's tab, with a status dot for the link to your game, and a cleaner layout on both.

**Needs BlockDesigner 0.4.24 or later** (plugin API 6). Older BlockDesigners keep 1.2.0 until BlockDesigner itself is updated.

## New
- **Status dot on the BlockCompanion page's button:** green when connected to a game, yellow while connecting, grey while waiting, even before you open the page.
- **Its setting in the Settings window:** **Count mobs as their spawn eggs** is on Resource Tracker's page in Settings (the gear on the tab). Your choice is kept.
- **Copy materials list** and **Send project to the game** can be given keys in Settings › Keybinds.
- **A link to the BlockCompanion page** next to the in-game progress on the Materials page.

## Changed
- **Two pages:** the tab now has a Materials page and a BlockCompanion page instead of one long page. The page you used last comes back, and where you count, the sort, Hide done and the game link's settings are remembered between runs.
- **Materials page:** where to count at the top, then the items; the overall progress, Copy list, Save CSV and Reset at the bottom. **Reset…** asks before it forgets what you gathered.
- **BlockCompanion page:** the games first, each with a Connected, Connecting or Disconnected badge; then sending, game progress and installing the mod; how the link stands, and what an install did, at the bottom. Choosing where to install uses BlockDesigner's own dialogs.
- **Use its textures** uses BlockDesigner's resource packs directly.
- The progress bars use the theme's own colours.

## Fixed
- **Narrow tabs:** nothing is cut off at 300 pixels wide any more.
- The counts no longer refresh twice after each change.

---

# Resource Tracker 1.2.0

A live link to the game: send your project into Minecraft, have the game follow every change you make, and count what your chests in the game hold. It works with BlockCompanion 0.1.0 or later.

**Needs BlockDesigner 0.4.17 or later** (plugin API 5). **Use its textures** needs BlockDesigner 0.4.24 or later; on older versions it says so and everything else works.

## New
- **Game link** in the Materials panel: every BlockCompanion game and server on this computer, with a green dot while it runs and grey once it's closed. Tick the ones your projects go to.
- **Send to game:** the open project appears in front of you in the game. A server on the same computer adds it to its shared schematics instead. Also in the Plugins menu as **Send project to the game**.
- **Live:** switch it on and every change you make here reaches the game a moment later, so the ghosts follow your edits.
- **Grab from the game:** BlockCompanion's **Grab from BD** button asks for the open project, and Resource Tracker sends it, even while the Materials panel is closed.
- **Linked chests count as gathered:** chests you link in the game with BlockCompanion's stick count towards what you have. Each row says how many are in chests, and **Copy list** and **Save CSV…** include them.
- **Install mod…** puts the latest BlockCompanion into a game's `mods` folder (or a Paper server's `plugins`), picking the jar for its loader and Minecraft version. It works for any game in the list, or a game folder you choose.
- **Use its textures** shows blocks here with the resource packs of the game selected in the list, and the pack its server sends.
- **Refresh** (↻ next to the scope) looks for games again, re-reads their progress and chests, and counts again.

## Changed
- Each item's bar fills red, orange, yellow, green from left to right as the item gets covered, and so does the bar over the whole build.

---

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
