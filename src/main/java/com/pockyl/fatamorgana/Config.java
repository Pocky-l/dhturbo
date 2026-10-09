package com.pockyl.fatamorgana;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Replace the Distant Horizons world generator with the Fata Morgana surface generator.",
                    "Takes effect when a world is loaded.")
            .translation("fatamorgana.configuration.enabled")
            .define("enabled", true);

    public static final ModConfigSpec.IntValue THREADS = BUILDER
            .comment("Worker threads of the generator, 0 = half of the CPU cores.",
                    "One LOD tile is split across all of them. Takes effect after a game restart.")
            .translation("fatamorgana.configuration.threads")
            .defineInRange("threads", 0, 0, 64);

    public static final ModConfigSpec.BooleanValue FULL_RESOLUTION = BUILDER
            .comment("Compute the height of every LOD column everywhere instead of one in four (the rest is interpolated).",
                    "About 3x slower, sharper distant cliffs.")
            .translation("fatamorgana.configuration.full_resolution")
            .define("full_resolution", false);

    public static final ModConfigSpec.BooleanValue FAKE_TREES = BUILDER
            .comment("Add approximate tree canopies to forests, so they are not bald until real chunks load.")
            .translation("fatamorgana.configuration.fake_trees")
            .define("fake_trees", true);

    public static final ModConfigSpec.BooleanValue REAL_TREES = BUILDER
            .comment("On detailed LODs near the player, replay the game's own tree placement: trees stand where and as",
                    "they will in the real world. Otherwise (and farther away) trees are approximate.")
            .translation("fatamorgana.configuration.real_trees")
            .define("real_trees", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static int threadCount() {
        int configured = THREADS.get();
        return configured > 0 ? configured : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }
}
