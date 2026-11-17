package dev.dueley.fabric.mixin;

import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** InventoryClickEvent: the menu Nether Star stays put in the lobby. Server side only (ServerPlayer). */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void dueley$keepMenuItem(int slotIndex, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer p && GameListener.refuseClick(p, (AbstractContainerMenu) (Object) this, slotIndex)) {
            ci.cancel();
        }
    }
}
