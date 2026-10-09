package com.pockyl.dhturbo.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import com.pockyl.dhturbo.DhTurbo;

@Mod(value = DhTurbo.MOD_ID, dist = Dist.CLIENT)
public final class DhTurboClient {
    public DhTurboClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        if (DhTurbo.DEBUG) {
            modBus.addListener(DemoKeys::register);
            NeoForge.EVENT_BUS.addListener(DemoKeys::tick);
        }
    }
}
