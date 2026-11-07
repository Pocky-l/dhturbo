package com.pockyl.dhturbo;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Generate distant terrain with Turbo for Distant Horizons instead of the Distant Horizons generator.")
            .translation("dhturbo.configuration.enabled")
            .define("enabled", true);

    public static final ForgeConfigSpec.IntValue THREADS = BUILDER
            .comment("Worker threads of the generator, 0 = two thirds of the CPU cores.",
                    "One LOD tile is split across all of them. Takes effect after a game restart.")
            .translation("dhturbo.configuration.threads")
            .defineInRange("threads", 0, 0, 64);

    public static final ForgeConfigSpec.BooleanValue FULL_RESOLUTION = BUILDER
            .comment("Let caves shape the height of LOD columns up to 4 blocks wide everywhere, as they do at cell corners.",
                    "Every column already has its own height; this changes few of them and costs about twice the time.")
            .translation("dhturbo.configuration.full_resolution")
            .define("full_resolution", false);

    public static final ForgeConfigSpec.BooleanValue FAKE_TREES = BUILDER
            .comment("Add approximate tree canopies to forests, so they are not bald until real chunks load.")
            .translation("dhturbo.configuration.fake_trees")
            .define("fake_trees", true);

    public static final ForgeConfigSpec.BooleanValue REAL_TREES = BUILDER
            .comment("On detailed LODs near the player, replay the game's own tree placement: most trees stand where and as",
                    "they will in the real world. Otherwise (and farther away) trees are approximate. Needs fake_trees.")
            .translation("dhturbo.configuration.real_trees")
            .define("real_trees", true);

    public static final ForgeConfigSpec.BooleanValue GROUND_COVER = BUILDER
            .comment("Put grass and the biome's flowers on generated distant ground. Distant Horizons shows them as a tint of",
                    "the ground, so meadows and flower fields look like they do up close.")
            .translation("dhturbo.configuration.ground_cover")
            .define("ground_cover", true);

    public static final ForgeConfigSpec.BooleanValue VANILLA_SURFACE = BUILDER
            .comment("Cover generated distant ground with the world's own surface rules (also those of data packs and mods):",
                    "gravel shores, calcite, coarse dirt, powder snow, badlands bands and hoodoos, icebergs - like up close.",
                    "Off: one typical block per biome, a little faster.")
            .translation("dhturbo.configuration.vanilla_surface")
            .define("vanilla_surface", true);

    public static final ForgeConfigSpec.BooleanValue REAL_CHUNKS_NEARBY = BUILDER
            .comment("Follow the Distant Horizons generator plan instead of switching it to 'surface only'. With its default plan",
                    "('surface then chunks') detailed terrain near you is first drawn fast by this mod, then Distant Horizons",
                    "replaces it with real chunk generation (every feature, slower, uses more CPU). Off: everything stays fast.",
                    "Singleplayer and servers with Distant Horizons; clients that generate the distance themselves stay fast.")
            .translation("dhturbo.configuration.real_chunks_nearby")
            .define("real_chunks_nearby", false);

    public static final ForgeConfigSpec.IntValue EXACT_RADIUS = BUILDER
            .comment("Chunks around you that get the world's real terrain in the background: caves, ores on cliffs, trees and",
                    "plants. Runs while the generator has nothing else to do, waits while the server is busy or you move fast.",
                    "Chunks inside your render distance are real anyway, so a radius below it does nothing. 0 = off. Singleplayer.")
            .translation("dhturbo.configuration.exact_radius")
            .defineInRange("exact_radius", 16, 0, 256);

    public static final ForgeConfigSpec.IntValue EXACT_RADIUS_SERVER = BUILDER
            .comment("The same on a dedicated server with Distant Horizons, around every player online. 0 = off: it takes",
                    "CPU from the server, and thread priorities do nothing on most Linux hosts.")
            .translation("dhturbo.configuration.exact_radius_server")
            .defineInRange("exact_radius_server", 0, 0, 256);

    public static final ForgeConfigSpec.BooleanValue STRUCTURES = BUILDER
            .comment("Build the world's structures on distant terrain: villages, outposts, temples, mansions, shipwrecks,",
                    "ruined portals and structures of mods, where and as the game will build them. Underground structures",
                    "are left out. Shown up to 16 blocks per LOD column.")
            .translation("dhturbo.configuration.structures")
            .define("structures", true);

    public static final ForgeConfigSpec.BooleanValue SOFT_PLANT_COLORS = BUILDER
            .comment("Client: when Distant Horizons tints the ground with the plants on it, blend their colour in softly instead",
                    "of painting the whole block (a poppy no longer turns a grass block red). Also for terrain from real chunks.",
                    "Takes effect for LODs built after the change (re-enter the world).")
            .translation("dhturbo.configuration.soft_plant_colors")
            .define("soft_plant_colors", true);

    public static final ForgeConfigSpec.BooleanValue SHARE_WORLDGEN = BUILDER
            .comment("Server without Distant Horizons: send players with this mod the world seed and generation settings, so",
                    "their client generates distant terrain itself. Turn off if players must not know the seed. A server with",
                    "Distant Horizons never sends them: it sends the distant terrain.")
            .translation("dhturbo.configuration.share_worldgen")
            .define("share_worldgen", true);

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_DIMENSIONS = BUILDER
            .comment("Dimensions whose distant terrain this mod leaves to the Distant Horizons generator, by id, for example",
                    "[\"minecraft:the_end\", \"twilightforest:twilight_forest\"]. That generator then draws its rough surface; for",
                    "real chunks turn on real_chunks_nearby too. Applies to terrain generated from then on.")
            .translation("dhturbo.configuration.excluded_dimensions")
            .defineListAllowEmpty(List.of("excluded_dimensions"), List::of,
                    value -> value instanceof String id && ResourceLocation.tryParse(id) != null);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    /** Whether DH Turbo generates the distant terrain of this dimension right now. */
    public static boolean generates(ResourceKey<Level> dimension) {
        return ENABLED.get() && !EXCLUDED_DIMENSIONS.get().contains(dimension.location().toString());
    }

    /** Chunks around players that get real terrain; the setting depends on who pays for the CPU. */
    public static int exactRadius(boolean dedicatedServer) {
        return (dedicatedServer ? EXACT_RADIUS_SERVER : EXACT_RADIUS).get();
    }

    public static int threadCount() {
        int configured = THREADS.get();
        return configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() * 2 / 3);
    }
}
