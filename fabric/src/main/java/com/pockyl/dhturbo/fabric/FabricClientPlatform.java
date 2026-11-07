package com.pockyl.dhturbo.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

import com.pockyl.dhturbo.platform.ClientPlatform;

/** {@link ClientPlatform} on Fabric. */
public final class FabricClientPlatform implements ClientPlatform {
    @Override
    public KeyMapping.Category keyCategory(Identifier id) {
        return KeyMapping.Category.register(id);
    }

    /** The game itself only reads keys while no screen is open. */
    @Override
    public KeyMapping inGameKey(String name, int key, KeyMapping.Category category) {
        return new KeyMapping(name, InputConstants.Type.KEYBOARD, key, category);
    }
}
