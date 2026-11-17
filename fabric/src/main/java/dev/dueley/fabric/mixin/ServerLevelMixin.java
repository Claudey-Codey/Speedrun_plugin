package dev.dueley.fabric.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Structure lookups (/locate, the good-seed search) must use the world's own seed. Vanilla passes the server's seed to
 * StructureCheck, which only matters for worlds with their own seed (Dueley's game worlds); Paper does the same fix.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @ModifyArg(method = "<init>", index = 8, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/StructureCheck;<init>(Lnet/minecraft/world/level/chunk/storage/ChunkScanAccess;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/biome/BiomeSource;JLcom/mojang/datafixers/DataFixer;)V"))
    private long dueley$levelSeed(long serverSeed) {
        return ((ServerLevel) (Object) this).getSeed();
    }
}
