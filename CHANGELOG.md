# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] - Unreleased
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
