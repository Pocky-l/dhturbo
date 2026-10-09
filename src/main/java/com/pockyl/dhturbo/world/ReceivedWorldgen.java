package com.pockyl.dhturbo.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import com.pockyl.dhturbo.network.WorldgenInfoPayload;

import org.jetbrains.annotations.Nullable;

/** What the server we are connected to told us about its world generation; empty outside such a server. */
public final class ReceivedWorldgen {
    private static volatile WorldgenInfoPayload current;

    private ReceivedWorldgen() {
    }

    public static void set(@Nullable WorldgenInfoPayload info) {
        current = info;
    }

    public static boolean present() {
        return current != null;
    }

    public static long seed() {
        return current.seed();
    }

    @Nullable
    public static LevelGenInfo forDimension(ResourceKey<Level> dimension) {
        WorldgenInfoPayload info = current;
        if (info == null) {
            return null;
        }
        for (LevelGenInfo level : info.levels()) {
            if (level.dimension().equals(dimension)) {
                return level;
            }
        }
        return null;
    }
}
