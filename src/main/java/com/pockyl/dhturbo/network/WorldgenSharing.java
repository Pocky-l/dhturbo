package com.pockyl.dhturbo.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.ReceivedWorldgen;

import java.util.ArrayList;
import java.util.List;

/** Sends players the world generation info when they join, if the server allows it (see {@link Config#SHARE_WORLDGEN}). */
public final class WorldgenSharing {
    private static final String PROTOCOL_VERSION = "1";
    /** Optional on both sides: players without the mod join servers with it and the other way round. */
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(DhTurbo.id("worldgen"),
            () -> PROTOCOL_VERSION, NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    private WorldgenSharing() {
    }

    public static void register() {
        CHANNEL.messageBuilder(WorldgenInfoPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(WorldgenInfoPayload::encode)
                .decoder(WorldgenInfoPayload::decode)
                .consumerMainThread((payload, context) -> received(payload))
                .add();
        MinecraftForge.EVENT_BUS.addListener(WorldgenSharing::playerJoined);
    }

    private static void received(WorldgenInfoPayload payload) {
        ReceivedWorldgen.set(payload);
        if (ModList.get().isLoaded("distanthorizons")) {
            // Same plan as on our own servers: no regeneration pass over tiles we already made.
            DhIntegration.applyPlan();
        }
        DhTurbo.LOGGER.info("The server shares its world generation ({} levels), distant terrain is generated locally",
                payload.levels().size());
    }

    private static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Config.SHARE_WORLDGEN.get()
                || !CHANNEL.isRemotePresent(player.connection.connection)) {
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
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new WorldgenInfoPayload(player.server.overworld().getSeed(), levels));
        }
    }
}
