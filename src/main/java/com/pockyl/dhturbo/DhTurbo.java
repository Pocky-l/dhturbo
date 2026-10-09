package com.pockyl.dhturbo;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import com.pockyl.dhturbo.dh.DhIntegration;

@Mod(DhTurbo.MOD_ID)
public final class DhTurbo {
    public static final String MOD_ID = "dhturbo";
    public static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Developer tools: demo keys and diagnostics logging. On in development runs (the workspace playtest client) or
     * with {@code -Ddhturbo.debug=true}; players never see them.
     */
    public static final boolean DEBUG = !FMLEnvironment.production || Boolean.getBoolean(MOD_ID + ".debug");

    public DhTurbo(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        if (ModList.get().isLoaded("distanthorizons")) {
            DhIntegration.register();
        } else {
            LOGGER.warn("Distant Horizons is not installed, DH Turbo has nothing to generate for");
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
