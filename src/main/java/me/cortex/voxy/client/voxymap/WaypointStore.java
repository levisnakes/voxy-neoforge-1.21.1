package me.cortex.voxy.client.voxymap;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists waypoints to config/voxymap_waypoints.txt using a simple
 * pipe-delimited text format (avoids Gson record/generics pitfalls).
 * Line format: dimension|name|x|y|z|color   (name has | and newlines stripped)
 */
public final class WaypointStore {
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("voxymap_waypoints.txt");
    private static final List<Waypoint> WAYPOINTS = new ArrayList<>();
    private static boolean loaded = false;

    private static final int[] PALETTE = {
            0xFF3BA4FF, 0xFFFF6B6B, 0xFF6BFF95, 0xFFFFD93B,
            0xFFB86BFF, 0xFFFF9F3B, 0xFF3BFFE0, 0xFFFF3BC1
    };

    private WaypointStore() {
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        WAYPOINTS.clear();
        if (!Files.exists(PATH)) return;
        try {
            for (String line : Files.readAllLines(PATH, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", 6);
                if (parts.length < 6) continue;
                try {
                    WAYPOINTS.add(new Waypoint(
                            parts[1],
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim()),
                            Integer.parseInt(parts[4].trim()),
                            (int) Long.parseLong(parts[5].trim()),
                            parts[0]));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            Logger.warn("[VoxyMap] Failed to read waypoints: " + e);
        }
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (Waypoint w : WAYPOINTS) {
            String safeName = w.name.replace("|", " ").replace("\n", " ").replace("\r", " ");
            sb.append(w.dimension).append('|').append(safeName).append('|')
                    .append(w.x).append('|').append(w.y).append('|').append(w.z).append('|')
                    .append(w.color).append('\n');
        }
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("[VoxyMap] Failed to save waypoints: " + e);
        }
    }

    public static List<Waypoint> forDimension(String dimension) {
        ensureLoaded();
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : WAYPOINTS) {
            if (w.dimension.equals(dimension)) {
                out.add(w);
            }
        }
        return out;
    }

    public static Waypoint add(String name, int x, int y, int z, String dimension) {
        ensureLoaded();
        int color = PALETTE[WAYPOINTS.size() % PALETTE.length];
        Waypoint w = new Waypoint(name, x, y, z, color, dimension);
        WAYPOINTS.add(w);
        save();
        return w;
    }

    public static void remove(Waypoint waypoint) {
        ensureLoaded();
        if (WAYPOINTS.remove(waypoint)) {
            save();
        }
    }

    public static int nextIndex() {
        ensureLoaded();
        return WAYPOINTS.size() + 1;
    }
}
