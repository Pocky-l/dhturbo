package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.RenderDropStats;
import com.pockyl.dhturbo.client.RenderSectionContent;

/**
 * Remembers how much geometry each LOD section was built with ({@link RenderSectionContent}, used by
 * {@code LodQuadTreeMixin} to not show empty sections) and reports drops ({@link RenderDropStats}).
 */
@Mixin(value = LodRenderSection.class, remap = false)
abstract class LodRenderSectionMixin implements RenderSectionContent {
    @Unique
    private static volatile boolean dhturbo$broken;
    @Unique
    private volatile int dhturbo$quads = -1;

    @Override
    public int dhturbo$quads() {
        return dhturbo$quads;
    }

    @Inject(method = "getAndBuildRenderData", at = @At("RETURN"))
    private void dhturbo$measure(CallbackInfoReturnable<LodQuadBuilder> callback) {
        if (dhturbo$broken) {
            return;
        }
        try {
            LodQuadBuilder builder = callback.getReturnValue();
            int quads = builder == null ? 0 : builder.getCurrentOpaqueQuadsCount() + builder.getCurrentTransparentQuadsCount();
            dhturbo$quads = quads;
            if (DhTurbo.DEBUG) {
                RenderDropStats.built(((LodRenderSection) (Object) this).pos, quads);
            }
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's render diagnostics failed and are switched off", e);
        }
    }
}
