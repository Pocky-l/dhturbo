package com.pockyl.dhturbo.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.ReceivedWorldgen;

import java.util.ArrayList;
import java.util.List;

/** Sends players the world generation info when they join, if the server allows it (see {@link Config#SHARE_WORLDGEN}). */
public final class WorldgenSharing {
    private WorldgenSharing() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(WorldgenSharing::registerPayloads);
        NeoForge.EVENT_BUS.addListener(WorldgenSharing::playerJoined);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(WorldgenInfoPayload.TYPE, WorldgenInfoPayload.STREAM_CODEC,
                (payload, context) -> {
                    ReceivedWorldgen.set(payload);
                    if (ModList.get().isLoaded("distanthorizons")) {
                        // Same plan as on our own servers: no regeneration pass over tiles we already made.
                        DhIntegration.applyPlan();
                    }
                    DhTurbo.LOGGER.info("The server shares its world generation ({} levels), distant terrain is generated locally",
                            payload.levels().size());
                });
    }

    private static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Config.SHARE_WORLDGEN.get()
                || !player.connection.hasChannel(WorldgenInfoPayload.TYPE)) {
            return;
        }
        // The singleplayer host generates through the integrated server itself; it has no use for the info.
        if (player.server.isSingleplayerOwner(player.getGameProfile())) {
            return;
        }
        List<LevelGenInfo> levels = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            LevelGenInfo.of(level).ifPresent(levels::add);
        }
        if (!levels.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new WorldgenInfoPayload(player.server.overworld().getSeed(), levels));
        }
    }
}
