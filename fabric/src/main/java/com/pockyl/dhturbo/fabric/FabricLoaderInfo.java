package com.pockyl.dhturbo.fabric;

import net.fabricmc.loader.api.FabricLoader;

import com.pockyl.dhturbo.platform.LoaderInfo;

/** {@link LoaderInfo} on Fabric: the loader knows its mods before any entry point runs. */
public final class FabricLoaderInfo implements LoaderInfo {
    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
    }
}
