package dev.dueley;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /toggle timer */
public final class ToggleCommand implements TabExecutor {
    private static final List<String> OPTIONS = List.of("timer");

    private final GameManager game;

    public ToggleCommand(GameManager game) {
        this.game = game;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("timer")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            boolean shown = game.toggleTimer(p);
            Msg.send(p, shown ? "<green>Timer shown." : "<gray>Timer hidden. <dark_gray>(/toggle timer to show it again)");
        } else {
            sender.sendMessage(Msg.mm(Msg.PREFIX + "<red>Usage: /toggle timer"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return OPTIONS.stream().filter(o -> o.startsWith(args[0].toLowerCase())).toList();
    }
}
