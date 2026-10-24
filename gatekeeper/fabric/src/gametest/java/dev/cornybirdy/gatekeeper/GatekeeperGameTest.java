package dev.cornybirdy.gatekeeper;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Runs on a real (headless) server in CI: turns each feature off and on and checks the game follows. */
public class GatekeeperGameTest {

    @GameTest
    public void enderChestsCanBeTurnedOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.ENDER_CHEST);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.STONE);
        BlockHitResult chest = hit(helper.absolutePos(new BlockPos(1, 1, 1)));
        BlockHitResult stone = hit(helper.absolutePos(new BlockPos(3, 1, 1)));
        CraftingMenu menu = new CraftingMenu(0, player.getInventory());
        Slot result = menu.getSlot(0);
        result.set(new ItemStack(Items.ENDER_CHEST));

        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.ENDER_CHESTS, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (use(player, level, chest) != InteractionResult.FAIL) {
                throw helper.assertionException("Opening an ender chest should be blocked");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_CHEST));
            if (use(player, level, stone) != InteractionResult.FAIL) {
                throw helper.assertionException("Placing an ender chest should be blocked");
            }
            if (result.mayPickup(player)) {
                throw helper.assertionException("Crafted ender chests should stay in the crafting grid");
            }

            Gatekeeper.setEnabled(level.getServer(), Feature.ENDER_CHESTS, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (use(player, level, chest) != InteractionResult.PASS) {
                throw helper.assertionException("Ender chests should open again once turned back on");
            }
            if (!result.mayPickup(player)) {
                throw helper.assertionException("Crafted ender chests should be takeable once turned back on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.ENDER_CHESTS, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        helper.succeed();
    }

    @GameTest
    public void endPortalsCanBeClosed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        BlockPos portal = helper.absolutePos(new BlockPos(1, 1, 1));
        EndPortalBlock block = (EndPortalBlock) Blocks.END_PORTAL;

        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, false);
            if (block.getPortalDestination(level, player, portal) != null) {
                throw helper.assertionException("End portals should go nowhere while the End is off");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
            if (level.getServer().getLevel(Level.END) != null && block.getPortalDestination(level, player, portal) == null) {
                throw helper.assertionException("End portals should work again once the End is turned back on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
        }
        helper.succeed();
    }

    @GameTest
    public void litPortalsGoDarkAndComeBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos portal = new BlockPos(1, 1, 1);
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
            helper.setBlock(portal, Blocks.END_PORTAL);
            Gatekeeper.setEnabled(level.getServer(), Feature.END, false);
            if (!helper.getBlockState(portal).isAir()) {
                throw helper.assertionException("A lit portal should go dark when the End is turned off");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
            if (!helper.getBlockState(portal).is(Blocks.END_PORTAL)) {
                throw helper.assertionException("The portal should light up again when the End is turned on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
        }
        helper.succeed();
    }

    @GameTest
    public void lastEyeWaitsForTheEnd(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        // Interior is x 2..4, z 2..4. Every frame gets an eye except the last one on the north side.
        BlockPos last = new BlockPos(4, 1, 1);
        for (int i = 0; i < 3; i++) {
            frame(helper, new BlockPos(2 + i, 1, 1), Direction.SOUTH, !new BlockPos(2 + i, 1, 1).equals(last));
            frame(helper, new BlockPos(2 + i, 1, 5), Direction.NORTH, true);
            frame(helper, new BlockPos(1, 1, 2 + i), Direction.EAST, true);
            frame(helper, new BlockPos(5, 1, 2 + i), Direction.WEST, true);
        }
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ENDER_EYE, 2));
            if (use(player, level, hit(helper.absolutePos(last))) != InteractionResult.SUCCESS) {
                throw helper.assertionException("The eye should go into the frame");
            }
            if (!helper.getBlockState(last).getValue(EndPortalFrameBlock.HAS_EYE)
                    || player.getMainHandItem().getCount() != 1) {
                throw helper.assertionException("The frame should have the eye and the player one eye less");
            }
            if (!helper.getBlockState(new BlockPos(3, 1, 3)).isAir()) {
                throw helper.assertionException("The portal must not light up while the End is off");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
            for (int x = 2; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    if (!helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.END_PORTAL)) {
                        throw helper.assertionException("The whole portal should light up once the End is on");
                    }
                }
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.END, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        helper.succeed();
    }

    @GameTest
    public void netherPortalsGoDarkAndComeBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, true);
            obsidianFrame(helper);
            helper.setBlock(new BlockPos(2, 2, 2), Blocks.FIRE);
            if (!helper.getBlockState(new BlockPos(2, 3, 2)).is(Blocks.NETHER_PORTAL)) {
                throw helper.assertionException("Fire in the frame should light the portal while the Nether is on");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, false);
            if (helper.getBlockState(new BlockPos(2, 3, 2)).is(Blocks.NETHER_PORTAL)) {
                throw helper.assertionException("A lit portal should go dark when the Nether is turned off");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, true);
            if (!helper.getBlockState(new BlockPos(3, 4, 2)).is(Blocks.NETHER_PORTAL)) {
                throw helper.assertionException("The portal should light up again when the Nether is turned on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, true);
        }
        helper.succeed();
    }

    @GameTest
    public void lightingWaitsForTheNether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, false);
            obsidianFrame(helper);
            helper.setBlock(new BlockPos(2, 2, 2), Blocks.FIRE);
            if (helper.getBlockState(new BlockPos(2, 3, 2)).is(Blocks.NETHER_PORTAL)) {
                throw helper.assertionException("The portal must not light up while the Nether is off");
            }
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, true);
            for (int x = 2; x <= 3; x++) {
                for (int y = 2; y <= 4; y++) {
                    if (!helper.getBlockState(new BlockPos(x, y, 2)).is(Blocks.NETHER_PORTAL)) {
                        throw helper.assertionException("The whole portal should light up once the Nether is on");
                    }
                }
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHER, true);
        }
        helper.succeed();
    }

    @GameTest
    public void netheriteArmorCanBeTurnedOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        SmithingMenu smithing = netheriteUpgrade(helper, player, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_ARMOR, false);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
            Gatekeeper.takeOffBannedArmor(player);
            if (!player.getItemBySlot(EquipmentSlot.HEAD).isEmpty() || player.getInventory().countItem(Items.NETHERITE_HELMET) != 1) {
                throw helper.assertionException("A worn netherite helmet should be moved into the inventory");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_CHESTPLATE));
            if (UseItemCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND) != InteractionResult.FAIL) {
                throw helper.assertionException("Right-clicking to put on netherite armor should be blocked");
            }
            if (resultSlot(smithing).mayPickup(player)) {
                throw helper.assertionException("Netherite armor should stay in the smithing table");
            }
            if (Gatekeeper.banned(player, Feature.NETHERITE_TOOLS)) {
                throw helper.assertionException("Turning armor off must not touch tools");
            }

            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_ARMOR, true);
            if (!resultSlot(smithing).mayPickup(player)) {
                throw helper.assertionException("Netherite armor should be takeable once turned back on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_ARMOR, true);
            player.getInventory().clearContent();
        }
        helper.succeed();
    }

    @GameTest
    public void netheriteToolsCanBeTurnedOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayer.get(level);
        BlockPos stone = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        SmithingMenu smithing = netheriteUpgrade(helper, player, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        try {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_TOOLS, false);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            if (PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, stone, level.getBlockState(stone), null)) {
                throw helper.assertionException("Mining with a netherite pickaxe should be blocked");
            }
            if (AttackEntityCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, player, null)
                    != InteractionResult.FAIL) {
                throw helper.assertionException("Hitting with a netherite tool should be blocked");
            }
            if (resultSlot(smithing).mayPickup(player)) {
                throw helper.assertionException("Netherite tools should stay in the smithing table");
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, stone, level.getBlockState(stone), null)) {
                throw helper.assertionException("Other tools should still work");
            }

            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_TOOLS, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_PICKAXE));
            if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, stone, level.getBlockState(stone), null)) {
                throw helper.assertionException("Netherite tools should work again once turned back on");
            }
        } finally {
            Gatekeeper.setEnabled(level.getServer(), Feature.NETHERITE_TOOLS, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        helper.succeed();
    }

    /** A smithing table with a diamond item, the upgrade template and an ingot in it, showing the netherite result. */
    private static SmithingMenu netheriteUpgrade(GameTestHelper helper, FakePlayer player, Item diamond, Item netherite) {
        SmithingMenu menu = new SmithingMenu(0, player.getInventory());
        menu.getSlot(0).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
        menu.getSlot(1).set(new ItemStack(diamond));
        menu.getSlot(2).set(new ItemStack(Items.NETHERITE_INGOT));
        if (!resultSlot(menu).getItem().is(netherite)) {
            throw helper.assertionException("The smithing table should offer " + netherite + ", found " + resultSlot(menu).getItem());
        }
        return menu;
    }

    @GameTest
    public void pvpIsOptIn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer a = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "PvpTestA"));
        FakePlayer b = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), "PvpTestB"));
        Pvp pvp = Gatekeeper.pvp();
        DamageSource hit = level.damageSources().playerAttack(a);
        if (ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, hit, 1)) {
            throw helper.assertionException("Players start with PvP off, so they can't hurt each other");
        }
        if (pvp.set(a.getUUID(), true).outcome() != Pvp.Outcome.CHANGED) {
            throw helper.assertionException("/pvp on should work the first time");
        }
        if (ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, hit, 1)) {
            throw helper.assertionException("PvP needs both players to have it on");
        }
        pvp.set(b.getUUID(), true);
        if (!ServerLivingEntityEvents.ALLOW_DAMAGE.invoker().allowDamage(b, hit, 1)) {
            throw helper.assertionException("Players with PvP on can hurt each other");
        }
        pvp.tag(a.getUUID());
        if (pvp.set(a.getUUID(), false).outcome() != Pvp.Outcome.IN_COMBAT) {
            throw helper.assertionException("PvP can't be turned off in combat");
        }
        pvp.endCombat(a.getUUID());
        if (pvp.set(a.getUUID(), false).outcome() != Pvp.Outcome.COOLDOWN) {
            throw helper.assertionException("PvP can't be changed again straight away");
        }
        helper.succeed();
    }

    private static Slot resultSlot(SmithingMenu menu) {
        return menu.getSlot(menu.getResultSlot());
    }

    /** A smallest-size obsidian frame along x, with the inside at x 2..3, y 2..4, z 2. */
    private static void obsidianFrame(GameTestHelper helper) {
        for (int y = 2; y <= 4; y++) {
            helper.setBlock(new BlockPos(1, y, 2), Blocks.OBSIDIAN);
            helper.setBlock(new BlockPos(4, y, 2), Blocks.OBSIDIAN);
        }
        for (int x = 2; x <= 3; x++) {
            helper.setBlock(new BlockPos(x, 1, 2), Blocks.OBSIDIAN);
            helper.setBlock(new BlockPos(x, 5, 2), Blocks.OBSIDIAN);
        }
    }

    private static void frame(GameTestHelper helper, BlockPos pos, Direction facing, boolean eye) {
        BlockState state = Blocks.END_PORTAL_FRAME.defaultBlockState()
                .setValue(EndPortalFrameBlock.FACING, facing)
                .setValue(EndPortalFrameBlock.HAS_EYE, eye);
        helper.setBlock(pos, state);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static InteractionResult use(FakePlayer player, ServerLevel level, BlockHitResult hit) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }
}
