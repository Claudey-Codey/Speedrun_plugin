package dev.dueley.fabric;

import dev.dueley.fabric.world.ArenaGenerator;
import dev.dueley.fabric.world.WorldService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dueley for Fabric: a server-side-only port of the Paper plugin. Works on a dedicated server and in singleplayer /
 * LAN / Essential-hosted worlds; players only need a vanilla-compatible client.
 */
public final class DueleyMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Dueley");

    private static GameManager game;
    private static Scheduler scheduler;

    @Override
    public void onInitialize() {
        // server-only chunk generator codec (chunk generators are never synced to clients)
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath("dueley", "arena"), ArenaGenerator.CODEC);
        DueleyConfig.saveDefault();

        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> DueleyCommand.register(dispatcher));
        GameListener.register();

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (scheduler != null) {
                scheduler.tick();
            }
        });
        // worlds can only be created once the server has finished starting
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DueleyConfig.saveDefault();
            Msg.init(server);
            scheduler = new Scheduler();
            game = new GameManager(server, scheduler, new WorldService(server));
            game.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (game != null) {
                game.shutdown();
            }
            game = null;
            if (scheduler != null) {
                scheduler.clear();
            }
            scheduler = null;
        });
    }

    /** The running game, or null while no server is running. */
    public static GameManager game() {
        return game;
    }

    static Scheduler scheduler() {
        return scheduler;
    }
}
