package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.generation.queues.AbstractLodRequestState;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.pockyl.dhturbo.client.ClientTurboQueue;

/**
 * Wraps the LOD source of multiplayer levels in {@link ClientTurboQueue}, which generates on the client when the
 * server shares its world generation and otherwise leaves everything to DH's download queue.
 */
@Mixin(value = AbstractLodRequestState.class, remap = false)
abstract class AbstractLodRequestStateMixin {
    @ModifyVariable(method = "<init>", argsOnly = true, at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lcom/seibel/distanthorizons/core/generation/queues/AbstractLodRequestState;retrievalQueue:Lcom/seibel/distanthorizons/core/generation/queues/IFullDataSourceRetrievalQueue;"))
    private IFullDataSourceRetrievalQueue dhturbo$wrapClientQueue(IFullDataSourceRetrievalQueue queue) {
        // dhLevel is assigned before retrievalQueue, so it is known here.
        return ((AbstractLodRequestState) (Object) this).dhLevel instanceof DhClientLevel level
                ? new ClientTurboQueue(level, queue)
                : queue;
    }
}
