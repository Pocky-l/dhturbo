package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.level.DhClientServerLevel;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.EmptySectionQueue;
import com.pockyl.dhturbo.client.RenderSectionContent;
import com.pockyl.dhturbo.dh.HorizonFill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps LODs with terrain on screen until whatever replaces them has terrain too.
 * <p>
 * When the player moves away from an area, Distant Horizons switches it to a coarser section and stops drawing the
 * finer ones at once, although the coarser section still has to be loaded (and, when its data is missing, generated).
 * The area is a hole meanwhile and "loads again" a moment later. Going the other way DH already waits for all
 * children, so this does the same for the way back: as long as the finer sections below fully cover the area, they
 * keep rendering. Likewise going finer: DH counts a section as ready once it is built, also when it was built empty
 * because its data is not generated yet (a 64-block hole on screen); empty sections are not counted as ready here, so
 * the coarser parent stays. Only while DH Turbo generates, so a comparison with plain DH stays honest.
 * <p>
 * Also steers what the tree asks for on ground nobody has seen yet, see {@link HorizonFill}, and spares it rebuilds.
 */
@Mixin(value = LodQuadTree.class, remap = false)
abstract class LodQuadTreeMixin {
    /** How many levels below a section are searched for finer replacements. */
    private static final int MAX_DEPTH = 4;
    /** The smallest radius of the ring checked for stored tiles, and the coarsest detail level checked on it. */
    private static final int UNSEEN_RING_BLOCKS = 1024;
    private static final byte UNSEEN_MAX_DETAIL = 3;
    /** How long the sections around a changed one wait for further changes before they are rebuilt. */
    private static final long NEIGHBOUR_DELAY_NANOS = 2_000_000_000L;

    @Shadow
    @Final
    private QuadTreeTickNodeHolder tickNodeHolder;

    /**
     * Set when our code failed inside DH's tree update. DH only catches {@code Exception} there; anything else
     * escaping would stop all LOD rendering, so every hook catches everything and switches itself off instead.
     */
    @Unique
    private static volatile boolean dhturbo$broken;

    @Shadow
    @Final
    private Set<Long> missingGenerationPosSet;
    @Shadow
    @Final
    private Set<Long> queuedGenerationPosSet;
    @Shadow
    @Final
    private AtomicBoolean queueThreadRunningRef;
    @Shadow
    @Final
    private FullDataSourceProviderV2 fullDataSourceProvider;
    @Shadow
    @Final
    private IDhClientLevel level;
    @Shadow
    @Final
    public int blockRenderDistanceDiameter;

    /**
     * Horizon first ({@link HorizonFill}): how many levels coarser than DH's wish this tree wants its sections right now;
     * 0 in the last pass, which wants what DH wants, -1 before the first tick.
     */
    @Unique
    private volatile int dhturbo$shift = -1;
    /** From the first tick of a fill until its last pass is through. */
    @Unique
    private boolean dhturbo$filling;
    /** Set once the tree has decided whether to fill. */
    @Unique
    private boolean dhturbo$decided;
    /** The coarsest level of this fill. */
    @Unique
    private int dhturbo$top;
    @Unique
    private long dhturbo$passStart;
    /** Sections at the wanted level in the last tree update, and how many of them have their terrain on screen. */
    @Unique
    private int dhturbo$wanted;
    @Unique
    private int dhturbo$ready;

    @Shadow
    private byte minRootRenderDetailLevel;
    @Shadow
    @Final
    private AtomicBoolean requeueAllRetrievalTasksRef;

    @Shadow
    @Final
    private ConcurrentLinkedQueue<Long> sectionsToReload;
    /** Neighbours of changed sections and when they last got a new neighbour. */
    @Unique
    private final Map<Long, Long> dhturbo$neighbours = new ConcurrentHashMap<>();

    /**
     * DH rebuilds a changed section and its four neighbours at once, and neighbours only for the faces along their shared
     * edge. Tiles arrive side by side, so a section was rebuilt up to five times in a few seconds; rebuilding is what
     * takes the most time after generating. Neighbours now wait until the tiles around them have settled.
     */
    @Inject(method = "queuePosToReload", at = @At("HEAD"), cancellable = true)
    private void dhturbo$deferNeighbours(long pos, CallbackInfo callback) {
        if (dhturbo$broken || !Config.ENABLED.get()) {
            return;
        }
        try {
            long now = System.nanoTime();
            long[] neighbours = new long[EDhDirection.CARDINAL_COMPASS.length];
            for (int i = 0; i < neighbours.length; i++) {
                neighbours[i] = DhSectionPos.getAdjacentPos(pos, EDhDirection.CARDINAL_COMPASS[i]);
            }
            sectionsToReload.add(pos);
            // Its own rebuild reads the neighbours as they are now.
            dhturbo$neighbours.remove(pos);
            for (long neighbour : neighbours) {
                dhturbo$neighbours.put(neighbour, now);
            }
            callback.cancel();
        } catch (Throwable e) {
            dhturbo$fail(e);
        }
    }

