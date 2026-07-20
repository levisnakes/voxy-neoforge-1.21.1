package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the crosshair and hotbar while the 3D map is open.
 */
@Mixin(Gui.class)
public abstract class MixinGui {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideCrosshair(CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideHotbar(CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
