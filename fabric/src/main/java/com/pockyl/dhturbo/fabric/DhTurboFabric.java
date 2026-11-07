package com.pockyl.dhturbo.fabric;

import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import fuzs.forgeconfigapiport.api.config.v2.ModConfigEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraftforge.fml.config.ModConfig;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

/** The Fabric entry point on both sides: the config, through Forge Config API Port, in the same file as on Forge. */
public final class DhTurboFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ForgeConfigRegistry.INSTANCE.register(DhTurbo.MOD_ID, ModConfig.Type.COMMON, Config.SPEC);
        ModConfigEvents.reloading(DhTurbo.MOD_ID).register(config -> {
            if (config.getSpec() == Config.SPEC) {
                DhTurbo.configReloaded();
            }
        });
        DhTurbo.init();
    }
}
