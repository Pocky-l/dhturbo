# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

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
