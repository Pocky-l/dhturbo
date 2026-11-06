package com.pockyl.dhturbo.mixin;

import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.RenderSectionContent;

/**
 * Remembers how much geometry each LOD section was built with ({@link RenderSectionContent}, used by
 * {@code LodQuadTreeMixin} to not show empty sections).
 */
@Mixin(value = LodRenderSection.class, remap = false)
abstract class LodRenderSectionMixin implements RenderSectionContent {
    @Unique
    private static volatile boolean dhturbo$broken;
    @Unique
    private volatile int dhturbo$quads = -1;
    @Unique
    private volatile boolean dhturbo$generatedEmpty;

    @Shadow
    @Final
    public long pos;
    @Shadow
    @Final
    private FullDataSourceProviderV2 fullDataSourceProvider;

    @Override
    public int dhturbo$quads() {
        return dhturbo$quads;
    }

    @Override
    public boolean dhturbo$generatedEmpty() {
        return dhturbo$generatedEmpty;
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
            dhturbo$generatedEmpty = quads == 0 && dhturbo$generated();
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's render diagnostics failed and are switched off", e);
        }
    }

    /** Whether every column of the section has data. A section without any is empty only until it is generated. */
    @Unique
    private boolean dhturbo$generated() {
        ByteArrayList steps = new ByteArrayList();
        fullDataSourceProvider.repo.getColumnGenerationStepForPos(pos, steps);
        for (int i = 0; i < steps.size(); i++) {
            if (steps.getByte(i) == EDhApiWorldGenerationStep.EMPTY.value) {
                return false;
            }
        }
        return !steps.isEmpty();
    }
}
