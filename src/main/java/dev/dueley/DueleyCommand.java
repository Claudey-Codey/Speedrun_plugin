package dev.dueley;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** /dueley menu | solo | info | toggle_timer | stop | time <seconds> | debug timer_forward <number> <seconds|minutes> */
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
            case "solo" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                game.toggleSolo(p);
            }
            case "info" -> sender.sendMessage(Msg.mm(Msg.PREFIX + game.describe()));
            case "toggle_timer" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                boolean shown = game.toggleTimer(p);
                Msg.send(p, shown ? "<green>Timer shown." : "<gray>Timer hidden. <dark_gray>(/dueley toggle_timer to show it again)");
            }
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
            case "debug" -> {
                if (!admin(sender)) {
                    return true;
                }
                debug(sender, args);
            }
            default -> {
                sender.sendMessage(Msg.mm(Msg.PREFIX + "<gold>Commands:"));
                sender.sendMessage(Msg.mm("<yellow>/dueley menu <gray>- open the seed selection screen"));
                sender.sendMessage(Msg.mm("<yellow>/dueley solo <gray>- practice alone"));
                sender.sendMessage(Msg.mm("<yellow>/dueley info <gray>- show game info"));
                sender.sendMessage(Msg.mm("<yellow>/dueley toggle_timer <gray>- show or hide the timer"));
                if (sender.hasPermission("dueley.admin")) {
                    sender.sendMessage(Msg.mm("<red>/dueley stop <gray>- abort the game"));
                    sender.sendMessage(Msg.mm("<red>/dueley time <seconds> <gray>- set the time left"));
                    sender.sendMessage(Msg.mm("<red>/dueley debug timer_forward <number> <seconds|minutes> <gray>- fast-forward the timer (testing)"));
                }
            }
        }
        return true;
    }

    private static final String DEBUG_USAGE = "Usage: /dueley debug timer_forward <number> <seconds|minutes>";
    private static final long MAX_NUMBER = 1_000_000;

    private void debug(CommandSender sender, String[] args) {
        if (args.length != 4 || !args[1].equalsIgnoreCase("timer_forward")) {
            sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        long number;
        try {
            number = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        int factor = switch (args[3].toLowerCase()) {
            case "seconds", "second", "sec", "s" -> 1;
            case "minutes", "minute", "min", "m" -> 60;
            default -> 0;
        };
        if (number < 1 || number > MAX_NUMBER || factor == 0) {
            sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        GameManager.ForwardResult result = game.forwardTimer((int) number * factor);
        sender.sendMessage(Msg.mm(Msg.PREFIX + result.message()));
        if (result.ok()) {
            JavaPlugin.getPlugin(DueleyPlugin.class).getLogger()
                    .info(sender.getName() + " used debug timer_forward " + args[2] + " " + args[3].toLowerCase());
        }
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
        boolean admin = sender.hasPermission("dueley.admin");
        List<String> options;
        if (args.length == 1) {
            options = new ArrayList<>(List.of("menu", "solo", "info", "toggle_timer"));
            if (admin) {
                options.addAll(List.of("stop", "time", "debug"));
            }
        } else if (admin && args[0].equalsIgnoreCase("debug")) {
            options = switch (args.length) {
                case 2 -> List.of("timer_forward");
                case 3 -> args[1].equalsIgnoreCase("timer_forward") ? List.of("1", "5", "10", "30") : List.of();
                case 4 -> args[1].equalsIgnoreCase("timer_forward") ? List.of("seconds", "minutes") : List.of();
                default -> List.of();
            };
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase();
        return options.stream().filter(o -> o.startsWith(prefix)).toList();
    }
}
