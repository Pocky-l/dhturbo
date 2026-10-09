package com.pockyl.dhturbo.world;

import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;

import com.pockyl.dhturbo.DhTurbo;

import java.util.List;

/**
 * Loads the world generation registries (noise, density functions, biomes, features, ...) from the data packs this
 * game instance has: vanilla plus every installed mod. A client in multiplayer never loads them, the server keeps
 * them to itself; this is what singleplayer does when it starts its integrated server, without starting one.
 */
public final class WorldgenLoader {
    private static volatile RegistryAccess.Frozen loaded;

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
        try (CloseableResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repository.openAllSelected())) {
            LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
            RegistryAccess.Frozen worldgen = RegistryDataLoader.load(resources, layers.getAccessForLoading(RegistryLayer.WORLDGEN),
                    DataPackRegistriesHooks.getDataPackRegistries());
            RegistryAccess.Frozen access = layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen).compositeAccess();
            DhTurbo.LOGGER.info("Loaded world generation data from {} packs in {} ms", selected.size(),
                    (System.nanoTime() - start) / 1_000_000);
            return access;
        }
    }
}
