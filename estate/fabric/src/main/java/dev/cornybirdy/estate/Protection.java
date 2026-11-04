package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.Denial;
import dev.cornybirdy.estate.core.Flag;
import dev.cornybirdy.estate.core.Permission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every protection decision that needs Minecraft types. Fabric API events cover players breaking blocks and using
 * blocks, items and entities; mixins call the {@code public static} methods for everything else (placing, fluids,
 * pistons, explosions, fire, dispensers, projectiles, mobs). Nothing inside a claim can be changed from outside it.
 */
public final class Protection {

    /** How close a mob has to be to a protected claim before its block-changing goals stop. */

    private Protection() {
    }

    // ------------------------------------------------------------------ players: blocks

    static boolean mayBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(player instanceof ServerPlayer sp) || rt.bypassing(sp)) {
            return true;
        }
        Denial denial = rt.manager.checkBuild(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ(), sp.getUUID());
        if (denial != null) {
            rt.deny(sp, denial);
            return false;
        }
        return true;
    }

    static void broken(Level level, BlockPos pos) {
        EstateRuntime rt = Estate.runtime();
        if (rt != null) {
            rt.placements.removed(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ());
            if (level instanceof ServerLevel serverLevel) {
                rt.autoClaimer.broken(serverLevel, pos);
            }
        }
    }

    /** Called by {@code BlockItemMixin} before a player places a block. False stops it. */
    public static boolean mayPlace(BlockPlaceContext context, Block block) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(context.getPlayer() instanceof ServerPlayer sp) || rt.bypassing(sp)) {
            return true;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        List<BlockPos> spots = new ArrayList<>(2);
        spots.add(pos);
        if (block instanceof DoorBlock || block instanceof DoublePlantBlock) {
            spots.add(pos.above());
        } else if (block instanceof BedBlock) {
            spots.add(pos.relative(context.getHorizontalDirection()));
        }
        for (BlockPos spot : spots) {
            Denial denial = rt.manager.checkBuild(EstateRuntime.world(level), spot.getX(), spot.getY(), spot.getZ(), sp.getUUID());
            if (denial != null) {
                rt.deny(sp, denial);
                resync(sp, level, spots);
                return false;
            }
        }
        return true;
    }

    /** Called by {@code BlockItemMixin} after a player placed a block. */
    public static void placed(BlockPlaceContext context) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(context.getPlayer() instanceof ServerPlayer sp) || !(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        String block = BlockKinds.id(state.getBlock());
        rt.placements.placed(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ(), sp.getUUID(), block);
        rt.autoClaimer.placed(sp, level, pos, block);
    }

    static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(player instanceof ServerPlayer sp) || rt.bypassing(sp)) {
            return InteractionResult.PASS;
        }
        ClaimManager manager = rt.manager;
        String world = EstateRuntime.world(level);
        UUID id = sp.getUUID();
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        ItemStack held = sp.getItemInHand(hand);
        // Like vanilla: sneaking with something in either hand uses the item instead of the block.
        boolean itemFirst = sp.isSecondaryUseActive() && (!sp.getMainHandItem().isEmpty() || !sp.getOffhandItem().isEmpty());

        if (!itemFirst) {
            BlockKinds.Kind kind = rt.kinds.classify(level, pos, state);
            Denial denial = switch (kind) {
                case ACCESS -> manager.checkAction(world, pos.getX(), pos.getY(), pos.getZ(), id, Permission.INTERACT, null);
                case CONTAINER -> manager.checkAction(world, pos.getX(), pos.getY(), pos.getZ(), id, Permission.CONTAINERS, Flag.CONTAINER_ACCESS);
                case INTERACTIVE -> manager.checkAction(world, pos.getX(), pos.getY(), pos.getZ(), id, Permission.BUILD, Flag.BLOCK_BREAK_PLACE);
                case PLAIN -> null;
            };
            if (denial != null) {
                rt.deny(sp, denial);
                resync(sp, level, List.of(pos));
                return InteractionResult.FAIL;
            }
            // Doors, chests and the like always react to a click, so the held item never gets used.
            if (kind == BlockKinds.Kind.ACCESS || kind == BlockKinds.Kind.CONTAINER
                    || (kind == BlockKinds.Kind.INTERACTIVE && held.isEmpty())) {
                return InteractionResult.PASS;
            }
        }
        // Blocks are checked exactly where they land, in BlockItemMixin.
        if (held.isEmpty() || held.getItem() instanceof BlockItem || !BlockKinds.changesBlocks(held.getItem())) {
            return InteractionResult.PASS;
        }
        // Any other item used on a block (tools, buckets, bone meal, flint and steel, spawn eggs...) changes that
        // block or the space in front of it.
        for (BlockPos spot : List.of(pos, pos.relative(hit.getDirection()))) {
            Denial denial = manager.checkBuild(world, spot.getX(), spot.getY(), spot.getZ(), id);
            if (denial != null) {
                rt.deny(sp, denial);
                resync(sp, level, List.of(pos, pos.relative(hit.getDirection())));
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.PASS;
    }

    /** Buckets aim for themselves instead of using the clicked block, so they're checked here. */
    static InteractionResult useItem(Player player, Level level, InteractionHand hand) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(player instanceof ServerPlayer sp) || rt.bypassing(sp)
                || !(sp.getItemInHand(hand).getItem() instanceof BucketItem)) {
            return InteractionResult.PASS;
        }
        String world = EstateRuntime.world(level);
        Set<BlockPos> spots = new HashSet<>();
        for (boolean fluids : new boolean[]{true, false}) {
            HitResult hit = sp.pick(sp.blockInteractionRange(), 0, fluids);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                spots.add(blockHit.getBlockPos());
                spots.add(blockHit.getBlockPos().relative(blockHit.getDirection()));
            }
        }
        for (BlockPos spot : spots) {
            Denial denial = rt.manager.checkBuild(world, spot.getX(), spot.getY(), spot.getZ(), sp.getUUID());
            if (denial != null) {
                rt.deny(sp, denial);
                resync(sp, level, List.copyOf(spots));
                return InteractionResult.FAIL;
            }
        }
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ players: entities

    static InteractionResult useEntity(Player player, Level level, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hit) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(player instanceof ServerPlayer sp) || rt.bypassing(sp)
                || entity instanceof Player || entity instanceof Enemy) {
            return InteractionResult.PASS;
        }
        if (entity instanceof OwnableEntity pet && pet.getOwner() == sp) {
            return InteractionResult.PASS;
        }
        Permission permission;
        Flag flag;
        if (entity instanceof ItemFrame || entity instanceof ArmorStand || entity instanceof ContainerEntity) {
            permission = Permission.CONTAINERS;
            flag = Flag.CONTAINER_ACCESS;
        } else if (entity instanceof AbstractVillager || entity instanceof VehicleEntity) {
            permission = Permission.INTERACT;
            flag = null;
        } else {
            permission = Permission.BUILD;
            flag = Flag.ENTITY_DAMAGE;
        }
        BlockPos pos = entity.blockPosition();
        Denial denial = rt.manager.checkAction(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ(), sp.getUUID(), permission, flag);
        if (denial != null) {
            rt.deny(sp, denial);
            sp.containerMenu.sendAllDataToRemote();
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    static InteractionResult attackEntity(Player player, Level level, InteractionHand hand, Entity entity, net.minecraft.world.phys.EntityHitResult hit) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }
        if (entity instanceof Player) {
            // PvP is left to a PvP mod such as Gatekeeper.
            return InteractionResult.PASS;
        }
        if (!rt.bypassing(sp) && entityProtected(rt, entity, serverLevel, sp)) {
            Claim claim = rt.claimAt(level, entity.blockPosition());
            if (claim != null) {
                rt.deny(sp, new Denial(Denial.Kind.CLAIM, claim));
            }
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !(entity.level() instanceof ServerLevel level)) {
            return true;
        }
        return !protectEntity(entity, level, source);
    }

    /**
     * Called by the entity hurt mixins (item frames, paintings, armour stands, boats, minecarts) and for living
     * entities. True means the damage is cancelled.
     */
    public static boolean protectEntity(Entity entity, ServerLevel level, DamageSource source) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || entity instanceof Player || entity instanceof Enemy) {
            return false;
        }
        Claim claim = rt.claimAt(level, entity.blockPosition());
        if (claim == null) {
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer attacker) {
            return !rt.bypassing(attacker) && entityProtected(rt, entity, level, attacker);
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return rt.manager.flag(claim, Flag.EXPLOSIONS);
        }
        // Mobs, fire, falling and drowning hurt animals as in vanilla.
        return false;
    }

    private static boolean entityProtected(EstateRuntime rt, Entity entity, ServerLevel level, ServerPlayer attacker) {
        if (entity instanceof Enemy || entity instanceof Player) {
            return false;
        }
        if (entity instanceof OwnableEntity pet && pet.getOwner() == attacker) {
            return false;
        }
        // A wolf, bee or golem that's attacking you can always be fought back.
        if (entity instanceof Mob mob && mob.getTarget() == attacker) {
            return false;
        }
        BlockPos pos = entity.blockPosition();
        return rt.manager.checkAction(EstateRuntime.world(level), pos.getX(), pos.getY(), pos.getZ(), attacker.getUUID(),
                Permission.BUILD, Flag.ENTITY_DAMAGE) != null;
    }

    // ------------------------------------------------------------------ the world (called by mixins)

    /** {@code ServerExplosionMixin}: drops blocks the explosion may not touch. */
    public static List<BlockPos> filterExplosion(ServerLevel level, Vec3 center, List<BlockPos> positions) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || positions.isEmpty()) {
            return positions;
        }
        Claim origin = rt.claimAt(level, BlockPos.containing(center));
        List<BlockPos> kept = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            Claim claim = rt.claimAt(level, pos);
            // Claims with explosions allowed still can't be blown up from outside.
            if (claim == null || (!rt.manager.flag(claim, Flag.EXPLOSIONS) && sameOwner(origin, claim))) {
                kept.add(pos);
            }
        }
        return kept.size() == positions.size() ? positions : kept;
    }

    /** {@code FlowingFluidMixin}: false stops water or lava flowing from one spot into the next. */
    public static boolean mayFlow(Level level, BlockPos from, BlockPos to) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return true;
        }
        Claim target = rt.claimAt(level, to);
        if (target == null || !rt.manager.flag(target, Flag.FLUID_FLOW_IN)) {
            return true;
        }
        return sameOwner(rt.claimAt(level, from), target);
    }

    /** {@code PistonBaseBlockMixin}: false stops a piston that would move blocks into, out of or inside another claim. */
    public static boolean mayPistonMove(Level level, BlockPos pistonPos, Direction direction, boolean extending) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return true;
        }
        PistonStructureResolver resolver = new PistonStructureResolver(level, pistonPos, direction, extending);
        if (!resolver.resolve()) {
            return true;
        }
        Direction push = resolver.getPushDirection();
        Claim piston = rt.claimAt(level, pistonPos);
        List<BlockPos> touched = new ArrayList<>();
        touched.add(pistonPos.relative(direction));
        for (BlockPos pos : resolver.getToPush()) {
            touched.add(pos);
            touched.add(pos.relative(push));
        }
        touched.addAll(resolver.getToDestroy());
        for (BlockPos pos : touched) {
            Claim claim = rt.claimAt(level, pos);
            if (claim != null && rt.manager.flag(claim, Flag.PISTON_CROSS) && !sameOwner(piston, claim)) {
                return false;
            }
        }
        return true;
    }

    /** {@code FireBlockMixin}: true if fire may not burn blocks away or spread here. */
    public static boolean fireProtected(Level level, BlockPos pos) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return false;
        }
        Claim claim = rt.claimAt(level, pos);
        return claim != null && rt.manager.flag(claim, Flag.FIRE_SPREAD);
    }

    /** {@code DispenserBlockMixin}: false stops a dispenser outside a claim from firing into it. */
    public static boolean mayDispense(ServerLevel level, BlockState state, BlockPos pos) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null || !state.hasProperty(DispenserBlock.FACING)) {
            return true;
        }
        BlockPos front = pos.relative(state.getValue(DispenserBlock.FACING));
        Claim target = rt.claimAt(level, front);
        if (target == null || !rt.manager.flag(target, Flag.BLOCK_BREAK_PLACE)) {
            return true;
        }
        return sameOwner(rt.claimAt(level, pos), target);
    }

    /** {@code ProjectileMixin}: false stops arrows and other projectiles from changing blocks in a claim. */
    public static boolean projectileMayInteract(Projectile projectile, ServerLevel level, BlockPos pos) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return true;
        }
        Claim claim = rt.claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        if (projectile.getOwner() instanceof ServerPlayer shooter) {
            return rt.bypassing(shooter) || rt.manager.has(claim, shooter.getUUID(), Permission.BUILD)
                    || !rt.manager.flag(claim, Flag.BLOCK_BREAK_PLACE);
        }
        // Projectiles from mobs and dispensers behave as in vanilla.
        return true;
    }

    /** {@code FarmlandBlockMixin}: false keeps farmland from being trampled. */
    public static boolean mayTrample(Entity entity, Level level, BlockPos pos) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            return true;
        }
        Claim claim = rt.claimAt(level, pos);
        if (claim == null) {
            return true;
        }
        if (entity instanceof ServerPlayer player) {
            return rt.bypassing(player) || rt.manager.has(claim, player.getUUID(), Permission.BUILD);
        }
        Entity rider = entity.getFirstPassenger();
        if (rider instanceof ServerPlayer player) {
            return rt.manager.has(claim, player.getUUID(), Permission.BUILD);
        }
        // Mobs trample as in vanilla.
        return true;
    }

    /** True if the source spot is in a claim of the target claim's owner. */
    private static boolean sameOwner(Claim source, Claim target) {
        return source != null && (source.owner().equals(target.owner())
                || (source.communityId() != 0 && source.communityId() == target.communityId()));
    }

    /** The client already shows the change; send it the real blocks and inventory back. */
    private static void resync(ServerPlayer player, Level level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            player.connection.send(new ClientboundBlockUpdatePacket(level, pos));
        }
        player.containerMenu.sendAllDataToRemote();
    }
}
