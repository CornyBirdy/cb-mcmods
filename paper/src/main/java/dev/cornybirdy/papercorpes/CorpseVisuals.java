package dev.cornybirdy.papercorpes;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.UUID;

/**
 * Spawns and removes the entities that make up a corpse. Everything here is a vanilla entity, so players need no mods:
 * <ul>
 *     <li>a {@link Mannequin} with the player's skin, lying down</li>
 *     <li>an {@link Interaction} box over it, because a lying body's own hitbox is tiny</li>
 *     <li>later, item displays of a skull and bones when it turns into a skeleton</li>
 * </ul>
 * Every entity is tagged with the corpse id so it can be matched back after restarts.
 */
final class CorpseVisuals {

    /** Length of a lying body from feet to head, in blocks. */
    private static final double BODY_LENGTH = 1.8;

    /** How high the name floats above the ground. */
    private static final double LABEL_HEIGHT = 0.9;

    private final PaperCorpes plugin;

    CorpseVisuals(PaperCorpes plugin) {
        this.plugin = plugin;
    }

    /** The corpse id an entity belongs to, or null if it isn't part of a corpse. */
    UUID corpseId(Entity entity) {
        String id = entity.getPersistentDataContainer().get(plugin.corpseKey(), PersistentDataType.STRING);
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    void spawnBody(Corpse corpse, PlayerProfile profile) {
        Location loc = corpse.location();
        if (loc == null) {
            return;
        }
        Settings settings = plugin.settings();
        Pose pose = lyingPose();
        Mannequin body = loc.getWorld().spawn(loc, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile(profile));
            // The body is only drawn within ~12 blocks (a lying body has a tiny hitbox), so the name goes on a
            // separate label that can be seen from much further away.
            m.setCustomNameVisible(false);
            m.setDescription(null);
            m.setInvulnerable(true);
            m.setImmovable(true);
            m.setGravity(false);
            m.setCollidable(false);
            m.setSilent(true);
            m.setPersistent(true);
            m.setRotation(loc.getYaw(), 0);
            m.setBodyYaw(loc.getYaw());
            m.setPose(pose, true);
            tag(m, corpse.id());
        });
        corpse.entityIds().add(body.getUniqueId());

        Location center = alongBody(loc, BODY_LENGTH / 2);
        Interaction hitbox = loc.getWorld().spawn(center, Interaction.class, i -> {
            i.setInteractionWidth(settings.hitboxWidth());
            i.setInteractionHeight(settings.hitboxHeight());
            i.setResponsive(true);
            i.setPersistent(true);
            tag(i, corpse.id());
        });
        corpse.entityIds().add(hitbox.getUniqueId());

