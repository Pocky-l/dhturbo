package com.pockyl.dhturbo.client;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.util.objects.quadTree.QuadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import com.pockyl.dhturbo.DhTurbo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Diagnostics for LODs that should be on screen but are not: every 10 seconds logs how many sections were holes
 * while the player looked around, why (no section yet, waiting to load, loading, loaded without data), how far away
 * and at which detail level. Called from the quad tree thread only, through {@code LodQuadTreeMixin}; it lives outside
 * the mixin package because classes there cannot be loaded directly.
 */
public final class HoleStats {
    private static final long INTERVAL_NANOS = 10_000_000_000L;
    /** Levels above a section that can hold a rendering ancestor; DH trees are far shallower. */
    private static final int MAX_ANCESTOR_LEVELS = 16;
    private static final String[] REASONS = {"no section", "waiting to load", "loading", "no data"};

    private static final Set<Long> HOLES = new HashSet<>();
    private static final long[] REASON_COUNTS = new long[REASONS.length];
    private static final long[] DETAIL_COUNTS = new long[16];
    private static long distanceSum;
    private static long holeTicks;
    private static long coveredTicks;
    private static long emptyTicks;
    private static long nextReport = System.nanoTime() + INTERVAL_NANOS;

    private HoleStats() {
    }

    public static void covered() {
        coveredTicks++;
    }

    /** A built but empty section was kept off screen in favour of its parent. */
    public static void empty() {
        emptyTicks++;
    }

    private static final List<QuadNode<LodRenderSection>> CANDIDATES = new ArrayList<>();
    private static final Set<Long> RENDERED = new HashSet<>();

    public static void candidate(QuadNode<LodRenderSection> node) {
        CANDIDATES.add(node);
    }

    /** Candidates not covered by a rendering ancestor are holes on screen. */
    public static void endTick(Collection<QuadNode<LodRenderSection>> enabled, Collection<QuadNode<LodRenderSection>> enabledDeleteChildren) {
        RENDERED.clear();
        enabled.forEach(node -> RENDERED.add(node.sectionPos));
        enabledDeleteChildren.forEach(node -> RENDERED.add(node.sectionPos));
        for (QuadNode<LodRenderSection> node : CANDIDATES) {
            if (!coveredByAncestor(node.sectionPos)) {
                hole(node);
            }
        }
        CANDIDATES.clear();
        report();
    }

    private static boolean coveredByAncestor(long pos) {
        long current = pos;
        for (int level = 0; level < MAX_ANCESTOR_LEVELS; level++) {
            current = DhSectionPos.getParentPos(current);
            if (RENDERED.contains(current)) {
                return true;
            }
        }
        return false;
    }

    private static void hole(QuadNode<LodRenderSection> node) {
        LodRenderSection section = node.value;
        int reason;
        if (section == null) {
            reason = 0;
        } else if (section.gpuUploadInProgress()) {
            reason = 2;
        } else if (!section.gpuUploadComplete()) {
            reason = 1;
        } else {
            reason = 3;
        }
        holeTicks++;
        REASON_COUNTS[reason]++;
        if (HOLES.add(node.sectionPos)) {
            int detail = DhSectionPos.getDetailLevel(node.sectionPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
            DETAIL_COUNTS[Math.clamp(detail, 0, DETAIL_COUNTS.length - 1)]++;
            distanceSum += distance(node.sectionPos);
        }
    }

    private static int distance(long pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return -1;
        }
        double dx = DhSectionPos.getCenterBlockPosX(pos) - player.getX();
        double dz = DhSectionPos.getCenterBlockPosZ(pos) - player.getZ();
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private static void report() {
        long now = System.nanoTime();
        if (now < nextReport) {
            return;
        }
        nextReport = now + INTERVAL_NANOS;
        StringBuilder reasons = new StringBuilder();
        for (int i = 0; i < REASONS.length; i++) {
            reasons.append(String.format(Locale.ROOT, " %s %d;", REASONS[i], REASON_COUNTS[i]));
            REASON_COUNTS[i] = 0;
        }
        StringBuilder details = new StringBuilder();
        for (int i = 0; i < DETAIL_COUNTS.length; i++) {
            if (DETAIL_COUNTS[i] > 0) {
                details.append(String.format(Locale.ROOT, " d%d: %d;", i, DETAIL_COUNTS[i]));
                DETAIL_COUNTS[i] = 0;
            }
        }
        int sections = HOLES.size();
        DhTurbo.LOGGER.info(String.format(Locale.ROOT,
                "[holes] last 10 s: %d sections were holes (~%d blocks away),%s | hole checks:%s | kept finer: %d | empty kept off: %d",
                sections, sections == 0 ? 0 : distanceSum / sections, details, reasons, coveredTicks, emptyTicks));
        HOLES.clear();
        distanceSum = 0;
        holeTicks = 0;
        coveredTicks = 0;
        emptyTicks = 0;
    }
}
