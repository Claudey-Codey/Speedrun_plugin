package dev.hourduel;

import org.bukkit.Material;
import org.bukkit.generator.structure.Structure;

import java.util.List;

/** Structures a "random good seed" can be built around. */
public enum GoodStructure {
    VILLAGE("Village", Material.BELL, List.of(
            Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA,
            Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA)),
    SHIPWRECK("Shipwreck", Material.OAK_BOAT, List.of(
            Structure.SHIPWRECK, Structure.SHIPWRECK_BEACHED)),
    TRIAL_CHAMBER("Trial Chamber", Material.TRIAL_KEY, List.of(
            Structure.TRIAL_CHAMBERS));

    private final String displayName;
    private final Material icon;
    private final List<Structure> structures;

    GoodStructure(String displayName, Material icon, List<Structure> structures) {
        this.displayName = displayName;
        this.icon = icon;
        this.structures = structures;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public List<Structure> structures() {
        return structures;
    }
}
