package dev.dueley.fabric;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.List;

/** Structures a "random good seed" can be built around. */
public enum GoodStructure {
    VILLAGE("Village", Items.BELL, List.of(
            BuiltinStructures.VILLAGE_PLAINS, BuiltinStructures.VILLAGE_DESERT, BuiltinStructures.VILLAGE_SAVANNA,
            BuiltinStructures.VILLAGE_SNOWY, BuiltinStructures.VILLAGE_TAIGA)),
    SHIPWRECK("Shipwreck", Items.OAK_BOAT, List.of(
            BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED)),
    TRIAL_CHAMBER("Trial Chamber", Items.TRIAL_KEY, List.of(
            BuiltinStructures.TRIAL_CHAMBERS));

    private final String displayName;
    private final Item icon;
    private final List<ResourceKey<Structure>> structures;

    GoodStructure(String displayName, Item icon, List<ResourceKey<Structure>> structures) {
        this.displayName = displayName;
        this.icon = icon;
        this.structures = structures;
    }

    public String displayName() {
        return displayName;
    }

    public Item icon() {
        return icon;
    }

    public List<ResourceKey<Structure>> structures() {
        return structures;
    }
}
