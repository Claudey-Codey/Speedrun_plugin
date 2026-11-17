package dev.dueley.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** PlayerRespawnEvent#setRespawnLocation: Dueley decides where a player respawns. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @WrapOperation(method = "respawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/TeleportTransition$PostTeleportTransition;)Lnet/minecraft/world/level/portal/TeleportTransition;"))
    private TeleportTransition dueley$respawnLocation(ServerPlayer player, boolean consumeSpawnBlock,
                                                      TeleportTransition.PostTeleportTransition post, Operation<TeleportTransition> original) {
        return GameListener.respawn(player, original.call(player, consumeSpawnBlock, post));
    }
}
