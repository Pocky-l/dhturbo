package com.pockyl.dhturbo.surface;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;

import com.pockyl.dhturbo.compat.ReTerraForged;

/**
 * Prepares a {@link RandomState} the generator builds so it computes the terrain like the level's own. Vanilla and
 * data-pack worlds need nothing; some terrain mods attach their state to the level's random state only
 * ({@link ReTerraForged}).
 */
@FunctionalInterface
public interface NoiseBinder {
    /** For generators without a server level (clients, tests): nothing to bind. */
    NoiseBinder NONE = state -> {
    };

    void bind(RandomState state);

    /** Binds random states the way the given level's own one is set up. */
    static NoiseBinder of(ServerLevel level) {
        return state -> ReTerraForged.bind(state, level);
    }
}
