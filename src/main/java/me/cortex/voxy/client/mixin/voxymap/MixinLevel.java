package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forces clear noon weather/lighting on the client while the 3D map is open.
 * MC 1.21.1: time comes from Level.getDayTime() (no ClientClockManager,
 * no getOverworldClockTime/getDefaultClockTime/isDarkOutside).
 */
@Mixin(Level.class)
public abstract class MixinLevel {
    @Unique
    private boolean voxymap$isClientMapWeatherOverrideActive() {
        return VoxyMapCameraController.isActive()
                && (Object) this instanceof ClientLevel;
    }

    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
    private void voxymap$keepMapSkyBright(CallbackInfoReturnable<Integer> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0);
        }
    }

    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void voxymap$forceDayForMap(CallbackInfoReturnable<Long> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(6000L);
        }
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearRainForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearThunderForMap(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "isRaining", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearRainingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isThundering", at = @At("HEAD"), cancellable = true)
    private void voxymap$clearThunderingStateForMap(CallbackInfoReturnable<Boolean> cir) {
        if (voxymap$isClientMapWeatherOverrideActive()) {
            cir.setReturnValue(false);
        }
    }
}
