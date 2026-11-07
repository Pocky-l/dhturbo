package com.pockyl.dhturbo.world;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.Block;

import com.pockyl.dhturbo.platform.Platform;

import java.util.List;

/** The part of {@link WorldgenLoader} that depends on the Minecraft version: filling the registries from the packs. */
final class WorldgenRegistries {
    private WorldgenRegistries() {
    }

    static RegistryAccess.Frozen load(ResourceManager packs, LayeredRegistryAccess<RegistryLayer> layers) {
        // As WorldLoader: the pending tags of the static registries (block tags in features) only feed this load, and
        // the loader binds the tags of what it loads (a structure's biomes are a biome tag).
        List<Registry.PendingTags<?>> staticTags = TagLoader.loadTagsForExistingRegistries(packs, layers.getLayer(RegistryLayer.STATIC));
        List<HolderLookup.RegistryLookup<?>> lookups =
                TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLDGEN), staticTags);
        return RegistryDataLoader.load(packs, lookups, Platform.INSTANCE.worldgenRegistries(), Util.backgroundExecutor()).join();
    }

    static HolderGetter<Block> blocks() {
        return BuiltInRegistries.BLOCK;
    }
}
