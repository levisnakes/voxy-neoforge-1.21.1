package me.cortex.voxy.client.voxymap;

import me.cortex.voxy.client.config.VoxyConfig;

/**
 * Suppresses Voxy's environmental fog while the 3D map is open, restoring
 * the user's setting afterwards. Ported from VoxyMap (3D-maps-Voxy-Addon).
 */
public final class VoxyMapFogBridge {

    private static Boolean previousEnvironmentalFog = null;

    private VoxyMapFogBridge() {
    }

    public static void suppressEnvironmentalFogForMap() {
        setEnvironmentalFog(false, true);
    }

    public static void restoreEnvironmentalFogAfterMap() {
        if (previousEnvironmentalFog == null) return;
        setEnvironmentalFog(previousEnvironmentalFog, false);
        previousEnvironmentalFog = null;
    }

    private static void setEnvironmentalFog(boolean value, boolean rememberPrevious) {
        try {
            var config = VoxyConfig.CONFIG;
            boolean current = config.useEnvironmentalFog;
            if (rememberPrevious && previousEnvironmentalFog == null) {
                previousEnvironmentalFog = current;
            }
            if (current == value) {
                return;
            }
            config.useEnvironmentalFog = value;
        } catch (Throwable ignored) {
        }
    }
}
