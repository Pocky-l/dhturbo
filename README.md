<p align="center">
  <img src="src/main/resources/logo.png" alt="Turbo for Distant Horizons" width="160">
</p>

<h1 align="center">Turbo for Distant Horizons</h1>

<p align="center">
  <b>Distant terrain several times faster</b> - an addon for <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons">Distant Horizons</a> that builds distant terrain straight from the world noise, with the game's own trees and structures.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><img alt="CurseForge downloads" src="https://img.shields.io/curseforge/dt/1734835?logo=curseforge&label=CurseForge&color=F16436"></a>
  <a href="https://www.minecraft.net"><img alt="Minecraft 1.21.1 | 1.20.1" src="https://img.shields.io/badge/Minecraft-1.21.1%20%7C%201.20.1-62B47A"></a>
  <a href="https://neoforged.net"><img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-F16436"></a>
  <a href="https://files.minecraftforge.net"><img alt="Forge" src="https://img.shields.io/badge/Loader-Forge-DFA86A"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons"><img alt="Requires Distant Horizons 3.3+" src="https://img.shields.io/badge/Requires-Distant%20Horizons%203.3%2B-3082D6"></a>
  <a href="LICENSE"><img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

<p align="center">
  <a href="https://www.youtube.com/watch?v=scB_5JrGfXc"><img src="https://img.youtube.com/vi/scB_5JrGfXc/hqdefault.jpg" alt="Turbo for Distant Horizons showcase video" width="720"></a>
</p>

## Versions

