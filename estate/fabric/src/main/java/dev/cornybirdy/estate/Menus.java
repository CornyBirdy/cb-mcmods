package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Check;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.Cells;
import dev.cornybirdy.estate.core.EstateConfig;
import dev.cornybirdy.estate.core.Flag;
import dev.cornybirdy.estate.core.Permission;
import dev.cornybirdy.estate.core.TrustTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The /land screens. Every action here also exists as a command. */
final class Menus {

    private static final Map<Flag, Item> FLAG_ICONS = new EnumMap<>(Flag.class);

    static {
        FLAG_ICONS.put(Flag.BLOCK_BREAK_PLACE, Items.IRON_PICKAXE);
        FLAG_ICONS.put(Flag.CONTAINER_ACCESS, Items.CHEST);
        FLAG_ICONS.put(Flag.EXPLOSIONS, Items.TNT);
        FLAG_ICONS.put(Flag.FIRE_SPREAD, Items.FLINT_AND_STEEL);
        FLAG_ICONS.put(Flag.FLUID_FLOW_IN, Items.WATER_BUCKET);
        FLAG_ICONS.put(Flag.PISTON_CROSS, Items.PISTON);
        FLAG_ICONS.put(Flag.MOB_GRIEFING, Items.CREEPER_HEAD);
        FLAG_ICONS.put(Flag.ENTITY_DAMAGE, Items.ARMOR_STAND);
        FLAG_ICONS.put(Flag.PVP, Items.IRON_SWORD);
    }

    private Menus() {
    }

    // ------------------------------------------------------------------ main menu

    static void main(EstateRuntime rt, ServerPlayer player) {
        Gui.open(player, rt.msg.get("menu-title"), 3, (gui, p) -> {
            Msg msg = rt.msg;
            ClaimManager manager = rt.manager;
            UUID id = p.getUUID();
            Claim here = rt.claimAt(p.level(), p.blockPosition());
            String standing = here == null ? msg.raw("wilderness")
                    : msg.raw("claim-label").replace("<owner>", rt.name(here.owner())).replace("<claim>", here.name());
            boolean auto = manager.autoClaim(id);

            ItemStack info = head(p.getUUID(), msg.item("menu-info-name"), msg.lore("menu-info-lore",
                    "used", manager.budgetUsed(id), "limit", manager.budgetLimit(id), "left", manager.budgetLeft(id),
                    "claims", manager.claimsOf(id).size(), "max", rt.config.maxClaimsPerPlayer,
                    "autoclaim", msg.raw(auto ? "on" : "off"), "here", standing));
            gui.set(4, info);
            gui.set(10, Gui.item(Items.ENDER_EYE, msg.item("menu-borders"), msg.lore("menu-borders-lore")), (pl, c) -> {
                pl.closeContainer();
                rt.borders.show(pl, 0);
                rt.send(pl, "borders-shown", "seconds", rt.config.borderParticleSeconds);
            });
            gui.set(11, Gui.item(Items.GRASS_BLOCK, msg.item("menu-new"), msg.lore("menu-new-lore",
                    "size", rt.config.newClaimSize, "cost", rt.config.newClaimSize * rt.config.newClaimSize)),
                    (pl, c) -> newClaim(rt, pl));
            gui.set(12, Gui.item(Items.PISTON, msg.item("menu-resize"), msg.lore("menu-resize-lore")),
                    (pl, c) -> {
                        Claim claim = target(rt, pl, Permission.RESIZE);
                        if (claim != null) {
                            resize(rt, pl, claim);
                        }
                    });
            gui.set(13, Gui.item(Items.PLAYER_HEAD, msg.item("menu-trust"), msg.lore("menu-trust-lore")),
                    (pl, c) -> {
                        Claim claim = target(rt, pl, Permission.TRUST);
                        if (claim != null) {
                            trust(rt, pl, claim, 0);
                        }
                    });
            ItemStack autoItem = Gui.item(auto ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(),
                    msg.item(auto ? "menu-autoclaim-on" : "menu-autoclaim-off"),
                    msg.lore("menu-autoclaim-lore", "threshold", rt.config.autoClaimThreshold));
            gui.set(14, auto ? Gui.glow(autoItem) : autoItem, (pl, c) -> {
                boolean now = !manager.autoClaim(pl.getUUID());
                manager.setAutoClaim(pl.getUUID(), now);
                rt.send(pl, now ? "autoclaim-on" : "autoclaim-off", "threshold", rt.config.autoClaimThreshold);
                gui.redraw();
            });
            gui.set(15, Gui.item(Items.REDSTONE_TORCH, msg.item("menu-flags"), msg.lore("menu-flags-lore")),
                    (pl, c) -> {
                        Claim claim = target(rt, pl, null);
                        if (claim != null) {
                            flags(rt, pl, claim);
                        }
                    });
            gui.set(16, Gui.item(Items.BARRIER, msg.item("menu-unclaim"), msg.lore("menu-unclaim-lore")),
                    (pl, c) -> {
                        Claim claim = target(rt, pl, null);
                        if (claim != null) {
                            unclaim(rt, pl, claim);
                        }
                    });
            gui.set(18, Gui.item(Items.BOOK, msg.item("menu-help"), msg.lore("menu-help-lore")), (pl, c) -> {
                pl.closeContainer();
                LandCommand.help(rt, pl.createCommandSourceStack());
            });
            gui.set(22, Gui.item(Items.CHEST, msg.item("menu-list"), msg.lore("menu-list-lore",
                    "claims", manager.claimsOf(id).size())), (pl, c) -> claimList(rt, pl, pl.getUUID()));
            if (rt.isAdmin(p)) {
                gui.set(26, Gui.item(Items.COMMAND_BLOCK, msg.item("menu-admin"), msg.lore("menu-admin-lore")),
                        (pl, c) -> adminSettings(rt, pl, 0));
            }
        });
    }

