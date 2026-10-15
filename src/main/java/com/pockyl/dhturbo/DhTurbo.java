package com.pockyl.dhturbo;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import com.pockyl.dhturbo.compare.GenerationComparison;
import com.pockyl.dhturbo.compare.PregenBenchmark;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.network.WorldgenSharing;

@Mod(DhTurbo.MOD_ID)
public final class DhTurbo {
    public static final String MOD_ID = "dhturbo";
    public static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Developer tools: demo keys and diagnostics logging. On in development runs (the workspace playtest client) or
     * with {@code -Ddhturbo.debug=true}; players never see them.
     */
    public static final boolean DEBUG = !FMLEnvironment.production || Boolean.getBoolean(MOD_ID + ".debug");

    // The no-argument constructor keeps the jar loadable on NeoForge for 1.20.1 as well.
    public DhTurbo() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        WorldgenSharing.register();
        GenerationComparison.registerIfRequested();
        PregenBenchmark.registerIfRequested();
        if (ModList.get().isLoaded("distanthorizons")) {
            DhIntegration.register();
            // The generator plan follows real_chunks_nearby as soon as it is changed in game.
            modBus.addListener((ModConfigEvent.Reloading event) -> {
                if (event.getConfig().getSpec() == Config.SPEC) {
                    try {
                        DhIntegration.applyPlan();
                    } catch (RuntimeException e) {
                        // Distant Horizons is not ready yet (no world); the plan is applied when a level loads.
                    }
                }
            });
        } else {
            LOGGER.info("Distant Horizons is not installed: DH Turbo only shares the world generation with players");
        }
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
