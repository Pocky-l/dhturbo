package com.pockyl.dhturbo.world.version;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/** What differs between Minecraft versions in who a player is; see {@link WorldgenApi}. */
public final class PlayerApi {
    private PlayerApi() {
    }

    public static MinecraftServer server(ServerPlayer player) {
        return player.level().getServer();
    }

    /** The host of a singleplayer world. */
    public static boolean isHost(ServerPlayer player) {
        return server(player).isSingleplayerOwner(player.nameAndId());
    }

    public static boolean isOperator(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
