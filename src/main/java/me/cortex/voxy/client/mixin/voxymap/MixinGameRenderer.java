package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import me.cortex.voxy.client.voxymap.VoxyMapGuiRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3D map hooks for MC 1.21.1's GameRenderer:
 *  - overrides the FOV with the map camera's zoom-derived FOV
 *  - renders Voxy into the main target right before the HUD/screen draws (shader path)
 *  - hides the held item while the map camera is active
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void voxymap$useMapFov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (VoxyMapCameraController.isActive()) {
            cir.setReturnValue((double) VoxyMapCameraController.fov());
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.BEFORE))
    private void voxymap$renderGuiMapBeforeHud(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        VoxyMapGuiRenderer.render(this.minecraft);
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideHandInMap(Camera camera, float partialTick, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
