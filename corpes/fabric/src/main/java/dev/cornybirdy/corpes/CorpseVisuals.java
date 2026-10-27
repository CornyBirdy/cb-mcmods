package dev.cornybirdy.corpes;

import com.mojang.authlib.GameProfile;
import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Spawns and removes the entities that make up a corpse. Everything here is a vanilla entity, so players need no mods:
 * <ul>
 *     <li>a {@link Mannequin} with the player's skin, lying down</li>
 *     <li>an interaction box over it, because a lying body's own hitbox is tiny</li>
 *     <li>later, item displays of a skull and bones when it turns into a skeleton</li>
 * </ul>
 * Every entity gets the scoreboard tag {@value #TAG} so it can be matched back after restarts. The entities are built
 * from NBT because their setters aren't public.
 */
final class CorpseVisuals {

    static final String TAG = "corpes";
    private static final String ID_TAG_PREFIX = "corpes:";

    /** Length of a lying body from feet to head, in blocks. */
    private static final double BODY_LENGTH = 1.8;

    /** How high the name floats above the ground. */
    private static final double LABEL_HEIGHT = 0.9;

    private final MinecraftServer server;
    private final RegistryOps<Tag> ops;

    CorpseVisuals(MinecraftServer server) {
        this.server = server;
        this.ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
    }

    /** The corpse id an entity belongs to, or null if it isn't part of a corpse. */
    static UUID corpseId(Entity entity) {
        if (!entity.entityTags().contains(TAG)) {
            return null;
        }
        for (String tag : entity.entityTags()) {
            if (tag.startsWith(ID_TAG_PREFIX)) {
                try {
                    return UUID.fromString(tag.substring(ID_TAG_PREFIX.length()));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    ServerLevel level(Corpse corpse) {
        Identifier id = Identifier.tryParse(corpse.world);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    static String worldId(Level level) {
        return level.dimension().identifier().toString();
    }

    void spawnBody(Corpse corpse, GameProfile profile, Settings settings) {
        ServerLevel level = level(corpse);
        if (level == null) {
            return;
        }
        CompoundTag body = new CompoundTag();
        body.store("profile", ResolvableProfile.CODEC, ops, ResolvableProfile.createResolved(profile));
        body.store("pose", Mannequin.POSE_CODEC, ops, Pose.SLEEPING);
        body.putBoolean("immovable", true);
        // The body is only drawn within ~12 blocks (a lying body has a tiny hitbox), so the name goes on a separate
        // label that can be seen from much further away.
        body.putBoolean("hide_description", true);
        body.putBoolean("Invulnerable", true);
        body.putBoolean("NoGravity", true);
        body.putBoolean("Silent", true);
        Entity mannequin = spawn(level, EntityTypes.MANNEQUIN, body, new Vec3(corpse.x, corpse.y, corpse.z), corpse.yaw, corpse);
        if (mannequin instanceof LivingEntity living) {
            living.setYBodyRot(corpse.yaw);
            living.setYHeadRot(corpse.yaw);
        }

        CompoundTag hitbox = new CompoundTag();
        hitbox.putFloat("width", settings.hitboxWidth);
        hitbox.putFloat("height", settings.hitboxHeight);
        hitbox.putBoolean("response", true);
        spawn(level, EntityTypes.INTERACTION, hitbox, alongBody(corpse, BODY_LENGTH / 2), 0, corpse);

        if (settings.showName) {
            Component name = settings.message("corpse-name", Map.of("player", corpse.ownerName))
                    .append("\n")
                    .append(settings.message("corpse-description", Map.of("date", settings.formatDate(corpse.createdAt))));
            spawnLabel(level, corpse, name, settings);
        }
    }

    /**
     * Replaces the body with a skull and bones. Does nothing if the corpse's entities aren't loaded.
     *
     * @return true if the corpse is now a skeleton
     */
    boolean skeletonize(Corpse corpse, Settings settings) {
        ServerLevel level = level(corpse);
        if (level == null || !level.isPositionEntityTicking(BlockPos.containing(corpse.x, corpse.y, corpse.z))) {
            return false;
        }

        Iterator<UUID> it = corpse.entityIds.iterator();
        while (it.hasNext()) {
            Entity entity = level.getEntity(it.next());
            if (entity == null || entity instanceof Mannequin || entity instanceof Display.TextDisplay) {
                if (entity != null) {
                    entity.discard();
                }
                it.remove();
            }
        }

        float yawRad = (float) Math.toRadians(corpse.yaw);
        Quaternionf alongBody = new Quaternionf().rotateY(-yawRad);

        // Skull at the head end, bones for the ribs and legs, laid flat on the ground.
        spawnItem(level, corpse, alongBody(corpse, BODY_LENGTH - 0.25), Items.SKELETON_SKULL,
                new Transformation(new Vector3f(0, 0.35f, 0), alongBody, new Vector3f(0.7f), new Quaternionf()));
        spawnItem(level, corpse, alongBody(corpse, BODY_LENGTH * 0.55), Items.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(45)), 0.9f));
        spawnItem(level, corpse, alongBody(corpse, BODY_LENGTH * 0.25), Items.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(-20)), 0.75f));
        spawnItem(level, corpse, alongBody(corpse, 0.05).add(perpendicular(corpse, 0.25)), Items.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(80)), 0.6f));

        if (settings.showName) {
            spawnLabel(level, corpse, settings.message("remains-name", Map.of("player", corpse.ownerName)), settings);
        }

        corpse.skeleton = true;
        return true;
    }

    /** Turns the outline on the corpse's body or bones on or off. Returns false if none of them are loaded. */
    boolean setGlowing(Corpse corpse, boolean glowing) {
        ServerLevel level = level(corpse);
        boolean any = false;
        if (level != null) {
            for (UUID id : corpse.entityIds) {
                Entity entity = level.getEntity(id);
                if (entity instanceof Mannequin || entity instanceof Display.ItemDisplay) {
                    entity.setGlowingTag(glowing);
                    any = true;
                }
            }
        }
        return any;
    }

    /** Removes the corpse's entities that are currently loaded. Unloaded ones are cleaned up when they load. */
    void removeAll(Corpse corpse) {
        ServerLevel level = level(corpse);
        if (level != null) {
            for (UUID id : corpse.entityIds) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
        }
        corpse.entityIds.clear();
    }

    /**
     * Picks a spot for the corpse near where the player died: out of the void, lava and solid blocks, and
     * down onto the ground if they died in mid-air.
     */
    static Vec3 safeLocation(ServerLevel level, Vec3 death) {
        int minY = level.getMinY();
        int maxY = level.getMaxY() - 1;
        double x = death.x;
        double y = death.y;
        double z = death.z;

        if (y < minY + 1) {
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            if (top > minY) {
                y = top;
            } else {
                LevelData.RespawnData spawn = level.getRespawnData();
                BlockPos pos = spawn.pos();
                return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }
        }

        BlockPos pos = BlockPos.containing(x, y, z);
        for (int i = 0; i < 64 && pos.getY() < maxY && (isLava(level.getBlockState(pos)) || isSolidBlock(level, pos)); i++) {
            pos = pos.above();
            y = pos.getY();
        }

        for (int i = 0; i < 64 && pos.getY() > minY && isPassable(level.getBlockState(pos)); i++) {
            BlockPos below = pos.below();
            if (!isPassable(level.getBlockState(below))) {
                break;
            }
            pos = below;
            y = pos.getY();
        }
        return new Vec3(x, y, z);
    }

    private static boolean isLava(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA);
    }

    private static boolean isSolidBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.canOcclude() && state.isCollisionShapeFullBlock(level, pos);
    }

    /** Air and plants can be fallen through; liquids and anything solid can't. */
    private static boolean isPassable(BlockState state) {
        return !state.isSolid() && !state.liquid();
    }

    /** A floating name over the corpse, visible from {@code label-view-distance} blocks away. */
    private void spawnLabel(ServerLevel level, Corpse corpse, Component text, Settings settings) {
        CompoundTag label = new CompoundTag();
        label.store("text", ComponentSerialization.CODEC, ops, text);
        label.store("billboard", Display.BillboardConstraints.CODEC, ops, Display.BillboardConstraints.CENTER);
        label.putFloat("view_range", settings.labelViewDistance / 64f);
        label.putBoolean("see_through", settings.labelSeeThrough);
        spawn(level, EntityTypes.TEXT_DISPLAY, label, alongBody(corpse, BODY_LENGTH / 2).add(0, LABEL_HEIGHT, 0), 0, corpse);
    }

    private Entity spawn(ServerLevel level, EntityType<?> type, CompoundTag data, Vec3 at, float yaw, Corpse corpse) {
        Entity entity = EntityType.loadEntityRecursive(type, data, level, EntitySpawnReason.COMMAND, e -> {
            e.snapTo(at.x, at.y, at.z, yaw, 0);
            return e;
        });
        if (entity == null) {
            return null;
        }
        entity.addTag(TAG);
        entity.addTag(ID_TAG_PREFIX + corpse.id);
        if (!level.addFreshEntity(entity)) {
            return null;
        }
        corpse.entityIds.add(entity.getUUID());
        return entity;
    }

    private void spawnItem(ServerLevel level, Corpse corpse, Vec3 at, Item item,
                           Transformation transformation) {
        CompoundTag display = new CompoundTag();
        display.store("item", ItemStack.CODEC, ops, new ItemStack(item));
        display.store("item_display", ItemDisplayContext.CODEC, ops, ItemDisplayContext.NONE);
        display.store("transformation", Transformation.CODEC, ops, transformation);
        spawn(level, EntityTypes.ITEM_DISPLAY, display, at, 0, corpse);
    }

    private static Transformation flat(Quaternionf rotation, float scale) {
        return new Transformation(new Vector3f(0, 0.03f, 0), rotation.rotateX((float) Math.toRadians(-90)),
                new Vector3f(scale), new Quaternionf());
    }

    /**
     * A point {@code distance} blocks from the feet towards the head. With no bed, the client lays a sleeping body
     * with its head towards (-cos yaw, sin yaw).
     */
    private static Vec3 alongBody(Corpse corpse, double distance) {
        double yaw = Math.toRadians(corpse.yaw);
        return new Vec3(corpse.x - Math.cos(yaw) * distance, corpse.y, corpse.z + Math.sin(yaw) * distance);
    }

    private static Vec3 perpendicular(Corpse corpse, double distance) {
        double yaw = Math.toRadians(corpse.yaw);
        return new Vec3(Math.sin(yaw) * distance, 0, Math.cos(yaw) * distance);
    }
}
