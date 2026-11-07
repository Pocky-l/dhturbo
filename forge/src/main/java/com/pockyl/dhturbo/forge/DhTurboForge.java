package com.pockyl.dhturbo.forge;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

@Mod(DhTurbo.MOD_ID)
public final class DhTurboForge {
    // The no-argument constructor keeps the jar loadable on NeoForge for 1.20.1 as well.
    public DhTurboForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                DhTurbo.configReloaded();
            }
        });
        ForgePlatform.registerChannel();
        DhTurbo.init();
    }
}
