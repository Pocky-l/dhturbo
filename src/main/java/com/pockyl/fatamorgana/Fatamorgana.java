package com.pockyl.fatamorgana;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

import com.pockyl.fatamorgana.dh.DhIntegration;

@Mod(Fatamorgana.MOD_ID)
public final class Fatamorgana {
    public static final String MOD_ID = "fatamorgana";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Fatamorgana(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        if (ModList.get().isLoaded("distanthorizons")) {
            DhIntegration.register();
        } else {
            LOGGER.warn("Distant Horizons is not installed, Fata Morgana has nothing to generate for");
        }
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