    /**
     * The claim a menu button acts on: the one the player stands in if they may change it, otherwise their only
     * claim. Tells them what to do if that doesn't pick exactly one.
     *
     * @param permission what a trusted player needs to change it, or null for owner only
     */
    static Claim target(EstateRuntime rt, ServerPlayer player, Permission permission) {
        Claim here = rt.claimAt(player.level(), player.blockPosition());
        if (here != null && mayManage(rt, player, here, permission)) {
            return here;
        }
        List<Claim> own = rt.manager.claimsOf(player.getUUID());
        if (here == null && own.size() == 1) {
            return own.get(0);
        }
        player.closeContainer();
        if (own.isEmpty() && here == null) {
            rt.send(player, "no-claims");
        } else if (here != null) {
            rt.send(player, "not-yours", "owner", rt.name(here.owner()));
        } else {
            rt.send(player, "stand-in-claim");
        }
        return null;
    }

    static boolean mayManage(EstateRuntime rt, ServerPlayer player, Claim claim, Permission permission) {
        if (claim.owner().equals(player.getUUID()) || rt.bypassing(player)) {
            return true;
        }
        return permission != null && rt.manager.has(claim, player.getUUID(), permission);
    }

    // ------------------------------------------------------------------ new claim and resize

    static void newClaim(EstateRuntime rt, ServerPlayer player) {
        ServerLevel level = player.level();
        String world = EstateRuntime.world(level);
        if (!rt.config.isClaimable(world) && !rt.bypassing(player)) {
            player.closeContainer();
            rt.send(player, "world-not-claimable");
            return;
        }
        int size = rt.config.newClaimSize;
        int cellSize = rt.manager.cellSize();
        ClaimDraft square = ClaimDraft.square(world, player.getBlockX(), player.getBlockZ(), cellSize, size, 0, 0);
        int[] ground = groundRange(level, square, player.getBlockY());
        int minY = Math.max(level.getMinY(), ground[0] - rt.config.verticalPadding);
        int maxY = Math.min(level.getMaxY(), ground[1] + rt.config.newClaimHeadroom);
        editor(rt, player, new ClaimDraft(null, world, square.cells(), minY, maxY));
    }

