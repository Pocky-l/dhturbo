# Turbo for Distant Horizons

**Distant terrain many times faster** - an addon for
[Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) that builds distant terrain straight
from the world noise, with the game's own trees and structures.

[![Turbo for Distant Horizons showcase video](https://img.youtube.com/vi/scB_5JrGfXc/hqdefault.jpg)](https://www.youtube.com/watch?v=scB_5JrGfXc)

*▶️ Watch the showcase on [YouTube](https://www.youtube.com/watch?v=scB_5JrGfXc)*

📦 **Requires** [NeoForge](https://neoforged.net) 1.21.1 and
[Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3 or newer

## ✨ Features

- 🚀 **Fast distant terrain** - level-of-detail terrain without generating chunks: the horizon fills as fast as the land
  around you.
- 📊 **Measured** - 17-19x faster than Distant Horizons' rough surface search at the same sampling, ~70x at full
  detail; heights equal the real world on 98% of columns near you, checked against real chunks
  ([how it works](https://github.com/Pocky-l/dhturbo/blob/main/docs/how-it-works.md)). The F3 screen shows the tiles
  made, tiles per second and time per tile.
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
- 🌐 **Multiplayer** - on a server it shares the seed and terrain settings, so players generate the distance
  themselves, even without Distant Horizons on the server.

**Limits:** underground structures (mineshafts, strongholds, ancient cities) and caves are not drawn.

## 🧩 Compatibility

Terrain, biome and structure mods that work through data packs are generated like vanilla. Checked against real
generation:

[![Terralith](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/terralith.png)](https://www.curseforge.com/minecraft/mc-mods/terralith) ✅ **[Terralith](https://www.curseforge.com/minecraft/mc-mods/terralith)** - its biomes, surface and structures in the distance (top block 94%, heights 96% exact)

[![Tectonic](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/compat/tectonic.png)](https://www.curseforge.com/minecraft/mc-mods/tectonic) ✅ **[Tectonic](https://www.curseforge.com/minecraft/mc-mods/tectonic)** - its mountains and rivers in the distance; its terrain takes the generator about three times longer

## ⚙️ Configuration

*Mods > Turbo for Distant Horizons > Config*: generator on/off, worker threads, full resolution, distant and real
trees, structures, the world's surface rules, grass and flowers, soft plant colours, real chunks nearby (follow the
Distant Horizons generator plan). Servers can stop sharing the world seed (`share_worldgen`).

## 💛 Credits

Made by **Pocky** · [source code](https://github.com/Pocky-l/dhturbo) · built on the Distant Horizons API by James
Seibel and the Distant Horizons team; the logo is the Distant Horizons icon with a lightning bolt.
*Not affiliated with the Distant Horizons team.*

<!-- more-mods:start -->
## More mods by Pocky

**🌦️ Weather & nature**

[![Hoarfrost: Snow & Blizzards](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/hoarfrost.png)](https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards) **[Hoarfrost: Snow & Blizzards](https://www.curseforge.com/minecraft/mc-mods/hoarfrost-snow-blizzards)** - seven kinds of snowfall, a living wind and soft snow on the land

[![Petrichor: Rain & Storms](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/petrichor.png)](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms) **[Petrichor: Rain & Storms](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms)** - realistic rain, puddles, drips and branching lightning

[![Rustling Leaves](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/rustling_leaves.png)](https://www.curseforge.com/minecraft/mc-mods/rustling-leaves) **[Rustling Leaves](https://www.curseforge.com/minecraft/mc-mods/rustling-leaves)** - falling leaves and leaf piles you can wade through, rake and blow away

**💡 Light**

[![Lumen Rigs](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/lumen_rigs.png)](https://www.curseforge.com/minecraft/mc-mods/lumen-rigs) **[Lumen Rigs](https://www.curseforge.com/minecraft/mc-mods/lumen-rigs)** - aimable spotlights and floodlights with colored light and visible beams

[![Neon Glowsticks](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/neon_glowsticks.png)](https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks) **[Neon Glowsticks](https://www.curseforge.com/minecraft/mc-mods/neon-glowsticks)** - throwable glowsticks that bounce, roll and light up the dark

**🎨 Graphics & performance**

[![Pocky Shaders](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/pocky_shaders.png)](https://www.curseforge.com/minecraft/mc-mods/pocky-shaders) **[Pocky Shaders](https://www.curseforge.com/minecraft/mc-mods/pocky-shaders)** - a cosy, natural shader pack that sets itself up

**⚔️ Gear & gameplay**

[![Holy Staff](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/holy_staff.png)](https://www.curseforge.com/minecraft/mc-mods/holy-staff) **[Holy Staff](https://www.curseforge.com/minecraft/mc-mods/holy-staff)** - a healing staff with three skills and flying heal numbers

[![Rancher's Vacpack](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/vacpack.png)](https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack) **[Rancher's Vacpack](https://www.curseforge.com/minecraft/mc-mods/ranchers-vacpack)** - a Slime Rancher vacuum gun for items and small mobs

**⚡ Tech & energy**

[![Mekanism Advanced Generators](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/mekanismadvancedgenerators.png)](https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators) **[Mekanism Advanced Generators](https://www.curseforge.com/minecraft/mc-mods/mekanism-advancedgenerators)** - nine tiers of solar panels, big solar generators, cubes and cables for Mekanism

**🛠️ Server tools**

[![Inventory Backups](https://raw.githubusercontent.com/Pocky-l/dhturbo/main/docs/more-mods/inventorybackups.png)](https://www.curseforge.com/minecraft/mc-mods/inventory-backups) **[Inventory Backups](https://www.curseforge.com/minecraft/mc-mods/inventory-backups)** - inventory and ender chest backups with one-click restore for admins

<!-- more-mods:end -->
