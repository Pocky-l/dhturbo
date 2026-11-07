package com.pockyl.dhturbo.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import com.pockyl.dhturbo.platform.ClientPlatform;

/** {@link ClientPlatform} on NeoForge. */
public final class NeoForgeClientPlatform implements ClientPlatform {
    /** Registered by {@link NeoForgeClient} with the keys. */
    @Override
    public KeyMapping.Category keyCategory(Identifier id) {
        return new KeyMapping.Category(id);
    }

    @Override
    public KeyMapping inGameKey(String name, int key, KeyMapping.Category category) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, category);
    }
}
