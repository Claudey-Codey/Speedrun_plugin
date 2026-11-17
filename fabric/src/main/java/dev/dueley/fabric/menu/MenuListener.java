package dev.dueley.fabric.menu;

import dev.dueley.fabric.GameManager;
import dev.dueley.fabric.GoodStructure;
import net.minecraft.server.level.ServerPlayer;

/**
 * Click handling for {@link SeedMenu}. sgui already cancels every click and drag in the menu (and in the player's
 * own inventory while it is open), so this only maps a clicked menu slot to the game action.
 */
public final class MenuListener {
    private MenuListener() {
    }

    static void onClick(GameManager game, SeedMenu menu, ServerPlayer p, int slot) {
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
}
