package com.pockyl.fatamorgana.mixin;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.fatamorgana.Config;
import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.dh.DataLossStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Stops finer LODs from erasing coarser ones that are already on screen.
 * <p>
 * When a tile is written, Distant Horizons rebuilds the quarter of every coarser section above it from that tile's
 * columns, unconditionally. Where the finer level has no data yet, the coarse columns are replaced with nothing, so
 * terrain the player already sees ahead turns into a hole until the neighbouring fine tiles are generated too. The
 * opposite direction (coarse into fine) already keeps existing data; this does the same here: columns whose four
 * finer columns are all ungenerated keep what they had. Only while Fata Morgana generates (fast fine tiles near the
 * player make it frequent), so a comparison with plain DH stays honest.
 */
@Mixin(value = FullDataSourceV2.class, remap = false)
abstract class FullDataSourceV2Mixin {
    @Unique
    private static final ThreadLocal<List<Object[]>> fatamorgana$kept = ThreadLocal.withInitial(ArrayList::new);
    @Unique
    private static volatile boolean fatamorgana$broken;

    @Shadow
    @Final
    public LongArrayList[] dataPoints;
    @Shadow
    @Final
    public ByteArrayList columnGenerationSteps;
    @Shadow
    @Final
    public ByteArrayList columnWorldCompressionMode;

    @Shadow
    public abstract long getPos();

    @Unique
    private static final ThreadLocal<int[]> fatamorgana$filledBefore = ThreadLocal.withInitial(() -> new int[1]);

    @Inject(method = "updateFromDataSource", at = @At("HEAD"))
    private void fatamorgana$countBefore(FullDataSourceV2 input, CallbackInfoReturnable<Boolean> callback) {
        if (fatamorgana$broken || !Fatamorgana.DEBUG) {
            return;
        }
        try {
            fatamorgana$filledBefore.get()[0] = DataLossStats.filledColumns((FullDataSourceV2) (Object) this);
        } catch (Throwable e) {
            fatamorgana$fail(e);
        }
    }

    @Inject(method = "updateFromDataSource", at = @At("RETURN"))
    private void fatamorgana$countAfter(FullDataSourceV2 input, CallbackInfoReturnable<Boolean> callback) {
        if (fatamorgana$broken || !Fatamorgana.DEBUG) {
            return;
        }
        try {
            FullDataSourceV2 self = (FullDataSourceV2) (Object) this;
            DataLossStats.update(self, input, fatamorgana$filledBefore.get()[0], DataLossStats.filledColumns(self));
        } catch (Throwable e) {
            fatamorgana$fail(e);
        }
    }

    @Inject(method = "updateFromOneBelowDetailLevel", at = @At("HEAD"))
    private void fatamorgana$rememberCoarse(FullDataSourceV2 input, int[] remappedIds, CallbackInfoReturnable<Boolean> callback) {
        List<Object[]> kept = fatamorgana$kept.get();
        kept.clear();
        if (fatamorgana$broken || !Config.ENABLED.get()) {
            return;
        }
        try {
            int width = FullDataSourceV2.WIDTH;
            long pos = getPos();
            long firstChild = DhSectionPos.getChildByIndex(pos, 0);
            int offsetX = DhSectionPos.getX(input.getPos()) == DhSectionPos.getX(firstChild) ? 0 : width / 2;
            int offsetZ = DhSectionPos.getZ(input.getPos()) == DhSectionPos.getZ(firstChild) ? 0 : width / 2;
            for (int x = 0; x < width; x += 2) {
                for (int z = 0; z < width; z += 2) {
                    int recipient = FullDataSourceV2.relativePosToIndex(x / 2 + offsetX, z / 2 + offsetZ);
                    LongArrayList existing = dataPoints[recipient];
                    if (existing != null && !existing.isEmpty() && fatamorgana$ungenerated(input, x, z)) {
                        kept.add(new Object[]{recipient, new LongArrayList(existing), columnGenerationSteps.getByte(recipient),
                                columnWorldCompressionMode.getByte(recipient)});
                    }
                }
            }
        } catch (Throwable e) {
            fatamorgana$fail(e);
            kept.clear();
        }
    }

    @Inject(method = "updateFromOneBelowDetailLevel", at = @At("RETURN"))
    private void fatamorgana$restoreCoarse(FullDataSourceV2 input, int[] remappedIds, CallbackInfoReturnable<Boolean> callback) {
        List<Object[]> kept = fatamorgana$kept.get();
        try {
            for (Object[] column : kept) {
                int recipient = (int) column[0];
                // Ids stay valid: a data source's block/biome mapping only grows.
                dataPoints[recipient] = (LongArrayList) column[1];
                columnGenerationSteps.set(recipient, (byte) column[2]);
                columnWorldCompressionMode.set(recipient, (byte) column[3]);
            }
        } catch (Throwable e) {
            fatamorgana$fail(e);
        } finally {
            kept.clear();
        }
    }

    /** True when none of the 2x2 finer columns at (x, z) of the input has been generated. */
    @Unique
    private static boolean fatamorgana$ungenerated(FullDataSourceV2 input, int x, int z) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                int index = FullDataSourceV2.relativePosToIndex(x + dx, z + dz);
                byte step = input.columnGenerationSteps.getByte(index);
                LongArrayList column = input.dataPoints[index];
                if (step != EDhApiWorldGenerationStep.EMPTY.value || (column != null && !column.isEmpty())) {
                    return false;
                }
            }
        }
        return true;
    }

    @Unique
    private static void fatamorgana$fail(Throwable error) {
        fatamorgana$broken = true;
        Fatamorgana.LOGGER.error("Fata Morgana's Distant Horizons data hook failed and is switched off", error);
    }
}
