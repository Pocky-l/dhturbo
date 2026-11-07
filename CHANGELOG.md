# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.0+26.3] - 2026-11-07
### Added
- A version for Minecraft 26.3, for [NeoForge](https://neoforged.net/) and [Fabric](https://fabricmc.net/) (with
  [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and
  [Forge Config API Port](https://www.curseforge.com/minecraft/mc-mods/forge-config-api-port)), with everything of 1.6.0.
  It needs [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3.4 for 26.3.

## [1.6.0+26.2] - 2026-11-07
### Added
- A version for Minecraft 26.2, for [NeoForge](https://neoforged.net/) and [Fabric](https://fabricmc.net/) (with
  [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and
  [Forge Config API Port](https://www.curseforge.com/minecraft/mc-mods/forge-config-api-port)), with everything of 1.6.0.
  It needs [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3.3 for 26.2.

## [1.6.0+1.21.11] - 2026-11-07
### Added
- A version for Minecraft 1.21.11, for [NeoForge](https://neoforged.net/) and [Fabric](https://fabricmc.net/) (with
  [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and
  [Forge Config API Port](https://www.curseforge.com/minecraft/mc-mods/forge-config-api-port)), with everything of 1.6.0.
  It needs [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) 3.3.3 for 1.21.11.

## [1.6.0-fabric] - 2026-11-07
### Added
- A [Fabric](https://fabricmc.net/) version for Minecraft 1.21.1, with the same generator, commands and config file as on
  NeoForge. It needs [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons),
  [Fabric API](https://www.curseforge.com/minecraft/mc-mods/fabric-api) and
  [Forge Config API Port](https://www.curseforge.com/minecraft/mc-mods/forge-config-api-port).

## [1.6.0] - 2026-11-06
### Added
- Horizon first: in singleplayer, ground you have not seen shows at once. The whole view fills with coarse terrain within
  about 3 seconds of joining a new world, then sharpens pass by pass, starting with what you look at. In test runs (6-core PC,
  LOD radius 256 chunks) half the view took 11 seconds before and all of it 32; the picture is now 95% final after 15 seconds
  instead of 28, and every tile is done in about 27.
- Saved chunks show in the distance: builds, adventure maps and pregenerated terrain reach Distant Horizons as the world
  saved them, nearest first, in the background. Before, an existing world showed the seed's terrain until you went there.
- `/dhturbo regenerate` for operators and the host: deletes the world's distant terrain, which is then drawn again.
  Terrain drawn by an older version keeps its old look until then.
- Exact terrain near you: while the generator has nothing else to do, the chunks around you are redrawn by the game's
  real generator in the background: caves, ores on cliffs, lakes above sea level, trees and plants. In test runs 92 to
  95% of columns then matched the real world. It waits while the server is busy or you move fast. Config
  `exact_radius` (chunks, default 16, 0 = off); dedicated servers opt in with `exact_radius_server`. Chunks the world has
  saved are left as they are.
- Distant End islands get their chorus and small islands, and the distant Nether its huge fungi, vines, basalt and glowstone:
  the game's own features, with its own seeds, up to 4-block columns in the End and 2-block columns in the Nether.
- The game's tree placement is replayed up to 4-block columns (before: full detail only): about 7 in 10 tree columns land
  on the real spot at full detail, over half at 4-block columns, jungles far fewer.
- A line on the F3 screen shows what the generator is doing, the exact pass included.
- A line in chat for whoever runs the world when the mod cannot read a dimension's terrain and leaves it to Distant
  Horizons.
### Changed
- Distant Horizons does far less work per tile in singleplayer: a tile no longer rewrites and rebuilds its parents, and a
  section is rebuilt once when the tiles around it have settled, not once for each of them.
- Forests on 2- and 4-block columns are as dense as real ones in Distant Horizons: wide columns take wood and leaves from
  their whole width, not only from their corner.
- Needs Distant Horizons 3.3.3 or a later 3.3 version: it reaches into Distant Horizons, and other versions are untested.
- A server with Distant Horizons no longer sends players the world seed: it sends them the distant terrain anyway.
- The generator is 1.8x as fast in Distant Horizons' own pregeneration with a third less CPU time, and fills the
  horizon about 20% sooner, with more trees and exact heights included: terrain and biomes now share what they compute
  per column. With Terralith and Tectonic tiles take less than half the time.
- Distant heights are exact: every column gets its own height instead of a blend of its neighbours. On land, 4-block
  columns match the real world on 94% of columns instead of 56%, 16-block columns on 96% instead of 42%.
- Distant biomes follow the game's own blur at their borders: they differ on 0.3% of columns, before 2 to 17%, so grass
  and water colours line up.
- `threads` now defaults to two thirds of the CPU cores (was half): the generator runs below normal priority, and it
  measured 12% faster.
- `full_resolution` now only adds the pull of caves on columns up to 4 blocks wide; every column has its own height
  anyway.
- The accuracy figures are measured more fairly: real and generated columns go through the same check, and blocks are
  also compared one by one.
### Fixed
- Structures in the Nether and the End no longer count as lit by a sky those worlds do not have.
- On some starts the mod did nothing: Distant Horizons picked its own generator before this one was ready and kept it for
  the session, so the distance came out bare and slow with no message. In test runs on a fast PC this happened on one
  start in four, and more often the longer the world took to load. Distant Horizons now waits for the choice.
- A Distant Horizons generator plan set to "disabled", or "chunks only" with existing chunks only, is respected: nothing
  is generated, also not by this mod.
- The End keeps its detail around the islands: sections of pure void counted as not generated yet, so the coarse terrain
  next to them never gave way to finer terrain.
- After leaving a dimension and coming back on a server, structures around you are prepared ahead again.
- Distant trees keep their shape: branches no longer grow into walls of logs down to the ground, bushes no longer join
  the crowns above them, and the leaves of far forests no longer reach the ground.
- Trunks no longer stand as thick as a wide LOD column: columns over 2 blocks wide show crowns only.

## [1.5.0] - 2026-10-26
### Added
- [ReTerraForged: From the Ashes](https://www.curseforge.com/minecraft/mc-mods/reterraforged-from-the-ashes) support:
  its continents, mountains and rivers now show in the distance as it generates them. Before, the distant terrain of
  its worlds did not match the real one at all. Its erosion is not drawn, so distant land can sit a few blocks higher than the real chunks.
  In multiplayer the server draws these worlds; players do not generate them themselves.
- Worlds whose terrain the mod cannot read (for example a mod with its own chunk generator) are detected when they
  load and left to the Distant Horizons generator, with a line in the log saying why, instead of being drawn wrong.
### Fixed
- Near you, forests of terrain mods with trees of their own (ReTerraForged) were bare in the distance: they get the
  mod's tree shapes now.

## [1.4.0] - 2026-10-25
### Added
- Config `excluded_dimensions`: dimensions listed there (by id, for example `minecraft:the_end` or a mod's dimension)
  get their distant terrain from the Distant Horizons generator, the rest still from this mod. Works in singleplayer,
  on servers and for clients that generate the distance themselves, and takes effect without restarting the game.
### Removed
- The "DH Turbo: ... tiles" status line. It showed up on the screen even with the debug screen (F3) closed.

## [1.3.0] - 2026-10-16
### Added
- Structures in the distance: villages, pillager outposts, desert and jungle temples, witch huts, igloos, woodland
  mansions, ocean monuments, shipwrecks, ocean ruins, ruined portals and the structures of mods and data packs are
  built where and as the game will build them, with the same buildings, streets and blocks. Villages flatten the ground
  under their houses like in the real world. Shown up to 16 blocks per distant column. Config `structures`.
- Structures also show when the game generates the distance itself in multiplayer.
- The Nether and the End: distant caverns, the lava sea, soul sand valleys, nylium forests and basalt deltas under the
  bedrock roof, floating End islands, and their fortresses, bastions and end cities, block for block. Before, the
  Nether only got Distant Horizons' flat rough surface.
- The game's own grass, flowers, sugar cane and pumpkins near you: the vegetation is replayed where the game puts it,
  so the distant meadow no longer changes when the real chunks load.
- Structures are light on the CPU: wide distant columns get a lighter build of the same structures, and idle worker
  threads prepare the structures around you before Distant Horizons asks for their tiles. The F3 line shows how many
  areas are ready and how many CPU cores the generator is using.
- Config `real_chunks_nearby`: follow the Distant Horizons generator plan instead of switching it to "surface only".
  With its default plan, detailed terrain near you is drawn fast first and then replaced by real chunk generation.
### Changed
- The speed figures in the description are measured in Distant Horizons' own pregeneration now: 3.7x faster than its
  default chunk generation and 4.7x faster than its rough surface mode on the same world. The earlier "17-70x" timed
  the terrain search alone, before trees, plants, structures and surface rules were added.
### Fixed
- Snowy ground showed grass-green stripes in the distance: soft plant colours treated snow layers like grass and
  mixed in only a little of their white. Snow layers keep their full colour now.
- End islands were drawn as pillars down to the bottom of the world; they float now.
- Buried structures of data packs (Terralith's underground cabins) are no longer built under the distant ground.

## [1.2.0] - 2026-10-13
### Added
- A line on the F3 debug screen shows the generator at work: tiles made since the game started, tiles per second, time
  per tile and tiles in progress. It counts the distant terrain made in singleplayer and on servers that share their
  world generation.

## [1.1.0] - 2026-10-12
### Added
- Grass and flowers on distant ground: meadows, plains and flower forests get grass and the biome's own flowers, with
  tulip fields and flower patches where the game puts them. Config `ground_cover`.
- Lava lakes in the distance, where and as the game places them.
- Distant ground is covered by the world's own surface rules, also those of data packs and terrain mods: gravel
  shores, calcite, coarse dirt, powder snow, mud, badlands clay bands and hoodoos, icebergs in frozen oceans - the same
  blocks as up close (98.5% of columns in a comparison with real generation, up from 96.7%). Config
  `vanilla_surface` (off: one typical block per biome, a little faster).
### Changed
- Flowers and other small plants no longer paint the whole block under them in the distance (a poppy made a red block,
  a dandelion a yellow one): their colour is blended softly into the ground, grass shades it a little, and the ground
  keeps its material for shaders. Works for all distant terrain, also the one built from real chunks. Config
  `soft_plant_colors`.
### Fixed
- Distant terrain heights match the real world much more closely (exact on 98% of columns near the player, up from
  70%): the terrain between the game's sample points is now interpolated the way the game does it, with the same values
  the game uses. Fewer phantom shores, hills and dips that change when real chunks load; grassy shores no longer turn
  into water. With `full_resolution` on, the next level of detail is exact on 98% of columns too.
- Snow in the distance now matches the real world (98% of snowy columns in a comparison with real generation, up from
  70%): cold ground keeps its grass or rock under a thin snow layer like the game's, tree crowns get snow too, and
  mountain slopes turn to bare rock or packed ice only where the game's own rule says so.
- Powder snow fields no longer turn grey in the distance with soft plant colours on.
- Steep slopes, ravines and cliffs no longer turn into bare stone in the distance and grass up close: the top keeps the
  biome's block like in the real world (bare rock only on snowy mountain slopes), and the walls show the layers under
  it (dirt, sandstone, clay bands, then stone).
- Jungle trees near the player failed to appear in distant terrain, and trees could grow inside each other: trees are
  now placed on the terrain as the game does, with trees already standing there taken into account.

## [1.0.0] - 2026-10-09
### Added
- Distant terrain for [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) generated
  straight from the world noise, without generating chunks: many times faster than the Distant Horizons generator.
- Each terrain tile is generated on all worker threads at once.
- The game's own trees on detailed terrain near the player; species-shaped trees in distant forests.
- Snow, ice, bare rock on steep slopes, badlands terracotta bands and biome-specific seabeds in the distance.
- Distant terrain no longer vanishes for a moment while Distant Horizons switches it to more detail.
- Multiplayer: a server with this mod shares its world generation with players who have it, and their game generates
  the distant terrain itself, also on servers without Distant Horizons.
- Config: generator on/off, worker threads, full resolution, distant trees, real trees, sharing the world generation.
