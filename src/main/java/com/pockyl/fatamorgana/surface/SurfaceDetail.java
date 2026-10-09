package com.pockyl.fatamorgana.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Top block of a land column beyond the biome's default: snow by temperature and height, bare rock on steep slopes,
 * terracotta bands in badlands. Approximates vanilla surface rules from what the LOD tile knows (height, slope).
 */
public final class SurfaceDetail {
    /**
     * Height change per horizontal block from which a slope counts as steep. Vanilla's {@code steep} surface
     * condition fires at 4 blocks over 2 (slope 2); LOD heights are interpolated and smoother, so a bit lower.
     */
    public static final double STEEP = 1.4;
    /** Badlands keep red sand on low flat ground and show their bands above. */
    private static final int BADLANDS_BANDS_FROM_Y = 80;
    private static final Block[] BANDS = {
            Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.TERRACOTTA, Blocks.YELLOW_TERRACOTTA,
            Blocks.TERRACOTTA, Blocks.BROWN_TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA, Blocks.ORANGE_TERRACOTTA
    };

    private SurfaceDetail() {
    }

    public static BlockState top(Holder<Biome> biome, BiomeLook look, long seed, int x, int y, int z, double slope) {
        boolean steep = slope >= STEEP;
        if (biome.is(BiomeTags.IS_BADLANDS) && (steep || y >= BADLANDS_BANDS_FROM_Y)) {
            return band(seed, y).defaultBlockState();
        }
        if (biome.value().coldEnoughToSnow(new BlockPos(x, y, z))) {
            return steep ? Blocks.STONE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (!steep) {
            return look.top();
        }
        Block top = look.top().getBlock();
        if (top == Blocks.SAND) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        if (top == Blocks.RED_SAND) {
            return Blocks.RED_SANDSTONE.defaultBlockState();
        }
        return Blocks.STONE.defaultBlockState();
    }

    /** Horizontal clay bands: a fixed pattern shifted by the seed, two blocks per band. */
    private static Block band(long seed, int y) {
        int offset = (int) Math.floorMod(seed, BANDS.length);
        return BANDS[Math.floorMod(Math.floorDiv(y, 2) + offset, BANDS.length)];
    }
}
