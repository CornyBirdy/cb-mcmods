package dev.cornybirdy.estate;

import com.mojang.math.Transformation;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.Outline;
import dev.cornybirdy.estate.core.Region;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws claim borders for the player who asked: green for their own claims, red for other players' claims, yellow for
 * buffers, aqua for town zones, and white (or red when it can't be confirmed) for a claim being drawn or resized.
 * They fade by themselves after {@code borderParticleSeconds}.
 * <p>
 * With {@code borderStyle: blocks} (the default) a claim is drawn as a box of tinted glass walls, floor and ceiling,
 * with glowing edges that show through terrain. It's all block display entities that only exist on that player's
 * screen: the server sends the entity packets to them alone and never adds the entities to the world, so nothing is
 * left behind after a restart. Each redraw only sends what changed, in one bundle, so nothing flickers. With
 * {@code particles} the borders are coloured dust, redrawn every half second.
 */
final class Borders {

    /** One display on the player's screen: what it shows, where, and how big. Equal specs are the same display. */
    record Spec(BlockState block, double x, double y, double z, float sx, float sy, float sz, int glow) {
        double distanceSq(double px, double py, double pz) {
            double dx = Math.max(0, Math.max(x - px, px - (x + sx)));
            double dy = Math.max(0, Math.max(y - py, py - (y + sy)));
            double dz = Math.max(0, Math.max(z - pz, pz - (z + sz)));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private enum Kind {
        /** Glass walls, floor and ceiling with glowing edges. */
        BOX,
        /** A glowing line around it at one height. */
        LINE
    }

    /** One thing to draw: its area, height range, colour and how. {@code lineY} is used for lines. */
    private record Shape(Region region, int grow, int minY, int maxY, Colour colour, Kind kind, int lineY) {
    }

    enum Colour {
        OWN(0x55FF55, Blocks.LIME_STAINED_GLASS, Blocks.EMERALD_BLOCK),
        OTHER(0xFF5555, Blocks.RED_STAINED_GLASS, Blocks.REDSTONE_BLOCK),
        BUFFER(0xFFDD33, Blocks.YELLOW_STAINED_GLASS, Blocks.GOLD_BLOCK),
        TOWN(0x55FFFF, Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.DIAMOND_BLOCK),
        DRAFT_OK(0xFFFFFF, Blocks.WHITE_STAINED_GLASS, Blocks.QUARTZ_BLOCK),
        DRAFT_BAD(0xFF2222, Blocks.RED_STAINED_GLASS, Blocks.REDSTONE_BLOCK),
        CORNER_1(0xFF3333, Blocks.RED_STAINED_GLASS, Blocks.REDSTONE_BLOCK),
        CORNER_2(0x3388FF, Blocks.BLUE_STAINED_GLASS, Blocks.LAPIS_BLOCK);

        final int rgb;
        final BlockState glass;
        final BlockState edge;
        final DustParticleOptions dust;

        Colour(int rgb, net.minecraft.world.level.block.Block glass, net.minecraft.world.level.block.Block edge) {
            this.rgb = rgb;
            this.glass = glass.defaultBlockState();
            this.edge = edge.defaultBlockState();
            this.dust = new DustParticleOptions(rgb, 1.4f);
        }
    }

    private static final int REDRAW_TICKS = 10;
    /** How far around the player borders are drawn, sideways and up or down. */
    private static final int VIEW_DISTANCE = 48;
    private static final int VIEW_HEIGHT = 32;
    private static final int MAX_PARTICLES = 1500;
    private static final int MAX_DISPLAYS = 600;
    /** Glass walls are split into panels of at most this many blocks, so the glass texture doesn't stretch. */
    private static final int PANEL = 16;
    /** Thickness of the glass panels and of the glowing edges, in blocks. */
    private static final float GLASS = 0.02f;
    private static final float EDGE = 0.06f;
    /** Display entities are drawn up to this many times 64 blocks away. */
    private static final float VIEW_RANGE = 4f;

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
                if (tick % REDRAW_TICKS == 0) {
                    // Follows the player as they walk; only what changed is sent.
                    refresh(player);
                }
                continue;
            }
            if (!session.displays.isEmpty()) {
                clearDisplays(player, session);
            }
            if (session.draft != null && !(player.containerMenu instanceof Gui) && session.selection == null) {
                // The editor was closed and its preview has faded; don't bring it back with the next "Show borders".
                session.draft = null;
            }
        }
    }

    /** What to draw for this player right now. */
    private List<Shape> shapes(ServerPlayer player, Session session) {
        ServerLevel level = player.level();
        String world = EstateRuntime.world(level);
        int lineY = (player.getBlockY() >> 2) << 2;
        List<Shape> shapes = new ArrayList<>();
        if (session.draft != null && session.draft.world.equals(world) && !session.draft.tooBig()) {
            ClaimDraft draft = session.draft;
            shapes.add(new Shape(draft.region(), 0, draft.minY(), draft.maxY(),
                    session.draftOk ? Colour.DRAFT_OK : Colour.DRAFT_BAD, Kind.BOX, 0));
        }
        int buffer = rt.config.bufferDistance;
        for (Claim claim : rt.manager.claimsNear(world, player.getBlockX(), player.getBlockZ(), VIEW_DISTANCE + buffer)) {
            if (session.bordersClaim != 0 && claim.id() != session.bordersClaim) {
                continue;
            }
            if (session.draft != null && session.draft.claimId != null && session.draft.claimId == claim.id()) {
                continue;
            }
            boolean own = claim.owner().equals(player.getUUID());
            shapes.add(new Shape(claim.region(), 0, claim.minY(), claim.maxY(), own ? Colour.OWN : Colour.OTHER, Kind.BOX, 0));
            if (buffer > 0 && !own && !rt.manager.bufferWaived(claim, player.getUUID())) {
                int bottom = claim.minY() - buffer;
                int top = claim.maxY() + buffer;
                shapes.add(new Shape(claim.region(), buffer, bottom, top, Colour.BUFFER, Kind.LINE,
                        Math.max(bottom, Math.min(top, lineY))));
            }
        }
        if (session.bordersClaim == 0) {
            for (TownZone town : rt.manager.towns()) {
                if (town.world().equals(world)) {
                    shapes.add(new Shape(Region.of(town.box()), 0, level.getMinY(), level.getMaxY(), Colour.TOWN, Kind.LINE, lineY));
                }
            }
        }
        return shapes;
    }

    // ------------------------------------------------------------------ block displays

    /** The displays the player should see now, nearest first. */
    private List<Spec> specs(ServerPlayer player, Session session) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        List<Spec> specs = new ArrayList<>();
        for (Shape shape : shapes(player, session)) {
            List<Outline.Segment> segments = Outline.of(shape.region(), shape.grow());
            if (shape.kind() == Kind.LINE) {
                for (Outline.Segment segment : segments) {
                    if (near(segment, px, pz)) {
                        specs.add(edge(shape.colour(), segment, shape.lineY() + 0.02));
                    }
                }
                continue;
            }
            int bottom = shape.minY();
            int top = shape.maxY() + 1;
            boolean seeBottom = Math.abs(bottom - py) <= VIEW_HEIGHT;
            boolean seeTop = Math.abs(top - py) <= VIEW_HEIGHT;
            int wallFrom = Math.max(bottom, floorTo(py - VIEW_HEIGHT, bottom));
            int wallTo = Math.min(top, floorTo(py + VIEW_HEIGHT, bottom) + PANEL);
            Set<Long> corners = new LinkedHashSet<>();
            for (Outline.Segment segment : segments) {
                if (!near(segment, px, pz)) {
                    continue;
                }
                walls(shape.colour(), segment, wallFrom, wallTo, px, pz, specs);
                if (seeBottom) {
                    specs.add(edge(shape.colour(), segment, bottom));
                }
                if (seeTop) {
                    specs.add(edge(shape.colour(), segment, top));
                }
                corners.add(((long) segment.x1() << 32) | (segment.z1() & 0xFFFFFFFFL));
                corners.add(((long) segment.x2() << 32) | (segment.z2() & 0xFFFFFFFFL));
            }
            for (long corner : corners) {
                int cx = (int) (corner >> 32);
                int cz = (int) corner;
                specs.add(new Spec(shape.colour().edge, cx - EDGE / 2, bottom, cz - EDGE / 2, EDGE, top - bottom, EDGE,
                        shape.colour().rgb));
            }
            for (Region.Box box : shape.region().boxes()) {
                if (seeBottom) {
                    flat(shape.colour(), box, bottom, px, pz, specs);
                }
                if (seeTop) {
                    flat(shape.colour(), box, top, px, pz, specs);
                }
            }
        }
        ClaimDraft draft = session.draft;
        if (draft != null && draft.world.equals(EstateRuntime.world(player.level()))) {
            // Like Litematica: corner 1 is a red cube, corner 2 a blue one.
            for (int which = 1; which <= 2; which++) {
                int[] c = draft.corner(which);
                Colour colour = which == 1 ? Colour.CORNER_1 : Colour.CORNER_2;
                specs.add(new Spec(colour.edge, c[0] - 0.01, c[1] - 0.01, c[2] - 0.01, 1.02f, 1.02f, 1.02f, colour.rgb));
            }
        }
        if (specs.size() > MAX_DISPLAYS) {
            specs.sort(Comparator.comparingDouble(spec -> spec.distanceSq(px, py, pz)));
            specs = new ArrayList<>(specs.subList(0, MAX_DISPLAYS));
        }
        return specs;
    }

    /** The highest multiple of {@link #PANEL} above {@code origin} that's at or below {@code value}. */
    private static int floorTo(double value, int origin) {
        return origin + (int) Math.floor((value - origin) / PANEL) * PANEL;
    }

    /** Glass panels standing on a border line, from {@code from} to {@code to}. */
    private static void walls(Colour colour, Outline.Segment segment, int from, int to, double px, double pz, List<Spec> specs) {
        boolean alongX = segment.z1() == segment.z2();
        int start = alongX ? Math.min(segment.x1(), segment.x2()) : Math.min(segment.z1(), segment.z2());
        int end = start + segment.length();
        for (int a = start; a < end; a += PANEL) {
            int b = Math.min(end, a + PANEL);
            double cx = alongX ? (a + b) / 2.0 : segment.x1();
            double cz = alongX ? segment.z1() : (a + b) / 2.0;
            if (Math.hypot(cx - px, cz - pz) > VIEW_DISTANCE + PANEL) {
                continue;
            }
            for (int y = from; y < to; y += PANEL) {
                int height = Math.min(to, y + PANEL) - y;
                if (alongX) {
                    specs.add(new Spec(colour.glass, a, y, segment.z1() - GLASS / 2, b - a, height, GLASS, -1));
                } else {
                    specs.add(new Spec(colour.glass, segment.x1() - GLASS / 2, y, a, GLASS, height, b - a, -1));
                }
            }
        }
    }

    /** Glass panels covering a box at one height (the floor or the ceiling). */
    private static void flat(Colour colour, Region.Box box, int y, double px, double pz, List<Spec> specs) {
        for (int x = box.minX(); x <= box.maxX(); x += PANEL) {
            int x2 = Math.min(box.maxX() + 1, x + PANEL);
            for (int z = box.minZ(); z <= box.maxZ(); z += PANEL) {
                int z2 = Math.min(box.maxZ() + 1, z + PANEL);
                double dx = Math.max(0, Math.max(x - px, px - x2));
                double dz = Math.max(0, Math.max(z - pz, pz - z2));
                if (Math.hypot(dx, dz) > VIEW_DISTANCE) {
                    continue;
                }
                specs.add(new Spec(colour.glass, x, y - GLASS / 2, z, x2 - x, GLASS, z2 - z, -1));
            }
        }
    }

    /** A thin glowing bar along a border line. */
    private static Spec edge(Colour colour, Outline.Segment segment, double y) {
        boolean alongX = segment.z1() == segment.z2();
        float length = segment.length();
        double x = Math.min(segment.x1(), segment.x2()) - (alongX ? 0 : EDGE / 2);
        double z = Math.min(segment.z1(), segment.z2()) - (alongX ? EDGE / 2 : 0);
        return alongX
                ? new Spec(colour.edge, x, y - EDGE / 2, z, length, EDGE, EDGE, colour.rgb)
                : new Spec(colour.edge, x, y - EDGE / 2, z, EDGE, EDGE, length, colour.rgb);
    }

    private static boolean near(Outline.Segment segment, double px, double pz) {
        return distanceToSegment(segment, px, pz) <= VIEW_DISTANCE;
    }

    /** Sends only the difference between what the player's client shows and what it should show, in one bundle. */
    private void sendDisplays(ServerPlayer player, Session session) {
        if (session.displaysFor != player || session.displaysLevel != player.level()) {
            // A respawn or a dimension change cleared the client's entities already.
            session.displays.clear();
            session.displaysFor = player;
            session.displaysLevel = player.level();
        }
        List<Spec> wanted = specs(player, session);
        Set<Spec> keep = new HashSet<>(wanted);
        List<Integer> gone = new ArrayList<>();
        for (Iterator<Map.Entry<Spec, Integer>> it = session.displays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Spec, Integer> entry = it.next();
            if (!keep.contains(entry.getKey())) {
                gone.add(entry.getValue());
                it.remove();
            }
        }
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        if (!gone.isEmpty()) {
            packets.add(new ClientboundRemoveEntitiesPacket(gone.stream().mapToInt(Integer::intValue).toArray()));
        }
        ServerLevel level = player.level();
        RegistryOps<Tag> ops = rt.server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for (Spec spec : wanted) {
            if (!session.displays.containsKey(spec)) {
                int id = spawn(level, ops, spec, packets);
                if (id != 0) {
                    session.displays.put(spec, id);
                }
            }
        }
        if (!packets.isEmpty()) {
            player.connection.send(new ClientboundBundlePacket(packets));
        }
    }

    /** One block display scaled to a box, only ever sent to this player. Returns its entity id, or 0. */
    private static int spawn(ServerLevel level, RegistryOps<Tag> ops, Spec spec,
                             List<Packet<? super ClientGamePacketListener>> packets) {
        CompoundTag data = new CompoundTag();
        data.store(Display.BlockDisplay.TAG_BLOCK_STATE, BlockState.CODEC, ops, spec.block());
        data.store(Display.TAG_TRANSFORMATION, Transformation.CODEC, ops, new Transformation(new Vector3f(),
                new Quaternionf(), new Vector3f(spec.sx(), spec.sy(), spec.sz()), new Quaternionf()));
        data.store(Display.TAG_BRIGHTNESS, Brightness.CODEC, ops, Brightness.FULL_BRIGHT);
        if (spec.glow() >= 0) {
            data.putInt(Display.TAG_GLOW_COLOR_OVERRIDE, spec.glow());
        }
        data.putFloat(Display.TAG_VIEW_RANGE, VIEW_RANGE);
        Entity entity = EntityType.loadEntityRecursive(EntityTypes.BLOCK_DISPLAY, data, level, EntitySpawnReason.COMMAND, e -> {
            e.snapTo(spec.x(), spec.y(), spec.z(), 0, 0);
            return e;
        });
        if (entity == null) {
            return 0;
        }
        if (spec.glow() >= 0) {
            entity.setGlowingTag(true);
        }
        packets.add(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), spec.x(), spec.y(), spec.z(), 0, 0,
                EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            packets.add(new ClientboundSetEntityDataPacket(entity.getId(), values));
        }
        return entity.getId();
    }

    private static void clearDisplays(ServerPlayer player, Session session) {
        if (!session.displays.isEmpty()) {
            if (session.displaysFor == player && session.displaysLevel == player.level()) {
                player.connection.send(new ClientboundRemoveEntitiesPacket(
                        session.displays.values().stream().mapToInt(Integer::intValue).toArray()));
            }
            session.displays.clear();
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
            DustParticleOptions dust = shape.colour().dust;
            double eyeLine = shape.kind() == Kind.LINE ? shape.lineY() + 0.5
                    : Math.max(shape.minY(), Math.min(shape.maxY(), py + 1)) + 0.5;
            for (Outline.Segment segment : Outline.of(shape.region(), shape.grow())) {
                if (budget[0] <= 0) {
                    return;
                }
                if (!near(segment, player.getX(), player.getZ())) {
                    continue;
                }
                line(player, segment, eyeLine, dust, budget);
                if (shape.kind() == Kind.LINE) {
                    continue;
                }
                if (Math.abs(shape.maxY() + 1 - py) <= 16) {
                    line(player, segment, shape.maxY() + 1, dust, budget);
                }
                if (Math.abs(shape.minY() - py) <= 16) {
                    line(player, segment, shape.minY(), dust, budget);
                }
                int bottom = Math.max(shape.minY(), py - 3);
                int top = Math.min(shape.maxY() + 1, py + 5);
                for (int y = bottom; y <= top && budget[0] > 0; y++) {
                    particle(player, segment.x1(), y + 0.5, segment.z1(), dust, budget);
                    particle(player, segment.x2(), y + 0.5, segment.z2(), dust, budget);
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
