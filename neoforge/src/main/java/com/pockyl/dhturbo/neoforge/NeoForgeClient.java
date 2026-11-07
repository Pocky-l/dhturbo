package com.pockyl.dhturbo.neoforge;

import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.ClientGeneration;
import com.pockyl.dhturbo.client.DemoKeys;
import com.pockyl.dhturbo.dh.TurboActivity;

@Mod(value = DhTurbo.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClient {
    private static final Identifier DEBUG_ENTRY = DhTurbo.id("status");

    public NeoForgeClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Leaving a server forgets its seed and generators (Distant Horizons is optional, guard its classes).
        if (ModList.get().isLoaded("distanthorizons")) {
            NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientGeneration.reset());
            modBus.addListener(RegisterDebugEntriesEvent.class, event -> {
                event.register(DEBUG_ENTRY, (displayer, level, clientChunk, serverChunk) -> displayer.addLine(TurboActivity.debugLine()));
                event.includeInProfile(DEBUG_ENTRY, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY);
            });
        }
        if (DhTurbo.DEBUG) {
            modBus.addListener((RegisterKeyMappingsEvent event) -> {
                event.registerCategory(DemoKeys.category());
                DemoKeys.keys().forEach(event::register);
            });
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> DemoKeys.tick());
        }
    }
}
