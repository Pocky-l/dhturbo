package com.pockyl.dhturbo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.FullDataToRenderDataTransformer;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.dhturbo.client.PlantTint;

/**
 * Soft plant colours in LOD columns ({@link PlantTint}). Call sites in DH 3.3's {@code setRenderColumnView}, in order:
 * {@code getBlockColor} of a snow layer (0), a water surface plant (1), a skipped block (2) and a drawn block (3);
 * {@code ColorUtil.setAlpha} for snow (0) and for "tint the block below" (1); {@code getMaterialId} for the ice check
 * (0) and for the data point (1).
 */
@Mixin(value = FullDataToRenderDataTransformer.class, remap = false)
abstract class FullDataToRenderDataTransformerMixin {
    @Unique
    private static final String GET_BLOCK_COLOR = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;"
            + "getBlockColor(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos;"
            + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IBiomeWrapper;"
            + "Lcom/seibel/distanthorizons/core/dataObjects/fullData/sources/FullDataSourceV2;"
            + "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;)I";

    @Inject(method = "setRenderColumnView", at = @At("HEAD"))
    private static void dhturbo$startColumn(CallbackInfo callback) {
        if (!PlantTint.broken()) {
            PlantTint.startColumn();
        }
    }

    @WrapOperation(method = "setRenderColumnView", at = @At(value = "INVOKE", target = GET_BLOCK_COLOR, ordinal = 2))
    private static int dhturbo$skippedColor(IClientLevelWrapper level, DhBlockPos pos, IBiomeWrapper biome,
                                            FullDataSourceV2 source, IBlockStateWrapper block, Operation<Integer> original) {
        int color = original.call(level, pos, biome, source, block);
        if (!PlantTint.broken()) {
            try {
                PlantTint.ignoredColor(block, color);
            } catch (Throwable e) {
                PlantTint.fail(e);
            }
        }
        return color;
    }

    @WrapOperation(method = "setRenderColumnView", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/coreapi/util/ColorUtil;setAlpha(II)I", ordinal = 1))
    private static int dhturbo$tintBelow(int color, int alpha, Operation<Integer> original) {
        int painted = original.call(color, alpha);
        if (PlantTint.broken()) {
            return painted;
        }
        try {
            return PlantTint.tintBelow(painted);
        } catch (Throwable e) {
            PlantTint.fail(e);
            return painted;
        }
    }

    @WrapOperation(method = "setRenderColumnView", at = @At(value = "INVOKE", target = GET_BLOCK_COLOR, ordinal = 3))
    private static int dhturbo$drawnColor(IClientLevelWrapper level, DhBlockPos pos, IBiomeWrapper biome,
                                          FullDataSourceV2 source, IBlockStateWrapper block, Operation<Integer> original) {
        int color = original.call(level, pos, biome, source, block);
        if (PlantTint.broken()) {
            return color;
        }
        try {
            return PlantTint.groundColor(block, color);
        } catch (Throwable e) {
            PlantTint.fail(e);
            return color;
        }
    }

    @WrapOperation(method = "setRenderColumnView", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/block/IBlockStateWrapper;getMaterialId()B", ordinal = 1))
    private static byte dhturbo$material(IBlockStateWrapper block, Operation<Byte> original) {
        byte material = original.call(block);
        if (PlantTint.broken()) {
            return material;
        }
        try {
            return PlantTint.material(material);
        } catch (Throwable e) {
            PlantTint.fail(e);
            return material;
        }
    }
}
