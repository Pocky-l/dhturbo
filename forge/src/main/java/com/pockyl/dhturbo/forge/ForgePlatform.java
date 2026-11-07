package com.pockyl.dhturbo.forge;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.PieceBeardifierModifier;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DataPackRegistriesHooks;
import net.minecraftforge.resource.ResourcePackLoader;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.network.WorldgenInfoPayload;
import com.pockyl.dhturbo.network.WorldgenSharing;
import com.pockyl.dhturbo.platform.Platform;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** {@link Platform} on Forge. */
public final class ForgePlatform implements Platform {
    private static final String PROTOCOL_VERSION = "1";
    /** Optional on both sides: players without the mod join servers with it and the other way round. */
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(DhTurbo.id("worldgen"),
            () -> PROTOCOL_VERSION, NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    static void registerChannel() {
        CHANNEL.messageBuilder(WorldgenInfoPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(WorldgenInfoPayload::encode)
                .decoder(WorldgenInfoPayload::decode)
                .consumerMainThread((payload, context) -> WorldgenSharing.received(payload))
                .add();
    }

    @Nullable
    @Override
    public MinecraftServer currentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    @Override
    public List<RegistryDataLoader.RegistryData<?>> worldgenRegistries() {
        return DataPackRegistriesHooks.getDataPackRegistries();
    }

    /** What a server starts with: vanilla plus one pack per mod (MinecraftServer.configurePackRepository). */
    @Override
    public PackRepository dataPacks() {
        PackRepository repository = new PackRepository(new ServerPacksSource());
        ResourcePackLoader.loadResourcePacks(repository, ServerLifecycleHooks::buildPackFinder);
        return repository;
    }

    @Override
    public boolean isModDataPack(String packId) {
        return packId.startsWith("mod");
    }

    @Override
    public ResourceLocation biomeTag(String forgePath, String fabricPath) {
        return new ResourceLocation("forge", forgePath);
    }

    @Nullable
    @Override
    public PieceBeard pieceBeard(StructurePiece piece) {
        return piece instanceof PieceBeardifierModifier modifier
                ? new PieceBeard(modifier.getBeardifierBox(), modifier.getTerrainAdjustment(), modifier.getGroundLevelDelta())
                : null;
    }

    @Override
    public boolean hasChannel(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }

    @Override
    public void send(ServerPlayer player, WorldgenInfoPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> listener) {
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> listener.accept(event.getServer()));
    }

    @Override
    public void onServerStopped(Runnable listener) {
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> listener.run());
    }

    @Override
    public void onPlayerJoined(Consumer<ServerPlayer> listener) {
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                listener.accept(player);
            }
        });
    }

    @Override
    public void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener) {
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> listener.accept(event.getDispatcher()));
    }
}
