package dev.dueley.fabric.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** A position in a world with a view direction (what a Bukkit {@code Location} is to the plugin). */
public record Loc(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    public Loc(ServerLevel level, double x, double y, double z) {
        this(level, x, y, z, 0f, 0f);
    }

    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public void teleport(ServerPlayer p) {
        p.teleportTo(level, x, y, z, Set.of(), yaw, pitch, true);
    }
}
