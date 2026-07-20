package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the partial-tick sky darkness (used by the lightmap) at full daylight
 * while the 3D map is open.
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel {
    @Inject(method = "getSkyDarken(F)F", at = @At("HEAD"), cancellable = true)
    private void voxymap$keepMapSkyBright(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue(0.0f);
        }
    }
}