    @Inject(method = "updateAllRenderSections", at = @At("HEAD"))
    private void dhturbo$reloadSettledNeighbours(DhBlockPos2D playerPos, CallbackInfo callback) {
        if (dhturbo$neighbours.isEmpty()) {
            return;
        }
        // Also when the hooks are off: these sections wait for a rebuild nobody else will start.
        long now = System.nanoTime();
        long delay = dhturbo$broken ? 0 : NEIGHBOUR_DELAY_NANOS;
        dhturbo$neighbours.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < delay) {
                return false;
            }
            sectionsToReload.add(entry.getKey());
            return true;
        });
    }

    @Inject(method = "updateAllRenderSections", at = @At("HEAD"))
    private void dhturbo$updateShift(DhBlockPos2D playerPos, CallbackInfo callback) {
        if (dhturbo$broken || dhturbo$decided && !dhturbo$filling) {
            return;
        }
        try {
            if (!dhturbo$decided) {
                dhturbo$decided = true;
                dhturbo$top = dhturbo$unseenGround(playerPos) ? HorizonFill.firstLevel(blockRenderDistanceDiameter) : 0;
                dhturbo$shift = dhturbo$top;
                dhturbo$filling = dhturbo$top > 0;
                dhturbo$passStart = System.nanoTime();
                if (dhturbo$filling) {
                    HorizonFill.started();
                }
                if (DhTurbo.DEBUG) {
                    DhTurbo.LOGGER.info("[horizon] {} starts at detail {}", level.getLevelWrapper().getDimensionName(), dhturbo$top);
                }
            } else if (HorizonFill.passDone(dhturbo$passStart, dhturbo$shift == 0, dhturbo$wanted, dhturbo$ready)) {
                if (DhTurbo.DEBUG) {
                    DhTurbo.LOGGER.info("[horizon] pass {} done in {} ms: {} of {} sections", dhturbo$shift,
                            (System.nanoTime() - dhturbo$passStart) / 1_000_000, dhturbo$ready, dhturbo$wanted);
                }
                dhturbo$passStart = System.nanoTime();
                if (dhturbo$shift > 0) {
                    dhturbo$shift--;
                    if (dhturbo$shift == 0) {
                        // Sections that got a preview ask again when DH wants them for good.
                        requeueAllRetrievalTasksRef.set(true);
                        HorizonFill.previewsOver();
                    }
                } else {
                    dhturbo$filling = false;
                    HorizonFill.finished();
                    dhturbo$rebuildLevelEdges(playerPos);
                }
            }
            dhturbo$wanted = 0;
            dhturbo$ready = 0;
            LocalPlayer player = Minecraft.getInstance().player;
            if (dhturbo$filling && player != null) {
                HorizonFill.look(player.getYRot(), blockRenderDistanceDiameter);
            }
            // DH caps what it wants at a level that depends on the view; this runs right after it worked that out.
            if (dhturbo$shift > 0 && dhturbo$top > minRootRenderDetailLevel) {
                minRootRenderDetailLevel = (byte) dhturbo$top;
            }
        } catch (Throwable e) {
            dhturbo$fail(e);
        }
    }

    /** Sections next to another detail level were built without a neighbour to compare with during the fill. */
    @Unique
    private void dhturbo$rebuildLevelEdges(DhBlockPos2D playerPos) {
        List<QuadNode<LodRenderSection>> shown = new ArrayList<>(tickNodeHolder.getEnabledNodes());
        shown.addAll(tickNodeHolder.getEnableDeleteChildrenNodes());
        LodQuadTree tree = (LodQuadTree) (Object) this;
        for (QuadNode<LodRenderSection> node : shown) {
            if (node == null) {
                continue;
            }
            int detail = DhSectionPos.getDetailLevel(node.sectionPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS) {
                if (tree.calcExpectedDetailLevel(playerPos, DhSectionPos.getAdjacentPos(node.sectionPos, direction)) != detail) {
                    sectionsToReload.add(node.sectionPos);
                    break;
                }
            }
        }
    }

    /** Leaving the world or dimension in the middle of a fill ends it. */
    @Inject(method = "close", at = @At("HEAD"))
    private void dhturbo$endFill(CallbackInfo callback) {
        if (dhturbo$filling) {
            dhturbo$filling = false;
            HorizonFill.finished();
        }
    }

    /**
     * No stored tile on a ring around the player: nothing here has been seen before. The ring lies outside the real chunks
     * around the player and only fine sections are checked, because a coarse one holds those chunks' data within seconds.
     */
    @Unique
    private boolean dhturbo$unseenGround(DhBlockPos2D playerPos) {
        if (!Config.ENABLED.get() || !(level instanceof DhClientServerLevel)
                || !HorizonFill.applies(level.getLevelWrapper().getDimensionName())) {
            return false;
        }
        int radius = Math.max(blockRenderDistanceDiameter / 6, UNSEEN_RING_BLOCKS);
        for (int direction = 0; direction < 8; direction++) {
            double angle = direction * Math.PI / 4;
            int x = playerPos.x + (int) (radius * Math.cos(angle));
            int z = playerPos.z + (int) (radius * Math.sin(angle));
            for (byte detail = 0; detail <= UNSEEN_MAX_DETAIL; detail++) {
                long pos = DhSectionPos.encodeContaining((byte) (detail + 6), new DhBlockPos(x, 0, z));
                if (fullDataSourceProvider.repo.existsWithKey(pos)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Inject(method = "calcExpectedDetailLevel(Lcom/seibel/distanthorizons/core/pos/blockPos/DhBlockPos2D;J)B",
            at = @At("RETURN"), cancellable = true)
    private void dhturbo$applyShift(DhBlockPos2D playerPos, long sectionPos, CallbackInfoReturnable<Byte> callback) {
        int shift = dhturbo$shift;
        if (shift <= 0 || dhturbo$broken) {
            return;
        }
        try {
            int wished = callback.getReturnValueB();
            callback.setReturnValue((byte) Math.max(wished, Math.min(wished + shift, dhturbo$top)));
            if (DhSectionPos.getDetailLevel(sectionPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL > wished) {
                HorizonFill.markPreview(sectionPos);
            }
        } catch (Throwable e) {
            dhturbo$fail(e);
        }
    }

    /** What goes into DH's waiting list first, see {@link HorizonFill#look}. */
    @ModifyVariable(method = "startQueuedRetrievalTasks", at = @At("HEAD"), argsOnly = true)
    private DhBlockPos2D dhturbo$orderFromAhead(DhBlockPos2D playerPos) {
        return dhturbo$filling ? new DhBlockPos2D(playerPos.x + HorizonFill.aheadX(), playerPos.z + HorizonFill.aheadZ()) : playerPos;
    }

    /** While the horizon fills, requests go on although the loaders are busy building the sections the tree just made. */
    @Inject(method = "threadPoolCanAcceptWorldGenTasks", at = @At("HEAD"), cancellable = true)
    private static void dhturbo$keepRequesting(CallbackInfoReturnable<Boolean> callback) {
        if (!dhturbo$broken && HorizonFill.filling()) {
            callback.setReturnValue(true);
        }
    }

    /** A section between the top of the tree and the wanted level only counts as covering its area when it has terrain. */
    @Redirect(method = "onDetailLevelTooLow", at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/render/QuadTree/LodRenderSection;canRender()Z"))
    private boolean dhturbo$canCover(LodRenderSection section) {
        if (dhturbo$broken || !Config.ENABLED.get() || !section.canRender()) {
            return section.canRender();
        }
        try {
            RenderSectionContent content = (RenderSectionContent) section;
            return content.dhturbo$quads() != 0 || content.dhturbo$generatedEmpty();
        } catch (Throwable e) {
            dhturbo$fail(e);
            return section.canRender();
        }
    }

    @Inject(method = "onDesiredDetailLevel", at = @At("HEAD"), cancellable = true)
    private void dhturbo$keepFinerUntilReady(QuadNode<LodRenderSection> quadNode, QuadNode<LodRenderSection> parentNode,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (dhturbo$broken) {
            return;
        }
        try {
            if (dhturbo$filling && ((QuadTree<?>) (Object) this).isSectionPosInBounds(quadNode.sectionPos)) {
                dhturbo$wanted++;
                if (renders(quadNode)) {
                    dhturbo$ready++;
                }
            }
            keepFinerUntilReady(quadNode, parentNode, callback);
        } catch (Throwable e) {
            dhturbo$fail(e);
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
            callback.setReturnValue(true);
        } else if (quadNode.value != null && quadNode.value.canRender()) {
            // Built but empty (no data generated for it yet): report it as not renderable, so the coarser parent
            // that has terrain here keeps rendering instead of a hole. DH itself treats any built section as ready.
            tickNodeHolder.addDisableNode(quadNode);
            // DH would request its generation only if it were shown; queue it for generation anyway.
            ((EmptySectionQueue) tickNodeHolder).dhturbo$queueEmpty(quadNode);
            callback.setReturnValue(false);
        }
    }

    @Unique
    private static void dhturbo$fail(Throwable error) {
        dhturbo$broken = true;
        HorizonFill.abandon();
        DhTurbo.LOGGER.error("DH Turbo's Distant Horizons render hooks failed and are switched off", error);
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

    /** Has what it will ever have on screen: built, and not built empty because it is not generated yet. */
    private static boolean renders(QuadNode<LodRenderSection> node) {
        if (node.value == null || !node.value.canRender()) {
            return false;
        }
        RenderSectionContent content = (RenderSectionContent) node.value;
        return content.dhturbo$quads() != 0 || content.dhturbo$generatedEmpty();
    }
}
