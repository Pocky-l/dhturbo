package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.QuadTree.QuadTreeTickNodeHolder;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.EmptySectionQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Feeds empty sections that {@code LodQuadTreeMixin} keeps off screen into Distant Horizons' generation list.
 * <p>
 * DH requests generation only for the sections it enables this tick ({@code getWorldGenNodesNearToFar} returns the
 * "enable, delete children" nodes), relying on showing empty sections (holes) until their data arrives. Once an empty
 * section is hidden behind its parent, it would never be generated; here it is added back, the whole list sorted
 * nearest first as DH does.
 */
@Mixin(value = QuadTreeTickNodeHolder.class, remap = false)
abstract class QuadTreeTickNodeHolderMixin implements EmptySectionQueue {
    @Unique
    private static volatile boolean dhturbo$broken;
    @Unique
    private final List<QuadNode<LodRenderSection>> dhturbo$empty = new ArrayList<>();

    @Override
    public void dhturbo$queueEmpty(QuadNode<LodRenderSection> node) {
        dhturbo$empty.add(node);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void dhturbo$clear(CallbackInfo callback) {
        dhturbo$empty.clear();
    }

    @Inject(method = "getWorldGenNodesNearToFar", at = @At("RETURN"))
    private void dhturbo$addEmpty(DhBlockPos2D center, CallbackInfoReturnable<ArrayList<QuadNode<LodRenderSection>>> callback) {
        if (dhturbo$broken || dhturbo$empty.isEmpty()) {
            return;
        }
        try {
            ArrayList<QuadNode<LodRenderSection>> nodes = callback.getReturnValue();
            for (QuadNode<LodRenderSection> node : dhturbo$empty) {
                if (!nodes.contains(node)) {
                    nodes.add(node);
                }
            }
            nodes.sort(Comparator.comparingLong(node -> dhturbo$distanceSq(node.sectionPos, center)));
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's Distant Horizons generation hook failed and is switched off", e);
        }
    }

    @Unique
    private static long dhturbo$distanceSq(long pos, DhBlockPos2D center) {
        long dx = DhSectionPos.getCenterBlockPosX(pos) - center.x;
        long dz = DhSectionPos.getCenterBlockPosZ(pos) - center.z;
        return dx * dx + dz * dz;
    }
}
