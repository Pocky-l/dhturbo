package com.pockyl.dhturbo.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge and Fabric keep this cache a plain map (MC-271899). Workers placing the same template at once then get a
 * ConcurrentModificationException, and the piece is missing from the distant structure.
 */
@Mixin(StructureTemplate.Palette.class)
abstract class StructureTemplatePaletteMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhturbo$concurrentCache(CallbackInfo ci) {
        cache = new ConcurrentHashMap<>();
    }
}
