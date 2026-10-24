package dev.cornybirdy.estate;

import com.mojang.math.Transformation;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.Outline;
import dev.cornybirdy.estate.core.TownZone;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws claim borders for the player who asked: green for their own claims, red for other players' claims, yellow for
 * buffers, aqua for town zones, and white (or red when it can't be confirmed) for a claim being drawn or resized.
 * They fade by themselves after {@code borderParticleSeconds}.
 * <p>
 * With {@code borderStyle: blocks} (the default) the borders are thin glowing bars made of block display entities that
 * show through walls. They only exist on that player's screen: the server sends the entity packets to them alone and
 * never adds the entities to the world, so nothing is left behind after a restart. With {@code particles} they're
 * coloured dust, redrawn every half second.
 */
final class Borders {

    /** One outline to draw: its lines, height range and colour. */
    private record Shape(List<Outline.Segment> segments, int minY, int maxY, Colour colour, boolean posts) {
    }

    enum Colour {
        OWN(0x55FF55, Blocks.EMERALD_BLOCK.defaultBlockState()),
        OTHER(0xFF5555, Blocks.REDSTONE_BLOCK.defaultBlockState()),
        BUFFER(0xFFDD33, Blocks.GOLD_BLOCK.defaultBlockState()),
        TOWN(0x55FFFF, Blocks.DIAMOND_BLOCK.defaultBlockState()),
        DRAFT_OK(0xFFFFFF, Blocks.QUARTZ_BLOCK.defaultBlockState()),
        DRAFT_BAD(0xFF2222, Blocks.REDSTONE_BLOCK.defaultBlockState()),
        CORNER_1(0xFF3333, Blocks.REDSTONE_BLOCK.defaultBlockState()),
        CORNER_2(0x3388FF, Blocks.LAPIS_BLOCK.defaultBlockState());

        final int rgb;
        final BlockState block;
        final DustParticleOptions dust;

        Colour(int rgb, BlockState block) {
            this.rgb = rgb;
            this.block = block;
            this.dust = new DustParticleOptions(rgb, 1.4f);
        }
    }

    private static final int REDRAW_TICKS = 10;
    private static final int VIEW_DISTANCE = 64;
    private static final int MAX_PARTICLES = 1500;
    private static final int MAX_DISPLAYS = 400;
    /** Bar thickness in blocks. */
    private static final float BAR = 0.08f;
    /** How far above and below the player the corner posts reach. */
    private static final int POST_BELOW = 6;
    private static final int POST_ABOVE = 12;

    private final EstateRuntime rt;

    Borders(EstateRuntime rt) {
        this.rt = rt;
    }

    private boolean blocks() {
        return !"particles".equals(rt.config.borderStyle);
    }

    /** Shows borders around the player for a while. @param onlyClaim a claim id to show alone, or 0 */
    void show(ServerPlayer player, int onlyClaim) {
        Session session = rt.session(player);
        session.bordersUntil = rt.server.getTickCount() + rt.config.borderParticleSeconds * 20L;
        session.bordersClaim = onlyClaim;
        refresh(player);
    }

    /** Draws again straight away, e.g. after the claim being edited changed. */
    void refresh(ServerPlayer player) {
        Session session = rt.session(player);
        if (blocks()) {
            sendDisplays(player, session);
        } else {
            clearDisplays(player, session);
            drawParticles(player, session);
        }
    }

    void tick(long tick) {
        for (ServerPlayer player : rt.server.getPlayerList().getPlayers()) {
            Session session = rt.session(player);
            if (session.bordersUntil > tick) {
                if (!blocks() && tick % REDRAW_TICKS == 0) {
                    drawParticles(player, session);
                }
                continue;
            }
            if (session.displayIds.length > 0) {
                clearDisplays(player, session);
            }
            if (session.draft != null && !(player.containerMenu instanceof Gui) && session.selection == null) {
                // The editor was closed and its preview has faded; don't bring it back with the next "View borders".
                session.draft = null;
            }
        }
    }

    /** What to draw for this player right now. */
    private List<Shape> shapes(ServerPlayer player, Session session) {
        ServerLevel level = player.level();
        String world = EstateRuntime.world(level);
        int cellSize = rt.manager.cellSize();
        List<Shape> shapes = new ArrayList<>();
        if (session.draft != null && session.draft.world.equals(world)) {
            ClaimDraft draft = session.draft;
            shapes.add(new Shape(Outline.of(draft.cells(), cellSize, 0), draft.minY(), draft.maxY(),
                    session.draftOk ? Colour.DRAFT_OK : Colour.DRAFT_BAD, true));
        }
        for (Claim claim : rt.manager.claimsNear(world, player.getBlockX(), player.getBlockZ(), VIEW_DISTANCE)) {
            if (session.bordersClaim != 0 && claim.id() != session.bordersClaim) {
                continue;
            }
            if (session.draft != null && session.draft.claimId != null && session.draft.claimId == claim.id()) {
                continue;
            }
            boolean own = claim.owner().equals(player.getUUID());
            shapes.add(new Shape(Outline.of(claim.cells(), cellSize, 0), claim.minY(), claim.maxY(),
                    own ? Colour.OWN : Colour.OTHER, true));
            int buffer = rt.config.bufferDistance;
            if (buffer > 0 && !own) {
                shapes.add(new Shape(Outline.of(claim.cells(), cellSize, buffer), claim.minY() - buffer,
                        claim.maxY() + buffer, Colour.BUFFER, false));
            }
        }
        if (session.bordersClaim == 0) {
            for (TownZone town : rt.manager.towns()) {
                if (town.world().equals(world)) {
                    shapes.add(new Shape(List.of(
                            new Outline.Segment(town.minX(), town.minZ(), town.maxX() + 1, town.minZ()),
                            new Outline.Segment(town.minX(), town.maxZ() + 1, town.maxX() + 1, town.maxZ() + 1),
                            new Outline.Segment(town.minX(), town.minZ(), town.minX(), town.maxZ() + 1),
                            new Outline.Segment(town.maxX() + 1, town.minZ(), town.maxX() + 1, town.maxZ() + 1)),
                            level.getMinY(), level.getMaxY(), Colour.TOWN, false));
                }
            }
        }
        return shapes;
    }

    // ------------------------------------------------------------------ block displays

    private void sendDisplays(ServerPlayer player, Session session) {
        clearDisplays(player, session);
        ServerLevel level = player.level();
        RegistryOps<Tag> ops = rt.server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        int py = player.getBlockY();
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (Shape shape : shapes(player, session)) {
            double lineY = Math.max(shape.minY(), Math.min(shape.maxY(), py)) + 0.02;
            Set<Long> corners = new LinkedHashSet<>();
            for (Outline.Segment segment : shape.segments()) {
                if (ids.size() >= MAX_DISPLAYS) {
                    break;
                }
                if (distanceToSegment(segment, player.getX(), player.getZ()) > VIEW_DISTANCE) {
                    continue;
                }
                boolean alongX = segment.z1() == segment.z2();
                float length = segment.length();
                Vector3f scale = alongX ? new Vector3f(length, BAR, BAR) : new Vector3f(BAR, BAR, length);
                double x = Math.min(segment.x1(), segment.x2()) - (alongX ? 0 : BAR / 2);
                double z = Math.min(segment.z1(), segment.z2()) - (alongX ? BAR / 2 : 0);
                bar(level, ops, shape.colour(), x, lineY, z, scale, packets, ids);
                corners.add(((long) segment.x1() << 32) | (segment.z1() & 0xFFFFFFFFL));
                corners.add(((long) segment.x2() << 32) | (segment.z2() & 0xFFFFFFFFL));
            }
            if (!shape.posts()) {
                continue;
            }
            int bottom = Math.max(shape.minY(), py - POST_BELOW);
            int top = Math.min(shape.maxY() + 1, py + POST_ABOVE);
            if (top <= bottom) {
                continue;
            }
            for (long corner : corners) {
                if (ids.size() >= MAX_DISPLAYS) {
                    break;
                }
                int cx = (int) (corner >> 32);
                int cz = (int) corner;
                bar(level, ops, shape.colour(), cx - BAR / 2, bottom, cz - BAR / 2, new Vector3f(BAR, top - bottom, BAR), packets, ids);
            }
        }
        ClaimDraft draft = session.draft;
        if (draft != null && draft.world.equals(EstateRuntime.world(level))) {
            // Like Litematica: corner 1 is a red cube, corner 2 a blue one.
            for (int which = 1; which <= 2; which++) {
                int[] c = draft.corner(which);
                bar(level, ops, which == 1 ? Colour.CORNER_1 : Colour.CORNER_2, c[0] - 0.01, c[1] - 0.01, c[2] - 0.01,
                        new Vector3f(1.02f), packets, ids);
            }
        }
        if (!packets.isEmpty()) {
            player.connection.send(new ClientboundBundlePacket(packets));
        }
        session.displayIds = ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One glowing bar: a block display scaled to a thin box, only ever sent to this player. */
    private static void bar(ServerLevel level, RegistryOps<Tag> ops, Colour colour, double x, double y, double z,
                            Vector3f scale, List<Packet<? super ClientGamePacketListener>> packets, List<Integer> ids) {
        CompoundTag data = new CompoundTag();
        data.store(Display.BlockDisplay.TAG_BLOCK_STATE, BlockState.CODEC, ops, colour.block);
        data.store(Display.TAG_TRANSFORMATION, Transformation.CODEC, ops,
                new Transformation(new Vector3f(), new Quaternionf(), scale, new Quaternionf()));
        data.store(Display.TAG_BRIGHTNESS, Brightness.CODEC, ops, Brightness.FULL_BRIGHT);
        data.putInt(Display.TAG_GLOW_COLOR_OVERRIDE, colour.rgb);
        data.putFloat(Display.TAG_VIEW_RANGE, 2f);
        Entity entity = EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, data, level, EntitySpawnReason.COMMAND, e -> {
            e.snapTo(x, y, z, 0, 0);
            return e;
        });
        if (entity == null) {
            return;
        }
        entity.setGlowingTag(true);
        packets.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), x, y, z, 0, 0,
                EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            packets.add(new ClientboundSetEntityDataPacket(entity.getId(), values));
        }
        ids.add(entity.getId());
    }

    private static void clearDisplays(ServerPlayer player, Session session) {
        if (session.displayIds.length > 0) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(session.displayIds));
            session.displayIds = new int[0];
        }
    }

    // ------------------------------------------------------------------ particles

    private void drawParticles(ServerPlayer player, Session session) {
        int[] budget = {MAX_PARTICLES};
        int py = player.getBlockY();
        ClaimDraft draft = session.draft;
        if (draft != null && draft.world.equals(EstateRuntime.world(player.level()))) {
            for (int which = 1; which <= 2; which++) {
                int[] c = draft.corner(which);
                DustParticleOptions dust = (which == 1 ? Colour.CORNER_1 : Colour.CORNER_2).dust;
                for (double[] o : new double[][]{{0, 0, 0}, {1, 0, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 0}, {1, 1, 0}, {0, 1, 1}, {1, 1, 1}}) {
                    particle(player, c[0] + o[0], c[1] + o[1], c[2] + o[2], dust, budget);
                }
            }
        }
        for (Shape shape : shapes(player, session)) {
            double eyeLine = Math.max(shape.minY(), Math.min(shape.maxY(), py + 1)) + 0.5;
            for (Outline.Segment segment : shape.segments()) {
                if (budget[0] <= 0) {
                    return;
                }
                if (distanceToSegment(segment, player.getX(), player.getZ()) > VIEW_DISTANCE) {
                    continue;
                }
                line(player, segment, eyeLine, shape.colour().dust, budget);
                if (Math.abs(shape.maxY() + 1 - py) <= 16) {
                    line(player, segment, shape.maxY() + 1, shape.colour().dust, budget);
                }
                if (Math.abs(shape.minY() - py) <= 16) {
                    line(player, segment, shape.minY(), shape.colour().dust, budget);
                }
                int bottom = Math.max(shape.minY(), py - 3);
                int top = Math.min(shape.maxY() + 1, py + 5);
                for (int y = bottom; y <= top && budget[0] > 0; y++) {
                    particle(player, segment.x1(), y + 0.5, segment.z1(), shape.colour().dust, budget);
                    particle(player, segment.x2(), y + 0.5, segment.z2(), shape.colour().dust, budget);
                }
            }
        }
    }

    private static void line(ServerPlayer player, Outline.Segment segment, double y, DustParticleOptions dust, int[] budget) {
        int length = segment.length();
        for (int i = 0; i <= length && budget[0] > 0; i++) {
            double t = length == 0 ? 0 : (double) i / length;
            double x = segment.x1() + (segment.x2() - segment.x1()) * t;
            double z = segment.z1() + (segment.z2() - segment.z1()) * t;
            particle(player, x, y, z, dust, budget);
        }
    }

    private static void particle(ServerPlayer player, double x, double y, double z, DustParticleOptions dust, int[] budget) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        if (dx * dx + dz * dz > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }
        budget[0]--;
        player.level().sendParticles(player, dust, true, true, x, y, z, 1, 0, 0, 0, 0);
    }

    private static double distanceToSegment(Outline.Segment s, double px, double pz) {
        double cx = Math.max(Math.min(s.x1(), s.x2()), Math.min(px, Math.max(s.x1(), s.x2())));
        double cz = Math.max(Math.min(s.z1(), s.z2()), Math.min(pz, Math.max(s.z1(), s.z2())));
        return Math.hypot(px - cx, pz - cz);
    }
}
