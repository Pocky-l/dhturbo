# Turbo for Distant Horizons

**Distant terrain several times faster** - an addon for
[Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) that builds distant terrain straight
from the world noise, with the game's own trees and structures.

[![Turbo for Distant Horizons showcase video](https://img.youtube.com/vi/scB_5JrGfXc/hqdefault.jpg)](https://www.youtube.com/watch?v=scB_5JrGfXc)

*▶️ Watch the showcase on [YouTube](https://www.youtube.com/watch?v=scB_5JrGfXc)*

📦 **Requires** [NeoForge](https://neoforged.net) 1.21.1 or [Forge](https://files.minecraftforge.net) 1.20.1, and
[Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3 (3.3.3 or a later 3.3 on 1.21.1)

## ✨ Features

- 🚀 **Fast distant terrain** - level-of-detail terrain without generating chunks: a far tile costs about as much as a
  near one.
- ⚡ **Horizon first** - in singleplayer the whole view of a new world is there about 3 seconds after you join, coarse at
  first, then it sharpens pass by pass, starting with what you look at. Every tile is done in under 30 seconds (6-core PC,
  LOD radius 256 chunks).
- 📊 **Measured** - in Distant Horizons' own pregeneration (1 km around spawn, same world): about 7x faster than its
  default chunk generation with under a fifth of the CPU time; 94-98% of land columns have the real height at every
  distance and biomes agree on 99.5%, checked against real chunks
  ([how it works](https://github.com/Pocky-l/dhturbo/blob/main/docs/how-it-works.md)). In play, Distant Horizons' own
  saving and meshing often set the pace.
- 🎯 **Exact terrain near you** - while the generator is idle, the chunks just past your render distance are redrawn by
  the game's real generator, with caves, ores on cliffs, trees and plants: in test runs 92 to 95% of columns then
  matched the real world. It waits while the server is busy or you move fast. Config `exact_radius`; dedicated servers
  opt in with `exact_radius_server`.
- 🏠 **Your world in the distance** - chunks the world has saved (builds, adventure maps, pregenerated terrain) reach
  Distant Horizons as they are, nearest first, in the background.
- 🌲 **Real trees and plants** - the game's own vegetation is replayed up to 4-block columns: trees, grass, flowers,
  sugar cane and pumpkins with its species and shapes. About 7 in 10 tree columns land on the real spot at full detail,
  over half at 4-block columns, jungles far fewer; farther away, approximate trees shaped like their species.
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
- 🌐 **Multiplayer** - a server without Distant Horizons shares the seed and terrain settings, so players generate the
  distance themselves; a server with it sends its own distant terrain and keeps the seed.

**Limits:** terrain already drawn stays as it was until you run `/dhturbo regenerate`, also after an update. Players
generating the distance on a server without Distant Horizons get the seed's terrain: builds show once they have been
there. Beyond `exact_radius`, underground structures (mineshafts, strongholds, ancient cities) and caves are not drawn.

## 🧩 Compatibility

Terrain, biome and structure mods that work through data packs are generated like vanilla. Checked against real
generation:

[![Terralith](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/terralith.png)](https://www.curseforge.com/minecraft/mc-mods/terralith) ✅ **[Terralith](https://www.curseforge.com/minecraft/mc-mods/terralith)** - its biomes, surface and structures in the distance (top block 94%, land heights 95% exact)

[![Tectonic](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/tectonic.png)](https://www.curseforge.com/minecraft/mc-mods/tectonic) ✅ **[Tectonic](https://www.curseforge.com/minecraft/mc-mods/tectonic)** - its mountains and rivers in the distance; its terrain takes the generator about twice as long

[![ReTerraForged](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/reterraforged.png)](https://www.curseforge.com/minecraft/mc-mods/reterraforged-from-the-ashes) ✅ **[ReTerraForged: From the Ashes](https://www.curseforge.com/minecraft/mc-mods/reterraforged-from-the-ashes)** - its continents, mountains and rivers in the distance (heights within 4 blocks on 99% of columns: its erosion is not drawn). In multiplayer the server draws them, players do not generate them themselves

A world whose terrain the mod cannot read (a mod with its own chunk generator) is left to Distant Horizons, which draws its rough surface there: the mod checks every world against the game's generator when it loads and tells whoever runs the world in chat.

## ⚙️ Configuration

*Mods > Turbo for Distant Horizons > Config*: generator on/off, worker threads, exact terrain radius (singleplayer and
dedicated servers apart), full resolution,
distant and real trees, structures, the world's surface rules, grass and flowers, soft plant colours, real chunks nearby (follow the
Distant Horizons generator plan), dimensions to leave to Distant Horizons (`excluded_dimensions`). Servers without
Distant Horizons can stop sharing the world seed (`share_worldgen`). With F3 open, a line shows what the mod is doing.
`/dhturbo regenerate` redraws the world's distant terrain, for example after an update.

## 💛 Credits

Made by **Pocky** · [source code](https://github.com/Pocky-l/dhturbo) · built on the Distant Horizons API by James
Seibel and the Distant Horizons team; the logo is the Distant Horizons icon with a lightning bolt.
*Not affiliated with the Distant Horizons team.*

<!-- more-mods:start -->
## More mods by Pocky

**🌦️ Weather & nature**

[![Hoarfrost: Snow & Blizzards](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/hoarfrost.png)](https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards) **[Hoarfrost: Snow & Blizzards](https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards)** - seven kinds of snowfall, a living wind and soft snow on the land

[![Petrichor: Rain & Storms](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/petrichor.png)](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms) **[Petrichor: Rain & Storms](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms)** - realistic rain, puddles, drips and branching lightning

[![Rustling Leaves](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/rustling_leaves.png)](https://www.curseforge.com/minecraft/mc-mods/rustling-leaves) **[Rustling Leaves](https://www.curseforge.com/minecraft/mc-mods/rustling-leaves)** - falling leaves and leaf piles you can wade through and rake, client-side

**💡 Light**

[![Lumen Rigs](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/lumen_rigs.png)](https://www.curseforge.com/minecraft/mc-mods/lumen-rigs) **[Lumen Rigs](https://www.curseforge.com/minecraft/mc-mods/lumen-rigs)** - aimable spotlights and floodlights with colored light and visible beams

[![Neon Glowsticks](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/neon_glowsticks.png)](https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks) **[Neon Glowsticks](https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks)** - throwable glowsticks that bounce, roll and light up the dark

**🎨 Graphics & performance**

[![Pocky Shaders](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/pocky_shaders.png)](https://www.curseforge.com/minecraft/shaders/pocky-shaders) **[Pocky Shaders](https://www.curseforge.com/minecraft/shaders/pocky-shaders)** - a cosy, natural shader pack that shows the weather of these mods

**⚔️ Gear & gameplay**

[![Holy Staff](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/holy_staff.png)](https://www.curseforge.com/minecraft/mc-mods/holy-staff) **[Holy Staff](https://www.curseforge.com/minecraft/mc-mods/holy-staff)** - a healing staff with three skills and flying heal numbers

[![Rancher's Vacpack](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/vacpack.png)](https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack) **[Rancher's Vacpack](https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack)** - a Slime Rancher vacuum gun for items and small mobs

**🔮 Magic**

[![HexTale](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/hextale.png)](https://www.curseforge.com/minecraft/mc-mods/hextale) **[HexTale](https://www.curseforge.com/minecraft/mc-mods/hextale)** - hextech magic to research, distil and bind with runes, inspired by Arcane and Thaumcraft

**⚡ Tech & energy**

[![Mekanism Advanced Generators](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/mekanismadvancedgenerators.png)](https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators) **[Mekanism Advanced Generators](https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators)** - nine tiers of solar panels, big solar generators, cubes and cables for Mekanism

**🛠️ Server tools**

[![Inventory Backups](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/inventorybackups.png)](https://www.curseforge.com/minecraft/mc-mods/inventory-backups) **[Inventory Backups](https://www.curseforge.com/minecraft/mc-mods/inventory-backups)** - inventory and ender chest backups with one-click restore for admins

<!-- more-mods:end -->
