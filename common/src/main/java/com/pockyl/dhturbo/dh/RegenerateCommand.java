package com.pockyl.dhturbo.dh;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import com.pockyl.dhturbo.DhTurbo;

/**
 * {@code /dhturbo regenerate}: finished tiles stay as they were drawn, so fixes in the generator reach only new ones.
 * This deletes the world's distant terrain; the generator draws it again and saved chunks are read again.
 */
final class RegenerateCommand {
    private RegenerateCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(DhTurbo.MOD_ID)
                .requires(RegenerateCommand::allowed)
                .then(Commands.literal("regenerate").executes(context -> run(context.getSource()))));
    }

    /** Operators, and the host of a singleplayer world, also without cheats. */
    private static boolean allowed(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS) || source.getPlayer() != null
                && source.getServer().isSingleplayerOwner(source.getPlayer().getGameProfile());
    }

    private static int run(CommandSourceStack source) {
        source.sendSuccess(() -> message("clearing", "clearing distant terrain..."), true);
        MinecraftServer server = source.getServer();
        // Clearing the database blocks.
        Thread thread = new Thread(() -> {
            Component result;
            try {
                result = DhIntegration.clearLodData() > 0
                        ? message("cleared", "distant terrain cleared, generating again")
                        : message("clear_unavailable", "nothing to clear");
            } catch (RuntimeException | LinkageError e) {
                DhTurbo.LOGGER.error("Could not delete the distant terrain", e);
                result = message("clear_failed", "could not clear distant terrain, see the log");
            }
            Component message = result;
            server.execute(() -> source.sendSuccess(() -> message, true));
        }, "DH Turbo Regenerate");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static Component message(String key, String fallback) {
        return Component.translatableWithFallback("dhturbo.message." + key, fallback);
    }
}
