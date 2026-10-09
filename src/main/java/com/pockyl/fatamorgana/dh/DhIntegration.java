package com.pockyl.fatamorgana.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import com.pockyl.fatamorgana.Config;
import com.pockyl.fatamorgana.Fatamorgana;

/** Hooks the generator into Distant Horizons for every level it can handle. */
public final class DhIntegration {
    private DhIntegration() {
    }

    public static void register() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> event) {
                levelLoaded(event.value.levelWrapper);
            }
        });
    }

    private static void levelLoaded(IDhApiLevelWrapper levelWrapper) {
        if (!Config.ENABLED.get() || levelWrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL) {
            return;
        }
        if (!(levelWrapper.getWrappedMcObject() instanceof ServerLevel level)
                || !(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return;
        }
        // Surface search from above would land on the roof of ceiling dimensions such as the Nether.
        if (level.dimensionType().hasCeiling()) {
            return;
        }
        try {
            DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper,
                    new FataWorldGenerator(levelWrapper, level, generator));
            if (result.success) {
                Fatamorgana.LOGGER.info("Generating distant terrain of {} with Fata Morgana", levelWrapper.getDimensionName());
            } else {
                Fatamorgana.LOGGER.warn("Distant Horizons refused the generator for {}: {}",
                        levelWrapper.getDimensionName(), result.message);
            }
        } catch (RuntimeException e) {
            Fatamorgana.LOGGER.error("Could not set up the generator for {}", levelWrapper.getDimensionName(), e);
        }
    }
}
