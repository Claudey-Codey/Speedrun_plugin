package dev.dueley;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;

/** Small text helpers. All strings use MiniMessage tags. */
public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gradient:#ff5555:#ffaa00><b>DUELEY</b></gradient> <dark_gray>»</dark_gray> ";

    private Msg() {
    }

    public static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Item names/lore: MiniMessage without the default italic style. */
    public static Component item(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void send(Player player, String miniMessage) {
        player.sendMessage(mm(PREFIX + miniMessage));
    }

    public static void broadcast(String miniMessage) {
        Bukkit.broadcast(mm(PREFIX + miniMessage));
    }

    public static void title(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        player.showTitle(Title.title(mm(title), mm(subtitle), Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(fadeOutTicks * 50L))));
    }

    public static void sound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** 3725 -> "62:05" */
    public static String clock(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
