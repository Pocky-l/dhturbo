package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.dh.HorizonFill;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Distant Horizons starts as many tiles at a time as it has threads of its own, plus one. Our tiles run on this mod's
 * workers and spend part of their time on steps that use one thread, so that many leave most workers idle.
 */
@Mixin(value = WorldGenerationQueue.class, remap = false)
abstract class WorldGenerationQueueMixin {
    @Shadow
    @Final
    private IDhApiWorldGenerator generator;
    @Shadow
    @Final
    private ConcurrentHashMap<Long, DataSourceRetrievalTask> inProgressGenTasksByLodPos;

    /** Which waiting tile starts next, see {@link HorizonFill#look}. */
    @ModifyVariable(method = "startAndSetTargetPos", at = @At("HEAD"), argsOnly = true)
    private DhBlockPos2D dhturbo$orderFromAhead(DhBlockPos2D target) {
        int x = HorizonFill.aheadX();
        int z = HorizonFill.aheadZ();
        return x == 0 && z == 0 ? target : new DhBlockPos2D(target.x + x, target.z + z);
    }

    @Inject(method = "isGeneratorBusy", at = @At("HEAD"), cancellable = true)
    private void dhturbo$keepWorkersBusy(CallbackInfoReturnable<Boolean> callback) {
        int limit = DhIntegration.tilesInFlight(generator);
        if (limit > 0) {
            callback.setReturnValue(inProgressGenTasksByLodPos.size() >= limit);
        }
    }
}
