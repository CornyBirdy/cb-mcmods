package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Cells;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.PlacementOracle;
import dev.cornybirdy.estate.core.TrustTier;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Runs on a real (headless) server in CI: checks every mixin applies and claims really protect blocks. */
public class EstateGameTest {

    /** Loading each mixin target applies its mixins; a target that moved in a Minecraft update fails here. */
    @GameTest
    public void mixinTargetsLoad(GameTestHelper helper) {
        List<String> targets = List.of(
                "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal",
                "net.minecraft.world.entity.monster.Enderman$EndermanLeaveBlockGoal",
                "net.minecraft.world.entity.animal.rabbit.Rabbit$RaidGardenGoal",
                "net.minecraft.world.entity.ai.goal.BreakDoorGoal",
                "net.minecraft.world.entity.ai.goal.DoorInteractGoal",
                "net.minecraft.world.entity.boss.wither.WitherBoss",
                "net.minecraft.world.entity.monster.Ravager",
                "net.minecraft.world.level.block.FarmlandBlock",
                "net.minecraft.world.level.block.FireBlock",
                "net.minecraft.world.level.block.DispenserBlock",
                "net.minecraft.world.level.ServerExplosion",
                "net.minecraft.world.level.material.FlowingFluid",
                "net.minecraft.world.level.block.piston.PistonBaseBlock",
                "net.minecraft.world.entity.projectile.Projectile",
                "net.minecraft.world.item.BlockItem",
                "net.minecraft.world.entity.decoration.ItemFrame",
                "net.minecraft.world.entity.decoration.BlockAttachedEntity",
                "net.minecraft.world.entity.vehicle.VehicleEntity",
                "net.minecraft.world.entity.decoration.ArmorStand");
        for (String target : targets) {
            try {
                Class.forName(target, true, EstateGameTest.class.getClassLoader());
            } catch (Throwable t) {
                throw helper.assertionException("Mixin target " + target + " failed to load: " + t);
            }
        }
        helper.succeed();
    }

