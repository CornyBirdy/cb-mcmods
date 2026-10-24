package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Check;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.Region;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

/**
 * Drawing claims as a box between two corners, the way Litematica selects areas. {@code /land claim} hands out a
 * temporary claim tool: left-click for corner 1 (red), right-click for corner 2 (blue), on the block the player looks
 * at up to 64 blocks away. Until corner 2 is set it follows where the player looks, so the outline and cost update
 * live. The tool never breaks blocks, hurts anything or wears out.
 * {@code /land pos1|pos2 [x y z]} set corners to exact coordinates, and the editor screen nudges or types them.
 * Drawing right next to your own claim adds the box to it. The tool disappears when the claim is confirmed or
 * cancelled, after 10 idle minutes, or on logout. A dropped tool vanishes, and while a claim is being drawn the player
 * gets it back.
 */
final class ClaimTool {

    /** A claim being drawn. The draft itself lives in {@link Session#draft}. */
    static final class Selection {
        /** The blocks the player picked with the tool or "where you stand"; heights are worked out from them. */
        BlockPos picked1;
        BlockPos picked2;
        /** Corner 2 follows the crosshair until it's set. */
        boolean followLook = true;
        long lastActive;
        long lastLeftTick = -100;
        long lastRightTick = -100;
    }

    private static final String TOOL_KEY = "estate_claim_tool";
    private static final int REACH = 64;
    private static final int FOLLOW_TICKS = 4;
    private static final long IDLE_MILLIS = 10 * 60 * 1000;

    private final EstateRuntime rt;

    ClaimTool(EstateRuntime rt) {
        this.rt = rt;
    }

    // ------------------------------------------------------------------ starting and ending

    /** {@code /land claim}: hands out the tool and explains it. */
    void start(ServerPlayer player) {
        String world = EstateRuntime.world(player.level());
        if (!rt.config.isClaimable(world) && !rt.bypassing(player)) {
            rt.send(player, "world-not-claimable");
            return;
        }
        Session session = rt.session(player);
        session.draft = null;
        Selection selection = new Selection();
        selection.lastActive = System.currentTimeMillis();
        session.selection = selection;
        if (!giveTool(player)) {
            session.selection = null;
            return;
        }
        rt.send(player, "claim-tool-start", "reach", REACH);
    }

    /** Starts resizing a claim: the editor and the tool both work on its box. */
    void startResize(ServerPlayer player, Claim claim) {
        Session session = rt.session(player);
        session.draft = ClaimDraft.resize(claim);
        Selection selection = new Selection();
        selection.followLook = false;
        selection.lastActive = System.currentTimeMillis();
        session.selection = selection;
        changed(player, false);
    }

    /** Puts the tool in the player's inventory if it isn't there. False if there's no room. */
    boolean giveTool(ServerPlayer player) {
        if (hasTool(player)) {
            return true;
        }
        if (player.getInventory().add(tool())) {
            return true;
        }
        rt.send(player, "claim-tool-no-room");
        return false;
    }

    /** Ends drawing: forgets the selection and takes the tool back. */
    void end(ServerPlayer player) {
        Session session = rt.session(player);
        session.selection = null;
        session.draft = null;
        removeTools(player);
        rt.borders.refresh(player);
    }

    static boolean isTool(ItemStack item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(TOOL_KEY);
    }

    private ItemStack tool() {
        ItemStack item = new ItemStack(Items.GOLDEN_SHOVEL);
        item.set(DataComponents.CUSTOM_NAME, rt.msg.item("claim-tool-name"));
        item.set(DataComponents.LORE, new ItemLore(rt.msg.lore("claim-tool-lore")));
        item.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Not a shovel any more: it doesn't dig, break blocks in creative or wear out.
        item.set(DataComponents.TOOL, new Tool(List.of(), 0f, 0, false));
        item.remove(DataComponents.MAX_DAMAGE);
        item.remove(DataComponents.DAMAGE);
        item.remove(DataComponents.ATTRIBUTE_MODIFIERS);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(TOOL_KEY, true);
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return item;
    }

