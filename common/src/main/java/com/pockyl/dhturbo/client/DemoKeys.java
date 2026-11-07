package com.pockyl.dhturbo.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.platform.ClientPlatform;
import com.pockyl.dhturbo.platform.Platform;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keys for showing the mod off: switch between DH Turbo and the Distant Horizons generator, and wipe the
 * generated LODs so the distant terrain builds up again in front of the camera.
 */
public final class DemoKeys {
    private static final KeyMapping.Category CATEGORY = ClientPlatform.INSTANCE.keyCategory(DhTurbo.id("keys"));
    private static final KeyMapping TOGGLE = ClientPlatform.INSTANCE.inGameKey("key." + DhTurbo.MOD_ID + ".toggle",
            GLFW.GLFW_KEY_F7, CATEGORY);
    private static final KeyMapping CLEAR = ClientPlatform.INSTANCE.inGameKey("key." + DhTurbo.MOD_ID + ".clear_lods",
            GLFW.GLFW_KEY_F8, CATEGORY);
    private static final AtomicBoolean CLEARING = new AtomicBoolean();

    private DemoKeys() {
    }

    /** For the loader to register. */
    public static KeyMapping.Category category() {
        return CATEGORY;
    }

    /** For the loader to register. */
    public static List<KeyMapping> keys() {
        return List.of(TOGGLE, CLEAR);
    }

    public static void tick() {
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
        show(Component.translatable("dhturbo.message." + (active ? "enabled" : "disabled"))
                .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    private static void clear() {
        if (!distantHorizonsLoaded() || !CLEARING.compareAndSet(false, true)) {
            return;
        }
        show(Component.translatable("dhturbo.message.clearing"));
        // Deleting the LOD database can take a moment; keep the render thread free.
        Util.backgroundExecutor().execute(() -> {
            int levels;
            try {
                levels = DhIntegration.clearLodData();
            } catch (RuntimeException | LinkageError e) {
                DhTurbo.LOGGER.error("Could not clear the Distant Horizons LODs", e);
                levels = -1;
            } finally {
                CLEARING.set(false);
            }
            int result = levels;
            Minecraft.getInstance().execute(() -> show(result > 0
                    ? Component.translatable("dhturbo.message.cleared").withStyle(ChatFormatting.GREEN)
                    : Component.translatable(result == 0 ? "dhturbo.message.clear_unavailable" : "dhturbo.message.clear_failed")
                            .withStyle(ChatFormatting.RED)));
        });
    }

    private static boolean distantHorizonsLoaded() {
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            return true;
        }
        show(Component.translatable("dhturbo.message.no_distant_horizons").withStyle(ChatFormatting.RED));
        return false;
    }

    private static void show(MutableComponent message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.literal("DH Turbo: ").append(message));
        }
    }
}
