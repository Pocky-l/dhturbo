package com.pockyl.dhturbo.world;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.platform.Platform;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
                            WorldgenRegistries.blocks());
                } catch (IOException | RuntimeException e) {
                    templatesUnavailable = true;
                    DhTurbo.LOGGER.warn("Cannot read structure templates; distant structures are left out", e);
                }
            }
            return templates;
        }
    }

    private static RegistryAccess.Frozen load() {
        long start = System.nanoTime();
        PackRepository repository = Platform.INSTANCE.dataPacks();
        repository.reload();
        // Vanilla and the mods' built-in data; optional feature packs (bundles, trade rebalance) do not touch worldgen.
        List<String> selected = repository.getAvailablePacks().stream()
                .filter(pack -> pack.isRequired() || pack.getId().equals("vanilla") || Platform.INSTANCE.isModDataPack(pack.getId()))
                .map(Pack::getId)
                .toList();
        repository.setSelected(selected);
        CloseableResourceManager packs = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected());
        LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
        RegistryAccess.Frozen worldgen = WorldgenRegistries.load(packs, layers);
        RegistryAccess.Frozen access = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
        resources = packs;
        DhTurbo.LOGGER.info("Loaded world generation data from {} packs in {} ms", selected.size(),
                (System.nanoTime() - start) / 1_000_000);
        return access;
    }
}
