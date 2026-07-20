package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapCameraController;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Redirects the vanilla camera to the 3D map's orbital camera while the map is open.
 * MC 1.21.1: Camera.setup(BlockGetter, Entity, boolean, boolean, float); no fov field
 * on Camera (fov is overridden in MixinGameRenderer instead).
 */
@Mixin(Camera.class)
public abstract class MixinCamera {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("RETURN"))
    private void voxymap$useMapCamera(BlockGetter level, Entity entity, boolean detached, boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!VoxyMapCameraController.isActive()) {
            return;
        }

        this.detached = true;
        this.setPosition(VoxyMapCameraController.cameraX(), VoxyMapCameraController.cameraY(), VoxyMapCameraController.cameraZ());
        this.setRotation(VoxyMapCameraController.cameraYaw(), VoxyMapCameraController.cameraPitch());
    }
}
