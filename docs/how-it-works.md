# How Turbo for Distant Horizons works

Distant Horizons (DH) asks a world generator for level-of-detail tiles: a square of columns, each column a list of
block runs from the bottom of the world to the top. This mod registers such a generator for every noise-based level
(`IDhApiWorldGenerator`, return type `API_DATA_SOURCES`) and fills the tiles without generating a single chunk. This
page explains how, and how to check the result yourself.

All source files named here are in `src/main/java/com/pockyl/dhturbo/`.

## 1. Terrain height without chunks (`surface/SurfaceSampler`, `surface/SurfaceTile`)

Vanilla terrain is a 3D density function: a block is solid where the density is positive. Chunk generation evaluates
it on a lattice of cell corners (every 4 blocks across, every 8 blocks up in the overworld) and interpolates between
them. Evaluated point by point outside of chunk generation, the density is slow: its cache markers do nothing there,
and caves make every column expensive. Three things make it cheap here:

- **Column caches.** `ColumnCachingVisitor` rewrites the density function so the 2D parts (continentalness, erosion,
  the terrain splines) are computed once per column, exactly as vanilla's `flat_cache` and `cache_2d` would inside a
  chunk. Every further probe in the column pays only for the 3D noises.
- **No caves in the search.** `CaveStripper` replaces the cave density functions with a constant, and the world's
  noise settings are rebuilt with that density through `RandomState.create` with the same seed, so every noise is
  wired exactly as in the real world.
- **Probing on cell corners only.** The surface is searched on vanilla's cell corners, starting at the height of the
  neighbouring column (2-3 probes per column), and the value probed is the one vanilla interpolates (inside its
  `interpolated` marker), not the squeezed final density. Columns between corners interpolate the corner densities
  trilinearly, as vanilla does, using the real values with caves.

Result: heights equal the real world on 98% of full-detail columns (the rest are mostly cave openings, which the
distant terrain leaves closed).

## 2. The world's own surface (`surface/VanillaSurface`)

The world's surface rules (`NoiseGeneratorSettings.surfaceRule`, also those of data packs and terrain mods) run on a
virtual column laid out as the noise step leaves it: rock to the ground, water to sea level. The loop is the same as
`SurfaceSystem.buildSurface`, with a real `SurfaceRules.Context`, so grass, sand, gravel shores, calcite, powder snow,
badlands bands, hoodoos and icebergs come out as in the real world. Snow layers and ice follow vanilla's
`freeze_top_layer`.

## 3. Trees and plants (`surface/RealTrees`, `surface/VirtualLevel`)

Vanilla decorates a chunk by seeding one random per feature from the world seed, the chunk position and the feature's
index in its generation step. Seeds do not depend on other features, so the vegetation of a chunk can be replayed on
its own. Near the player the mod replays every surface vegetation feature (trees, grass, flowers, sugar cane, pumpkins)
with vanilla's seeds on a `VirtualLevel`: a `WorldGenLevel` implementation that reads the computed terrain and records
the blocks placed. Trees therefore stand where the game will grow them, with the same species and shape. Farther away,
where one column covers several blocks, trees and plants are approximated per biome.

## 4. Structures (`surface/RealStructures`)

- **Where.** The game's own structure placement: `ChunkGeneratorStructureState` with the world's structure sets, the
  same weighted pick per chunk as `ChunkGenerator.createStructures`, and `Structure.generate` with the same seed. A
  structure exists in the distance exactly when it exists in the world.
- **What.** The pieces are built chunk by chunk on a `VirtualLevel`, with the random each chunk gives the structure
  in the `SURFACE_STRUCTURES` step, so villages get the same houses, streets and blocks.
- **The ground under them.** Structures that adapt the terrain (villages flatten the ground under their houses) get
  vanilla's `Beardifier` added to the final density, block by block, exactly as chunk generation adds it.
- **Two builds.** Columns up to 2 blocks wide get the exact build above. Wider columns get a coarse one: terrain
  sampled every 8 blocks (every 4, with vanilla's cave-aware heights, for structures up to 64 blocks across, which
  settle on the averaged ground), and under each piece that adapts the terrain the ground set to the piece's floor
  instead of the `Beardifier` block by block. On every 4th column its top is within 2 blocks of the exact build on 93%
  of columns, at a fraction of the cost. Most structures are only ever seen in wide columns.
- Heights asked during placement are answered by `SampledHeightGenerator` from the sampler instead of computing the
  whole column, and remembered per column: a village asks the same columns again for every piece it tries.
- Ocean monuments check about 3000 biomes around them before they start, mostly on land; 27 of the same samples
  reject nearly every chunk first.

Built structures are cached and shared by every tile and detail level.

## 5. The Nether and the End (`surface/ProfileTerrain`)

These worlds are not a height field: the Nether has caverns under a ceiling, the End floating islands. Their columns
are built from the full vertical profile: the density on every cell level of the corners, interpolated to every
block, then the surface rules over the whole column (netherrack, soul sand valleys, nylium, basalt deltas, bedrock
floor and roof). Fortresses, bastions and end cities are built as in section 4.

