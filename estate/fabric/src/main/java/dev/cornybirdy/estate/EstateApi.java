package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Claim;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * For other mods on the same server. All methods are safe to call on the server thread
 * and return the "unclaimed" answer while Estate isn't running.
 */
public final class EstateApi {

    private EstateApi() {
    }

    /** The owner of the claim at a spot, or null if it's unclaimed. */
    public static UUID ownerAt(ServerLevel level, BlockPos pos) {
        EstateRuntime runtime = Estate.runtime();
        Claim claim = runtime == null ? null : runtime.claimAt(level, pos);
        return claim == null ? null : claim.owner();
    }

    /** Whether a player may place or break a block at a spot, by Estate's rules (claims, buffers, trust). */
    public static boolean mayBuild(ServerLevel level, BlockPos pos, UUID player) {
        EstateRuntime runtime = Estate.runtime();
        return runtime == null || runtime.manager.checkBuild(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ(), player) == null;
    }
}
