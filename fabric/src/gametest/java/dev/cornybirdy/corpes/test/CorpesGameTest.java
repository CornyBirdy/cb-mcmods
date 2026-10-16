package dev.cornybirdy.corpes.test;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Runs on a real (headless) server in CI: kills a player holding items and checks the corpse, the loot menu and
 * that nothing can be put into it.
 */
public class CorpesGameTest {

    @GameTest(maxTicks = 100)
    public void deathCreatesLootableCorpse(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TestPlayer player = new TestPlayer(level, new GameProfile(UUID.randomUUID(), "CorpseTester"));
        BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
        player.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 90, 0);

        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(20, new ItemStack(Items.COBBLESTONE, 32));
        player.getInventory().setItem(39, new ItemStack(Items.IRON_HELMET));
        player.getInventory().setItem(40, new ItemStack(Items.SHIELD));

        player.setHealth(0);
        player.die(level.damageSources().generic());

        if (!player.getInventory().isEmpty()) {
            throw helper.assertionException("Inventory should have moved into the corpse");
        }
        AABB area = new AABB(at.getX() - 6, at.getY() - 70, at.getZ() - 6, at.getX() + 6, at.getY() + 6, at.getZ() + 6);
        if (!level.getEntitiesOfClass(ItemEntity.class, area, e -> true).isEmpty()) {
            throw helper.assertionException("Items were dropped on the ground");
        }
        List<Mannequin> bodies = level.getEntitiesOfClass(Mannequin.class, area, e -> e.entityTags().contains("corpes"));
        List<Interaction> hitboxes = level.getEntitiesOfClass(Interaction.class, area, e -> e.entityTags().contains("corpes"));
        List<Display.TextDisplay> labels = level.getEntitiesOfClass(Display.TextDisplay.class, area, e -> e.entityTags().contains("corpes"));
        if (bodies.size() != 1 || hitboxes.size() != 1 || labels.size() != 1) {
            throw helper.assertionException("Expected one body, hitbox and label, found " + bodies.size() + ", "
                    + hitboxes.size() + " and " + labels.size());
        }

        // Respawned player opens the corpse.
        player.setHealth(20);
        InteractionResult result = UseEntityCallback.EVENT.invoker()
                .interact(player, level, InteractionHand.MAIN_HAND, hitboxes.getFirst(), null);
        if (result == InteractionResult.PASS || !(player.containerMenu instanceof ChestMenu menu)) {
            throw helper.assertionException("Right-clicking the corpse should open its menu");
        }

        expect(helper, menu, 0, Items.IRON_HELMET, "helmet in the top-left slot");
        expect(helper, menu, 4, Items.SHIELD, "offhand next to the armour");
        expect(helper, menu, 20, Items.COBBLESTONE, "main inventory in the same slot");
        expect(helper, menu, 36, Items.DIAMOND_SWORD, "hotbar in row 5");
        expect(helper, menu, 8, Items.PLAYER_HEAD, "info head");
        if (menu.getSlot(1).getItem().get(DataComponents.CUSTOM_NAME) == null) {
            throw helper.assertionException("Empty chestplate slot should be labelled");
        }

        // Nothing goes in: shift-click from the player's inventory, and labels can't be taken.
        player.getInventory().setItem(9, new ItemStack(Items.COBBLESTONE, 16));
        // In a six-row chest menu the player's inventory starts at menu slot 54 (player slot 9).
        menu.clicked(54, 0, ContainerInput.QUICK_MOVE, player);
        if (menu.getSlot(20).getItem().getCount() != 32) {
            throw helper.assertionException("Shift-clicking must not put items into the corpse");
        }
        menu.clicked(1, 0, ContainerInput.PICKUP, player);
        if (!menu.getCarried().isEmpty()) {
            throw helper.assertionException("Labels must not be picked up");
        }

        // Take all empties the corpse, which then disappears.
        menu.clicked(53, 0, ContainerInput.PICKUP, player);
        if (player.getInventory().countItem(Items.DIAMOND_SWORD) != 1
                || player.getInventory().countItem(Items.IRON_HELMET) != 1
                || player.getInventory().countItem(Items.COBBLESTONE) != 48) {
            throw helper.assertionException("Take all should move everything into the inventory");
        }
        helper.runAfterDelay(2, () -> {
            boolean gone = level.getEntitiesOfClass(Entity.class, area, e -> e.entityTags().contains("corpes")).isEmpty();
            if (!gone) {
                throw helper.assertionException("An emptied corpse should be removed");
            }
            helper.succeed();
        });
    }

    private static void expect(GameTestHelper helper, AbstractContainerMenu menu, int slot, Item item,
                               String what) {
        if (menu.getSlot(slot).getItem().getItem() != item) {
            throw helper.assertionException("Expected " + what + " (slot " + slot + "), found " + menu.getSlot(slot).getItem());
        }
    }

    /** A fake player that can open menus, so the test can click around in the corpse menu. */
    private static final class TestPlayer extends FakePlayer {

        TestPlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public OptionalInt openMenu(MenuProvider provider) {
            AbstractContainerMenu menu = provider.createMenu(1, getInventory(), this);
            containerMenu = menu;
            return OptionalInt.of(menu.containerId);
        }
    }
}
