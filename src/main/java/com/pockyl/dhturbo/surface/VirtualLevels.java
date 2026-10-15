package com.pockyl.dhturbo.surface;

/** Tells the pretend worlds features and structures are placed on apart from real ones (for hooks into vanilla). */
public final class VirtualLevels {
    private VirtualLevels() {
    }

    public static boolean is(Object level) {
        return level instanceof VirtualLevel.View;
    }
}
