package dev.dueley.fabric.menu;

import dev.dueley.fabric.GameManager;
import dev.dueley.fabric.GoodStructure;
import dev.dueley.fabric.Msg;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.containerwrappers.AbstractWrapperMenu;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * The seed selection screens: a 27-slot chest menu made with sgui (a server-side GUI that vanilla clients see as a
 * normal chest). Same layout, items, names and lore as the plugin's SeedMenu; clicks are handled by {@link MenuListener}.
 */
public final class SeedMenu extends SimpleGui {
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
    private final GameManager game;

    private SeedMenu(GameManager game, ServerPlayer player, Page page, Component title) {
        super(MenuType.GENERIC_9x3, player, false);
        this.game = game;
        this.page = page;
        setTitle(title);
        setLockPlayerInventory(true); // every click is cancelled while the menu is open, like the plugin
    }

    public Page page() {
        return page;
    }

    /** Is one of the seed menus open for this player? */
    public static boolean isOpen(ServerPlayer p) {
        return p.containerMenu instanceof AbstractWrapperMenu m && m.getBackingGui() instanceof SeedMenu;
    }

    public static SeedMenu main(GameManager game, ServerPlayer player, String playersLine, int gatherMinutes) {
        SeedMenu menu = new SeedMenu(game, player, Page.MAIN, Msg.mm("<dark_gray>Dueley » </dark_gray><black>Choose a Seed"));
        menu.fill(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK));
        menu.setSlot(SLOT_RANDOM, item(Items.WHEAT_SEEDS, "<yellow><b>Random Seed",
                "<gray>A completely random world.",
                "",
                "<green>▶ Click to play"));
        menu.setSlot(SLOT_GOOD, item(Items.DIAMOND, "<aqua><b>Random Good Seed",
                "<gray>A random seed with a great",
                "<gray>structure close to spawn:",
                "<dark_gray> • <white>Village",
                "<dark_gray> • <white>Shipwreck",
                "<dark_gray> • <white>Trial Chamber",
                "<gray>You get a <gold>compass</gold> that points to it!",
                "",
                "<green>▶ Click to choose a structure"));
        menu.setSlot(SLOT_CUSTOM, item(Items.NAME_TAG, "<light_purple><b>Custom Seed",
                "<gray>Type any seed in chat.",
                "<gray>Numbers or text both work.",
                "",
                "<green>▶ Click to enter a seed"));
        menu.setSlot(SLOT_INFO, item(Items.CLOCK, "<gold><b>How it works",
                "<gray>Players: " + playersLine,
                "<gray>Each player gets <white>their own world</white>",
                "<gray>with the <white>same seed</white>.",
                "<gray>You have <white>" + gatherMinutes + " minutes</white> to get gear,",
                "<gray>then you <red>fight</red> in a shrinking arena!"));
        return menu;
    }

    public static SeedMenu good(GameManager game, ServerPlayer player) {
        SeedMenu menu = new SeedMenu(game, player, Page.GOOD, Msg.mm("<dark_gray>Dueley » </dark_gray><black>Pick a Structure"));
        menu.fill(Items.STAINED_GLASS_PANE.pick(DyeColor.CYAN));
        menu.setSlot(SLOT_VILLAGE, structureItem(GoodStructure.VILLAGE, "<green>",
                "<gray>Beds, food, a blacksmith chest", "<gray>and villagers to trade with."));
        menu.setSlot(SLOT_SHIPWRECK, structureItem(GoodStructure.SHIPWRECK, "<aqua>",
                "<gray>Treasure maps, iron, emeralds", "<gray>and maybe buried treasure."));
        menu.setSlot(SLOT_TRIAL, structureItem(GoodStructure.TRIAL_CHAMBER, "<gold>",
                "<gray>Vaults, trial spawners and", "<gray>great loot (if you survive)."));
        menu.setSlot(SLOT_BACK, item(Items.ARROW, "<red>← Back"));
        return menu;
    }

    @Override
    public boolean onClick(int index, ClickType type, ContainerInput action, GuiElement element) {
        if (index >= 0 && index < getVirtualSize()) {
            MenuListener.onClick(game, this, getPlayer(), index);
        }
        return false;
    }

    private static ItemStack structureItem(GoodStructure s, String color, String... desc) {
        String[] lore = Arrays.copyOf(desc, desc.length + 3);
        lore[desc.length] = "<gray>You get a compass pointing to it.";
        lore[desc.length + 1] = "";
        lore[desc.length + 2] = "<green>▶ Click to find a seed";
        return item(s.icon(), color + "<b>" + s.displayName(), lore);
    }

    private void fill(Item pane) {
        ItemStack filler = item(pane, " ");
        for (int i = 0; i < getVirtualSize(); i++) {
            setSlot(i, filler.copy());
        }
    }

    /** An item with a name and lore (no italics) and every item flag set, like the plugin's SeedMenu.item. */
    public static ItemStack item(Item material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        stack.set(DataComponents.CUSTOM_NAME, Msg.item(name));
        if (lore.length > 0) {
            List<Component> lines = Arrays.stream(lore).map(l -> (Component) Msg.item(l)).toList();
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        stack.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(false, ALL_ITEM_FLAGS));
        return stack;
    }

    /** What Paper hides for {@code ItemFlag.values()}. */
    private static final SequencedSet<DataComponentType<?>> ALL_ITEM_FLAGS = new LinkedHashSet<>(List.of(
            DataComponents.ENCHANTMENTS, DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.UNBREAKABLE,
            DataComponents.CAN_BREAK, DataComponents.CAN_PLACE_ON, DataComponents.DYED_COLOR, DataComponents.TRIM,
            DataComponents.STORED_ENCHANTMENTS,
            // HIDE_ADDITIONAL_TOOLTIP
            DataComponents.BANNER_PATTERNS, DataComponents.BEES, DataComponents.BLOCK_ENTITY_DATA, DataComponents.BLOCK_STATE,
            DataComponents.BUNDLE_CONTENTS, DataComponents.CHARGED_PROJECTILES, DataComponents.CONTAINER,
            DataComponents.CONTAINER_LOOT, DataComponents.FIREWORK_EXPLOSION, DataComponents.FIREWORKS,
            DataComponents.INSTRUMENT, DataComponents.MAP_ID, DataComponents.POT_DECORATIONS, DataComponents.POTION_CONTENTS,
            DataComponents.WRITTEN_BOOK_CONTENT, DataComponents.JUKEBOX_PLAYABLE));
}
