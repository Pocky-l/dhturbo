package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.HorizonFill;

/**
 * Distant Horizons reads the section next door, at the same detail level, to leave out the faces along a shared edge. Where
 * the detail level changes that section often has no data of its own: our tiles stay in their section, nothing is copied
 * down from the coarser one. An empty neighbour reads as open air and the edge gets a wall, which shows as a straight bright
 * line on water. So a missing neighbour is read from the section above it, for this one look only.
 */
@Mixin(value = FullDataSourceProviderV2.class, remap = false)
abstract class FullDataSourceProviderV2Mixin {
    @Unique
    private static volatile boolean dhturbo$broken;

    @Inject(method = "getAdjForDirection", at = @At("RETURN"))
    private void dhturbo$neighbourFromAbove(long pos, EDhDirection direction, CallbackInfoReturnable<FullDataSourceV2> callback) {
        FullDataSourceV2 neighbour = callback.getReturnValue();
        // During a fill nearly every neighbour is missing and sections are replaced within seconds anyway; the tree rebuilds
        // the edges between detail levels when it is through.
        if (dhturbo$broken || neighbour == null || !neighbour.isEmpty || !Config.ENABLED.get() || HorizonFill.filling()) {
            return;
        }
        try {
            try (FullDataSourceV2 above = ((FullDataSourceProviderV2) (Object) this).get(DhSectionPos.getParentPos(pos))) {
                if (above != null && !above.isEmpty) {
                    neighbour.updateFromDataSource(above);
                    // Only the shared edge is looked at; the whole section would cost a full colour pass.
                    neighbour.clearAllNonAdjData(direction);
                }
            }
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's look at neighbouring sections failed and is switched off", e);
        }
    }
}
