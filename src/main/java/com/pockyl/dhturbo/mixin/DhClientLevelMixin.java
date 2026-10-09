package com.pockyl.dhturbo.mixin;

import com.seibel.distanthorizons.core.level.ClientLevelModule;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.ClientGeneration;
import com.pockyl.dhturbo.client.ClientLevelState;

/**
 * Lets Distant Horizons "generate" in a multiplayer level when DH Turbo can generate it on the client. DH only asks
 * for LODs on a server that runs Distant Horizons; with the server's shared world generation the answers come from
 * {@code ClientTurboQueue} instead.
 */
@Mixin(value = DhClientLevel.class, remap = false)
abstract class DhClientLevelMixin implements ClientLevelState {
    @Unique
    private static volatile boolean dhturbo$broken;

    @Shadow
    @Final
    private ClientNetworkState networkState;
    @Shadow
    @Final
    public IClientLevelWrapper levelWrapper;
    @Shadow
    @Final
    public ClientLevelModule clientside;

    @Override
    public boolean dhturbo$serverSendsLods() {
        return networkState != null;
    }

    @Inject(method = "shouldDoWorldGen", at = @At("HEAD"), cancellable = true)
    private void dhturbo$generateOnClient(CallbackInfoReturnable<Boolean> callback) {
        if (dhturbo$broken || networkState != null) {
            return;
        }
        try {
            // Same conditions DH uses for downloading: the level the player is in, while LODs are being rendered.
            if (Minecraft.getInstance().level == levelWrapper.getWrappedMcObject() && clientside.isRendering()
                    && ClientGeneration.active((DhClientLevel) (Object) this)) {
                callback.setReturnValue(true);
            }
        } catch (Throwable e) {
            dhturbo$broken = true;
            DhTurbo.LOGGER.error("DH Turbo's client generation hook failed and is switched off", e);
        }
    }
}
