package com.pockyl.dhturbo.world;

import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the world generation registries (noise, density functions, biomes, features, structures, ...) from the data
 * packs this game instance has: vanilla plus every installed mod. A client in multiplayer never loads them, the server
 * keeps them to itself; this is what singleplayer does when it starts its integrated server, without starting one.
 * Structure templates come from the same packs.
 */
public final class WorldgenLoader {
    private static volatile RegistryAccess.Frozen loaded;
    /** The packs' resources, kept open for structure templates (they are read on first use). */
    private static volatile CloseableResourceManager resources;
    private static volatile StructureTemplateManager templates;
    private static volatile boolean templatesUnavailable;

    private WorldgenLoader() {
    }

    /** Cached after the first call; loading takes a moment, call it off the render thread. */
    public static RegistryAccess.Frozen get() {
        RegistryAccess.Frozen access = loaded;
        if (access == null) {
            synchronized (WorldgenLoader.class) {
                access = loaded;
                if (access == null) {
                    access = load();
                    loaded = access;
                }
            }
        }
        return access;
    }

    /**
     * Structure templates of the same packs, or null when they cannot be read. The manager wants a world folder for
     * templates saved in game; it gets an empty one in the temporary directory.
     */
    @Nullable
    public static StructureTemplateManager templates() {
        StructureTemplateManager manager = templates;
        if (manager != null || templatesUnavailable) {
            return manager;
        }
        synchronized (WorldgenLoader.class) {
            if (templates == null && !templatesUnavailable) {
                get();
                try {
                    Path folder = Files.createTempDirectory(DhTurbo.MOD_ID);
                    LevelStorageSource.LevelStorageAccess storage = LevelStorageSource.createDefault(folder)
                            .createAccess("templates");
                    templates = new StructureTemplateManager(resources, storage, DataFixers.getDataFixer(),
                            BuiltInRegistries.BLOCK.asLookup());
                } catch (IOException | RuntimeException e) {
                    templatesUnavailable = true;
                    DhTurbo.LOGGER.warn("Cannot read structure templates; distant structures are left out", e);
                }
            }
            return templates;
        }
    }

    private static <T> void bindTags(ResourceManager packs, RegistryAccess.RegistryEntry<T> entry) {
        Registry<T> registry = entry.value();
        TagLoader<Holder<T>> loader = new TagLoader<>(registry::getHolder, Registries.tagsDirPath(entry.key()));
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        loader.loadAndBuild(packs).forEach((id, holders) -> tags.put(TagKey.create(entry.key(), id), List.copyOf(holders)));
        registry.bindTags(tags);
    }

    private static RegistryAccess.Frozen load() {
        long start = System.nanoTime();
        PackRepository repository = ServerPacksSource.createVanillaTrustedRepository();
        repository.reload();
        // Vanilla and the mods' built-in data; optional feature packs (bundles, trade rebalance) do not touch worldgen.
        List<String> selected = repository.getAvailablePacks().stream()
                .filter(pack -> pack.isRequired() || pack.getId().equals("vanilla") || pack.getId().startsWith("mod"))
                .map(Pack::getId)
                .toList();
        repository.setSelected(selected);
        CloseableResourceManager packs = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected());
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(packs, layers.getAccessForLoading(RegistryLayer.WORLDGEN),
                DataPackRegistriesHooks.getDataPackRegistries());
        // Tags too (a structure's biomes are a biome tag), as the server binds them after loading.
        worldgen.registries().forEach(entry -> bindTags(packs, entry));
        RegistryAccess.Frozen access = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
        resources = packs;
        DhTurbo.LOGGER.info("Loaded world generation data from {} packs in {} ms", selected.size(),
                (System.nanoTime() - start) / 1_000_000);
        return access;
    }
}
