package com.pockyl.dhturbo.forge;

import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import com.pockyl.dhturbo.platform.LoaderInfo;

/** {@link LoaderInfo} on Forge: the loading mod list, which exists before any mod is constructed. */
public final class ForgeLoaderInfo implements LoaderInfo {
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
        return file == null || file.getMods().isEmpty() ? "?" : file.getMods().get(0).getVersion().toString();
    }
}
