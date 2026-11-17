package dev.dueley.fabric.mixin;

import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nether portals can be lit in Dueley's own overworlds and nethers. Vanilla only allows it in the worlds keyed
 * minecraft:overworld / minecraft:the_nether; Paper checks the dimension type instead, and so does this.
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void dueley$portalsInOwnWorlds(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel serverLevel && GameListener.portalDimension(serverLevel)) {
            cir.setReturnValue(true);
        }
    }
}
