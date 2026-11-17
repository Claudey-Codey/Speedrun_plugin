package dev.dueley.fabric;

public enum SeedMode {
    RANDOM("Random Seed"),
    GOOD("Random Good Seed"),
    CUSTOM("Custom Seed");

    private final String displayName;

    SeedMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
