package com.pockyl.dhturbo.fabric;

import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents;
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
        ConfigRegistry.INSTANCE.register(DhTurbo.MOD_ID, ModConfig.Type.COMMON, Config.SPEC);
        ModConfigEvents.reloading(DhTurbo.MOD_ID).register(config -> {
            if (config.getSpec() == Config.SPEC) {
                DhTurbo.configReloaded();
            }
        });
        PayloadTypeRegistry.clientboundPlay().register(WorldgenInfoPayload.TYPE, WorldgenInfoPayload.STREAM_CODEC);
        DhTurbo.init();
    }
}
