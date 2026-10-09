package com.pockyl.fatamorgana.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.dh.DhIntegration;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keys for showing the mod off: switch between Fata Morgana and the Distant Horizons generator, and wipe the
 * generated LODs so the distant terrain builds up again in front of the camera.
 */
final class DemoKeys {
    private static final String CATEGORY = "key.categories." + Fatamorgana.MOD_ID;
    private static final KeyMapping TOGGLE = new KeyMapping("key." + Fatamorgana.MOD_ID + ".toggle",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY);
    private static final KeyMapping CLEAR = new KeyMapping("key." + Fatamorgana.MOD_ID + ".clear_lods",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, CATEGORY);
    private static final AtomicBoolean CLEARING = new AtomicBoolean();

    private DemoKeys() {
    }

    static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(CLEAR);
    }

    static void tick(ClientTickEvent.Post event) {
        while (TOGGLE.consumeClick()) {
            toggle();
        }
        while (CLEAR.consumeClick()) {
            clear();
        }
    }

    private static void toggle() {
        if (!distantHorizonsLoaded()) {
            return;
        }
        boolean active = !DhIntegration.active();
        DhIntegration.setActive(active);
        show(Component.translatable("fatamorgana.message." + (active ? "enabled" : "disabled"))
                .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    private static void clear() {
        if (!distantHorizonsLoaded() || !CLEARING.compareAndSet(false, true)) {
            return;
        }
        show(Component.translatable("fatamorgana.message.clearing"));
        // Deleting the LOD database can take a moment; keep the render thread free.
        Util.backgroundExecutor().execute(() -> {
            int levels;
            try {
                levels = DhIntegration.clearLodData();
            } catch (RuntimeException | LinkageError e) {
                Fatamorgana.LOGGER.error("Could not clear the Distant Horizons LODs", e);
                levels = -1;
            } finally {
                CLEARING.set(false);
            }
            int result = levels;
            Minecraft.getInstance().execute(() -> show(result > 0
                    ? Component.translatable("fatamorgana.message.cleared").withStyle(ChatFormatting.GREEN)
                    : Component.translatable(result == 0 ? "fatamorgana.message.clear_unavailable" : "fatamorgana.message.clear_failed")
                            .withStyle(ChatFormatting.RED)));
        });
    }

    private static boolean distantHorizonsLoaded() {
        if (ModList.get().isLoaded("distanthorizons")) {
            return true;
        }
        show(Component.translatable("fatamorgana.message.no_distant_horizons").withStyle(ChatFormatting.RED));
        return false;
    }

    private static void show(MutableComponent message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal("Fata Morgana: ").append(message), true);
        }
    }
}
