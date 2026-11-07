package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.generation.queues.AbstractLodRequestState;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.ClientTurboQueue;

/**
 * Wraps the LOD source of multiplayer levels in {@link ClientTurboQueue}, which generates on the client when the
 * server shares its world generation and otherwise leaves everything to DH's download queue.
 * <p>
 * The field is replaced once the constructor is done: a {@code @ModifyVariable} right before the field write would
 * change the local variable only after its old value is already on the stack, so the wrapper would be dropped.
 */
@Mixin(value = AbstractLodRequestState.class, remap = false)
abstract class AbstractLodRequestStateMixin {
    @Unique
    private static volatile boolean dhturbo$broken;

    @Shadow
    @Final
    public IDhLevel dhLevel;
    @Shadow
    @Final
    @Mutable
    public IFullDataSourceRetrievalQueue retrievalQueue;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhturbo$wrapClientQueue(CallbackInfo callback) {
        if (dhturbo$broken || !(dhLevel instanceof DhClientLevel level) || retrievalQueue instanceof ClientTurboQueue) {
            return;
        }
        try {
            retrievalQueue = new ClientTurboQueue(level, retrievalQueue);
            if (DhTurbo.DEBUG) {
                DhTurbo.LOGGER.info("[client gen] LOD request queue of {} wrapped", level);
            }
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's client generation queue hook failed and is switched off", e);
        }
    }
}
