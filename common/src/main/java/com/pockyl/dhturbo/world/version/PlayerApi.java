package com.pockyl.dhturbo.world.version;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** What differs between Minecraft versions in who a player is; see {@link WorldgenApi}. */
public final class PlayerApi {
    private PlayerApi() {
    }

    public static MinecraftServer server(ServerPlayer player) {
        return player.server;
    }

    /** The host of a singleplayer world. */
    public static boolean isHost(ServerPlayer player) {
        return player.server.isSingleplayerOwner(player.getGameProfile());
    }

    public static boolean isOperator(ServerPlayer player) {
        return player.hasPermissions(Commands.LEVEL_GAMEMASTERS);
    }

    public static boolean isOperator(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }
}
