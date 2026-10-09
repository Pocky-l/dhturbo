package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

/**
 * Hooks the generator into Distant Horizons for every level it can handle, and the runtime switches used for
 * demonstrations (on/off and wiping the generated LODs). The switches use DH internals: the public API has no way to
 * delete LOD data, so they may break with a DH update and are kept here in one place.
 */
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

    /** Whether DH Turbo generates; when off, its generators hand every request to DH's own generator. */
    public static boolean active() {
        return Config.ENABLED.get();
    }

    /** Switches generation between DH Turbo and DH's own generator, for new requests from now on. */
    public static void setActive(boolean active) {
        Config.ENABLED.set(active);
        Config.ENABLED.save();
        applyPlan();
    }

    /**
     * Under its default {@code SURFACE_THEN_CHUNKS} plan DH, once the rough pass is done, re-requests every tile within
     * its regeneration range at full block detail to replace it with real chunks. Routed to this generator that only
     * produces the same fake surface again, a few kilometres around the player, and keeps going around the old place
     * after a teleport (about 90% of all requests in the first playtest). {@code SURFACE_ONLY} skips that pass. It is
     * set as an API override, so the player's own DH config is left untouched, and removed while we are off.
     */
    public static void applyPlan() {
        if (active()) {
            DhApi.Delayed.configs.worldGenerator().GeneratorPlan().setValue(EDhApiGeneratorPlan.SURFACE_ONLY, DhTurbo.MOD_ID);
        } else {
            DhApi.Delayed.configs.worldGenerator().GeneratorPlan().clearValue();
        }
    }

    /**
     * Deletes every LOD DH has stored for the loaded levels and drops the rendered ones, so all distant terrain is
     * generated again by whichever generator is active. Blocks while the database is cleared; call off the render thread.
     *
     * @return the number of levels cleared, 0 when there is no local world (multiplayer gets LODs from the server)
     */
    public static int clearLodData() {
        AbstractDhWorld world = SharedApi.getAbstractDhWorld();
        if (world == null) {
            return 0;
        }
        int cleared = 0;
        for (IDhLevel level : world.getAllLoadedLevels()) {
            if (level instanceof AbstractDhServerLevel serverLevel) {
                serverLevel.getFullDataProvider().repo.deleteAll();
                if (serverLevel instanceof DhClientServerLevel clientServerLevel) {
                    clientServerLevel.clearRenderCache();
                }
                cleared++;
            }
        }
        DhTurbo.LOGGER.info("Deleted the Distant Horizons LODs of {} levels", cleared);
        return cleared;
    }

    private static void levelLoaded(IDhApiLevelWrapper levelWrapper) {
        if (levelWrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL) {
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
            // Registered even while switched off, so generation can be switched on without reloading the world.
            DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper,
                    new TurboWorldGenerator(levelWrapper, level, generator));
            if (result.success) {
                applyPlan();
                DhTurbo.LOGGER.info("Generating distant terrain of {} with DH Turbo", levelWrapper.getDimensionName());
            } else {
                DhTurbo.LOGGER.warn("Distant Horizons refused the generator for {}: {}",
                        levelWrapper.getDimensionName(), result.message);
            }
        } catch (RuntimeException e) {
            DhTurbo.LOGGER.error("Could not set up the generator for {}", levelWrapper.getDimensionName(), e);
        }
    }
}