    static void resize(EstateRuntime rt, ServerPlayer player, Claim claim) {
        editor(rt, player, ClaimDraft.of(claim));
    }

    /** Lowest and highest ground in the area (only loaded chunks are looked at), including the player's height. */
    private static int[] groundRange(ServerLevel level, ClaimDraft draft, int playerY) {
        int low = playerY;
        int high = playerY;
        int cellSize = Estate.runtime().manager.cellSize();
        for (long cell : draft.cells()) {
            int x0 = Cells.x(cell) * cellSize;
            int z0 = Cells.z(cell) * cellSize;
            for (int[] offset : new int[][]{{0, 0}, {cellSize - 1, 0}, {0, cellSize - 1}, {cellSize - 1, cellSize - 1}, {cellSize / 2, cellSize / 2}}) {
                BlockPos column = new BlockPos(x0 + offset[0], playerY, z0 + offset[1]);
                if (level.isLoaded(column)) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                    low = Math.min(low, y);
                    high = Math.max(high, y);
                }
            }
        }
        return new int[]{low, high};
    }

    private static Check evaluate(EstateRuntime rt, ServerPlayer player, ClaimDraft draft) {
        Claim claim = draft.claimId == null ? null : rt.manager.claim(draft.claimId);
        return rt.manager.check(draft.world, player.getUUID(), draft.cells(), draft.minY(), draft.maxY(), claim,
                rt.oracle(), rt.bypassing(player));
    }

    private static void editor(EstateRuntime rt, ServerPlayer player, ClaimDraft draft) {
        Session session = rt.session(player);
        session.draft = draft;
        Claim existing = draft.claimId == null ? null : rt.manager.claim(draft.claimId);
        Component title = existing == null ? rt.msg.get("editor-title-new")
                : rt.msg.get("editor-title-resize", "claim", existing.name());
        Gui.open(player, title, 5, (gui, p) -> {
            Msg msg = rt.msg;
            ServerLevel level = p.level();
            Check check = evaluate(rt, p, draft);
            session.draftOk = check.allowed();
            int cellSize = rt.manager.cellSize();
            String status = check.allowed() ? msg.raw("editor-ok")
                    : msg.raw(check.problem().messageKey);
            status = Text.parse(status, Msg.placeholders(Messages.problemPlaceholders(rt, check))).getString();
            int cost = check.cost();
            ItemStack info = Gui.item(check.allowed() ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(),
                    msg.item(check.allowed() ? "editor-info-ok" : "editor-info-bad"),
                    msg.lore("editor-info-lore", "cells", draft.cells().size(),
                            "area", draft.cells().size() * cellSize * cellSize,
                            "miny", draft.minY(), "maxy", draft.maxY(),
                            "cost", (cost >= 0 ? "+" : "") + cost,
                            "left", check.budgetLeft() - cost,
                            "status", status));
            gui.set(4, info);

            ClaimDraft.Side[] sides = ClaimDraft.Side.values();
            Item[] icons = {Items.ARROW, Items.ARROW, Items.ARROW, Items.ARROW, Items.FEATHER, Items.ANVIL};
            for (int i = 0; i < sides.length; i++) {
                ClaimDraft.Side side = sides[i];
                ItemStack icon = Gui.item(icons[i], msg.item("side-" + side.name().toLowerCase(java.util.Locale.ROOT)),
                        msg.lore("editor-side-lore"));
                gui.set(10 + i, draft.side() == side ? Gui.glow(icon) : icon, (pl, c) -> {
                    draft.setSide(side);
                    gui.redraw();
                });
            }
            String sideName = msg.raw("side-" + draft.side().name().toLowerCase(java.util.Locale.ROOT));
            gui.set(20, Gui.item(Items.STAINED_GLASS_PANE.red(), msg.item("editor-shrink", "side", sideName),
                    msg.lore("editor-shrink-lore", "step", rt.config.resizeVerticalStep)), (pl, c) -> {
                if (!draft.shrink(rt.config.resizeVerticalStep)) {
                    rt.warn(pl, "editor-cant-shrink");
                }
                rt.borders.show(pl, 0);
                gui.redraw();
            });
            gui.set(22, Gui.item(Items.COMPASS, msg.item("editor-side", "side", sideName),
                    msg.lore("editor-side-info")));
            gui.set(24, Gui.item(Items.STAINED_GLASS_PANE.lime(), msg.item("editor-grow", "side", sideName),
                    msg.lore("editor-grow-lore", "step", rt.config.resizeVerticalStep)), (pl, c) -> {
                if (!draft.grow(rt.config.resizeVerticalStep, level.getMinY(), level.getMaxY())) {
                    rt.warn(pl, "editor-cant-grow");
                }
                rt.borders.show(pl, 0);
                gui.redraw();
            });
            gui.set(36, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
            gui.set(40, Gui.item(Items.ENDER_EYE, msg.item("editor-preview"), msg.lore("editor-preview-lore")),
                    (pl, c) -> {
                        pl.closeContainer();
                        rt.session(pl).draft = draft;
                        rt.borders.show(pl, 0);
                        rt.send(pl, "editor-preview-hint");
                    });
            ItemStack confirm = Gui.item(check.allowed() ? Items.EMERALD : Items.BARRIER,
                    msg.item(check.allowed() ? "editor-confirm" : "editor-confirm-blocked"),
                    msg.lore("editor-confirm-lore", "status", status));
            gui.set(44, check.allowed() ? Gui.glow(confirm) : confirm, (pl, c) -> confirm(rt, pl, draft, gui));
            gui.onClose(pl -> {
                // Keep the outline for the preview button; otherwise forget the draft when the screen closes.
                Session s = rt.session(pl);
                if (s.draft == draft && s.bordersUntil <= rt.server.getTickCount()) {
                    s.draft = null;
                }
            });
        });
        rt.borders.show(player, 0);
    }

    private static void confirm(EstateRuntime rt, ServerPlayer player, ClaimDraft draft, Gui gui) {
        Check check = evaluate(rt, player, draft);
        if (!check.allowed()) {
            rt.send(player, check.problem().messageKey, Messages.problemPlaceholders(rt, check));
            gui.redraw();
            return;
        }
        Session session = rt.session(player);
        ClaimManager manager = rt.manager;
        if (draft.claimId == null) {
            String name = rt.msg.raw("default-claim-name").replace("<player>", player.getGameProfile().name());
            ClaimManager.Change change = manager.create(draft.world, player.getUUID(), name, draft.cells(), draft.minY(),
                    draft.maxY(), player.getUUID(), rt.oracle(), rt.bypassing(player));
            if (!change.ok()) {
                rt.send(player, change.check().problem().messageKey, Messages.problemPlaceholders(rt, change.check()));
                return;
            }
            rt.send(player, "claim-created", "claim", change.claim().name(), "cells", change.claim().size(),
                    "left", manager.budgetLeft(player.getUUID()));
        } else {
            Claim claim = manager.claim(draft.claimId);
            if (claim == null) {
                player.closeContainer();
                rt.send(player, "claim-gone");
                return;
            }
            Check result = manager.resize(claim, draft.cells(), draft.minY(), draft.maxY(), player.getUUID(), rt.oracle(),
                    rt.bypassing(player));
            if (!result.allowed()) {
                rt.send(player, result.problem().messageKey, Messages.problemPlaceholders(rt, result));
                return;
            }
            rt.send(player, "claim-resized", "claim", claim.name(), "cells", claim.size(),
                    "left", manager.budgetLeft(claim.owner()));
        }
        session.noBudgetShown = false;
        session.draft = null;
        player.closeContainer();
        rt.borders.show(player, 0);
    }

    // ------------------------------------------------------------------ trust

    static void trust(EstateRuntime rt, ServerPlayer player, Claim claim, int page) {
        Gui.open(player, rt.msg.get("trust-title", "claim", claim.name()), 6, (gui, p) -> {
            Msg msg = rt.msg;
            if (rt.manager.claim(claim.id()) == null) {
                p.closeContainer();
                return;
            }
            boolean owner = claim.owner().equals(p.getUUID()) || rt.bypassing(p);
            List<Map.Entry<UUID, TrustTier>> entries = new ArrayList<>(claim.trust().entrySet());
            entries.sort(Comparator.comparing(e -> rt.name(e.getKey()).toLowerCase(java.util.Locale.ROOT)));
            int start = page * 45;
            for (int i = start; i < Math.min(entries.size(), start + 45); i++) {
                UUID target = entries.get(i).getKey();
                TrustTier tier = entries.get(i).getValue();
                String name = rt.name(target);
                ItemStack head = head(target, msg.item("trust-entry", "player", name, "tier", tierName(rt, tier)),
                        msg.lore("trust-entry-lore", "tier", tierName(rt, tier), "allows", allows(rt, tier)));
                gui.set(i - start, head, (pl, c) -> {
                    if (!mayManage(rt, pl, claim, Permission.TRUST)) {
                        return;
                    }
                    if (tier == TrustTier.MANAGE && !owner) {
                        rt.warn(pl, "trust-owner-only");
                        return;
                    }
                    if (c.right()) {
                        rt.manager.trust(claim, target, null, pl.getUUID());
                        rt.send(pl, "untrusted", "player", name, "claim", claim.name());
                    } else {
                        TrustTier next = tier.next();
                        if (next == TrustTier.MANAGE && !owner) {
                            next = TrustTier.ACCESS;
                        }
                        rt.manager.trust(claim, target, next, pl.getUUID());
                    }
                    gui.redraw();
                });
            }
            gui.set(45, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
            if (page > 0) {
                gui.set(47, Gui.item(Items.ARROW, msg.item("previous-page"), List.of()), (pl, c) -> trust(rt, pl, claim, page - 1));
            }
            gui.set(49, Gui.item(Items.EMERALD, msg.item("trust-add"), msg.lore("trust-add-lore")),
                    (pl, c) -> addTrust(rt, pl, claim));
            if (entries.size() > start + 45) {
                gui.set(51, Gui.item(Items.ARROW, msg.item("next-page"), List.of()), (pl, c) -> trust(rt, pl, claim, page + 1));
            }
            gui.set(53, Gui.item(Items.BOOK, msg.item("trust-help"), msg.lore("trust-help-lore")));
        });
    }

    private static void addTrust(EstateRuntime rt, ServerPlayer player, Claim claim) {
        Gui.open(player, rt.msg.get("trust-add-title"), 6, (gui, p) -> {
            Msg msg = rt.msg;
            int slot = 0;
            for (ServerPlayer online : rt.server.getPlayerList().getPlayers()) {
                UUID target = online.getUUID();
                if (slot >= 45 || target.equals(claim.owner()) || claim.trust().containsKey(target)) {
                    continue;
                }
                String name = online.getGameProfile().name();
                gui.set(slot++, head(target, msg.item("trust-add-entry", "player", name), msg.lore("trust-add-entry-lore")),
                        (pl, c) -> {
                            if (!mayManage(rt, pl, claim, Permission.TRUST)) {
                                return;
                            }
                            rt.manager.ensurePlayer(target, name);
                            rt.manager.trust(claim, target, TrustTier.BUILD, pl.getUUID());
                            rt.send(pl, "trusted", "player", name, "tier", tierName(rt, TrustTier.BUILD), "claim", claim.name());
                            rt.notifyOwner(target, msg.get("trusted-you", "player", pl.getGameProfile().name(),
                                    "claim", claim.name(), "tier", tierName(rt, TrustTier.BUILD)));
                            trust(rt, pl, claim, 0);
                        });
            }
            if (slot == 0) {
                gui.set(22, Gui.item(Items.PAPER, msg.item("trust-add-nobody"), msg.lore("trust-add-nobody-lore")));
            }
            gui.set(45, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> trust(rt, pl, claim, 0));
        });
    }

    static String tierName(EstateRuntime rt, TrustTier tier) {
        return rt.msg.raw("tier-" + tier.key());
    }

    private static String allows(EstateRuntime rt, TrustTier tier) {
        List<String> names = new ArrayList<>();
        for (Permission permission : Permission.values()) {
            if (rt.config.tierAllows(tier, permission)) {
                names.add(rt.msg.raw("permission-" + permission.key));
            }
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------ flags

    static void flags(EstateRuntime rt, ServerPlayer player, Claim claim) {
        Gui.open(player, rt.msg.get("flags-title", "claim", claim.name()), 2, (gui, p) -> {
            Msg msg = rt.msg;
            boolean admin = rt.isAdmin(p);
            int slot = 0;
            for (Flag flag : Flag.values()) {
                boolean value = rt.manager.flag(claim, flag);
                boolean editable = admin || (rt.config.ownerMayEdit(flag) && claim.owner().equals(p.getUUID()));
                String state = msg.raw(flag == Flag.PVP ? (value ? "pvp-on" : "pvp-off") : (value ? "protected" : "unprotected"));
                ItemStack icon = Gui.item(FLAG_ICONS.get(flag), msg.item("flag-" + flag.key, "state", state),
                        msg.lore(editable ? "flag-lore-editable" : "flag-lore-locked", "state", state,
                                "about", msg.raw("flag-" + flag.key + "-about")));
                gui.set(slot++, icon, editable ? (pl, c) -> {
                    Boolean next = c.right() && admin ? null : !rt.manager.flag(claim, flag);
                    rt.manager.setFlag(claim, flag, next, pl.getUUID());
                    gui.redraw();
                } : null);
            }
            gui.set(17, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
        });
    }

    // ------------------------------------------------------------------ claim list and unclaim

    static void claimList(EstateRuntime rt, ServerPlayer player, UUID owner) {
        Gui.open(player, rt.msg.get("list-title", "player", rt.name(owner)), 6, (gui, p) -> {
            Msg msg = rt.msg;
            int cellSize = rt.manager.cellSize();
            List<Claim> claims = rt.manager.claimsOf(owner);
            for (int i = 0; i < Math.min(45, claims.size()); i++) {
                Claim claim = claims.get(i);
                int[] center = center(claim, cellSize);
                gui.set(i, Gui.item(Items.GRASS_BLOCK, msg.item("list-entry", "claim", claim.name()),
                        msg.lore("list-entry-lore", "id", claim.id(), "cells", claim.size(), "x", center[0], "z", center[1],
                                "miny", claim.minY(), "maxy", claim.maxY(), "trusted", claim.trust().size(),
                                "world", claim.world().replace("minecraft:", ""),
                                "exempt", msg.raw(claim.expiryExempt() ? "yes" : "no"))), (pl, c) -> {
                    pl.closeContainer();
                    rt.borders.show(pl, claim.id());
                    rt.send(pl, "list-shown", "claim", claim.name(), "x", center[0], "z", center[1]);
                });
            }
            if (claims.isEmpty()) {
                gui.set(22, Gui.item(Items.PAPER, msg.item("list-empty"), msg.lore("list-empty-lore")));
            }
            gui.set(45, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
        });
    }

    static int[] center(Claim claim, int cellSize) {
        long sx = 0;
        long sz = 0;
        for (long cell : claim.cells()) {
            sx += Cells.x(cell) * (long) cellSize + cellSize / 2;
            sz += Cells.z(cell) * (long) cellSize + cellSize / 2;
        }
        int n = Math.max(1, claim.size());
        return new int[]{(int) (sx / n), (int) (sz / n)};
    }

    static void unclaim(EstateRuntime rt, ServerPlayer player, Claim claim) {
        Gui.open(player, rt.msg.get("unclaim-title", "claim", claim.name()), 3, (gui, p) -> {
            Msg msg = rt.msg;
            gui.set(11, Gui.item(Items.STAINED_GLASS_PANE.lime(), msg.item("unclaim-yes", "claim", claim.name()),
                    msg.lore("unclaim-yes-lore", "cells", claim.share(claim.owner()))), (pl, c) -> {
                pl.closeContainer();
                LandCommand.doUnclaim(rt, pl, claim);
            });
            gui.set(15, Gui.item(Items.STAINED_GLASS_PANE.red(), msg.item("unclaim-no"), List.of()), (pl, c) -> main(rt, pl));
        });
    }

    // ------------------------------------------------------------------ admin settings

    static void adminSettings(EstateRuntime rt, ServerPlayer player, int page) {
        if (!rt.isAdmin(player)) {
            return;
        }
        Gui.open(player, rt.msg.get("settings-title"), 6, (gui, p) -> {
            Msg msg = rt.msg;
            List<String> names = EstateConfig.settingNames();
            int start = page * 45;
            for (int i = start; i < Math.min(names.size(), start + 45); i++) {
                String name = names.get(i);
                String value = rt.config.get(name);
                if (EstateConfig.isNumber(name)) {
                    EstateConfig.Range range = EstateConfig.range(name);
                    gui.set(i - start, Gui.item(Items.PAPER, msg.item("setting-name", "setting", name, "value", value),
                            msg.lore("setting-number-lore", "value", value,
                                    "min", range == null ? "?" : range.min(), "max", range == null ? "?" : range.max())),
                            (pl, c) -> {
                                int step = (c.shift() ? 10 : 1) * (c.right() ? -1 : 1);
                                changeSetting(rt, pl, name, String.valueOf(Integer.parseInt(rt.config.get(name)) + step));
                                gui.redraw();
                            });
                } else if (EstateConfig.isBoolean(name)) {
                    boolean on = Boolean.parseBoolean(value);
                    gui.set(i - start, Gui.item(on ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(),
                            msg.item("setting-name", "setting", name, "value", value), msg.lore("setting-boolean-lore")),
                            (pl, c) -> {
                                changeSetting(rt, pl, name, String.valueOf(!Boolean.parseBoolean(rt.config.get(name))));
                                gui.redraw();
                            });
                } else {
                    String shown = value.length() > 120 ? value.substring(0, 117) + "..." : value;
                    gui.set(i - start, Gui.item(Items.BOOK, msg.item("setting-name", "setting", name, "value", ""),
                            msg.lore("setting-list-lore", "value", shown, "setting", name)));
                }
            }
            gui.set(45, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
            if (page > 0) {
                gui.set(47, Gui.item(Items.ARROW, msg.item("previous-page"), List.of()), (pl, c) -> adminSettings(rt, pl, page - 1));
            }
            if (names.size() > start + 45) {
                gui.set(51, Gui.item(Items.ARROW, msg.item("next-page"), List.of()), (pl, c) -> adminSettings(rt, pl, page + 1));
            }
        });
    }

    /** Changes a setting live and saves it; shared by the settings menu and /land admin set. Null on success. */
    static String changeSetting(EstateRuntime rt, ServerPlayer actor, String name, String value) {
        String canonical = EstateConfig.canonicalName(name);
        if ("cellSize".equals(canonical) && rt.manager.hasClaims()) {
            String error = rt.msg.raw("cell-size-locked").replace("<size>", String.valueOf(rt.manager.cellSize()));
            if (actor != null) {
                actor.sendSystemMessage(Text.parse(error, Map.of()));
            }
            return error;
        }
        String old = canonical == null ? null : rt.config.get(canonical.contains(".") ? canonical.substring(0, canonical.indexOf('.')) : canonical);
        String error = rt.config.set(name, value);
        if (error == null) {
            String saveError = rt.saveConfig();
            rt.manager.log(new dev.cornybirdy.estate.core.Action(rt.manager.now(), actor == null ? null : actor.getUUID(),
                    "admin-set", null, null, null, null, null, canonical + ": " + old + " -> " + value));
            if (saveError != null) {
                error = rt.msg.raw("config-save-failed");
            }
        }
        if (error != null && actor != null) {
            actor.sendSystemMessage(Text.parse("<red>" + error, Map.of()));
        }
        return error;
    }

    // ------------------------------------------------------------------ items

    static ItemStack head(UUID player, Component name, List<Component> lore) {
        ItemStack head = Gui.item(Items.PLAYER_HEAD, name, lore);
        head.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(player));
        return head;
    }
}
