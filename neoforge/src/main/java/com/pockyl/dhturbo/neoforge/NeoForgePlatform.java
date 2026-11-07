package com.pockyl.dhturbo.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.PieceBeardifierModifier;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import com.pockyl.dhturbo.platform.Platform;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** {@link Platform} on NeoForge. */
public final class NeoForgePlatform implements Platform {
    @Nullable
    @Override
    public MinecraftServer currentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    @Override
    public List<RegistryDataLoader.RegistryData<?>> worldgenRegistries() {
        return DataPackRegistriesHooks.getDataPackRegistries();
    }

    @Override
    public PackRepository dataPacks() {
        return ServerPacksSource.createVanillaTrustedRepository();
    }

    @Override
    public boolean isModDataPack(String packId) {
        return packId.startsWith("mod");
    }

    @Nullable
    @Override
    public PieceBeard pieceBeard(StructurePiece piece) {
        return piece instanceof PieceBeardifierModifier modifier
                ? new PieceBeard(modifier.getBeardifierBox(), modifier.getTerrainAdjustment(), modifier.getGroundLevelDelta())
                : null;
    }

    @Override
    public boolean hasChannel(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerStopped(Runnable listener) {
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> listener.run());
    }

    @Override
    public void onPlayerJoined(Consumer<ServerPlayer> listener) {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                listener.accept(player);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> listener.accept(event.getDispatcher()));
    }
}
