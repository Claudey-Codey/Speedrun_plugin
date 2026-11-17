package dev.dueley.fabric.mixin;

import dev.dueley.fabric.GameListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The lobby and the arena never have weather (only server levels are affected). */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "canHaveWeather", at = @At("HEAD"), cancellable = true)
    private void dueley$noWeather(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level && GameListener.noWeather(level)) {
            cir.setReturnValue(false);
        }
    }
}
