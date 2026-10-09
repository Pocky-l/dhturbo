package com.pockyl.fatamorgana.mixin;

import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.QuadTree.QuadTreeTickNodeHolder;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadTree;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.fatamorgana.Config;
import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.client.HoleStats;
import com.pockyl.fatamorgana.client.RenderSectionContent;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps LODs with terrain on screen until whatever replaces them has terrain too.
 * <p>
 * When the player moves away from an area, Distant Horizons switches it to a coarser section and stops drawing the
 * finer ones at once, although the coarser section still has to be loaded (and, when its data is missing, generated).
 * The area is a hole meanwhile and "loads again" a moment later. Going the other way DH already waits for all
 * children, so this does the same for the way back: as long as the finer sections below fully cover the area, they
 * keep rendering. Likewise going finer: DH counts a section as ready once it is built, also when it was built empty
 * because its data is not generated yet (a 64-block hole on screen); empty sections are not counted as ready here, so
 * the coarser parent stays. Only while Fata Morgana generates, so a comparison with plain DH stays honest.
 */
@Mixin(value = LodQuadTree.class, remap = false)
abstract class LodQuadTreeMixin {
    /** How many levels below a section are searched for finer replacements. */
    private static final int MAX_DEPTH = 4;

    @Shadow
    @Final
    private QuadTreeTickNodeHolder tickNodeHolder;

    /**
     * Set when our code failed inside DH's tree update. DH only catches {@code Exception} there; anything else
     * escaping would stop all LOD rendering, so every hook catches everything and switches itself off instead.
     */
    @Unique
    private static volatile boolean fatamorgana$broken;

    @Inject(method = "onDesiredDetailLevel", at = @At("HEAD"), cancellable = true)
    private void fatamorgana$keepFinerUntilReady(QuadNode<LodRenderSection> quadNode, QuadNode<LodRenderSection> parentNode,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (fatamorgana$broken) {
            return;
        }
        try {
            keepFinerUntilReady(quadNode, parentNode, callback);
        } catch (Throwable e) {
            fatamorgana$fail(e);
        }
    }

    @Unique
    private void keepFinerUntilReady(QuadNode<LodRenderSection> quadNode, QuadNode<LodRenderSection> parentNode,
                                     CallbackInfoReturnable<Boolean> callback) {
        if (!Config.ENABLED.get() || renders(quadNode)
                || !((QuadTree<?>) (Object) this).isSectionPosInBounds(quadNode.sectionPos)
                || tickNodeHolder.getEnabledNodes().contains(parentNode)) {
            return;
        }
        List<QuadNode<LodRenderSection>> cover = new ArrayList<>();
        if (coveredByFiner(quadNode, cover, MAX_DEPTH)) {
            // Going coarser: the finer sections stay until this one has terrain. The section itself keeps loading
            // (queued before this method); once it renders, DH swaps it in and deletes the children as usual.
            cover.forEach(tickNodeHolder::addEnableNode);
            tickNodeHolder.addDisableNode(quadNode);
            HoleStats.covered();
            callback.setReturnValue(true);
        } else if (quadNode.value != null && quadNode.value.canRender()) {
            // Built but empty (no data generated for it yet): report it as not renderable, so the coarser parent
            // that has terrain here keeps rendering instead of a hole. DH itself treats any built section as ready.
            tickNodeHolder.addDisableNode(quadNode);
            HoleStats.empty();
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "onDesiredDetailLevel", at = @At("RETURN"))
    private void fatamorgana$collectHoleCandidates(QuadNode<LodRenderSection> quadNode, QuadNode<LodRenderSection> parentNode,
                                                   CallbackInfoReturnable<Boolean> callback) {
        // Not rendering itself; whether an ancestor covers it is only known once the whole tree is updated.
        if (fatamorgana$broken || callback.getReturnValueZ()) {
            return;
        }
        try {
            HoleStats.candidate(quadNode);
        } catch (Throwable e) {
            fatamorgana$fail(e);
        }
    }

    @Inject(method = "updateAllRenderSections", at = @At("TAIL"))
    private void fatamorgana$countHoles(CallbackInfo callback) {
        if (fatamorgana$broken) {
            return;
        }
        try {
            HoleStats.endTick(tickNodeHolder.getEnabledNodes(), tickNodeHolder.getEnableDeleteChildrenNodes());
        } catch (Throwable e) {
            fatamorgana$fail(e);
        }
    }

    @Unique
    private static void fatamorgana$fail(Throwable error) {
        fatamorgana$broken = true;
        Fatamorgana.LOGGER.error("Fata Morgana's Distant Horizons render hooks failed and are switched off", error);
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

    /** Has terrain on screen: built, and not built empty. */
    private static boolean renders(QuadNode<LodRenderSection> node) {
        return node.value != null && node.value.canRender() && ((RenderSectionContent) node.value).fatamorgana$quads() != 0;
    }
}
