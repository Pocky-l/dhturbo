package com.pockyl.dhturbo.fabric.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.pockyl.dhturbo.fabric.StatusDebugEntry;

import java.util.Map;

/** The generator's line on the debug screen (F3): an entry of its own, in the default profile; see {@link StatusDebugEntry}. */
@Mixin(DebugScreenEntries.class)
abstract class DebugScreenEntriesMixin {
    @Shadow
    @Final
    @Mutable
    public static Map<DebugScreenProfile, Map<Identifier, DebugScreenEntryStatus>> PROFILES;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void dhturbo$status(CallbackInfo ci) {
        PROFILES = StatusDebugEntry.register(PROFILES);
    }
}
