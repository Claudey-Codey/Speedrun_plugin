package dev.trogger.menu;

import dev.trogger.GoodStructure;
import dev.trogger.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/** The seed selection screens (a chest menu). */
public final class SeedMenu implements InventoryHolder {
    public enum Page { MAIN, GOOD }

    public static final int SLOT_RANDOM = 11;
    public static final int SLOT_GOOD = 13;
    public static final int SLOT_CUSTOM = 15;
    public static final int SLOT_VILLAGE = 11;
    public static final int SLOT_SHIPWRECK = 13;
    public static final int SLOT_TRIAL = 15;
    public static final int SLOT_BACK = 22;
    public static final int SLOT_INFO = 22;

    private final Page page;
    private final Inventory inventory;

    private SeedMenu(Page page, Component title) {
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 27, title);
    }

    public Page page() {
        return page;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static SeedMenu main(String playersLine, int gatherMinutes) {
        SeedMenu menu = new SeedMenu(Page.MAIN, Msg.mm("<dark_gray>Trogger » </dark_gray><black>Choose a Seed"));
        Inventory inv = menu.inventory;
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        inv.setItem(SLOT_RANDOM, item(Material.WHEAT_SEEDS, "<yellow><b>Random Seed",
                "<gray>A completely random world.",
                "",
                "<green>▶ Click to play"));
        inv.setItem(SLOT_GOOD, item(Material.DIAMOND, "<aqua><b>Random Good Seed",
                "<gray>A random seed with a great",
                "<gray>structure close to spawn:",
                "<dark_gray> • <white>Village",
                "<dark_gray> • <white>Shipwreck",
                "<dark_gray> • <white>Trial Chamber",
                "<gray>You get a <gold>compass</gold> that points to it!",
                "",
                "<green>▶ Click to choose a structure"));
        inv.setItem(SLOT_CUSTOM, item(Material.NAME_TAG, "<light_purple><b>Custom Seed",
                "<gray>Type any seed in chat.",
                "<gray>Numbers or text both work.",
                "",
                "<green>▶ Click to enter a seed"));
        inv.setItem(SLOT_INFO, item(Material.CLOCK, "<gold><b>How it works",
                "<gray>Players: " + playersLine,
                "<gray>Each player gets <white>their own world</white>",
                "<gray>with the <white>same seed</white>.",
                "<gray>You have <white>" + gatherMinutes + " minutes</white> to get gear,",
                "<gray>then you <red>fight</red> in a shrinking arena!"));
        return menu;
    }

    public static SeedMenu good() {
        SeedMenu menu = new SeedMenu(Page.GOOD, Msg.mm("<dark_gray>Trogger » </dark_gray><black>Pick a Structure"));
        Inventory inv = menu.inventory;
        fill(inv, Material.CYAN_STAINED_GLASS_PANE);
        inv.setItem(SLOT_VILLAGE, structureItem(GoodStructure.VILLAGE, "<green>",
                "<gray>Beds, food, a blacksmith chest", "<gray>and villagers to trade with."));
        inv.setItem(SLOT_SHIPWRECK, structureItem(GoodStructure.SHIPWRECK, "<aqua>",
                "<gray>Treasure maps, iron, emeralds", "<gray>and maybe buried treasure."));
        inv.setItem(SLOT_TRIAL, structureItem(GoodStructure.TRIAL_CHAMBER, "<gold>",
                "<gray>Vaults, trial spawners and", "<gray>great loot (if you survive)."));
        inv.setItem(SLOT_BACK, item(Material.ARROW, "<red>← Back"));
        return menu;
    }

    private static ItemStack structureItem(GoodStructure s, String color, String... desc) {
        String[] lore = Arrays.copyOf(desc, desc.length + 3);
        lore[desc.length] = "<gray>You get a compass pointing to it.";
        lore[desc.length + 1] = "";
        lore[desc.length + 2] = "<green>▶ Click to find a seed";
        return item(s.icon(), color + "<b>" + s.displayName(), lore);
    }

    private static void fill(Inventory inv, Material pane) {
        ItemStack filler = item(pane, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    public static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Msg.item(name));
        if (lore.length > 0) {
            List<Component> lines = Arrays.stream(lore).map(Msg::item).toList();
            meta.lore(lines);
        }
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }
}
