package com.pockyl.dhturbo.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import com.pockyl.dhturbo.platform.ClientPlatform;

/** {@link ClientPlatform} on Fabric. */
public final class FabricClientPlatform implements ClientPlatform {
    /** The game itself only reads keys while no screen is open. */
    @Override
    public KeyMapping inGameKey(String name, int key, String category) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, key, category);
    }
}
