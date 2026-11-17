package dev.hourduel;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Wires Bukkit events into the {@link GameManager}. */
public final class GameListener implements Listener {
    private final HourDuelPlugin plugin;
    private final GameManager game;

    public GameListener(HourDuelPlugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    // ───────── join / quit ─────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        game.onJoin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        game.onQuit(e.getPlayer());
    }

    // ───────── custom seed typed in chat ─────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!game.isTypingSeed(p)) {
            return;
        }
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> game.handleCustomSeed(p, text));
    }

    // ───────── freeze / protection ─────────

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (game.isLobby(p.getWorld()) && to.getY() < 60) {
            p.teleport(new Location(p.getWorld(), 0.5, 100, 0.5, p.getYaw(), p.getPitch()));
            return;
        }
        if (game.isLocked(p) && (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY())) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && game.isProtected(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && game.isProtected(p) && e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (blocked(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (game.isLobby(e.getPlayer().getWorld())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLobbyInventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && game.isLobby(p.getWorld()) && p.getGameMode() != GameMode.CREATIVE
                && game.isMenuItem(e.getCurrentItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (game.isMenuItem(e.getItem()) && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true);
            game.openMainMenu(p);
            return;
        }
        if (game.isLocked(p)) {
            e.setCancelled(true);
        }
    }

    private boolean blocked(Player p) {
        if (p.getGameMode() == GameMode.CREATIVE && p.hasPermission("hourduel.admin")) {
            return false;
        }
        return game.isLobby(p.getWorld()) || game.isLocked(p);
    }

    // ───────── death / respawn ─────────

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        game.onDeath(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        Location loc = game.respawnLocation(e.getPlayer(), e.isBedSpawn() || e.isAnchorSpawn(), e.getRespawnLocation());
        if (loc != null) {
            e.setRespawnLocation(loc);
        }
    }

    // ───────── portals: every player has their own nether ─────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent e) {
        handlePortal(e.getFrom(), e.getCause(), e, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (!game.isManagedWorld(e.getFrom().getWorld())) {
            return;
        }
        if (e.getPortalType() != org.bukkit.PortalType.NETHER) {
            e.setCancelled(true);
            return;
        }
        Location target = game.netherPortalTarget(e.getFrom());
        if (target == null) {
            e.setCancelled(true);
        } else {
            e.setTo(target);
        }
    }

    private void handlePortal(Location from, PlayerTeleportEvent.TeleportCause cause, PlayerPortalEvent e, Entity who) {
        if (!game.isManagedWorld(from.getWorld())) {
            return;
        }
        if (cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            e.setCancelled(true);
            if (who instanceof Player p) {
                Msg.send(p, "<red>The End is closed during Hour Duel — focus on your gear!");
            }
            return;
        }
        Location target = game.netherPortalTarget(from);
        if (target == null) {
            e.setCancelled(true);
        } else {
            e.setTo(target);
        }
    }
}
