# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - Unreleased
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
