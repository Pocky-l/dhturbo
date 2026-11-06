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

Terrain and biomes read the same noises (continentalness, erosion, ridges): one set of column caches serves both,
as vanilla's noise chunk does, so each is computed once per column.

Wider columns stand on cell corners, where the search gives the real height directly, so every column gets its own
search instead of a blend of its neighbours.

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
its own. Up to 4-block columns the mod replays every surface vegetation feature (trees, grass, flowers, sugar cane,
pumpkins) with vanilla's seeds on a `VirtualLevel`: a `WorldGenLevel` implementation that reads the computed terrain and
records the blocks placed. Trees get the game's species and shape, and most of them its spot: columns with a tree here or
in the real world agree on 71% at 1-block columns and 54% at 4-block columns, in jungles far less (all trees of a chunk
share one random, so one difference moves every later tree); a wide
column shows what stands on its corner, block by block with the air between, except trunks: a column over 2 blocks wide
would make them as thick as itself, so it shows the crown only. Farther away trees and plants are approximated per biome.

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

## 6. Exact terrain near the player (`surface/ExactChunks`, `dh/ExactRefiner`)

The sections above are a fast approximation. While the generator has nothing else to do, the chunks within `exact_radius`
of a player are generated for real: biomes, noise, surface, carvers, features and structures run as in chunk generation,
on proto chunks of our own, without the chunk map, lighting or saving, and the real level never hears of their blocks
(a vanilla region would register their beds and bells as villager points of interest). Decorating a chunk needs its neighbours up to the
carver step, so chunks come in batches of 8 x 8 sharing a two-chunk border (2.25 proto chunks per chunk; the idea is from
[Horizon](https://github.com/horizon-rust/horizon)). Chunks three apart are decorated in parallel. They reach Distant
Horizons through its API for real chunks, so it lights and stores them like chunks a player loaded, and chunks it
already holds as real are left alone. Those include everything inside the render distance, so a radius below it does
nothing. A chunk costs about 75 ms of CPU, against about 6 ms for the fast tile, so this runs on half of the workers at
the lowest thread priority (which most Linux hosts ignore) and only while ticks stay under 35 ms and no player has moved
faster than 10 blocks a second for 3 seconds. `exact_radius` (default 16) counts for singleplayer; dedicated servers pay
for it with their tick time and opt in with `exact_radius_server`.

Before any of that come the chunks the world has saved (`surface/SavedChunks`). The seed knows nothing of builds or of
terrain from another generator, so each chunk on disk that Distant Horizons does not hold yet is handed to it as it is,
nearest first, out to the LOD distance, through the same API. This does not wait for tiles and does not depend on
`exact_radius`, and the exact pass never replaces a saved chunk. The game's own chunk reader cannot be used: it reports
every section to the running level's points of interest and light engine, so blocks and biomes are decoded separately.

## 7. Speed

- One tile is split over every worker thread (fork-join), so a single request uses the whole CPU.
- Structures, the most expensive part, are built once and cached.
- **Prepared ahead** (`surface/StructurePrefetch`). Distant Horizons asks for tiles at its own pace (saving and
  meshing set it), so the workers are idle much of the time. That time goes to the structures around the players:
  areas 256 blocks wide, nearest first, out to where tiles show structures. They land in the same caches, so the tile
  that needs them later does not wait. It pauses while tiles are queued or half of the workers are busy.
- **Horizon first** (`dh/HorizonFill`, `mixin/LodQuadTreeMixin`). A tile costs about the same at every detail level, so
  the coarsest tiles buy the most land. On ground without stored tiles (singleplayer) Distant Horizons is told to want
  every section several levels coarser than it would: about 25 tiles cover the view. The wish comes down one level per
  pass, once nearly all sections of the pass are on screen. Each pass halves the column width everywhere at once, so the near
  ground sharpens first, and tiles are ordered from a point ahead of the player, so what the player looks at comes first.
  A section replaces its parent when all four have terrain. Tiles a later pass replaces are previews: terrain and surface
  only, marked below the step Distant Horizons needs, so it asks for the real tile if it ever wants that section for good.
- **Less work in Distant Horizons.** Measured in the game, rebuilding sections took as much CPU as generating them. A tile
  used to update up to eight parent levels and fill its children with stretched columns, and every update rebuilt the
  section and its four neighbours. In singleplayer a tile now stays in its own section, and neighbours are rebuilt once
  the tiles around them have settled. Where the detail level changes, the section next door often has no data then, which
  would read as open air and draw a wall; it is read from the section above it instead, after the fill, for the edge only.
  While the view fills, the workers run at Distant Horizons' own thread priority and
  more tiles are under way at once.
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

Results on 2026-11-06 (seed 20261009, 48 random areas within 20 000 blocks; real and generated columns go through the
same check):

| What | 1 block | 4 blocks | 8 blocks | 16 blocks | Exact pass |
|---|---|---|---|---|---|
| Height exact, all columns | 98.1% | 96.3% | 97.5% | 98.6% | 100% |
| Height exact, land above y 66 | 97.6% | 93.6% | 95.4% | 96.3% | 100% |
| Top block kind | 98.3% | 97.7% | 98.6% | 99.3% | 100% |
| Biome | 99.7% | 99.7% | 99.7% | 99.5% | 99.2% |
| Blocks from 8 under the ground up | 99.3% | 99.1% | 98.5% | 98.6% | 99.75% |

Exact pass: 6 to 8 areas, `-PcompareExact=1`, on the chunks as generated. Its biome line compares one block lower than
the real column. Stored in Distant Horizons, which is what you see in game, runs of 128 chunks (`-PpregenExact=true`)
gave the real top block and height, trees included, on 92.5 to 95.3% of columns.

No generator can reach 100% of the blocks. The game's own result depends on the order chunks are generated in, because
trees of neighbouring chunks overlap: the same chunks decorated in opposite orders agree on 99.73% of all blocks and on
68% of whole columns. The exact pass stands at 99.72% against real chunks, which is that limit; its ground is identical.

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
[Tectonic](https://www.curseforge.com/minecraft/mc-mods/tectonic): top block kind 93.5%, heights exact 98.1%, land 95.1%.

With [ReTerraForged: From the Ashes](https://www.curseforge.com/minecraft/mc-mods/reterraforged-from-the-ashes)
0.0.22 (its default preset): top block kind 90.5%, heights exact 60.3% and within 4 blocks 99%. Its terrain comes from
its own heightmap, which the generator reads; the erosion it adds while chunks generate is not drawn, so land comes out
1-4 blocks higher and eroded slopes keep their grass.

Before drawing a world the generator compares 32 of its columns with the world's own generator (heights within 6
blocks; the Nether and the End block by block) and needs 75% to agree. Vanilla and ReTerraForged worlds agree on 32 of
32; the ReTerraForged world read without its heightmap (what 1.4.0 drew) on 0, amplified terrain checked against the
normal overworld on 18. Worlds that fail are left to Distant Horizons' own generator.

### Speed

Filling the view of a new world, in the game: fresh world, seed 20261009, 300 blocks up, LOD radius 256 chunks, 6 Distant
Horizons threads, 6-core / 12-thread PC, 3 runs each in turns, medians. Seconds after joining:

| | This mod | This mod, before |
|---|---|---|
| Half of the land on screen | 3 or less | 11 |
| 99% of the land on screen | 3 or less | 32 |
| 95% of the picture as it ends up | 15 | 28 |

Every tile is done after about 27 seconds. The first frames are coarse: 32-block columns for a moment. With a limit of 60 FPS
the frame rate stays at 58 (lowest 52).

In Distant Horizons' own pipeline: `/dh pregen` on a dedicated server with Distant Horizons 3.3.3 (its 6 world
generator threads), 64 chunks around 0 0 (16 384 chunks, full detail), seed 20261009, a fresh world per run:

```
./gradlew runPregenServer -PpregenRadius=64 [-PpregenLabel=<name>]
```

| Generator | Time | Chunks per second | CPU time |
|---|---|---|---|
| This mod | 18 s | about 900 | 146 s |
| This mod 1.5.0 | 33 s | about 500 | 224 s |
| Distant Horizons, default plan (real chunks) | 131 to 214 s | 77 to 125 | 821 to 1081 s |
| Distant Horizons, `SURFACE_ONLY` (rough surface) | 164 to 197 s | 83 to 100 | 989 to 1137 s |

Medians of six runs alternating with the old version, on a machine doing other work too; single runs vary by 20%. The
Distant Horizons rows are the two runs we have, the faster one from 2026-10-16.

Switch the mod off (`enabled = false`) and set Distant Horizons' `generatorPlan` for the other rows. Full detail is
the costliest part of the horizon; farther out the mod samples fewer columns, while the default plan still generates
every chunk.

Filling the horizon around a player, without Distant Horizons (the generator alone):

```
./gradlew runCompareServer -PcompareHorizon=4096 [-PcompareHorizonRate=20] [-PcomparePrefetch=true]
```

fills the horizon around three fresh spawns the way Distant Horizons asks for it: tiles of 64 columns, finer near the
player, nearest first, as many in flight as Distant Horizons allows. 6 worker threads in 1.5.0, 8 in this version, 4096
blocks around the player:

| | Horizon filled |
|---|---|
| Terrain only (`structures = false`), 1.3.0 | 4.1-6.0 s |
| With structures, first version | 12.9-15.2 s |
| With structures, 1.5.0 | 7.4-9.6 s |
| With structures, more trees and exact heights, this version | 5.1-8.7 s |

At Distant Horizons' pace in game (20 tiles a second here), the idle time prepares the structures ahead. Time per tile
1-2 km out (detail 3): p95 310-710 ms without, 40-45 ms with it.
