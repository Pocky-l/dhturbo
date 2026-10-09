package com.pockyl.fatamorgana.mixin;

import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.QuadTree.QuadTreeTickNodeHolder;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.fatamorgana.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps finer LODs on screen until the coarser one replacing them is ready.
 * <p>
 * When the player moves away from an area, Distant Horizons switches it to a coarser section and stops drawing the
 * finer ones at once, although the coarser section still has to be loaded (and, when its data is missing, generated).
 * The area is a hole meanwhile and "loads again" a moment later. Going the other way DH already waits for all
 * children, so this does the same for the way back: as long as the finer sections below fully cover the area, they
 * keep rendering. Only while Fata Morgana generates, so a comparison with plain DH stays honest.
 */
@Mixin(value = LodQuadTree.class, remap = false)
abstract class LodQuadTreeMixin {
    /** How many levels below a section are searched for finer replacements. */
    private static final int MAX_DEPTH = 4;

    @Shadow
    @Final
    private QuadTreeTickNodeHolder tickNodeHolder;

    @Inject(method = "onDesiredDetailLevel", at = @At("HEAD"), cancellable = true)
    private void fatamorgana$keepFinerUntilReady(QuadNode<LodRenderSection> quadNode, QuadNode<LodRenderSection> parentNode,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (!Config.ENABLED.get() || renders(quadNode)
                || !((QuadTree<?>) (Object) this).isSectionPosInBounds(quadNode.sectionPos)
                || tickNodeHolder.getEnabledNodes().contains(parentNode)) {
            return;
        }
        List<QuadNode<LodRenderSection>> cover = new ArrayList<>();
        if (!coveredByFiner(quadNode, cover, MAX_DEPTH)) {
            return;
        }
        // The section itself keeps loading (queued before this method); once it can render, DH swaps it in and
        // deletes these children as usual.
        cover.forEach(tickNodeHolder::addEnableNode);
        tickNodeHolder.addDisableNode(quadNode);
        callback.setReturnValue(true);
    }

    private static boolean coveredByFiner(QuadNode<LodRenderSection> node, List<QuadNode<LodRenderSection>> cover, int depth) {
        if (depth == 0) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            QuadNode<LodRenderSection> child = node.getChildByIndex(i);
            if (child == null) {
                return false;
            }
            if (renders(child)) {
                cover.add(child);
            } else if (!coveredByFiner(child, cover, depth - 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean renders(QuadNode<LodRenderSection> node) {
        return node.value != null && node.value.canRender();
    }
}
