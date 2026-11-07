package com.pockyl.dhturbo.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.gametest.GameTests;
import com.pockyl.dhturbo.network.WorldgenInfoPayload;
import com.pockyl.dhturbo.network.WorldgenSharing;

@Mod(DhTurbo.MOD_ID)
public final class DhTurboNeoForge {
    public DhTurboNeoForge(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        GameTests.register(modBus);
        modBus.addListener(DhTurboNeoForge::registerPayloads);
        modBus.addListener(ModConfigEvent.Reloading.class, event -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                DhTurbo.configReloaded();
            }
        });
        DhTurbo.init();
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(WorldgenInfoPayload.TYPE, WorldgenInfoPayload.STREAM_CODEC,
                (payload, context) -> WorldgenSharing.received(payload));
    }
}
