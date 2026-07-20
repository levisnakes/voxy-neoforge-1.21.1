package me.cortex.voxy.client.voxymap;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Settings for the 3D map view, ported from VoxyMap (3D-maps-Voxy-Addon).
 * NeoForge: config dir comes from FMLPaths instead of FabricLoader.
 */
public final class VoxyMapSettings {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("voxymap.properties");

    private static boolean loaded = false;
    private static boolean pauseSingleplayer = true;
    private static double cameraSpeedMultiplier = 1.0;
    private static boolean showPlayers = true;
    private static boolean showWaypoints = true;
    private static boolean showClaims = true;

    private VoxyMapSettings() {
    }

    public static void load() {
        if (loaded) return;
        loaded = true;

        Properties properties = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
                properties.load(input);
            } catch (IOException ignored) {
            }
        }

        pauseSingleplayer = Boolean.parseBoolean(properties.getProperty("pauseSingleplayer", Boolean.toString(pauseSingleplayer)));
        cameraSpeedMultiplier = clamp(readDouble(properties.getProperty("cameraSpeedMultiplier"), cameraSpeedMultiplier), 0.25, 4.0);
        showPlayers = Boolean.parseBoolean(properties.getProperty("showPlayers", Boolean.toString(showPlayers)));
        showWaypoints = Boolean.parseBoolean(properties.getProperty("showWaypoints", Boolean.toString(showWaypoints)));
        showClaims = Boolean.parseBoolean(properties.getProperty("showClaims", Boolean.toString(showClaims)));
    }

    public static void save() {
        Properties properties = new Properties();
        properties.setProperty("pauseSingleplayer", Boolean.toString(pauseSingleplayer));
        properties.setProperty("cameraSpeedMultiplier", String.format(Locale.ROOT, "%.2f", cameraSpeedMultiplier));
        properties.setProperty("showPlayers", Boolean.toString(showPlayers));
        properties.setProperty("showWaypoints", Boolean.toString(showWaypoints));
        properties.setProperty("showClaims", Boolean.toString(showClaims));

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "VoxyMap client settings");
            }
        } catch (IOException ignored) {
        }
    }

    public static boolean pauseSingleplayer() {
        load();
        return pauseSingleplayer;
    }

    public static void togglePauseSingleplayer() {
        load();
        pauseSingleplayer = !pauseSingleplayer;
        save();
    }

    public static double cameraSpeedMultiplier() {
        load();
        return cameraSpeedMultiplier;
    }

    public static void changeCameraSpeed(double delta) {
        load();
        cameraSpeedMultiplier = clamp(cameraSpeedMultiplier + delta, 0.25, 4.0);
        save();
    }

    public static boolean showPlayers() {
        load();
        return showPlayers;
    }

    public static void toggleShowPlayers() {
        load();
        showPlayers = !showPlayers;
        save();
    }

    public static boolean showWaypoints() {
        load();
        return showWaypoints;
    }

    public static void toggleShowWaypoints() {
        load();
        showWaypoints = !showWaypoints;
        save();
    }

    public static boolean showClaims() {
        load();
        return showClaims;
    }

    public static void toggleShowClaims() {
        load();
        showClaims = !showClaims;
        save();
    }

    private static double readDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