        if (settings.showName()) {
            Component name = plugin.message("corpse-name", Placeholder.unparsed("player", corpse.ownerName()))
                    .appendNewline()
                    .append(plugin.message("corpse-description",
                            Placeholder.unparsed("date", settings.formatDate(corpse.createdAt()))));
            spawnLabel(corpse, center.clone().add(0, LABEL_HEIGHT, 0), name);
        }
    }

    /**
     * Replaces the body with a skull and bones. Does nothing if the chunk's entities aren't loaded yet.
     *
     * @return true if the corpse is now a skeleton
     */
    boolean skeletonize(Corpse corpse) {
        Location loc = corpse.location();
        if (loc == null || !loc.isChunkLoaded() || !loc.getChunk().isEntitiesLoaded()) {
            return false;
        }

        Iterator<UUID> it = corpse.entityIds().iterator();
        while (it.hasNext()) {
            Entity entity = Bukkit.getEntity(it.next());
            if (entity == null || entity instanceof Mannequin || entity instanceof TextDisplay) {
                if (entity != null) {
                    entity.remove();
                }
                it.remove();
            }
        }

        float yawRad = (float) Math.toRadians(loc.getYaw());
        Quaternionf alongBody = new Quaternionf().rotateY(-yawRad);

        // Skull at the head end, bones for the ribs and legs, laid flat on the ground.
        spawnItem(corpse, alongBody(loc, BODY_LENGTH - 0.25), Material.SKELETON_SKULL,
                new Transformation(new Vector3f(0, 0.35f, 0), alongBody, new Vector3f(0.7f), new Quaternionf()));
        spawnItem(corpse, alongBody(loc, BODY_LENGTH * 0.55), Material.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(45)), 0.9f));
        spawnItem(corpse, alongBody(loc, BODY_LENGTH * 0.25), Material.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(-20)), 0.75f));
        spawnItem(corpse, alongBody(loc, 0.05).add(perpendicular(loc, 0.25)), Material.BONE,
                flat(new Quaternionf(alongBody).rotateY((float) Math.toRadians(80)), 0.6f));

        if (plugin.settings().showName()) {
            spawnLabel(corpse, alongBody(loc, BODY_LENGTH / 2).add(0, LABEL_HEIGHT, 0),
                    plugin.message("remains-name", Placeholder.unparsed("player", corpse.ownerName())));
        }

        corpse.setSkeleton(true);
        return true;
    }

    /** Removes the corpse's entities that are currently loaded. Unloaded ones are cleaned up when they load. */
    void removeAll(Corpse corpse) {
        for (UUID id : corpse.entityIds()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        corpse.entityIds().clear();
    }

    /**
     * Picks a spot for the corpse near where the player died: out of the void and solid blocks, up out of lava
     * ({@code float-on-lava}) and down onto the ground if they died in mid-air ({@code fall-to-ground}).
     */
    static Location safeLocation(Location death, Settings settings) {
        World world = death.getWorld();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 2;
        Location loc = death.clone();
        loc.setPitch(0);

        if (loc.getY() < minY + 1) {
            int top = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
            if (top > minY && world.getBlockAt(loc.getBlockX(), top, loc.getBlockZ()).getType().isSolid()) {
                loc.setY(top + 1);
            } else {
                Location spawn = world.getSpawnLocation();
                spawn.setYaw(loc.getYaw());
                spawn.setPitch(0);
                return spawn;
            }
        }

        Block block = loc.getBlock();
        for (int i = 0; i < 64 && block.getY() < maxY && ((settings.floatOnLava() && block.getType() == Material.LAVA) || block.getType().isOccluding()); i++) {
            block = block.getRelative(BlockFace.UP);
            loc.setY(block.getY());
        }

        for (int i = 0; settings.fallToGround() && i < 64 && block.getY() > minY && block.isPassable() && !block.isLiquid(); i++) {
            Block below = block.getRelative(BlockFace.DOWN);
            if (!below.isPassable() || below.isLiquid()) {
                break;
            }
            block = below;
            loc.setY(block.getY());
        }
        return loc;
    }

    /** A floating name over the corpse, visible from {@code label-view-distance} blocks away. */
    private void spawnLabel(Corpse corpse, Location at, Component text) {
        Settings settings = plugin.settings();
        TextDisplay label = at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setViewRange(settings.labelViewDistance() / 64f);
            t.setSeeThrough(settings.labelSeeThrough());
            t.setPersistent(true);
            tag(t, corpse.id());
        });
        corpse.entityIds().add(label.getUniqueId());
    }

    private void spawnItem(Corpse corpse, Location at, Material material, Transformation transformation) {
        at.setYaw(0);
        at.setPitch(0);
        ItemDisplay display = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(material));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(transformation);
            d.setPersistent(true);
            tag(d, corpse.id());
        });
        corpse.entityIds().add(display.getUniqueId());
    }

    private static Transformation flat(Quaternionf rotation, float scale) {
        return new Transformation(new Vector3f(0, 0.03f, 0), rotation.rotateX((float) Math.toRadians(-90)),
                new Vector3f(scale), new Quaternionf());
    }

    private void tag(Entity entity, UUID corpseId) {
        entity.getPersistentDataContainer().set(plugin.corpseKey(), PersistentDataType.STRING, corpseId.toString());
    }

    private static Pose lyingPose() {
        for (Pose pose : Mannequin.validPoses()) {
            if (pose == Pose.SLEEPING) {
                return Pose.SLEEPING;
            }
        }
        return Pose.SWIMMING;
    }

    /**
     * A point {@code distance} blocks from the feet towards the head. With no bed, the client lays a sleeping body
     * with its head towards (-cos yaw, sin yaw).
     */
    private static Location alongBody(Location feet, double distance) {
        double yaw = Math.toRadians(feet.getYaw());
        return feet.clone().add(new Vector(-Math.cos(yaw) * distance, 0, Math.sin(yaw) * distance));
    }

    private static Vector perpendicular(Location feet, double distance) {
        double yaw = Math.toRadians(feet.getYaw());
        return new Vector(Math.sin(yaw) * distance, 0, Math.cos(yaw) * distance);
    }
}
