package dev.dueley;

import dev.dueley.menu.MenuListener;
import dev.dueley.world.WorldService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DueleyPlugin extends JavaPlugin {
    private GameManager game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        WorldService worlds = new WorldService(this);
        game = new GameManager(this, worlds);

        getServer().getPluginManager().registerEvents(new GameListener(this, game), this);
        getServer().getPluginManager().registerEvents(new MenuListener(game), this);
        PluginCommand cmd = getCommand("dueley");
        if (cmd != null) {
            DueleyCommand executor = new DueleyCommand(game);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
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
