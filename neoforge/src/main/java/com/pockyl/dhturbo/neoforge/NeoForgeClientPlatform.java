package com.pockyl.dhturbo.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import com.pockyl.dhturbo.platform.ClientPlatform;

/** {@link ClientPlatform} on NeoForge. */
public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public KeyMapping inGameKey(String name, int key, String category) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, category);
    }
}
