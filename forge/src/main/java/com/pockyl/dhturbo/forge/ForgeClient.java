package com.pockyl.dhturbo.forge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.ClientGeneration;
import com.pockyl.dhturbo.client.DemoKeys;
import com.pockyl.dhturbo.dh.TurboActivity;

/**
 * Client-only setup on the mod bus. A subscriber class instead of code in the mod constructor, so a dedicated server never
 * loads any client class.
 */
@Mod.EventBusSubscriber(modid = DhTurbo.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeClient {
    private ForgeClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(DhTurbo.MOD_ID).ifPresent(container -> container.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> ConfigScreen.root(parent))));
        // Leaving a server forgets its seed and generators (Distant Horizons is optional, guard its classes).
        if (ModList.get().isLoaded("distanthorizons")) {
            MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut loggingOut) -> ClientGeneration.reset());
            // Only with F3 open: the event also fires while the debug screen is closed.
            MinecraftForge.EVENT_BUS.addListener((CustomizeGuiOverlayEvent.DebugText debugText) -> {
                if (Minecraft.getInstance().options.renderDebug) {
                    debugText.getRight().add(TurboActivity.debugLine());
                }
            });
        }
        if (DhTurbo.DEBUG) {
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent tick) -> {
                if (tick.phase == TickEvent.Phase.END) {
                    DemoKeys.tick();
                }
            });
        }
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        if (DhTurbo.DEBUG) {
            DemoKeys.keys().forEach(event::register);
        }
    }
}
