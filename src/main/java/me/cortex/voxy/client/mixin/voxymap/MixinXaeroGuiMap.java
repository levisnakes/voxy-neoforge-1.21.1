package me.cortex.voxy.client.mixin.voxymap;

import me.cortex.voxy.client.voxymap.VoxyMapReturnOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a "return to 3D map" button on top of Xaero's World Map screen.
 * @Pseudo: applies only when Xaero's World Map is installed. NeoForge builds of
 * Xaero use Mojmap names (the Fabric addon targeted intermediary method_25394).
 */
@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class MixinXaeroGuiMap {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"), remap = false, require = 0)
    private void voxymap$renderReturnButton(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        VoxyMapReturnOverlay.render(g, Minecraft.getInstance().getWindow().getGuiScaledWidth(), mouseX, mouseY);
    }

    @Inject(method = "mouseClicked(DDI)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void voxymap$clickReturnButton(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        if (button == GLFW.GLFW_MOUSE_BUTTON_1 && VoxyMapReturnOverlay.click(width, mouseX, mouseY)) {
            cir.setReturnValue(true);
        }
    }
}
