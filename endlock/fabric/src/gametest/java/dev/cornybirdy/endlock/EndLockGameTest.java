package dev.cornybirdy.endlock;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Runs on a real (headless) server in CI: turns each feature off and on and checks the game follows. */
public class EndLockGameTest {

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
            EndLock.setEnabled(level.getServer(), Feature.ENDER_CHESTS, false);
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

            EndLock.setEnabled(level.getServer(), Feature.ENDER_CHESTS, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            if (use(player, level, chest) != InteractionResult.PASS) {
                throw helper.assertionException("Ender chests should open again once turned back on");
            }
            if (!result.mayPickup(player)) {
                throw helper.assertionException("Crafted ender chests should be takeable once turned back on");
            }
        } finally {
            EndLock.setEnabled(level.getServer(), Feature.ENDER_CHESTS, true);
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
            EndLock.setEnabled(level.getServer(), Feature.END, false);
            if (block.getPortalDestination(level, player, portal) != null) {
                throw helper.assertionException("End portals should go nowhere while the End is off");
            }
            EndLock.setEnabled(level.getServer(), Feature.END, true);
            if (level.getServer().getLevel(Level.END) != null && block.getPortalDestination(level, player, portal) == null) {
                throw helper.assertionException("End portals should work again once the End is turned back on");
            }
        } finally {
            EndLock.setEnabled(level.getServer(), Feature.END, true);
        }
        helper.succeed();
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static InteractionResult use(FakePlayer player, ServerLevel level, BlockHitResult hit) {
        return UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }
}
