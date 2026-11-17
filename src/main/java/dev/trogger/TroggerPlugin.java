package dev.trogger;

import dev.trogger.menu.MenuListener;
import dev.trogger.world.WorldService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TroggerPlugin extends JavaPlugin {
    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        WorldService worlds = new WorldService(this);
        game = new GameManager(this, worlds);

        getServer().getPluginManager().registerEvents(new GameListener(this, game), this);
        getServer().getPluginManager().registerEvents(new MenuListener(game), this);
        PluginCommand cmd = getCommand("trogger");
        if (cmd != null) {
            TroggerCommand executor = new TroggerCommand(game);
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
