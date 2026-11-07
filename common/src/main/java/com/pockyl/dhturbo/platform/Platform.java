package com.pockyl.dhturbo.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import com.pockyl.dhturbo.network.WorldgenInfoPayload;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * What the common code needs from the mod loader on the server side. Each loader project provides one implementation,
 * listed in its {@code META-INF/services}.
 */
public interface Platform {
    Platform INSTANCE = LoaderInfo.load(Platform.class);

    /** How a structure piece of a mod shapes the terrain under it, where the loader lets a piece say so itself. */
    record PieceBeard(BoundingBox box, TerrainAdjustment adjustment, int groundLevelDelta) {
    }

    default boolean isModLoaded(String modId) {
        return LoaderInfo.INSTANCE.isModLoaded(modId);
    }

    default boolean isDevelopment() {
        return LoaderInfo.INSTANCE.isDevelopment();
    }

    /** The running server of this game instance: the dedicated or the integrated one, null when there is none. */
    @Nullable
    MinecraftServer currentServer();

    /** The registries data packs fill: vanilla's and those of the mods. */
    List<RegistryDataLoader.RegistryData<?>> worldgenRegistries();

    /** A new repository of the game's and the installed mods' data packs, without any world's. */
    PackRepository dataPacks();

    /** Whether a pack of {@link #dataPacks} that is not required holds the built-in data of the installed mods. */
    boolean isModDataPack(String packId);

    /** A biome tag for modded biomes: the loaders of this Minecraft version have no common ones. */
    ResourceLocation biomeTag(String forgePath, String fabricPath);

    /** What a piece says about the terrain under it, null when it leaves that to the game. */
    @Nullable
    PieceBeard pieceBeard(StructurePiece piece);

    /** Whether the player's client registered the channel (has this mod). */
    boolean hasChannel(ServerPlayer player);

    void send(ServerPlayer player, WorldgenInfoPayload payload);

    void onServerStarted(Consumer<MinecraftServer> listener);

    void onServerStopped(Runnable listener);

    void onPlayerJoined(Consumer<ServerPlayer> listener);

    void onRegisterCommands(Consumer<CommandDispatcher<CommandSourceStack>> listener);
}
