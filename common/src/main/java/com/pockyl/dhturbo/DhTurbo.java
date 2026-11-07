package com.pockyl.dhturbo;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import com.pockyl.dhturbo.compare.GenerationComparison;
import com.pockyl.dhturbo.compare.PregenBenchmark;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.network.WorldgenSharing;
import com.pockyl.dhturbo.platform.LoaderInfo;
import com.pockyl.dhturbo.platform.Platform;
import com.pockyl.dhturbo.world.TerrainSupport;

/** The mod's start, shared by the loaders; their entry points call {@link #init} once the config is registered. */
public final class DhTurbo {
    public static final String MOD_ID = "dhturbo";
    public static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Developer tools: demo keys and diagnostics logging. On in development runs (the workspace playtest client) or
     * with {@code -Ddhturbo.debug=true}; players never see them.
     */
    public static final boolean DEBUG = LoaderInfo.INSTANCE.isDevelopment() || Boolean.getBoolean(MOD_ID + ".debug");

    private DhTurbo() {
    }

    public static void init() {
        WorldgenSharing.register();
        TerrainSupport.register();
        GenerationComparison.registerIfRequested();
        PregenBenchmark.registerIfRequested();
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            DhIntegration.register();
        } else {
            LOGGER.info("Distant Horizons is not installed: DH Turbo only shares the world generation with players");
        }
    }

    /** The generator plan follows real_chunks_nearby as soon as it is changed in game. */
    public static void configReloaded() {
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            try {
                DhIntegration.applyPlan();
            } catch (RuntimeException e) {
                // Distant Horizons is not ready yet (no world); the plan is applied when a level loads.
            }
        }
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