| Minecraft | Loader | Source |
|---|---|---|
| 1.21.1 | [NeoForge](https://neoforged.net) | branch `main` |
| 1.20.1 | [Forge](https://files.minecraftforge.net) | branch `1.20.1` |

Both versions have the same features and need [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons)
3.3 or newer for their Minecraft version.

## Features

- 🚀 **Fast distant terrain** - level-of-detail terrain without generating chunks: the horizon fills as fast as the land
  around you.
- 📊 **Measured** - in Distant Horizons' own pregeneration (1 km around spawn, same world): 3.7x faster than its
  default chunk generation and 4.7x faster than its rough surface mode, with a quarter of the CPU time; heights equal
  the real world on 98% of columns near you, checked against real chunks
  ([how it works](https://github.com/Pocky-l/dhturbo/blob/main/docs/how-it-works.md)).
- 🧵 **Every core at work** - each tile is split across all worker threads, and while Distant Horizons is busy with
  its own work, idle threads prepare the structures around you before their tiles are asked for.
- 🌲 **Real trees and plants nearby** - the game's own vegetation is replayed: trees, grass, flowers, sugar cane and
  pumpkins in the same spots, species and shapes; farther away, approximate trees shaped like their species.
- 🏔️ **The world's own surface** - the game's surface rules (also those of data packs and terrain mods) cover the
  distant ground: gravel shores, calcite, powder snow, badlands bands and hoodoos, frozen ocean icebergs; snow and ice
  by temperature; cliffs show grass on top and dirt and stone on their walls, like the real ones.
- 🏘️ **Structures in the distance** - villages, pillager outposts, temples, witch huts, mansions, ocean monuments,
  shipwrecks, ocean ruins, ruined portals and the structures of mods stand where and as the game will build them, on
  the ground they flatten; in a comparison with real generation every structure was found at its real place.
- 🔥 **The Nether and the End** - caverns, the lava sea, soul sand valleys, nylium forests and basalt deltas under the
  bedrock roof, floating End islands; fortresses, bastions and end cities block for block.
- 🌋 **Lava lakes** - where and as the game places them.
- 🌼 **Grass and flowers** - distant meadows get grass and the biome's own flowers (tulip fields, flower forest
  patches where the game puts them); flowers no longer turn whole blocks red or yellow in the distance.
- 🖼️ **No vanishing terrain** while Distant Horizons swaps tiles for more detailed ones.
- 🌐 **Multiplayer** - on a server it shares the seed and terrain settings, so players generate the distance
  themselves, even without Distant Horizons on the server.

**Limits:** underground structures (mineshafts, strongholds, ancient cities) and caves are not drawn.

## Compatibility

Terrain, biome and structure mods that work through data packs are generated like vanilla. Checked against real
generation:

[![Terralith](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/terralith.png)](https://www.curseforge.com/minecraft/mc-mods/terralith) ✅ **[Terralith](https://www.curseforge.com/minecraft/mc-mods/terralith)** - its biomes, surface and structures in the distance (top block 94%, heights 96% exact)

[![Tectonic](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/tectonic.png)](https://www.curseforge.com/minecraft/mc-mods/tectonic) ✅ **[Tectonic](https://www.curseforge.com/minecraft/mc-mods/tectonic)** - its mountains and rivers in the distance; its terrain takes the generator about three times longer

## Controls

None. The mod works on its own once installed.

## Crafting

The mod adds no blocks or items.

## Configuration

`config/dhturbo-common.toml`, also editable in game (Mods -> Turbo for Distant Horizons -> Config):

| Option | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Generate distant terrain with this mod instead of the Distant Horizons generator |
| `threads` | `0` | Worker threads, 0 = half of the CPU cores (needs a restart) |
| `full_resolution` | `false` | Compute every terrain column instead of one in four; about 3x slower, sharper distant cliffs |
| `fake_trees` | `true` | Draw trees in distant forests |
| `real_trees` | `true` | Replay the game's tree placement on detailed terrain near the player |
| `vanilla_surface` | `true` | Cover distant ground with the world's own surface rules; off: one typical block per biome, a little faster |
| `real_chunks_nearby` | `false` | Follow the Distant Horizons generator plan: with its default plan, detailed terrain near you is drawn fast first, then replaced by real chunk generation |
| `structures` | `true` | Build the world's structures (villages, temples, shipwrecks, ...) on distant terrain; underground ones are left out |
| `ground_cover` | `true` | Grass and flowers: the game's own near you, per biome farther away |
| `soft_plant_colors` | `true` | Client: blend plant colours into the ground under them instead of painting the whole block (all LODs, also from real chunks) |
| `share_worldgen` | `true` | Server: send players with this mod the world seed and terrain settings, so they generate distant terrain themselves; turn off if players must not know the seed |

By default the mod switches the Distant Horizons generator plan to "surface only" at runtime, so Distant Horizons
does not spend time regenerating terrain this mod already made; with `real_chunks_nearby` it follows your plan instead.
Your Distant Horizons config file is not changed.

## Installation

1. Install [NeoForge](https://neoforged.net) for Minecraft 1.21.1, or [Forge](https://files.minecraftforge.net) for
   Minecraft 1.20.1.
2. Install [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3 or newer.
3. Put the mod for your version into the `mods` folder: on the client for singleplayer; on the server and the clients for multiplayer.
   Distant Horizons is needed on the clients; on the server it is optional.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runGameTestServer` runs the game tests; with the environment variable
`DHTURBO_BENCH=1` it also prints the generator benchmark.

## Credits

- Author: **Pocky**.
- Built on the [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) API by James Seibel
  and the Distant Horizons team.
- The logo is the Distant Horizons icon by the Distant Horizons team with a lightning bolt added.

<!-- more-mods:start -->
## More mods by Pocky

**🌦️ Weather & nature**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards"><img src="docs/more-mods/hoarfrost.png" width="48" alt="Hoarfrost: Snow & Blizzards"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards"><b>Hoarfrost: Snow & Blizzards</b></a> - seven kinds of snowfall, a living wind and soft snow on the land (<a href="https://github.com/Pocky-l/hoarfrost">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms"><img src="docs/more-mods/petrichor.png" width="48" alt="Petrichor: Rain & Storms"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms"><b>Petrichor: Rain & Storms</b></a> - realistic rain, puddles, drips and branching lightning (<a href="https://github.com/Pocky-l/petrichor">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><img src="docs/more-mods/rustling_leaves.png" width="48" alt="Rustling Leaves"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/rustling-leaves"><b>Rustling Leaves</b></a> - falling leaves and leaf piles you can wade through, rake and blow away (<a href="https://github.com/Pocky-l/rustling-leaves">source</a>)</td>
  </tr>
</table>

**💡 Light**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/lumen-rigs"><img src="docs/more-mods/lumen_rigs.png" width="48" alt="Lumen Rigs"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/lumen-rigs"><b>Lumen Rigs</b></a> - aimable spotlights and floodlights with colored light and visible beams (<a href="https://github.com/Pocky-l/lumen-rigs">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks"><img src="docs/more-mods/neon_glowsticks.png" width="48" alt="Neon Glowsticks"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks"><b>Neon Glowsticks</b></a> - throwable glowsticks that bounce, roll and light up the dark (<a href="https://github.com/Pocky-l/neon-glowsticks">source</a>)</td>
  </tr>
</table>

**🎨 Graphics & performance**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/pocky-shaders"><img src="docs/more-mods/pocky_shaders.png" width="48" alt="Pocky Shaders"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/pocky-shaders"><b>Pocky Shaders</b></a> - a cosy, natural shader pack that sets itself up (<a href="https://github.com/Pocky-l/pocky-shaders">source</a>)</td>
  </tr>
</table>

**⚔️ Gear & gameplay**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/holy-staff"><img src="docs/more-mods/holy_staff.png" width="48" alt="Holy Staff"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/holy-staff"><b>Holy Staff</b></a> - a healing staff with three skills and flying heal numbers (<a href="https://github.com/Pocky-l/holy-staff">source</a>)</td>
  </tr>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack"><img src="docs/more-mods/vacpack.png" width="48" alt="Rancher's Vacpack"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack"><b>Rancher's Vacpack</b></a> - a Slime Rancher vacuum gun for items and small mobs (<a href="https://github.com/Pocky-l/ranchers-vacpack">source</a>)</td>
  </tr>
</table>

**⚡ Tech & energy**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators"><img src="docs/more-mods/mekanismadvancedgenerators.png" width="48" alt="Mekanism Advanced Generators"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators"><b>Mekanism Advanced Generators</b></a> - nine tiers of solar panels, big solar generators, cubes and cables for Mekanism (<a href="https://github.com/Pocky-l/Mekanism-MoreSolarPanels">source</a>)</td>
  </tr>
</table>

**🛠️ Server tools**

<table>
  <tr>
    <td width="56"><a href="https://www.curseforge.com/minecraft/mc-mods/inventory-backups"><img src="docs/more-mods/inventorybackups.png" width="48" alt="Inventory Backups"></a></td>
    <td><a href="https://www.curseforge.com/minecraft/mc-mods/inventory-backups"><b>Inventory Backups</b></a> - inventory and ender chest backups with one-click restore for admins (<a href="https://github.com/Pocky-l/inventory-backups">source</a>)</td>
  </tr>
</table>

<!-- more-mods:end -->

## License

[MIT](LICENSE)
