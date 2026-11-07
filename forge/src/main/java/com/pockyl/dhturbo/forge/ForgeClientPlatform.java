package com.pockyl.dhturbo.forge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;

import com.pockyl.dhturbo.platform.ClientPlatform;

/** {@link ClientPlatform} on Forge. */
public final class ForgeClientPlatform implements ClientPlatform {
    @Override
    public KeyMapping inGameKey(String name, int key, String category) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, category);
    }
}
