<p align="center">
  <img src="src/main/resources/logo.png" alt="Turbo for Distant Horizons" width="160">
</p>

<h1 align="center">Turbo for Distant Horizons</h1>

<p align="center">
  <b>Distant terrain many times faster</b> - an addon for <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons">Distant Horizons</a> that builds distant terrain straight from the world noise, with the game's own trees nearby.
</p>

<p align="center">
  <a href="https://www.curseforge.com/minecraft/mc-mods/turbo-for-distant-horizons"><img alt="CurseForge downloads" src="https://img.shields.io/curseforge/dt/1734835?logo=curseforge&label=CurseForge&color=F16436"></a>
  <a href="https://www.minecraft.net"><img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A"></a>
  <a href="https://neoforged.net"><img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-F16436"></a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons"><img alt="Requires Distant Horizons 3.3+" src="https://img.shields.io/badge/Requires-Distant%20Horizons%203.3%2B-3082D6"></a>
  <a href="LICENSE"><img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue"></a>
</p>

<p align="center">
  <a href="https://www.youtube.com/watch?v=scB_5JrGfXc"><img src="https://img.youtube.com/vi/scB_5JrGfXc/hqdefault.jpg" alt="Turbo for Distant Horizons showcase video" width="720"></a>
</p>

## Features

- 🚀 **Fast distant terrain** - level-of-detail terrain without generating chunks: the horizon fills as fast as the land
  around you.
- 📊 **Measured** - 17-19x faster than Distant Horizons' rough surface search at the same sampling, ~70x at full
  detail; heights within one block of real generation on 90% of columns.
- 🧵 **Every core at work** - each tile is split across all worker threads.
- 🌲 **Real trees nearby** - the game's own tree placement is replayed: same spots, species and shapes; farther away,
  approximate trees shaped like their species.
- 🏔️ **Surface details** - snow by temperature and height, ice, badlands bands, biome seabeds; cliffs and ravines
  show grass on top and dirt and stone on their walls, like the real ones.
- 🌋 **Lava lakes** - where and as the game places them.
- 🌼 **Grass and flowers** - distant meadows get grass and the biome's own flowers (tulip fields, flower forest
  patches where the game puts them); flowers no longer turn whole blocks red or yellow in the distance.
- 🖼️ **No vanishing terrain** while Distant Horizons swaps tiles for more detailed ones.
- 🌐 **Multiplayer** - on a server it shares the seed and terrain settings, so players generate the distance
  themselves, even without Distant Horizons on the server.

**Limits:** the Nether keeps the Distant Horizons generator; structures show in the distance only once you have been
there.

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
| `ground_cover` | `true` | Put grass and the biome's flowers on distant ground |
| `soft_plant_colors` | `true` | Client: blend plant colours into the ground under them instead of painting the whole block (all LODs, also from real chunks) |
| `share_worldgen` | `true` | Server: send players with this mod the world seed and terrain settings, so they generate distant terrain themselves; turn off if players must not know the seed |

While enabled, the mod switches the Distant Horizons generator plan to "surface only" at runtime, so Distant Horizons
does not spend time regenerating terrain this mod already made. Your Distant Horizons config file is not changed.

## Installation

1. Install [NeoForge](https://neoforged.net) for Minecraft 1.21.1.
2. Install [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3 or newer.
3. Put this mod into the `mods` folder: on the client for singleplayer; on the server and the clients for multiplayer.
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
