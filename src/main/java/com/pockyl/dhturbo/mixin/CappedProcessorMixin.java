package com.pockyl.dhturbo.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.pockyl.dhturbo.surface.VirtualLevels;

/**
 * Vanilla's capped processor (suspicious sand and gravel in ocean ruins, trail ruins and the like) seeds its random from
 * {@code level.getLevel().getSeed()}. The virtual level distant structures are built on has no server level, so there
 * the seed comes from the virtual level itself; without this the whole ruin was skipped.
 */
@Mixin(CappedProcessor.class)
public abstract class CappedProcessorMixin {
    @WrapOperation(method = "finalizeProcessing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;getLevel()Lnet/minecraft/server/level/ServerLevel;"))
    private ServerLevel dhturbo$noServerLevel(ServerLevelAccessor level, Operation<ServerLevel> original) {
        return VirtualLevels.is(level) ? null : original.call(level);
    }

    @WrapOperation(method = "finalizeProcessing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
    private long dhturbo$virtualSeed(ServerLevel level, Operation<Long> original,
                                     @Local(argsOnly = true) ServerLevelAccessor accessor) {
        return level == null && accessor instanceof WorldGenLevel virtual ? virtual.getSeed() : original.call(level);
    }
}
