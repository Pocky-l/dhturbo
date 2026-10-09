package com.pockyl.fatamorgana.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
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

    /**
     * Under its default {@code SURFACE_THEN_CHUNKS} plan DH, once the rough pass is done, re-requests every tile within
     * its regeneration range at full block detail to replace it with real chunks. Routed to this generator that only
     * produces the same fake surface again, a few kilometres around the player, and keeps going around the old place
     * after a teleport (about 90% of all requests in the first playtest). {@code SURFACE_ONLY} skips that pass. It is
     * set as an API override, so the player's own DH config is left untouched.
     */
    private static void surfaceOnlyPlan() {
        DhApi.Delayed.configs.worldGenerator().GeneratorPlan().setValue(EDhApiGeneratorPlan.SURFACE_ONLY, Fatamorgana.MOD_ID);
    }

    private static void levelLoaded(IDhApiLevelWrapper levelWrapper) {
        if (levelWrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL) {
            return;
        }
        if (!Config.ENABLED.get()) {
            DhApi.Delayed.configs.worldGenerator().GeneratorPlan().clearValue();
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
                surfaceOnlyPlan();
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
