package dev.dueley.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /dueley menu | solo | info | toggle_timer | stop | time &lt;seconds&gt; | debug timer_forward &lt;number&gt; &lt;seconds|minutes&gt;
 * <p>
 * Registered with Brigadier as {@code /dueley} and {@code /dy}, each taking the rest of the line as one argument, so the
 * replies (help for unknown subcommands, "No permission.", usage lines) and the tab completion work exactly like the
 * plugin's Bukkit command. Suggestions come from the server, so they work for vanilla clients too.
 */
public final class DueleyCommand {
    /** Admin permission node for permission mods; without one, admin = op level 2. */
    public static final Identifier ADMIN_PERMISSION = Identifier.fromNamespaceAndPath("dueley", "admin");

    private DueleyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String label : List.of("dueley", "dy")) {
            dispatcher.register(Commands.literal(label)
                    .executes(ctx -> run(ctx.getSource(), new String[0]))
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> suggest(ctx.getSource(), builder))
                            .executes(ctx -> run(ctx.getSource(), StringArgumentType.getString(ctx, "args").split(" ")))));
        }
    }

    private static int run(CommandSourceStack sender, String[] args) {
        GameManager game = DueleyMod.game();
        if (game == null) {
            return 0;
        }
        ServerPlayer player = sender.getPlayer();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "menu" -> {
                if (player != null) {
                    game.openMainMenu(player);
                }
            }
            case "solo" -> {
                if (player == null) {
                    sender.sendSystemMessage(Component.literal("Players only."));
                    return 1;
                }
                game.toggleSolo(player);
            }
            case "info" -> sender.sendSystemMessage(Msg.mm(Msg.PREFIX + game.describe()));
            case "toggle_timer" -> {
                if (player == null) {
                    sender.sendSystemMessage(Component.literal("Players only."));
                    return 1;
                }
                boolean shown = game.toggleTimer(player);
                Msg.send(player, shown ? "<green>Timer shown." : "<gray>Timer hidden. <dark_gray>(/dueley toggle_timer to show it again)");
            }
            case "stop" -> {
                if (admin(sender)) {
                    game.stop();
                }
            }
            case "time" -> {
                if (!admin(sender)) {
                    return 1;
                }
                if (args.length < 2) {
                    sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>Usage: /dueley time <seconds>"));
                    return 1;
                }
                try {
                    int seconds = Integer.parseInt(args[1]);
                    boolean ok = game.setRemaining(seconds);
                    sender.sendSystemMessage(Msg.mm(Msg.PREFIX + (ok ? "<green>Time left set to " + Msg.clock(seconds)
                            : "<red>Only works while gathering.")));
                } catch (NumberFormatException e) {
                    sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>Not a number: " + args[1]));
                }
            }
            case "debug" -> {
                if (!admin(sender)) {
                    return 1;
                }
                debug(game, sender, args);
            }
            default -> {
                sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<gold>Commands:"));
                sender.sendSystemMessage(Msg.mm("<yellow>/dueley menu <gray>- open the seed selection screen"));
                sender.sendSystemMessage(Msg.mm("<yellow>/dueley solo <gray>- practice alone"));
                sender.sendSystemMessage(Msg.mm("<yellow>/dueley info <gray>- show game info"));
                sender.sendSystemMessage(Msg.mm("<yellow>/dueley toggle_timer <gray>- show or hide the timer"));
                if (isAdmin(sender)) {
                    sender.sendSystemMessage(Msg.mm("<red>/dueley stop <gray>- abort the game"));
                    sender.sendSystemMessage(Msg.mm("<red>/dueley time <seconds> <gray>- set the time left"));
                    sender.sendSystemMessage(Msg.mm("<red>/dueley debug timer_forward <number> <seconds|minutes> <gray>- fast-forward the timer (testing)"));
                }
            }
        }
        return 1;
    }

    private static final String DEBUG_USAGE = "Usage: /dueley debug timer_forward <number> <seconds|minutes>";
    private static final long MAX_NUMBER = 1_000_000;

    private static void debug(GameManager game, CommandSourceStack sender, String[] args) {
        if (args.length != 4 || !args[1].equalsIgnoreCase("timer_forward")) {
            sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        long number;
        try {
            number = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        int factor = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "seconds", "second", "sec", "s" -> 1;
            case "minutes", "minute", "min", "m" -> 60;
            default -> 0;
        };
        if (number < 1 || number > MAX_NUMBER || factor == 0) {
            sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>" + DEBUG_USAGE));
            return;
        }
        GameManager.ForwardResult result = game.forwardTimer((int) number * factor);
        sender.sendSystemMessage(Msg.mm(Msg.PREFIX + result.message()));
        if (result.ok()) {
            DueleyMod.LOGGER.info(sender.getTextName() + " used debug timer_forward " + args[2] + " " + args[3].toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Admin = the {@code dueley:admin} permission (from a permission mod), or op level 2 without one, or the host of a
     * singleplayer / LAN world (so the admin commands work there even with cheats off). The console is always admin.
     */
    public static boolean isAdmin(CommandSourceStack sender) {
        if (((PermissionContextOwner) sender).checkPermission(ADMIN_PERMISSION, PermissionLevel.GAMEMASTERS)) {
            return true;
        }
        ServerPlayer player = sender.getPlayer();
        return player != null && sender.getServer().isSingleplayerOwner(player.nameAndId());
    }

    private static boolean admin(CommandSourceStack sender) {
        if (isAdmin(sender)) {
            return true;
        }
        sender.sendSystemMessage(Msg.mm(Msg.PREFIX + "<red>No permission."));
        return false;
    }

    // ───────────────────────── tab completion (same as the plugin's onTabComplete) ─────────────────────────

    private static CompletableFuture<Suggestions> suggest(CommandSourceStack sender, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] args = remaining.split(" ", -1);
        boolean admin = isAdmin(sender);
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
            return builder.buildFuture();
        }
        String last = args[args.length - 1];
        String prefix = last.toLowerCase(Locale.ROOT);
        SuggestionsBuilder lastArg = builder.createOffset(builder.getStart() + remaining.length() - last.length());
        for (String o : options) {
            if (o.startsWith(prefix)) {
                lastArg.suggest(o);
            }
        }
        return lastArg.buildFuture();
    }
}
