package com.pockyl.dhturbo.fabric.mixin;

import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.dh.TurboActivity;
import com.pockyl.dhturbo.platform.Platform;

import java.util.List;

/** The generator's line on the right side of the debug screen (F3); Fabric has no event for it. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugScreenOverlayMixin {
    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void dhturbo$debugText(CallbackInfoReturnable<List<String>> cir) {
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            cir.getReturnValue().add(TurboActivity.debugLine());
        }
    }
}
