package me.cortex.voxy.client.voxymap;

import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Opens Xaero's World Map screen from the 3D map. Ported from VoxyMap
 * (3D-maps-Voxy-Addon), rewritten to use reflection so Voxy does not need
 * Xaero's World Map on the compile classpath (and tolerates version drift).
 */
public final class XaeroWorldMapBridge {

    private static final String XAERO_MOD_ID = "xaeroworldmap";

    private static Boolean available;

    private XaeroWorldMapBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = ModList.get() != null && ModList.get().isLoaded(XAERO_MOD_ID);
        }
        return available;
    }

    public static boolean openWorldMap(Minecraft client) {
        if (!isAvailable() || client == null || client.player == null) {
            return false;
        }

        try {
            Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = sessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);
            if (session == null) {
                Logger.warn("[VoxyMap] Xaero World Map is installed, but its current session is not ready yet.");
                return false;
            }

            Object mapProcessor = sessionClass.getMethod("getMapProcessor").invoke(session);
            if (mapProcessor == null) {
                Logger.warn("[VoxyMap] Xaero World Map session has no active map processor.");
                return false;
            }

            Class<?> guiMapClass = Class.forName("xaero.map.gui.GuiMap");
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Screen screen = null;
            for (Class<?> playerType : new Class<?>[]{Player.class, Entity.class}) {
                try {
                    Constructor<?> ctor = guiMapClass.getConstructor(Screen.class, Screen.class, mapProcessorClass, playerType);
                    screen = (Screen) ctor.newInstance(null, null, mapProcessor, client.player);
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (screen == null) {
                Logger.warn("[VoxyMap] Could not find a compatible GuiMap constructor in this Xaero World Map version.");
                return false;
            }
            client.setScreen(screen);
            return true;
        } catch (Throwable t) {
            Logger.warn("[VoxyMap] Could not open Xaero World Map from VoxyMap: " + t);
            return false;
        }
    }
}
