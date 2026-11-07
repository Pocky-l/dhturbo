package com.pockyl.dhturbo.neoforge;

import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import com.pockyl.dhturbo.platform.LoaderInfo;

/** {@link LoaderInfo} on NeoForge: the loading mod list, which exists before any mod is constructed. */
public final class NeoForgeLoaderInfo implements LoaderInfo {
    @Override
    public boolean isModLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    @Override
    public boolean isDevelopment() {
        return !FMLEnvironment.production;
    }

    @Override
    public String modVersion(String modId) {
        ModFileInfo file = LoadingModList.get().getModFileById(modId);
        return file == null || file.getMods().isEmpty() ? "?" : file.getMods().getFirst().getVersion().toString();
    }
}
