package com.pockyl.dhturbo.dh;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** Player positions as seen from generator threads. */
final class Players {
    private Players() {
    }

    /** Block X and Z of every player in the level (a racy read from generator threads, like below). */
    static List<int[]> positions(ServerLevel level) {
        List<int[]> positions = new ArrayList<>();
        try {
            List<ServerPlayer> players = level.players();
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer player = players.get(i);
                positions.add(new int[]{player.getBlockX(), player.getBlockZ()});
            }
        } catch (RuntimeException e) {
            // The list changed under us; the next call sees the new one.
        }
        return positions;
    }

    /**
     * Horizontal distance from the block to the nearest player of the level, {@link Double#MAX_VALUE} without players.
     * The player list belongs to the server thread; a racy read is good enough to pick a level of detail.
     */
    static double nearestDistance(ServerLevel level, int x, int z) {
        double nearest = Double.MAX_VALUE;
        try {
            List<ServerPlayer> players = level.players();
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer player = players.get(i);
                double dx = player.getX() - x;
                double dz = player.getZ() - z;
                nearest = Math.min(nearest, Math.sqrt(dx * dx + dz * dz));
            }
        } catch (RuntimeException e) {
            // The list changed under us (a player joined or left); try again with the next tile.
        }
        return nearest;
    }
}
