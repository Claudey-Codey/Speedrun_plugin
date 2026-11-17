package dev.dueley.fabric;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/** Small text helpers. All strings use MiniMessage tags (see {@link MiniMessage}), exactly like the plugin. */
public final class Msg {
    public static final String PREFIX = "<gradient:#ff5555:#ffaa00><b>DUELEY</b></gradient> <dark_gray>»</dark_gray> ";

    private static MinecraftServer server;

    private Msg() {
    }

    static void init(MinecraftServer s) {
        server = s;
    }

    public static MutableComponent mm(String miniMessage) {
        return MiniMessage.parse(miniMessage);
    }

    /** Item names/lore: MiniMessage without the default italic style. */
    public static MutableComponent item(String miniMessage) {
        // the root never carries a style of its own, so italic is "absent" there; tags inside still win
        return mm(miniMessage).withStyle(s -> s.withItalic(false));
    }

    public static void send(ServerPlayer player, String miniMessage) {
        player.sendSystemMessage(mm(PREFIX + miniMessage));
    }

    /** To every player and the console (like Bukkit.broadcast). */
    public static void broadcast(String miniMessage) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(mm(PREFIX + miniMessage), false);
        }
    }

    public static void actionBar(ServerPlayer player, Component text) {
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
    }

    public static void title(ServerPlayer player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeInTicks, stayTicks, fadeOutTicks));
        player.connection.send(new ClientboundSetSubtitleTextPacket(mm(subtitle)));
        player.connection.send(new ClientboundSetTitleTextPacket(mm(title)));
    }

    public static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch);
    }

    /** Like Bukkit's Player#playSound(Location, Sound, ...): only this player hears it, at their position, MASTER volume. */
    public static void sound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }

    /** 3725 -> "62:05" */
    public static String clock(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
