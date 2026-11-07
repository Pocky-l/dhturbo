package com.pockyl.dhturbo.dh;

import com.pockyl.dhturbo.Config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Horizon first: on ground nobody has seen yet, Distant Horizons is told to want everything several levels coarser than it
 * would, so a few tiles cover the whole view within seconds, and the wish comes down one level at a time as tiles arrive. A
 * tile costs about the same at every detail level, so the coarsest ones buy the most land, and because each pass halves the
 * column width everywhere at once, the near ground sharpens first. Each section replaces its parent, four at a time
 * ({@code LodQuadTreeMixin} keeps the parent until all four have terrain).
 * <p>
 * No DH classes here: the mixin passes in what it reads from the tree, so this also loads without DH.
 */
public final class HorizonFill {
    /** The widest view is covered by about this many tiles per side at the coarsest level. */
    private static final int TILES_PER_SIDE = 6;
    /** Width of a tile in blocks at detail level 0. */
    private static final int TILE_WIDTH = 64;
    /** Sections still missing when the next pass may start, so the workers never run dry between passes. */
    private static final int SECTIONS_IN_FLIGHT = 8;
    /** A pass gets this long at least: new sections have to reach the tree first. */
    private static final long PASS_SETTLE_NANOS = 100_000_000L;
    /** A pass that does not finish (generation paused, a level nobody can draw) is left after this long. */
    private static final long PASS_GIVE_UP_NANOS = 20_000_000_000L;
    /** The same for the last pass, which holds most of the tiles. */
    private static final long LAST_PASS_GIVE_UP_NANOS = 180_000_000_000L;

    /** The centre of attention lies this share of the view's width ahead of the player. */
    private static final double AHEAD_SHARE = 1.0 / 8;

    private static volatile int aheadX;
    private static volatile int aheadZ;
    private static final Set<String> GENERATED = ConcurrentHashMap.newKeySet();
    /** Sections wanted coarser than DH wants them: their tiles are replaced within seconds. */
    private static final Set<Long> PREVIEWS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FILLING = new AtomicInteger();

    private HorizonFill() {
    }

    /** DH Turbo answers this dimension's requests; the name is DH's. */
    public static void generates(String dimensionName) {
        GENERATED.add(dimensionName);
    }

    /** DH's own generator answers this dimension; it cannot draw coarse sections straight away. */
    public static void leftToDh(String dimensionName) {
        GENERATED.remove(dimensionName);
    }

    /** Whether a tree of this dimension should start coarse. */
    public static boolean applies(String dimensionName) {
        return Config.ENABLED.get() && GENERATED.contains(dimensionName)
                && !Config.EXCLUDED_DIMENSIONS.get().contains(dimensionName);
    }

    /** The detail level the view starts at, for a view of this many blocks across. */
    public static byte firstLevel(int viewDiameter) {
        int level = 0;
        while ((TILE_WIDTH << level) * TILES_PER_SIDE < viewDiameter) {
            level++;
        }
        return (byte) level;
    }

    /** Whether the current pass is through: nearly all sections it wants have their terrain on screen. */
    public static boolean passDone(long passStart, boolean last, int wanted, int ready) {
        long age = System.nanoTime() - passStart;
        return age >= (last ? LAST_PASS_GIVE_UP_NANOS : PASS_GIVE_UP_NANOS)
                || age >= PASS_SETTLE_NANOS && wanted > 0 && wanted - ready <= SECTIONS_IN_FLIGHT;
    }

    /**
     * While the view fills, tiles are ordered from a point ahead of the player, so what the player looks at sharpens first;
     * everything else is already on screen, a pass or two coarser.
     *
     * @param yaw the player's yaw in degrees
     */
    public static void look(float yaw, int viewDiameter) {
        double radians = Math.toRadians(yaw);
        aheadX = (int) (-Math.sin(radians) * viewDiameter * AHEAD_SHARE);
        aheadZ = (int) (Math.cos(radians) * viewDiameter * AHEAD_SHARE);
    }

    /** Offset of the point tiles are ordered from, in blocks; 0 unless a view is filling. */
    public static int aheadX() {
        return aheadX;
    }

    public static int aheadZ() {
        return aheadZ;
    }

    /** This section is only wanted until a finer pass replaces it. */
    public static void markPreview(long sectionPos) {
        PREVIEWS.add(sectionPos);
    }

    /** Whether the tile asked for now is such a section. */
    public static boolean takePreview(long sectionPos) {
        return PREVIEWS.remove(sectionPos);
    }

    /** From here on DH gets what it wants: nothing marked so far is a preview any more. */
    public static void previewsOver() {
        PREVIEWS.clear();
    }

    /** The hooks into DH switched themselves off: nothing will report the end of a fill. */
    public static void abandon() {
        FILLING.set(0);
        over();
    }

    private static void over() {
        PREVIEWS.clear();
        aheadX = 0;
        aheadZ = 0;
        WorkerPool.urgent(false);
    }

    /** A tree started filling. */
    public static void started() {
        if (FILLING.incrementAndGet() == 1) {
            WorkerPool.urgent(true);
        }
    }

    /** A tree reached full detail. */
    public static void finished() {
        if (FILLING.updateAndGet(count -> Math.max(0, count - 1)) == 0) {
            over();
        }
    }

    /** Whether any tree is filling: requests then go on even while DH's loaders are busy. */
    public static boolean filling() {
        return FILLING.get() > 0;
    }
}
