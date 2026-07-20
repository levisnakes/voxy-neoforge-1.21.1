package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides clouds and weather while the 3D map is open.
 * MC 1.21.1: renderClouds/renderSnowAndRain (addCloudsPass/addWeatherPass are 1.21.2+).
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererMap {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideClouds(CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void voxymap$hideWeather(CallbackInfo ci) {
        if (VoxyMapCameraController.isActive()) {
            ci.cancel();
        }
    }
}