    /** One test so nothing else claims land nearby while it runs. */
    @GameTest(maxTicks = 200)
    public void claimsProtectAndAutoClaimWorks(GameTestHelper helper) {
        EstateRuntime rt = Estate.runtime();
        if (rt == null) {
            throw helper.assertionException("Estate is not running");
        }
        ServerLevel level = helper.getLevel();
        String world = EstateRuntime.world(level);
        int cellSize = rt.manager.cellSize();
        BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
        long cell = Cells.containing(base.getX(), base.getZ(), cellSize);
        int cellX = Cells.x(cell) * cellSize;
        int cellZ = Cells.z(cell) * cellSize;
        int y = base.getY();
        BlockPos inside = new BlockPos(cellX + 3, y, cellZ + 3);
        BlockPos outside = new BlockPos(cellX - 1, y, cellZ + 3);
        UUID owner = UUID.randomUUID();
        rt.manager.seen(owner, "EstateTestOwner");
        Claim claim = rt.manager.create(world, owner, "test", Set.of(cell), y - 5, y + 10, owner, PlacementOracle.NONE, true).claim();
        if (claim == null) {
            throw helper.assertionException("Could not create a test claim");
        }
        FakePlayer player = FakePlayer.get(level);
        try {
            level.setBlockAndUpdate(inside, Blocks.DIRT.defaultBlockState());
            if (mayBreak(level, player, inside)) {
                throw helper.assertionException("A stranger must not break blocks in a claim");
            }

            BlockPos chest = inside.east();
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (use(player, level, chest) != InteractionResult.FAIL) {
                throw helper.assertionException("A stranger must not open chests in a claim");
            }
            // The claimed cell can reach past this test's own area, so clear the spot first.
            level.setBlockAndUpdate(inside.above(), Blocks.AIR.defaultBlockState());
            if (place(player, inside).consumesAction()) {
                throw helper.assertionException("A stranger must not place blocks in a claim");
            }
            if (level.getBlockState(inside.above()).is(Blocks.DIRT)) {
                throw helper.assertionException("The refused block must not be in the world");
            }

            rt.manager.trust(claim, player.getUUID(), TrustTier.BUILD, owner);
            if (!mayBreak(level, player, inside) || use(player, level, chest) != InteractionResult.PASS) {
                throw helper.assertionException("A trusted builder may break blocks and open chests");
            }
            rt.manager.trust(claim, player.getUUID(), null, owner);

            if (Protection.mayFlow(level, outside, outside.east())) {
                throw helper.assertionException("Water from outside must not flow into a claim");
            }
            if (!Protection.mayFlow(level, inside, inside.north())) {
                throw helper.assertionException("Water inside a claim flows as normal");
            }
            if (!Protection.fireProtected(level, inside)) {
                throw helper.assertionException("Fire must not burn blocks in a claim");
            }
            if (rt.manager.pvpAllowed(world, inside.getX(), inside.getY(), inside.getZ())) {
                throw helper.assertionException("PvP is off in claims by default");
            }

            BlockPos piston = outside.west();
            level.setBlockAndUpdate(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(outside, Blocks.DIRT.defaultBlockState());
            if (Protection.mayPistonMove(level, piston, Direction.EAST, true)) {
                throw helper.assertionException("A piston outside must not push blocks into a claim");
            }
            level.setBlockAndUpdate(piston, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(outside, Blocks.AIR.defaultBlockState());

            level.explode(null, outside.getX() + 0.5, y + 0.5, outside.getZ() + 0.5, 3.0f, Level.ExplosionInteraction.TNT);
            if (!level.getBlockState(inside).is(Blocks.DIRT)) {
                throw helper.assertionException("An explosion destroyed a block in a claim");
            }
        } finally {
            rt.manager.delete(claim, null, "test");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }

        // Auto-claim: 50 blocks in a cluster make a claim for the builder.
        rt.manager.setAutoClaim(player.getUUID(), true);
        int floorY = y + 20;
        try {
            int placed = 0;
            for (int dx = 0; dx < 7; dx++) {
                for (int dz = 0; dz < 8 && placed < 50; dz++) {
                    BlockPos support = new BlockPos(cellX + dx, floorY - 1, cellZ + dz);
                    level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(support.above(), Blocks.AIR.defaultBlockState());
                    if (place(player, support).consumesAction()) {
                        placed++;
                    }
                }
            }
            if (placed < 50) {
                throw helper.assertionException("Only " + placed + " of 50 blocks could be placed");
            }
            Claim auto = rt.manager.claimAt(world, cellX + 1, floorY, cellZ + 1);
            if (auto == null || !auto.owner().equals(player.getUUID())) {
                throw helper.assertionException("Placing 50 blocks with auto-claim on should make a claim");
            }
            rt.manager.delete(auto, null, "test");
        } finally {
            rt.manager.setAutoClaim(player.getUUID(), false);
            for (Claim leftover : rt.manager.claimsOf(player.getUUID())) {
                rt.manager.delete(leftover, null, "test");
            }
        }
        helper.succeed();
    }

    /**
     * Builds every /land screen for a fake player and clicks through drawing a claim by coordinates, resizing it in the
     * editor and removing it. Also draws the block display borders, which only go to that player.
     */
    @GameTest(maxTicks = 200)
    public void menusWork(GameTestHelper helper) {
        EstateRuntime rt = Estate.runtime();
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        UUID id = player.getUUID();
        String world = EstateRuntime.world(level);
        int cellSize = rt.manager.cellSize();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        boolean autoBefore = rt.manager.autoClaim(id);
        try {
            Menus.main(rt, player);
            Gui main = Gui.lastOffscreen;
            if (main == null || main.itemAt(Menus.SLOT_INFO).isEmpty()) {
                throw helper.assertionException("The main menu should show the budget");
            }
            main.press(Menus.SLOT_AUTOCLAIM, Gui.Click.LEFT);
            if (rt.manager.autoClaim(id) == autoBefore) {
                throw helper.assertionException("The auto-claim button should switch auto-claim");
            }
            main.press(Menus.SLOT_AUTOCLAIM, Gui.Click.LEFT);

            main.press(Menus.SLOT_CLAIM, Gui.Click.LEFT);
            if (rt.session(player).selection == null || !hasTool(player)) {
                throw helper.assertionException("Claim land should hand out the claim tool");
            }
            BlockPos corner2 = base.offset(12, 15, 5);
            rt.claimTool.setExact(player, 1, base);
            rt.claimTool.setExact(player, 2, corner2);
            int expected = ClaimDraft.create(world, cellSize, new int[]{base.getX(), base.getY(), base.getZ()},
                    new int[]{corner2.getX(), corner2.getY(), corner2.getZ()}).cells().size();
            Menus.editor(rt, player);
            Gui editor = Gui.lastOffscreen;
            editor.press(Menus.SLOT_CONFIRM, Gui.Click.LEFT);
            Claim claim = rt.claimAt(level, base);
            if (claim == null || !claim.owner().equals(id) || claim.size() != expected
                    || claim.minY() != base.getY() || claim.maxY() != corner2.getY()) {
                throw helper.assertionException("Confirming should claim the box between the corners: " + claim);
            }
            if (hasTool(player)) {
                throw helper.assertionException("The claim tool should go away once the claim is made");
            }

            main.press(Menus.SLOT_RESIZE, Gui.Click.LEFT);
            Gui resize = Gui.lastOffscreen;
            long rows = claim.cells().stream().map(dev.cornybirdy.estate.core.Cells::z).distinct().count();
            resize.press(Menus.SLOTS_CORNER2[0], Gui.Click.SHIFT_LEFT);
            resize.press(Menus.SLOT_CONFIRM, Gui.Click.LEFT);
            if (claim.size() != expected + rows) {
                throw helper.assertionException("Moving corner 2 one cell east should add a column, got " + claim.size());
            }

            main.press(Menus.SLOT_TRUST, Gui.Click.LEFT);
            Gui trust = Gui.lastOffscreen;
            if (trust == resize || !trust.press(49, Gui.Click.LEFT)) {
                throw helper.assertionException("The trust screen should have an add button");
            }
            main.press(Menus.SLOT_FLAGS, Gui.Click.LEFT);
            Gui.lastOffscreen.press(Menus.FLAGS_FIRST_SLOT + 8, Gui.Click.LEFT);
            if (!rt.manager.flag(claim, dev.cornybirdy.estate.core.Flag.PVP)) {
                throw helper.assertionException("Owners may switch PvP on in the protection screen");
            }
            main.press(Menus.SLOT_LIST, Gui.Click.LEFT);
            if (Gui.lastOffscreen.itemAt(0).isEmpty()) {
                throw helper.assertionException("The claim list should show the claim");
            }
            Menus.adminSettings(rt, player, 0);

            main.press(Menus.SLOT_UNCLAIM, Gui.Click.LEFT);
            Gui.lastOffscreen.press(11, Gui.Click.LEFT);
            if (rt.manager.claim(claim.id()) != null) {
                throw helper.assertionException("Confirming unclaim should remove the claim");
            }
        } finally {
            rt.claimTool.end(player);
            rt.manager.setAutoClaim(id, autoBefore);
            for (Claim leftover : rt.manager.claimsOf(id)) {
                rt.manager.delete(leftover, null, "test");
            }
        }
        helper.succeed();
    }

    private static boolean hasTool(FakePlayer player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (ClaimTool.isTool(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean mayBreak(ServerLevel level, FakePlayer player, BlockPos pos) {
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, level.getBlockState(pos), null);
    }

    private static InteractionResult use(FakePlayer player, ServerLevel level, BlockPos pos) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit(pos));
    }

    /** Places dirt on top of a block, the way a player would. */
    private static InteractionResult place(FakePlayer player, BlockPos on) {
        ItemStack dirt = new ItemStack(Items.DIRT, 64);
        player.setItemInHand(InteractionHand.MAIN_HAND, dirt);
        return ((BlockItem) Items.DIRT).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, dirt, hit(on)));
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }
}
