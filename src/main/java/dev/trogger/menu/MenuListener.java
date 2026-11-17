package dev.trogger.menu;

import dev.trogger.GameManager;
import dev.trogger.GoodStructure;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Click handling for {@link SeedMenu}. */
public final class MenuListener implements Listener {
    private final GameManager game;

    public MenuListener(GameManager game) {
        this.game = game;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof SeedMenu menu)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        int slot = e.getRawSlot();
        if (menu.page() == SeedMenu.Page.MAIN) {
            switch (slot) {
                case SeedMenu.SLOT_RANDOM -> game.chooseRandom(p);
                case SeedMenu.SLOT_GOOD -> game.openGoodMenu(p);
                case SeedMenu.SLOT_CUSTOM -> game.chooseCustom(p);
                default -> {
                }
            }
        } else {
            switch (slot) {
                case SeedMenu.SLOT_VILLAGE -> game.chooseGood(p, GoodStructure.VILLAGE);
                case SeedMenu.SLOT_SHIPWRECK -> game.chooseGood(p, GoodStructure.SHIPWRECK);
                case SeedMenu.SLOT_TRIAL -> game.chooseGood(p, GoodStructure.TRIAL_CHAMBER);
                case SeedMenu.SLOT_BACK -> game.openMainMenu(p);
                default -> {
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SeedMenu) {
            e.setCancelled(true);
        }
    }
}
