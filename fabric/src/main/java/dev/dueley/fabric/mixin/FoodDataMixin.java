package dev.dueley.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** FoodLevelChangeEvent: a protected player's food level doesn't go down. */
@Mixin(FoodData.class)
public abstract class FoodDataMixin {
    @WrapOperation(method = "tick", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/food/FoodData;foodLevel:I"))
    private void dueley$noHunger(FoodData food, int value, Operation<Void> original, @Local(argsOnly = true) ServerPlayer player) {
        if (value < food.getFoodLevel() && GameListener.noHunger(player)) {
            return;
        }
        original.call(food, value);
    }
}