    private static boolean hasTool(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isTool(inventory.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    static void removeTools(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isTool(inventory.getItem(i))) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        if (isTool(player.containerMenu.getCarried())) {
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ clicks

    private static boolean holdingTool(Player player) {
        return isTool(player.getMainHandItem());
    }

    /** Left-clicking a block with the tool: picks corner 1 instead of mining. Runs before the protection checks. */
    InteractionResult attackBlock(Player player, Level level, InteractionHand hand, BlockPos pos) {
        if (!(player instanceof ServerPlayer sp) || !holdingTool(sp)) {
            return InteractionResult.PASS;
        }
        leftClick(sp, pos);
        return InteractionResult.FAIL;
    }

    /** The tool doesn't hit mobs or players; the swing that comes with the click picks corner 1. */
    InteractionResult attackEntity(Player player) {
        return player instanceof ServerPlayer sp && holdingTool(sp) ? InteractionResult.FAIL : InteractionResult.PASS;
    }

    /** Nothing is ever broken with the tool in hand. */
    boolean mayBreak(Player player) {
        return !holdingTool(player);
    }

    /**
     * The player swung their arm, which is all the server hears of a left-click in the air or on something far away.
     * The same click on a nearby block already arrived as {@link #attackBlock}, and right-clicks and held-down mining
     * swing too, so swings close to another click are ignored.
     */
    void swing(ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !holdingTool(player)) {
            return;
        }
        Selection selection = rt.session(player).selection;
        long tick = rt.server.getTickCount();
        if (selection != null && (tick - selection.lastRightTick <= 1 || tick - selection.lastLeftTick < 5)) {
            if (tick - selection.lastLeftTick < 5) {
                selection.lastLeftTick = tick;
            }
            return;
        }
        leftClick(player, lookedAt(player));
    }

    private void leftClick(ServerPlayer player, BlockPos pos) {
        Selection selection = active(player);
        if (selection == null) {
            return;
        }
        long tick = rt.server.getTickCount();
        boolean repeat = tick - selection.lastLeftTick < 5;
        selection.lastLeftTick = tick;
        if (repeat) {
            return;
        }
        if (pos == null) {
            rt.warn(player, "claim-tool-look", "reach", REACH);
            return;
        }
        pick(player, 1, pos);
    }

    /** Right-clicking with the tool in the air or far away: corner 2. Runs before the protection checks. */
    InteractionResult useItem(Player player, Level level, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp) || !isTool(sp.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        rightClick(sp);
        return InteractionResult.SUCCESS;
    }

    /** Right-clicking a block with the tool: corner 2, instead of making a path. */
    InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer sp) || !isTool(sp.getItemInHand(hand))) {
            return InteractionResult.PASS;
        }
        rightClick(sp);
        sp.connection.send(new ClientboundBlockUpdatePacket(level, hit.getBlockPos()));
        return InteractionResult.SUCCESS;
    }

    private void rightClick(ServerPlayer player) {
        Selection selection = active(player);
        if (selection == null) {
            return;
        }
        // One click can arrive as both "use on block" and "use item", and holding the button repeats it.
        long tick = rt.server.getTickCount();
        boolean repeat = tick - selection.lastRightTick < 5;
        selection.lastRightTick = tick;
        if (repeat) {
            return;
        }
        BlockPos target = lookedAt(player);
        if (target == null) {
            rt.warn(player, "claim-tool-look", "reach", REACH);
            return;
        }
        pick(player, 2, target);
    }

    /** The selection the tool works on, or null (and the tool taken away) if it has expired. */
    private Selection active(ServerPlayer player) {
        Selection selection = rt.session(player).selection;
        if (selection == null) {
            removeTools(player);
            rt.send(player, "claim-tool-expired");
        }
        return selection;
    }

    private static BlockPos lookedAt(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0, false);
        return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit.getBlockPos() : null;
    }

    /**
     * Sets a corner to a picked block (with the tool, or where the player stands). The heights are worked out from
     * the ground: from {@code verticalPadding} below the lowest point to {@code newClaimHeadroom} above the highest.
     */
    void pick(ServerPlayer player, int which, BlockPos pos) {
        Session session = rt.session(player);
        Selection selection = selection(player);
        if (session.draft != null && session.draft.mode == ClaimDraft.Mode.RESIZE) {
            // Resizing: the corner moves sideways; its height stays (change it in the editor or with /land pos).
            session.draft.setCorner(which, pos.getX(), session.draft.corner(which)[1], pos.getZ());
            changed(player, true);
            return;
        }
        if (which == 1) {
            selection.picked1 = pos;
            if (selection.picked2 == null || selection.followLook) {
                selection.picked2 = pos;
            }
        } else {
            selection.picked2 = pos;
            selection.followLook = false;
            if (selection.picked1 == null) {
                selection.picked1 = pos;
            }
        }
        rebuild(player);
        changed(player, true);
    }

    /** {@code /land pos1|pos2 <x> <y> <z>}: puts a corner exactly there, height included. */
    void setExact(ServerPlayer player, int which, BlockPos pos) {
        Session session = rt.session(player);
        Selection selection = selection(player);
        if (session.draft == null) {
            selection.picked1 = pos;
            selection.picked2 = pos;
            session.draft = draftFor(player, new int[]{pos.getX(), pos.getY(), pos.getZ()}, new int[]{pos.getX(), pos.getY(), pos.getZ()});
        }
        session.draft.setCorner(which, pos.getX(), pos.getY(), pos.getZ());
        if (which == 2) {
            selection.followLook = false;
        }
        changed(player, true);
    }

    private Selection selection(ServerPlayer player) {
        Session session = rt.session(player);
        if (session.selection == null) {
            session.selection = new Selection();
        }
        session.selection.lastActive = System.currentTimeMillis();
        return session.selection;
    }

    /** Builds the draft from the two picked blocks, with heights from the ground. */
    private void rebuild(ServerPlayer player) {
        Session session = rt.session(player);
        Selection selection = session.selection;
        ServerLevel level = player.level();
        BlockPos a = selection.picked1;
        BlockPos b = selection.picked2;
        int[] ground = groundRange(level, Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), Math.min(a.getY(), b.getY()), Math.max(a.getY(), b.getY()));
        int bottom = Math.max(level.getMinY(), ground[0] - rt.config.verticalPadding);
        int top = Math.min(level.getMaxY(), ground[1] + rt.config.newClaimHeadroom);
        session.draft = draftFor(player, new int[]{a.getX(), bottom, a.getZ()}, new int[]{b.getX(), top, b.getZ()});
    }

    /** A new claim, or an extension of the player's own claim if the box touches or overlaps it. */
    private ClaimDraft draftFor(ServerPlayer player, int[] corner1, int[] corner2) {
        String world = EstateRuntime.world(player.level());
        ClaimDraft fresh = ClaimDraft.create(world, corner1, corner2);
        if (!fresh.tooBig()) {
            Region box = fresh.box();
            for (Claim claim : rt.manager.claimsOf(player.getUUID())) {
                if (claim.world().equals(world) && claim.overlapsY(fresh.minY() - 1, fresh.maxY() + 1)
                        && claim.region().within(box, 1)) {
                    return ClaimDraft.extend(claim, corner1, corner2);
                }
            }
        }
        return fresh;
    }

    /** Lowest and highest ground in the box (loaded chunks only), and the picked heights. */
    static int[] groundRange(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int low, int high) {
        int step = Math.max(1, Math.max(maxX - minX, maxZ - minZ) / 16);
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                if (level.isLoaded(new BlockPos(x, low, z))) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    low = Math.min(low, y);
                    high = Math.max(high, y);
                }
            }
        }
        return new int[]{low, high};
    }

    /** After a corner moved: redraw the outline and say what the claim would be. */
    void changed(ServerPlayer player, boolean announce) {
        Session session = rt.session(player);
        Check check = evaluate(player);
        session.draftOk = check != null && check.allowed();
        rt.borders.show(player, 0);
        if (announce) {
            summary(player, check);
        }
    }

    /** Checks the draft. Null if there's no draft or the box is too big. */
    Check evaluate(ServerPlayer player) {
        ClaimDraft draft = rt.session(player).draft;
        if (draft == null || draft.tooBig()) {
            return null;
        }
        Claim claim = draft.claimId == null ? null : rt.manager.claim(draft.claimId);
        return rt.manager.check(draft.world, player.getUUID(), draft.region(), draft.minY(), draft.maxY(), claim,
                rt.oracle(), rt.bypassing(player));
    }

    /** The draft in words, with Confirm / Edit / Cancel buttons. */
    void summary(ServerPlayer player, Check check) {
        ClaimDraft draft = rt.session(player).draft;
        if (draft == null) {
            return;
        }
        int[] c1 = draft.corner(1);
        int[] c2 = draft.corner(2);
        player.sendSystemMessage(rt.msg.get("draft-corners", "x1", c1[0], "y1", c1[1], "z1", c1[2], "x2", c2[0], "y2", c2[1], "z2", c2[2]));
        if (draft.tooBig()) {
            player.sendSystemMessage(rt.msg.get("draft-too-big"));
        } else {
            player.sendSystemMessage(rt.msg.get(draft.mode == ClaimDraft.Mode.NEW ? "draft-size-new" : "draft-size-change",
                    "width", draft.width(), "depth", draft.depth(),
                    "blocks", draft.region().area(), "miny", draft.minY(), "maxy", draft.maxY(),
                    "cost", check == null ? "?" : signed(check.cost()), "left", check == null ? "?" : check.budgetLeft() - check.cost()));
            if (check != null && !check.allowed()) {
                player.sendSystemMessage(rt.msg.get(check.problem().messageKey, Messages.problemPlaceholders(rt, check)));
            }
        }
        MutableComponent buttons = Component.empty();
        if (check != null && check.allowed()) {
            buttons.append(button("draft-button-confirm", "/land claim confirm"));
            buttons.append(Component.literal("  "));
        }
        buttons.append(button("draft-button-edit", "/land claim edit"));
        buttons.append(Component.literal("  "));
        buttons.append(button("draft-button-cancel", "/land claim cancel"));
        player.sendSystemMessage(buttons);
    }

    private MutableComponent button(String key, String command) {
        return rt.msg.get(key).withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** Makes the claim (or the change) real. Returns true if it worked. */
    boolean confirm(ServerPlayer player) {
        Session session = rt.session(player);
        ClaimDraft draft = session.draft;
        if (draft == null) {
            rt.send(player, "draft-none");
            return false;
        }
        Check check = evaluate(player);
        if (check == null) {
            rt.send(player, "draft-too-big");
            return false;
        }
        if (!check.allowed()) {
            rt.send(player, check.problem().messageKey, Messages.problemPlaceholders(rt, check));
            return false;
        }
        ClaimManager manager = rt.manager;
        boolean bypass = rt.bypassing(player);
        if (draft.claimId == null) {
            String name = rt.msg.raw("default-claim-name").replace("<player>", player.getGameProfile().name());
            ClaimManager.Change change = manager.create(draft.world, player.getUUID(), name, draft.region(), draft.minY(),
                    draft.maxY(), player.getUUID(), rt.oracle(), bypass);
            if (!change.ok()) {
                rt.send(player, change.check().problem().messageKey, Messages.problemPlaceholders(rt, change.check()));
                return false;
            }
            rt.send(player, "claim-created", "claim", change.claim().name(), "blocks", change.claim().size(),
                    "left", manager.budgetLeft(player.getUUID()));
        } else {
            Claim claim = manager.claim(draft.claimId);
            if (claim == null) {
                rt.send(player, "claim-gone");
                return false;
            }
            Check result = manager.resize(claim, draft.region(), draft.minY(), draft.maxY(), player.getUUID(), rt.oracle(), bypass);
            if (!result.allowed()) {
                rt.send(player, result.problem().messageKey, Messages.problemPlaceholders(rt, result));
                return false;
            }
            rt.send(player, "claim-resized", "claim", claim.name(), "blocks", claim.size(),
                    "left", manager.budgetLeft(claim.owner()));
        }
        session.noBudgetShown = false;
        session.draft = null;
        session.selection = null;
        removeTools(player);
        rt.borders.show(player, 0);
        return true;
    }

    // ------------------------------------------------------------------ ticking

    void tick(long tick) {
        if (tick % FOLLOW_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerPlayer player : rt.server.getPlayerList().getPlayers()) {
            Session session = rt.session(player);
            Selection selection = session.selection;
            if (selection == null) {
                continue;
            }
            if (now - selection.lastActive > IDLE_MILLIS) {
                end(player);
                rt.send(player, "claim-tool-timeout");
                continue;
            }
            if (!hasTool(player) && !isTool(player.containerMenu.getCarried())) {
                // Dropped or put away: it comes back while the claim is being drawn.
                player.getInventory().add(tool());
            }
            boolean holding = isTool(player.getMainHandItem()) || isTool(player.getOffhandItem());
            if (!holding) {
                continue;
            }
            selection.lastActive = now;
            if (selection.picked1 == null) {
                rt.actionBar(player, "claim-tool-hint-first");
                continue;
            }
            if (!selection.followLook || session.draft == null || session.draft.mode == ClaimDraft.Mode.RESIZE) {
                rt.actionBar(player, "claim-tool-hint-done");
                continue;
            }
            BlockPos target = lookedAt(player);
            if (target == null || target.equals(selection.picked2)) {
                continue;
            }
            selection.picked2 = target;
            rebuild(player);
            Check check = evaluate(player);
            session.draftOk = check != null && check.allowed();
            rt.borders.show(player, 0);
            ClaimDraft draft = session.draft;
            if (draft.tooBig()) {
                rt.actionBar(player, "claim-tool-live-too-big");
            } else {
                rt.actionBar(player, check != null && check.allowed() ? "claim-tool-live" : "claim-tool-live-bad",
                        "width", draft.width(), "depth", draft.depth(),
                        "blocks", draft.region().area(), "cost", check == null ? "?" : signed(check.cost()),
                        "left", check == null ? "?" : check.budgetLeft() - check.cost());
            }
        }
    }
}
