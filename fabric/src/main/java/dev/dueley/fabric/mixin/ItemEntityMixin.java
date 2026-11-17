package dev.dueley.fabric.mixin;

import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** EntityPickupItemEvent: no picking items up in the lobby or while locked. */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void dueley$noPickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer p && GameListener.refusePickup(p)) {
            ci.cancel();
        }
    }
}
