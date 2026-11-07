package com.pockyl.dhturbo.fabric;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.network.WorldgenInfoPayload;
import com.pockyl.dhturbo.platform.Platform;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;

/** {@link Platform} on Fabric. */
public final class FabricPlatform implements Platform {
    static final ResourceLocation CHANNEL = DhTurbo.id("worldgen");

    /** Fabric has no getter for it, so the server is remembered from its lifecycle. */
    @Nullable
    private volatile MinecraftServer server;

    public FabricPlatform() {
        ServerLifecycleEvents.SERVER_STARTING.register(starting -> server = starting);
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> server = null);
    }

    @Nullable
    @Override
    public MinecraftServer currentServer() {
        return server;
    }

    @Override
    public List<RegistryDataLoader.RegistryData<?>> worldgenRegistries() {
        return DynamicRegistries.getDynamicRegistries();
    }

    /** Fabric adds the mods' packs only to repositories with a world folder; this one stays empty. */
    @Override
    public PackRepository dataPacks() {
        try {
            return ServerPacksSource.createPackRepository(Files.createTempDirectory(DhTurbo.MOD_ID));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fabric's mod packs are all required. */
    @Override
    public boolean isModDataPack(String packId) {
        return false;
    }

    @Override
    public ResourceLocation biomeTag(String forgePath, String fabricPath) {
        return new ResourceLocation("c", fabricPath);
    }

    /** Only Forge lets a piece say this; here the game's own pieces are all there is. */
    @Nullable
    @Override
    public PieceBeard pieceBeard(StructurePiece piece) {
        return null;
    }

    @Override
    public boolean hasChannel(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, CHANNEL);
    }

    @Override
    public void send(ServerPlayer player, WorldgenInfoPayload payload) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        payload.encode(buffer);
        ServerPlayNetworking.send(player, CHANNEL, buffer);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        ServerLifecycleEvents.SERVER_STARTED.register(listener::accept);
    }

    @Override
    public void onServerStopped(Runnable listener) {
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> listener.run());
    }

    @Override
    public void onPlayerJoined(Consumer<ServerPlayer> listener) {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, joined) -> listener.accept(handler.player));
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> listener.accept(dispatcher));
    }
}
