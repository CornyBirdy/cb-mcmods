package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.Outline;
import dev.cornybirdy.estate.core.TownZone;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;

/**
 * Draws claim borders with coloured dust particles, only for the player who asked: green for their own claims,
 * red for other players' claims, yellow for buffers, aqua for town zones, and white (or red when it can't be
 * confirmed) for a claim being edited in the menu. Borders fade by themselves after {@code borderParticleSeconds}.
 */
final class Borders {

    private static final int REDRAW_TICKS = 10;
    private static final int VIEW_DISTANCE = 64;
    private static final int MAX_PARTICLES = 1500;

    private static final DustParticleOptions OWN = new DustParticleOptions(0x55FF55, 1.4f);
    private static final DustParticleOptions OTHER = new DustParticleOptions(0xFF5555, 1.4f);
    private static final DustParticleOptions BUFFER = new DustParticleOptions(0xFFDD33, 1.0f);
    private static final DustParticleOptions TOWN = new DustParticleOptions(0x55FFFF, 1.2f);
    private static final DustParticleOptions DRAFT_OK = new DustParticleOptions(0xFFFFFF, 1.6f);
    private static final DustParticleOptions DRAFT_BAD = new DustParticleOptions(0xFF2222, 1.6f);

    private final EstateRuntime rt;

    Borders(EstateRuntime rt) {
        this.rt = rt;
    }

    /** Shows borders around the player for a while. @param onlyClaim a claim id to show alone, or 0 */
    void show(ServerPlayer player, int onlyClaim) {
        Session session = rt.session(player);
        session.bordersUntil = rt.server.getTickCount() + rt.config.borderParticleSeconds * 20L;
        session.bordersClaim = onlyClaim;
        draw(player, session);
    }

    void tick(long tick) {
        if (tick % REDRAW_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : rt.server.getPlayerList().getPlayers()) {
            Session session = rt.session(player);
            if (session.bordersUntil > tick) {
                draw(player, session);
            } else if (session.draft != null && !(player.containerMenu instanceof Gui)) {
                // The editor was closed and its preview has faded; don't bring it back with the next "View borders".
                session.draft = null;
            }
        }
    }

    private void draw(ServerPlayer player, Session session) {
        ServerLevel level = player.level();
        String world = EstateRuntime.world(level);
        int cellSize = rt.manager.cellSize();
        int[] budget = {MAX_PARTICLES};
        double px = player.getX();
        double pz = player.getZ();
        int py = player.getBlockY();

        if (session.draft != null && session.draft.world.equals(world)) {
            ClaimDraft draft = session.draft;
            drawOutline(player, level, Outline.of(draft.cells(), cellSize, 0), draft.minY(), draft.maxY(), py, px, pz,
                    session.draftOk ? DRAFT_OK : DRAFT_BAD, budget);
        }

        Set<Claim> claims = rt.manager.claimsNear(world, (int) px, (int) pz, VIEW_DISTANCE);
        for (Claim claim : claims) {
            if (session.bordersClaim != 0 && claim.id() != session.bordersClaim) {
                continue;
            }
            if (session.draft != null && session.draft.claimId != null && session.draft.claimId == claim.id()) {
                continue;
            }
            boolean own = claim.owner().equals(player.getUUID());
            drawOutline(player, level, Outline.of(claim.cells(), cellSize, 0), claim.minY(), claim.maxY(), py, px, pz,
                    own ? OWN : OTHER, budget);
            int buffer = rt.config.bufferDistance;
            if (buffer > 0 && !own) {
                drawOutline(player, level, Outline.of(claim.cells(), cellSize, buffer), claim.minY() - buffer,
                        claim.maxY() + buffer, py, px, pz, BUFFER, budget);
            }
        }
        if (session.bordersClaim == 0) {
            for (TownZone town : rt.manager.towns()) {
                if (town.world().equals(world)) {
                    List<Outline.Segment> segments = List.of(
                            new Outline.Segment(town.minX(), town.minZ(), town.maxX() + 1, town.minZ()),
                            new Outline.Segment(town.minX(), town.maxZ() + 1, town.maxX() + 1, town.maxZ() + 1),
                            new Outline.Segment(town.minX(), town.minZ(), town.minX(), town.maxZ() + 1),
                            new Outline.Segment(town.maxX() + 1, town.minZ(), town.maxX() + 1, town.maxZ() + 1));
                    drawOutline(player, level, segments, level.getMinY(), level.getMaxY(), py, px, pz, TOWN, budget);
                }
            }
        }
    }

    /**
     * Draws the outline at the player's height (kept inside the claim's range) and posts at the corners, plus the
     * top and bottom edges when they're close enough to see.
     */
    private static void drawOutline(ServerPlayer player, ServerLevel level, List<Outline.Segment> segments,
                                    int minY, int maxY, int playerY, double px, double pz,
                                    DustParticleOptions colour, int[] budget) {
        double eyeLine = Math.max(minY, Math.min(maxY, playerY + 1)) + 0.5;
        for (Outline.Segment segment : segments) {
            if (budget[0] <= 0) {
                return;
            }
            if (distanceToSegment(segment, px, pz) > VIEW_DISTANCE) {
                continue;
            }
            line(player, level, segment, eyeLine, colour, budget);
            if (Math.abs(maxY + 1 - playerY) <= 16) {
                line(player, level, segment, maxY + 1, colour, budget);
            }
            if (Math.abs(minY - playerY) <= 16) {
                line(player, level, segment, minY, colour, budget);
            }
            int bottom = Math.max(minY, playerY - 3);
            int top = Math.min(maxY + 1, playerY + 5);
            for (int y = bottom; y <= top && budget[0] > 0; y++) {
                particle(player, level, segment.x1(), y + 0.5, segment.z1(), colour, budget);
                particle(player, level, segment.x2(), y + 0.5, segment.z2(), colour, budget);
            }
        }
    }

    private static void line(ServerPlayer player, ServerLevel level, Outline.Segment segment, double y,
                             DustParticleOptions colour, int[] budget) {
        int length = segment.length();
        for (int i = 0; i <= length && budget[0] > 0; i++) {
            double t = length == 0 ? 0 : (double) i / length;
            double x = segment.x1() + (segment.x2() - segment.x1()) * t;
            double z = segment.z1() + (segment.z2() - segment.z1()) * t;
            particle(player, level, x, y, z, colour, budget);
        }
    }

    private static void particle(ServerPlayer player, ServerLevel level, double x, double y, double z,
                                 DustParticleOptions colour, int[] budget) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        if (dx * dx + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }
        budget[0]--;
        level.sendParticles(player, colour, true, true, x, y, z, 1, 0, 0, 0, 0);
    }

    private static double distanceToSegment(Outline.Segment s, double px, double pz) {
        double cx = Math.max(Math.min(s.x1(), s.x2()), Math.min(px, Math.max(s.x1(), s.x2())));
        double cz = Math.max(Math.min(s.z1(), s.z2()), Math.min(pz, Math.max(s.z1(), s.z2())));
        return Math.hypot(px - cx, pz - cz);
    }
}
