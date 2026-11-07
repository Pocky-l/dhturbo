package com.pockyl.dhturbo.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.platform.Platform;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.ReceivedWorldgen;
import com.pockyl.dhturbo.world.TerrainSupport;
import com.pockyl.dhturbo.world.version.PlayerApi;

import java.util.ArrayList;
import java.util.List;

/** Sends players the world generation info when they join, if the server needs it and allows it (see {@link #shares}). */
public final class WorldgenSharing {
    private WorldgenSharing() {
    }

    public static void register() {
        Platform.INSTANCE.onPlayerJoined(WorldgenSharing::playerJoined);
    }

    /** On the client, when the info arrives. */
    public static void received(WorldgenInfoPayload payload) {
        ReceivedWorldgen.set(payload);
        if (Platform.INSTANCE.isModLoaded("distanthorizons")) {
            // Same plan as on our own servers: no regeneration pass over tiles we already made.
            DhIntegration.applyPlan();
        }
        DhTurbo.LOGGER.info("The server shares its world generation ({} levels), distant terrain is generated locally",
                payload.levels().size());
    }

    /** A server with Distant Horizons sends the distant terrain itself: its seed would give clients nothing but the seed. */
    public static boolean shares(boolean distantHorizonsOnServer) {
        return Config.SHARE_WORLDGEN.get() && !distantHorizonsOnServer;
    }

    private static void playerJoined(ServerPlayer player) {
        if (!shares(Platform.INSTANCE.isModLoaded("distanthorizons"))
                || !Platform.INSTANCE.hasChannel(player, WorldgenInfoPayload.TYPE)) {
            return;
        }
        // The singleplayer host generates through the integrated server itself; it has no use for the info.
        if (PlayerApi.isHost(player)) {
            return;
        }
        List<LevelGenInfo> levels = new ArrayList<>();
        for (ServerLevel level : PlayerApi.server(player).getAllLevels()) {
            // Levels whose terrain a client cannot rebuild from the noise settings alone stay with Distant Horizons.
            if (TerrainSupport.clientProblem(level).isEmpty()) {
                LevelGenInfo.of(level).ifPresent(levels::add);
            }
        }
        if (!levels.isEmpty()) {
            Platform.INSTANCE.send(player, new WorldgenInfoPayload(PlayerApi.server(player).overworld().getSeed(), levels));
        }
    }
}
