package com.pockyl.dhturbo.platform;

import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * What the client part needs from the mod loader. Each loader project provides one implementation, listed in its
 * {@code META-INF/services}; it is only loaded on the client.
 */
public interface ClientPlatform {
    ClientPlatform INSTANCE = LoaderInfo.load(ClientPlatform.class);

    /** A category of the controls screen. */
    KeyMapping.Category keyCategory(Identifier id);

    /** A key that only counts while playing, not in screens. */
    KeyMapping inGameKey(String name, int key, KeyMapping.Category category);
}
