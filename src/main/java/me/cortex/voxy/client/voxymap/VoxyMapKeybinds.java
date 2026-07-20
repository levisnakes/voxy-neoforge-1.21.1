package me.cortex.voxy.client.voxymap;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the "open 3D map" keybind (default M) and opens the MapScreen.
 * Uses a real KeyMapping instead of VoxyMap's raw GLFW polling so it is
 * rebindable in vanilla controls.
 */
public class VoxyMapKeybinds {
    public static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.voxymap.open_map",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.category.voxymap.main");

    @EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP);
        }
    }

    @EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
    public static class GameBus {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            var client = Minecraft.getInstance();
            while (OPEN_MAP.consumeClick()) {
                if (client.screen == null && client.level != null) {
                    client.setScreen(new MapScreen());
                }
            }
        }
    }
}
