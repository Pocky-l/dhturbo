package com.pockyl.fatamorgana.mixin;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.client.RenderDropStats;

/** Diagnostics only: reports how much geometry each LOD section is (re)built with, see {@link RenderDropStats}. */
@Mixin(value = LodRenderSection.class, remap = false)
abstract class LodRenderSectionMixin {
    @Unique
    private static volatile boolean fatamorgana$broken;

    @Inject(method = "getAndBuildRenderData", at = @At("RETURN"))
    private void fatamorgana$measure(CallbackInfoReturnable<LodQuadBuilder> callback) {
        if (fatamorgana$broken) {
            return;
        }
        try {
            LodQuadBuilder builder = callback.getReturnValue();
            int quads = builder == null ? 0 : builder.getCurrentOpaqueQuadsCount() + builder.getCurrentTransparentQuadsCount();
            RenderDropStats.built(((LodRenderSection) (Object) this).pos, quads);
        } catch (Throwable e) {
            fatamorgana$broken = true;
            Fatamorgana.LOGGER.error("Fata Morgana's render diagnostics failed and are switched off", e);
        }
    }
}
