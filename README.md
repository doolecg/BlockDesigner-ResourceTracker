<p align="center">
  <img src="docs/images/logo.png" alt="BlockDesigner logo" width="128" height="128">
</p>

<h1 align="center">Resource Tracker</h1>

<p align="center">
  The materials a build needs as the items you'd gather in survival, in stacks and shulker boxes,<br>
  what you have gathered so far and what is left, saved for each project.
</p>

<p align="center">
  <a href="https://github.com/doolecg/BlockDesigner-ResourceTracker/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/doolecg/BlockDesigner-ResourceTracker?label=release"></a>
  <a href="https://github.com/doolecg/BlockDesigner-ResourceTracker/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/doolecg/BlockDesigner-ResourceTracker/total"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/github/license/doolecg/BlockDesigner-ResourceTracker"></a>
  <img alt="Platform: Windows" src="https://img.shields.io/badge/platform-Windows-0078D6">
  <a href="https://github.com/doolecg/BlockDesigner"><img alt="BlockDesigner plugin API 5" src="https://img.shields.io/badge/BlockDesigner-plugin%20API%205-46C46E"></a>
</p>

---

Resource Tracker is a plugin for [BlockDesigner](https://github.com/doolecg/BlockDesigner), the Windows editor for Minecraft builds. It is released
on its own, separately from the app. It needs **BlockDesigner 0.4.17 or later** (plugin API 5).

**Contents:** [Download](#download-and-install) · [Features](#features) · [Building from source](#building-from-source) · [Project layout](#project-layout)

## Download and install

Get the latest version from the [releases page](https://github.com/doolecg/BlockDesigner-ResourceTracker/releases/latest):

1. Download `resource-tracker-<version>.jar`.
2. In BlockDesigner open **Plugins (puzzle icon) › Manage plugins… › Install…** and pick the jar.

It is on straight away, with a **Materials** page in its tab on the right. You can switch it off, reload or uninstall it in the same window, and it
updates itself (Plugins › Manage plugins… › Update plugins automatically). Plugins run with the same access as BlockDesigner itself, so only install
ones you trust.

## Features

### The Materials panel

- **Materials panel:** every item the build needs, most first, with its icon and how many **stacks and shulker boxes** that is
  ("2 shulkers + 3 stacks + 12").
- **Where to count:** all visible layers, the selected layers, the active layer, or the current selection. It recounts as you build.

### Gathering

- **Gathered:** type how many you have in each row: a number, stacks (`10s`), shulker boxes (`2sh`) or a sum (`1sh + 3s + 12`). **✓** marks an item
  as all gathered and **↺** takes that back. Each row shows what's left, and a bar shows the progress over the whole build.
- **Saved per project:** what you've gathered is kept for each project file, and comes back when you open it again.
- **Filter, hide done, sort** by most left, most needed or name.

### Copy and export

- **Copy list** puts what's left on the clipboard as text ("Oak Planks: 640 (10 stacks)"); **Save CSV…** writes needed, gathered and left for every
  item as a spreadsheet. **Plugins › Copy materials list** copies it without opening the panel.

### Entities

- **Mobs** (optional): count mobs as their spawn eggs. Paintings, item frames (and what they hold), armor stands, boats, minecarts and end crystals
  always count as their items.

### How blocks become items

The count is in the items you'd need in survival, not in blocks:

- A **double slab** is two slabs. **Doors, beds and tall plants** are one item for both halves. **Waterlogging** costs nothing extra.
- **Candles, sea pickles, turtle eggs, snow layers, petals and leaf litter** count how many there are in the block.
- **Crops** are their seeds (wheat → wheat seeds, carrots → carrot…). **Wall torches, signs, banners, heads and coral fans** are the item you place.
  **Potted plants** are a flower pot and the plant. **Redstone wire** is redstone dust and **tripwire** is string. **Candle cakes** are a cake and a candle.
- **Water and lava sources** are a bucket each; flowing water and lava, fire, portals and piston heads cost nothing.

## Building from source

You need Windows and a JDK 26 (Temurin 26 is what BlockDesigner uses; set `org.gradle.java.home` in
`gradle.properties` to yours). Then:

```
./gradlew jar      # build/libs/resource-tracker-<version>.jar
```

The plugin compiles against the BlockDesigner plugin API jars in [`libs/`](libs) (from BlockDesigner 0.4.17). The app
provides them, Jackson and JavaFX at runtime, so they are never bundled into the plugin. To target a newer API, replace them
with the jars from a newer BlockDesigner build (`./gradlew :plugin-api:jar :core:jar` in the
[BlockDesigner repository](https://github.com/doolecg/BlockDesigner)) and update the file names in `build.gradle.kts`.

The version is set in `build.gradle.kts` and copied into the jar's `blockdesigner-plugin.json`. To release a new
version, change it there, add a section to [RELEASE_NOTES.md](RELEASE_NOTES.md), build the jar and attach it to a
GitHub release tagged with the version.

For writing plugins, see BlockDesigner's [plugin guide](https://github.com/doolecg/BlockDesigner/blob/main/PLUGINS.md) and
[API reference](https://github.com/doolecg/BlockDesigner/blob/main/docs/plugin-api-reference.md).

### Tests

```
./gradlew test
```

The tests cover the plugin's logic that runs without the app, against the API jars in `libs/`.

## Project layout

| Path | What it does |
|---|---|
| `src/main/java` | The plugin's code: `Items` (blocks and entities to items), `Tally` (counting a scope), `Gathered` (saved progress), the panel |
| `src/main/resources/blockdesigner-plugin.json` | The manifest BlockDesigner reads: id, name, version, main class, API level |
| `src/test/java` | Tests |
| `libs/` | The BlockDesigner plugin API jars it compiles against |

## License

[MIT](LICENSE)
