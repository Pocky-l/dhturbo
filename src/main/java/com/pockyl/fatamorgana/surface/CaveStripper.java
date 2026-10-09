package com.pockyl.fatamorgana.surface;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Removes caves from a terrain density function, leaving the solid "shape" of the land.
 * <p>
 * Cave networks are registered density functions under a {@code caves/} path (vanilla: {@code overworld/caves/...};
 * datapacks usually follow it). They only ever cut air into terrain, so replacing each with a large constant turns
 * every {@code min(terrain, cave)} into plain terrain. That is both cheaper (cave noises are the most expensive 3D
 * part) and better looking from afar: cave mouths otherwise show up as random pits in the distant surface.
 * <p>
 * {@link DensityFunction#mapAll} loses registry keys (holders are rebuilt as direct holders), so cave nodes are
 * recognised by structural equality with the cave functions rewritten by the same visitor.
 */
final class CaveStripper implements DensityFunction.Visitor {
    private static final DensityFunction SOLID = DensityFunctions.constant(64.0);
    private static final int MAX_PASSES = 8;

    private final Set<DensityFunction> caveNodes = new HashSet<>();

    private CaveStripper() {
    }

    /** Returns {@code density} without caves, or {@code density} itself when it uses no registered cave function. */
    static DensityFunction strip(Registry<DensityFunction> registry, DensityFunction density) {
        CaveStripper stripper = new CaveStripper();
        // Cave functions reference each other; repeat until the rewritten forms stop changing.
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Set<DensityFunction> next = new HashSet<>();
            for (Map.Entry<ResourceKey<DensityFunction>, DensityFunction> entry : registry.entrySet()) {
                if (entry.getKey().location().getPath().contains("caves/")) {
                    next.add(new DensityFunctions.HolderHolder(Holder.direct(entry.getValue().mapAll(stripper))));
                }
            }
            if (next.equals(stripper.caveNodes)) {
                break;
            }
            stripper.caveNodes.clear();
            stripper.caveNodes.addAll(next);
        }
        return stripper.caveNodes.isEmpty() ? density : density.mapAll(stripper);
    }

    @Override
    public DensityFunction apply(DensityFunction function) {
        return caveNodes.contains(function) ? SOLID : function;
    }
}
