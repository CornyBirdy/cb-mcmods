package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Denial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gets a player out of a place they can't build in, such as inside someone else's claim or its buffer, by moving them
 * to the nearest spot where they could break a block.
 */
final class Unstuck {

    /** How far to look, in blocks. */
    private static final int MAX_RADIUS = 160;
    /** How far up and down from the player a standing spot may be. */
    private static final int MAX_DY = 16;
    private static final int COOLDOWN_TICKS = 200;

    private static final Map<UUID, Long> LAST_USED = new HashMap<>();

    private Unstuck() {
    }

    static int run(EstateRuntime rt, ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        String world = EstateRuntime.world(level);
        BlockPos feet = player.blockPosition();
        if (rt.bypassing(player) || free(rt, world, player, feet)) {
            rt.send(player, "unstuck-fine");
            return 0;
        }
        long now = rt.server.getTickCount();
        Long last = LAST_USED.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) {
            rt.send(player, "unstuck-wait", "seconds", (COOLDOWN_TICKS - (now - last) + 19) / 20);
            return 0;
        }
        BlockPos spot = find(rt, level, world, player, feet);
        if (spot == null) {
            rt.send(player, "unstuck-none");
            return 0;
        }
        LAST_USED.put(player.getUUID(), now);
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, Set.of(), player.getYRot(), player.getXRot(), true);
        rt.send(player, "unstuck-done");
        return 1;
    }

    /** Whether the player may break blocks at this spot (at their feet and head). */
    private static boolean free(EstateRuntime rt, String world, ServerPlayer player, BlockPos feet) {
        UUID id = player.getUUID();
        Denial feetDenial = rt.manager.checkBuild(world, feet.getX(), feet.getY(), feet.getZ(), id);
        Denial headDenial = rt.manager.checkBuild(world, feet.getX(), feet.getY() + 1, feet.getZ(), id);
        return feetDenial == null && headDenial == null;
    }

    /** The closest column, searching outwards in squares, with somewhere safe to stand where the player may build. */
    private static BlockPos find(EstateRuntime rt, ServerLevel level, String world, ServerPlayer player, BlockPos from) {
        for (int r = 1; r <= MAX_RADIUS; r++) {
            for (int i = -r; i <= r; i++) {
                int[][] ring = {{from.getX() + i, from.getZ() - r}, {from.getX() + i, from.getZ() + r},
                        {from.getX() - r, from.getZ() + i}, {from.getX() + r, from.getZ() + i}};
                for (int[] column : ring) {
                    BlockPos spot = standingSpot(level, column[0], from.getY(), column[1]);
                    if (spot != null && free(rt, world, player, spot)) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    /** The spot in a column nearest to {@code refY} with solid ground below and two blocks of air, or null. */
    private static BlockPos standingSpot(ServerLevel level, int x, int refY, int z) {
        if (!level.hasChunkAt(new BlockPos(x, refY, z))) {
            return null;
        }
        for (int d = 0; d <= MAX_DY; d++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (d == 0 && sign == -1) {
                    continue;
                }
                BlockPos feet = new BlockPos(x, refY + sign * d, z);
                if (safe(level, feet)) {
                    return feet;
                }
            }
        }
        return null;
    }

    private static boolean safe(ServerLevel level, BlockPos feet) {
        BlockState ground = level.getBlockState(feet.below());
        BlockState body = level.getBlockState(feet);
        BlockState head = level.getBlockState(feet.above());
        return !ground.getCollisionShape(level, feet.below()).isEmpty() && ground.getFluidState().isEmpty()
                && body.getCollisionShape(level, feet).isEmpty() && body.getFluidState().isEmpty()
                && head.getCollisionShape(level, feet.above()).isEmpty() && head.getFluidState().isEmpty();
    }
}
