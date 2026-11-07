package com.pockyl.dhturbo.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.client.ClientGeneration;
import com.pockyl.dhturbo.client.DemoKeys;
import com.pockyl.dhturbo.network.WorldgenInfoPayload;
import com.pockyl.dhturbo.network.WorldgenSharing;
import com.pockyl.dhturbo.platform.Platform;

/** The client part on Fabric: the channel's receiver, leaving a server, demo keys. */
public final class DhTurboFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Read on the network thread, handled on the game thread.
        ClientPlayNetworking.registerGlobalReceiver(FabricPlatform.CHANNEL, (client, handler, buffer, sender) -> {
            WorldgenInfoPayload payload = WorldgenInfoPayload.decode(buffer);
            client.execute(() -> WorldgenSharing.received(payload));
        });
        // Leaving a server forgets its seed and generators (Distant Horizons is optional, guard its classes).
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientGeneration.reset());
        }
        if (DhTurbo.DEBUG) {
            DemoKeys.keys().forEach(KeyBindingHelper::registerKeyBinding);
            ClientTickEvents.END_CLIENT_TICK.register(client -> DemoKeys.tick());
        }
    }
}
