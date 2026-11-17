package dev.hourduel;

import dev.hourduel.menu.MenuListener;
import dev.hourduel.world.WorldService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HourDuelPlugin extends JavaPlugin {
    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        WorldService worlds = new WorldService(this);
        game = new GameManager(this, worlds);

        getServer().getPluginManager().registerEvents(new GameListener(this, game), this);
        getServer().getPluginManager().registerEvents(new MenuListener(game), this);
        PluginCommand cmd = getCommand("hourduel");
        if (cmd != null) {
            HourDuelCommand executor = new HourDuelCommand(game);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
        PluginCommand toggle = getCommand("toggle");
        if (toggle != null) {
            ToggleCommand toggleExecutor = new ToggleCommand(game);
            toggle.setExecutor(toggleExecutor);
            toggle.setTabCompleter(toggleExecutor);
        }
        // worlds can only be created once the server has finished starting
        getServer().getScheduler().runTask(this, game::start);
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }
}
