package me.cortex.voxy.client.voxymap;

import me.cortex.voxy.common.Logger;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Reads claimed chunks from Open Parties and Claims via its client API, using
 * reflection so Voxy needs no compile dependency and tolerates OPAC being absent.
 * API path (OPAC 0.26.x): OpenPACClientAPI.get().getClaimsManager()
 *   .get(ResourceLocation dim, int chunkX, int chunkZ) -> IPlayerChunkClaimAPI
 *   claim.getPlayerId() -> UUID ; manager.getPlayerInfo(uuid).getClaimsColor() -> int
 */
public final class ClaimsBridge {
    private static Boolean available;

    private static Method getApi;          // OpenPACClientAPI.get()
    private static Method getClaimsManager; // .getClaimsManager()
    private static Method getClaim;        // manager.get(ResourceLocation, int, int)
    private static Method claimGetPlayerId; // IPlayerChunkClaimAPI.getPlayerId()
    private static Method hasPlayerInfo;    // manager.hasPlayerInfo(UUID)
    private static Method getPlayerInfo;    // manager.getPlayerInfo(UUID)
    private static Method infoGetColor;     // IPlayerClaimInfoAPI.getClaimsColor()
    private static Method infoGetName;      // IPlayerClaimInfoAPI.getClaimsName()

    private ClaimsBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            available = init();
        }
        return available;
    }

    private static boolean init() {
        try {
            if (ModList.get() == null || !ModList.get().isLoaded("openpartiesandclaims")) {
                return false;
            }
            Class<?> apiClass = Class.forName("xaero.pac.client.api.OpenPACClientAPI");
            getApi = apiClass.getMethod("get");
            getClaimsManager = apiClass.getMethod("getClaimsManager");

            Class<?> managerClass = Class.forName("xaero.pac.client.claims.api.IClientClaimsManagerAPI");
            getClaim = managerClass.getMethod("get", ResourceLocation.class, int.class, int.class);
            hasPlayerInfo = managerClass.getMethod("hasPlayerInfo", UUID.class);
            getPlayerInfo = managerClass.getMethod("getPlayerInfo", UUID.class);

            Class<?> claimClass = Class.forName("xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI");
            claimGetPlayerId = claimClass.getMethod("getPlayerId");

            Class<?> infoClass = Class.forName("xaero.pac.common.claims.player.api.IPlayerClaimInfoAPI");
            infoGetColor = infoClass.getMethod("getClaimsColor");
            infoGetName = infoClass.getMethod("getClaimsName");
            return true;
        } catch (Throwable t) {
            Logger.warn("[VoxyMap] Open Parties and Claims API not available: " + t);
            return false;
        }
    }

    /** A single claimed chunk: chunk coords + ARGB color + owner label (may be null). */
    public record ClaimedChunk(int chunkX, int chunkZ, int color, String owner) {
    }

    private static Object claimsManager() throws Exception {
        Object api = getApi.invoke(null);
        return getClaimsManager.invoke(api);
    }

    /**
     * Collects claimed chunks in the inclusive chunk-coordinate rectangle.
     * Returns an empty list (never throws) if OPAC is missing or errors.
     */
    public static java.util.List<ClaimedChunk> getClaims(ResourceLocation dimension,
                                                         int minChunkX, int minChunkZ,
                                                         int maxChunkX, int maxChunkZ,
                                                         int maxChunks) {
        java.util.List<ClaimedChunk> out = new java.util.ArrayList<>();
        if (!isAvailable()) {
            return out;
        }
        try {
            Object manager = claimsManager();
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    Object claim = getClaim.invoke(manager, dimension, cx, cz);
                    if (claim == null) {
                        continue;
                    }
                    Object ownerId = claimGetPlayerId.invoke(claim);
                    if (!(ownerId instanceof UUID uuid)) {
                        continue;
                    }
                    int color = 0x3BA4FF;
                    String owner = null;
                    if (Boolean.TRUE.equals(hasPlayerInfo.invoke(manager, uuid))) {
                        Object info = getPlayerInfo.invoke(manager, uuid);
                        if (info != null) {
                            Object c = infoGetColor.invoke(info);
                            if (c instanceof Integer ci) {
                                color = ci & 0xFFFFFF;
                            }
                            Object n = infoGetName.invoke(info);
                            if (n instanceof String ns && !ns.isBlank()) {
                                owner = ns;
                            }
                        }
                    }
                    out.add(new ClaimedChunk(cx, cz, 0xFF000000 | color, owner));
                    if (out.size() >= maxChunks) {
                        return out;
                    }
                }
            }
        } catch (Throwable t) {
            Logger.warn("[VoxyMap] Failed to read OPAC claims: " + t);
        }
        return out;
    }
}
