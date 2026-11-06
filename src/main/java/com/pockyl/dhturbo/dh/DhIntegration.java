package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiLevelType;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
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
import net.neoforged.neoforge.common.NeoForge;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.TerrainCheck;
import com.pockyl.dhturbo.world.TerrainSupport;
import com.pockyl.dhturbo.world.ReceivedWorldgen;

/**
 * Hooks the generator into Distant Horizons for every level it can handle, and the runtime switches used for
 * demonstrations (on/off and wiping the generated LODs). The switches use DH internals: the public API has no way to
 * delete LOD data, so they may break with a DH update and are kept here in one place.
 */
public final class DhIntegration {
    /** Far above what setting up a level takes; only there so a failure cannot hold Distant Horizons up for good. */
    private static final long GENERATOR_WAIT_SECONDS = 30;
    /** Tiles under way per worker: part of a tile runs on one thread, so one tile per worker leaves workers idle. */
    private static final int TILES_PER_WORKER = 2;
    /** One latch per server level, released once this mod has decided who generates that level. */
    private static final Map<Object, CountDownLatch> DECIDED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean followingPlan;

    private DhIntegration() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(RegenerateCommand::register);
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> event) {
                levelLoaded(event.value.levelWrapper);
            }
        });
    }

    /** Measuring tools: the Distant Horizons wrapper of a level with a generator of ours. */
    public static IDhApiLevelWrapper levelWrapper(ServerLevel level) {
        return TurboWorldGenerator.refiner(level.dimension()).wrapper();
    }

    /** Measuring tools: replaces one batch of chunks with real generation now; returns the chunks handed to DH. */
    public static long refineNow(ServerLevel level, int batchX, int batchZ) throws Exception {
        ExactRefiner refiner = TurboWorldGenerator.refiner(level.dimension());
        long before = refiner.refined();
        refiner.refine(batchX, batchZ);
        return refiner.refined() - before;
    }

    /** How many tiles may be under way at once for this generator; 0 when it is not ours or is switched off. */
    public static int tilesInFlight(Object generator) {
        return generator instanceof TurboWorldGenerator && active() ? WorkerPool.get().getParallelism() * TILES_PER_WORKER : 0;
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
     * set as an API override, so the player's own DH config is left untouched, and removed while we are off or while
     * {@code real_chunks_nearby} hands that pass to DH's own generator. A client generating the distance itself (the
     * server shares its world generation) cannot generate chunks, so there the override always applies. A player who
     * set the plan to {@code DISABLED}, or to existing chunks only, wants nothing generated and keeps it.
     */
    public static void applyPlan() {
        IDhApiConfigValue<EDhApiGeneratorPlan> plan = DhApi.Delayed.configs.worldGenerator().GeneratorPlan();
        boolean surfaceOnly = active() && (!Config.REAL_CHUNKS_NEARBY.get() || ReceivedWorldgen.present())
                && generationWanted();
        // The change listener also hears our own changes, so nothing happens when there is nothing to change.
        if (plan.getApiValue() == (surfaceOnly ? EDhApiGeneratorPlan.SURFACE_ONLY : null)) {
            return;
        }
        if (surfaceOnly) {
            plan.setValue(EDhApiGeneratorPlan.SURFACE_ONLY, DhTurbo.MOD_ID);
        } else {
            plan.clearValue();
        }
    }

    /** Whether the player's own Distant Horizons settings want terrain generated at all. */
    public static boolean generationWanted() {
        return DhApi.Delayed.configs.worldGenerator().GeneratorPlan().getTrueValue().generationEnabled
                && !existingChunksOnly();
    }

    /**
     * Chunks only, from existing chunks only: the player wants to see what the world holds and nothing else, which
     * DH's own generator does. With a surface pass in the plan terrain is still wanted, and saved chunks come on top.
     */
    static boolean existingChunksOnly() {
        return DhApi.Delayed.configs.worldGenerator().GeneratorPlan().getTrueValue() == EDhApiGeneratorPlan.CHUNKS_ONLY
                && DhApi.Delayed.configs.worldGenerator().chunkGeneratorMode().getTrueValue()
                == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY;
    }

    /** How far the player's LODs reach, in chunks. */
    static int lodRadiusChunks() {
        return DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
    }

    /** The plan follows the player's choice in Distant Horizons' settings as soon as it changes. */
    private static void followPlanChanges() {
        if (!followingPlan) {
            followingPlan = true;
            DhApi.Delayed.configs.worldGenerator().GeneratorPlan().addChangeListener(plan -> applyPlan());
            DhApi.Delayed.configs.worldGenerator().chunkGeneratorMode().addChangeListener(mode -> applyPlan());
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
        TurboWorldGenerator.forgetProgress();
        DhTurbo.LOGGER.info("Deleted the Distant Horizons LODs of {} levels", cleared);
        return cleared;
    }

    /**
     * Distant Horizons picks a level's generator once, on its own thread, when it creates the level's queue: without an
     * override at that moment it keeps its own generator for the whole session, and a later override is accepted but
     * never used. Setting up our generator takes longer than that thread needs, so it waits here (from
     * {@code ServerLodRequestStateMixin}) until {@link #levelLoaded} is through with the level.
     */
    public static void awaitGenerator(Object level) {
        try {
            if (!decided(level).await(GENERATOR_WAIT_SECONDS, TimeUnit.SECONDS)) {
                DhTurbo.LOGGER.warn("Distant Horizons started on {} before DH Turbo was ready, and uses its own generator there",
                        level);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CountDownLatch decided(Object level) {
        return DECIDED.computeIfAbsent(level, key -> new CountDownLatch(1));
    }

    private static void levelLoaded(IDhApiLevelWrapper levelWrapper) {
        followPlanChanges();
        if (levelWrapper.getLevelType() != EDhApiLevelType.SERVER_LEVEL) {
            return;
        }
        if (!(levelWrapper.getWrappedMcObject() instanceof ServerLevel level)) {
            return;
        }
        try {
            chooseGenerator(levelWrapper, level);
        } finally {
            decided(level).countDown();
        }
    }

    private static void chooseGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level) {
        HorizonFill.leftToDh(levelWrapper.getDimensionName());
        String unreadable = TerrainCheck.unreadable(level.getChunkSource().getGenerator());
        if (unreadable != null) {
            leaveToDistantHorizons(level, unreadable);
            return;
        }
        NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator();
        try {
            TurboWorldGenerator turbo = new TurboWorldGenerator(levelWrapper, level, generator);
            long start = System.nanoTime();
            String problem = TerrainSupport.problem(level, turbo.builder());
            if (problem != null) {
                turbo.close();
                leaveToDistantHorizons(level, problem);
                return;
            }
            if (DhTurbo.DEBUG) {
                DhTurbo.LOGGER.info("Terrain of {} checked in {} ms", levelWrapper.getDimensionName(),
                        (System.nanoTime() - start) / 1_000_000);
            }
            TerrainSupport.drawnByUs(level.dimension());
            // Registered even while switched off, so generation can be switched on without reloading the world.
            DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, turbo);
            if (result.success) {
                HorizonFill.generates(levelWrapper.getDimensionName());
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

    /**
     * Not registering a generator leaves the level to DH's own one. With the plan override, which is global, that is
     * DH's rough surface (from the level's real noise, so terrain mods work there), or its full generation with
     * {@code real_chunks_nearby}.
     */
    private static void leaveToDistantHorizons(ServerLevel level, String reason) {
        TerrainSupport.leftToDistantHorizons(level.dimension(), reason);
        DhTurbo.LOGGER.warn("DH Turbo cannot draw the distant terrain of {}: {}. Distant Horizons generates it instead",
                level.dimension().location(), reason);
    }
}
