package com.pockyl.dhturbo.platform;

import java.util.ServiceLoader;

/**
 * What the mod loader knows about the installed mods. The mixin plugin asks this while the game's classes are being
 * transformed, so no Minecraft class may appear here; everything else goes through {@link Platform}. Each loader
 * project provides one implementation, listed in its {@code META-INF/services}.
 */
public interface LoaderInfo {
    LoaderInfo INSTANCE = load(LoaderInfo.class);

    boolean isModLoaded(String modId);

    /** A development run, not a player's game. */
    boolean isDevelopment();

    /** The version of an installed mod, "?" when it is not known. */
    String modVersion(String modId);

    static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + type.getName() + " for this mod loader"));
    }
}
