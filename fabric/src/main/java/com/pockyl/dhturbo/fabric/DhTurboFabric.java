package com.pockyl.dhturbo.fabric;

import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.neoforged.fml.config.ModConfig;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.network.WorldgenInfoPayload;

/** The Fabric entry point on both sides: the config (through Forge Config API Port, same file as on NeoForge) and the payload. */
public final class DhTurboFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        NeoForgeConfigRegistry.INSTANCE.register(DhTurbo.MOD_ID, ModConfig.Type.COMMON, Config.SPEC);
        NeoForgeModConfigEvents.reloading(DhTurbo.MOD_ID).register(config -> {
            if (config.getSpec() == Config.SPEC) {
                DhTurbo.configReloaded();
            }
        });
        PayloadTypeRegistry.playS2C().register(WorldgenInfoPayload.TYPE, WorldgenInfoPayload.STREAM_CODEC);
        DhTurbo.init();
    }
}
