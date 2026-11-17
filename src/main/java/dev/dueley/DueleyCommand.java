package dev.dueley;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /dueley menu | info | stop | time <seconds> */
public final class DueleyCommand implements TabExecutor {
    private final GameManager game;

    public DueleyCommand(GameManager game) {
        this.game = game;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "menu" -> {
                if (sender instanceof Player p) {
                    game.openMainMenu(p);
                }
            }
            case "info" -> sender.sendMessage(Msg.mm(Msg.PREFIX + game.describe()));
            case "stop" -> {
                if (admin(sender)) {
                    game.stop();
                }
            }
            case "time" -> {
                if (!admin(sender)) {
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>Usage: /dueley time <seconds>"));
                    return true;
                }
                try {
                    int seconds = Integer.parseInt(args[1]);
                    boolean ok = game.setRemaining(seconds);
                    sender.sendMessage(Msg.mm(Msg.PREFIX + (ok ? "<green>Time left set to " + Msg.clock(seconds)
                            : "<red>Only works while gathering.")));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>Not a number: " + args[1]));
                }
            }
            default -> {
                sender.sendMessage(Msg.mm(Msg.PREFIX + "<gold>Commands:"));
                sender.sendMessage(Msg.mm("<yellow>/dueley menu <gray>- open the seed selection screen"));
                sender.sendMessage(Msg.mm("<yellow>/dueley info <gray>- show game info"));
                if (sender.hasPermission("dueley.admin")) {
                    sender.sendMessage(Msg.mm("<red>/dueley stop <gray>- abort the game"));
                    sender.sendMessage(Msg.mm("<red>/dueley time <seconds> <gray>- set the time left"));
                }
            }
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("dueley.admin")) {
            return true;
        }
        sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>No permission."));
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("menu", "info"));
        if (sender.hasPermission("dueley.admin")) {
            options.addAll(List.of("stop", "time"));
        }
        return options.stream().filter(o -> o.startsWith(args[0].toLowerCase())).toList();
    }
}
