package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.DhIntegration;

/**
 * Holds the creation of a level's generation queue until this mod has chosen the level's generator: see
 * {@link DhIntegration#awaitGenerator}. There is no API for this: the level load event comes on the server thread, and
 * Distant Horizons does not wait for its listeners before its own thread takes the generator.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.level.ServerLevelModule$LodRequestState", remap = false)
abstract class ServerLodRequestStateMixin {
    @Unique
    private static volatile boolean dhturbo$broken;

    @Inject(method = "createRetrievalQueue", at = @At("HEAD"))
    private static void dhturbo$awaitGenerator(IDhServerLevel level,
                                               CallbackInfoReturnable<IFullDataSourceRetrievalQueue> callback) {
        if (dhturbo$broken) {
            return;
        }
        try {
            DhIntegration.awaitGenerator(level.getLevelWrapper().getWrappedMcObject());
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's wait for the level's generator failed and is switched off", e);
        }
    }
}
