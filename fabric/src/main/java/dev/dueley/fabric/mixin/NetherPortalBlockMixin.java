package dev.dueley.fabric.mixin;

import dev.dueley.fabric.GameListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Player/EntityPortalEvent: in Dueley's worlds a nether portal leads to the player's own nether (or overworld).
 * Vanilla then finds or builds the exit portal around that spot.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
    @Shadow
    private TeleportTransition getExitPortal(ServerLevel newLevel, Entity entity, BlockPos portalEntryPos,
                                             BlockPos approximateExitPos, boolean toNether, WorldBorder worldBorder) {
        throw new AssertionError();
    }

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void dueley$ownNether(ServerLevel currentLevel, Entity entity, BlockPos portalEntryPos, CallbackInfoReturnable<TeleportTransition> cir) {
        GameListener.PortalTarget target = GameListener.netherPortal(currentLevel, entity);
        if (target == null) {
            return; // not one of Dueley's worlds: vanilla
        }
        if (target.target() == null) {
            cir.setReturnValue(null); // the portal does nothing
            return;
        }
        cir.setReturnValue(getExitPortal(target.target().level(), entity, portalEntryPos, target.approximateExit(),
                target.toNether(), target.target().level().getWorldBorder()));
    }
}