## 6. Speed

- One tile is split over every worker thread (fork-join), so a single request uses the whole CPU.
- Structures, the most expensive part, are built once and cached.
- **Prepared ahead** (`surface/StructurePrefetch`). Distant Horizons asks for tiles at its own pace (saving and
  meshing set it), so the workers are idle much of the time. That time goes to the structures around the players:
  areas 256 blocks wide, nearest first, out to where tiles show structures. They land in the same caches, so the tile
  that needs them later does not wait. It pauses while tiles are queued or half of the workers are busy.
- Optionally (`real_chunks_nearby`), detailed terrain near the player is first drawn this way and then handed to
  Distant Horizons' own chunk generator, following the player's Distant Horizons generator plan.

## Check it yourself

The comparison tool generates real chunks with the game and compares them with the columns this mod builds, column
by column and block by block:

```
./gradlew runCompareServer [-PcompareAreas=48] [-PcompareSpacings=1,4]
```

It starts a dedicated server on a fixed seed in `run/compare/`, writes `run/compare/dhturbo-compare.txt` and stops.
To include terrain mods, put their jars in `run/compare/mods/` and add `-PcompareWorld=<new world name>`.

Results on 2026-10-15 (seed 20261009, 48 random areas within 20 000 blocks):

| What | Full-detail columns | 4-block columns |
|---|---|---|
| Terrain height exact | 98.2% | 80.6% (97% with `full_resolution`) |
| Top block kind | 98.2% | 97.1% |
| Snowy columns | 99.7% | 100% |

| Structures (12 per world, real chunks generated under them) | Result |
|---|---|
| Overworld: start and area as in the real world | 12 / 12; top block of their columns 89.8% (the rest is vegetation that grows over them later) |
| Nether: fortresses, bastions, fossils, ruined portals | 12 / 12; 99.4% of 148 480 placed blocks identical |
| End: end cities | 12 / 12; 100% of 115 757 placed blocks identical |

| Worlds without a single surface | Blocks solid or open as in the real world |
|---|---|
| Nether | 97.2% (the rest: fungi, basalt and blackstone features) |
| End | 100% |

With [Terralith](https://www.curseforge.com/minecraft/mc-mods/terralith) and
[Tectonic](https://www.curseforge.com/minecraft/mc-mods/tectonic): top block kind 94.2%, land heights exact 95.7%.

With [ReTerraForged: From the Ashes](https://www.curseforge.com/minecraft/mc-mods/reterraforged-from-the-ashes)
0.0.22 (its default preset): top block kind 90.5%, heights exact 60.3% and within 4 blocks 99%. Its terrain comes from
its own heightmap, which the generator reads; the erosion it adds while chunks generate is not drawn, so land comes out
1-4 blocks higher and eroded slopes keep their grass.

Before drawing a world the generator compares 32 of its columns with the world's own generator (heights within 6
blocks; the Nether and the End block by block) and needs 75% to agree. Vanilla and ReTerraForged worlds agree on 32 of
32; the ReTerraForged world read without its heightmap (what 1.4.0 drew) on 0, amplified terrain checked against the
normal overworld on 18. Worlds that fail are left to Distant Horizons' own generator.

### Speed

In Distant Horizons' own pipeline: `/dh pregen` on a dedicated server with Distant Horizons 3.3.3 (its 6 world
generator threads), 64 chunks around 0 0 (16 384 chunks, full detail), seed 20261009, a fresh world per run:

```
./gradlew runPregenServer -PpregenRadius=64 [-PpregenLabel=<name>]
```

| Generator | Time | Chunks per second | CPU time |
|---|---|---|---|
| This mod | 35.1 s | 467 | 236 s |
| This mod, `structures = false` | 33.1 s | 496 | 231 s |
| Distant Horizons, default plan (real chunks) | 131.1 s | 125 | 821 s |
| Distant Horizons, `SURFACE_ONLY` (rough surface) | 164.2 s | 100 | 989 s |

Switch the mod off (`enabled = false`) and set Distant Horizons' `generatorPlan` for the other rows. Full detail is
the costliest part of the horizon; farther out the mod samples fewer columns, while the default plan still generates
every chunk.

Filling the horizon around a player, without Distant Horizons (the generator alone):

```
./gradlew runCompareServer -PcompareHorizon=4096 [-PcompareHorizonRate=20] [-PcomparePrefetch=true]
```

fills the horizon around three fresh spawns the way Distant Horizons asks for it: tiles of 64 columns, finer near the
player, nearest first, as many in flight as Distant Horizons allows. Results on 2026-10-15, 6 worker threads, 4096
blocks around the player:

| | Horizon filled |
|---|---|
| Terrain only (`structures = false`) | 4.1-6.0 s |
| With structures, first version | 12.9-15.2 s |
| With structures, two builds and cheaper searches | 7.2-8.9 s |

At Distant Horizons' pace in game (20 tiles a second here), the idle time prepares the structures ahead. Time per tile
1-2 km out (detail 3): p95 310-710 ms without, 40-45 ms with it.
