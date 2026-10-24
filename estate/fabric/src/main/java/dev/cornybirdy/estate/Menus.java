package dev.cornybirdy.estate;

import dev.cornybirdy.estate.core.Check;
import dev.cornybirdy.estate.core.Claim;
import dev.cornybirdy.estate.core.ClaimDraft;
import dev.cornybirdy.estate.core.ClaimManager;
import dev.cornybirdy.estate.core.EstateConfig;
import dev.cornybirdy.estate.core.Flag;
import dev.cornybirdy.estate.core.Permission;
import dev.cornybirdy.estate.core.TrustTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The /land screens. Every action here also exists as a command. Layout rules for readability: one job per button,
 * the name says what it is, the first lore lines say what it does, and the last line (yellow) says what clicking does.
 */
final class Menus {

    private static final Map<Flag, Item> FLAG_ICONS = new EnumMap<>(Flag.class);

    static {
        FLAG_ICONS.put(Flag.BLOCK_BREAK_PLACE, Items.IRON_PICKAXE);
        FLAG_ICONS.put(Flag.CONTAINER_ACCESS, Items.CHEST);
        FLAG_ICONS.put(Flag.EXPLOSIONS, Items.TNT);
        FLAG_ICONS.put(Flag.FIRE_SPREAD, Items.FLINT_AND_STEEL);
        FLAG_ICONS.put(Flag.FLUID_FLOW_IN, Items.WATER_BUCKET);
        FLAG_ICONS.put(Flag.PISTON_CROSS, Items.PISTON);
        FLAG_ICONS.put(Flag.ENTITY_DAMAGE, Items.ARMOR_STAND);
    }

    /** Main menu slots. */
    static final int SLOT_INFO = 4;
    static final int SLOT_BORDERS = 10;
    static final int SLOT_CLAIM = 12;
    static final int SLOT_RESIZE = 14;
    static final int SLOT_UNCLAIM = 16;
    static final int SLOT_TRUST = 19;
    static final int SLOT_FLAGS = 21;
    static final int SLOT_AUTOCLAIM = 23;
    static final int SLOT_LIST = 25;
    static final int SLOT_HELP = 27;
    static final int SLOT_ADMIN = 35;

    /** Editor slots: corner 1 x/y/z, corner 2 x/y/z. */
    static final int[] SLOTS_CORNER1 = {10, 11, 12};
    static final int[] SLOTS_CORNER2 = {14, 15, 16};
    static final int SLOT_CONFIRM = 53;

    private Menus() {
    }

    // ------------------------------------------------------------------ main menu

    static void main(EstateRuntime rt, ServerPlayer player) {
        Gui.open(player, rt.msg.get("menu-title"), 4, (gui, p) -> {
            Msg msg = rt.msg;
            ClaimManager manager = rt.manager;
            UUID id = p.getUUID();
            Claim here = rt.claimAt(p.level(), p.blockPosition());
            String standing = here == null ? msg.raw("wilderness")
                    : msg.raw("claim-label").replace("<owner>", rt.name(here.owner())).replace("<claim>", here.name());
            boolean auto = manager.autoClaim(id);
            int used = manager.budgetUsed(id);
            int limit = manager.budgetLimit(id);

            gui.set(SLOT_INFO, head(id, msg.item("menu-info-name"), msg.lore("menu-info-lore",
                    "barused", "|".repeat(barSteps(used, limit)), "barfree", "|".repeat(BAR_STEPS - barSteps(used, limit)), "used", used, "limit", limit, "left", manager.budgetLeft(id),
                    "claims", manager.claimsOf(id).size(), "max", rt.config.maxClaimsPerPlayer,
                    "autoclaim", msg.raw(auto ? "on" : "off"), "here", standing)));
            gui.set(SLOT_BORDERS, Gui.item(Items.ENDER_EYE, msg.item("menu-borders"), msg.lore("menu-borders-lore")), (pl, c) -> {
                pl.closeContainer();
                rt.borders.show(pl, 0);
                rt.send(pl, "borders-shown", "seconds", rt.config.borderParticleSeconds);
            });
            gui.set(SLOT_CLAIM, Gui.item(Items.GOLDEN_SHOVEL, msg.item("menu-claim"), msg.lore("menu-claim-lore")), (pl, c) -> {
                pl.closeContainer();
                rt.claimTool.start(pl);
            });
            gui.set(SLOT_RESIZE, Gui.item(Items.PISTON, msg.item("menu-resize"), msg.lore("menu-resize-lore")), (pl, c) -> {
                Claim claim = target(rt, pl, Permission.RESIZE);
                if (claim != null) {
                    resize(rt, pl, claim);
                }
            });
            gui.set(SLOT_UNCLAIM, Gui.item(Items.BARRIER, msg.item("menu-unclaim"), msg.lore("menu-unclaim-lore")), (pl, c) -> {
                Claim claim = target(rt, pl, null);
                if (claim != null) {
                    unclaim(rt, pl, claim);
                }
            });
            gui.set(SLOT_TRUST, Gui.item(Items.PLAYER_HEAD, msg.item("menu-trust"), msg.lore("menu-trust-lore")), (pl, c) -> {
                Claim claim = target(rt, pl, Permission.TRUST);
                if (claim != null) {
                    trust(rt, pl, claim, 0);
                }
            });
            gui.set(SLOT_FLAGS, Gui.item(Items.SHIELD, msg.item("menu-flags"), msg.lore("menu-flags-lore")), (pl, c) -> {
                Claim claim = target(rt, pl, null);
                if (claim != null) {
                    flags(rt, pl, claim);
                }
            });
            ItemStack autoItem = Gui.item(auto ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(),
                    msg.item(auto ? "menu-autoclaim-on" : "menu-autoclaim-off"),
                    msg.lore(auto ? "menu-autoclaim-on-lore" : "menu-autoclaim-off-lore", "threshold", rt.config.autoClaimThreshold));
            gui.set(SLOT_AUTOCLAIM, auto ? Gui.glow(autoItem) : autoItem, (pl, c) -> {
                boolean now = !manager.autoClaim(pl.getUUID());
                manager.setAutoClaim(pl.getUUID(), now);
                rt.send(pl, now ? "autoclaim-on" : "autoclaim-off", "threshold", rt.config.autoClaimThreshold);
                gui.redraw();
            });
            gui.set(SLOT_LIST, Gui.item(Items.CHEST, msg.item("menu-list"), msg.lore("menu-list-lore",
                    "claims", manager.claimsOf(id).size())), (pl, c) -> claimList(rt, pl, pl.getUUID()));
            gui.set(SLOT_HELP, Gui.item(Items.BOOK, msg.item("menu-help"), msg.lore("menu-help-lore")), (pl, c) -> {
                pl.closeContainer();
                LandCommand.help(rt, pl.createCommandSourceStack());
            });
            if (rt.isAdmin(p)) {
                gui.set(SLOT_ADMIN, Gui.item(Items.COMMAND_BLOCK, msg.item("menu-admin"), msg.lore("menu-admin-lore")),
                        (pl, c) -> adminSettings(rt, pl, 0));
            }
        });
    }

    private static final int BAR_STEPS = 20;

    /** How much of the 20-step budget bar is used. */
    static int barSteps(int used, int limit) {
        return limit <= 0 ? BAR_STEPS : Math.min(BAR_STEPS, (int) Math.round(used * (double) BAR_STEPS / limit));
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

    // ------------------------------------------------------------------ editor (coordinates, like Litematica)

    static void resize(EstateRuntime rt, ServerPlayer player, Claim claim) {
        rt.claimTool.startResize(player, claim);
        editor(rt, player);
    }

    /** Edits the player's current draft: corner coordinates, preview, confirm. */
    static void editor(EstateRuntime rt, ServerPlayer player) {
        Session session = rt.session(player);
        ClaimDraft start = session.draft;
        if (start == null) {
            player.closeContainer();
            rt.send(player, "draft-none");
            return;
        }
        Claim existing = start.claimId == null ? null : rt.manager.claim(start.claimId);
        Component title = existing == null ? rt.msg.get("editor-title-new") : rt.msg.get("editor-title-resize", "claim", existing.name());
        Gui.open(player, title, 6, (gui, p) -> {
            Msg msg = rt.msg;
            ClaimDraft draft = rt.session(p).draft;
            if (draft == null) {
                p.closeContainer();
                return;
            }
            Check check = rt.claimTool.evaluate(p);
            boolean ok = check != null && check.allowed();
            String status = draft.tooBig() ? msg.raw("draft-too-big") : ok ? msg.raw("editor-ok")
                    : Text.parse(msg.raw(check.problem().messageKey), Msg.placeholders(Messages.problemPlaceholders(rt, check))).getString();

            gui.set(4, Gui.item(ok ? Items.STAINED_GLASS_PANE.lime() : Items.STAINED_GLASS_PANE.red(),
                    msg.item(ok ? "editor-info-ok" : "editor-info-bad"),
                    msg.lore("editor-info-lore", "width", draft.width(), "depth", draft.depth(),
                            "blocks", draft.tooBig() ? "?" : draft.region().area(), "miny", draft.minY(), "maxy", draft.maxY(),
                            "height", draft.maxY() - draft.minY() + 1,
                            "cost", check == null ? "?" : ClaimTool.signed(check.cost()),
                            "left", check == null ? "?" : check.budgetLeft() - check.cost(), "status", status)));

            for (int which = 1; which <= 2; which++) {
                int corner = which;
                int[] pos = draft.corner(which);
                int[] slots = which == 1 ? SLOTS_CORNER1 : SLOTS_CORNER2;
                gui.set(slots[0] - 1,
                        Gui.item(which == 1 ? Items.STAINED_GLASS_PANE.red() : Items.STAINED_GLASS_PANE.blue(),
                                msg.item("editor-corner-" + which), msg.lore("editor-corner-lore", "x", pos[0], "y", pos[1], "z", pos[2])));
                String[] axes = {"x", "y", "z"};
                for (int axis = 0; axis < 3; axis++) {
                    int a = axis;
                    int big = 10;
                    gui.set(slots[axis], Gui.item(Items.PAPER, msg.item("editor-axis", "axis", axes[axis].toUpperCase(Locale.ROOT), "value", pos[axis]),
                            msg.lore(axis == 1 ? "editor-axis-y-lore" : "editor-axis-xz-lore", "big", big, "corner", corner)), (pl, c) -> {
                        int step = (c.shift() ? big : 1) * (c.right() ? -1 : 1);
                        ClaimDraft d = rt.session(pl).draft;
                        if (d != null) {
                            d.move(corner, a, step);
                            rt.claimTool.changed(pl, false);
                        }
                        gui.redraw();
                    });
                }
                gui.set(which == 1 ? 19 : 24, Gui.item(Items.COMPASS, msg.item("editor-here", "corner", which),
                        msg.lore("editor-here-lore", "corner", which)), (pl, c) -> {
                    ClaimDraft d = rt.session(pl).draft;
                    if (d != null && d.mode == ClaimDraft.Mode.RESIZE) {
                        d.setCorner(corner, pl.getBlockX(), d.corner(corner)[1], pl.getBlockZ());
                        rt.claimTool.changed(pl, false);
                    } else {
                        rt.claimTool.pick(pl, corner, pl.blockPosition().below());
                    }
                    gui.redraw();
                });
                gui.set(which == 1 ? 20 : 25, Gui.item(Items.WRITABLE_BOOK, msg.item("editor-type", "corner", which),
                        msg.lore("editor-type-lore", "corner", which)), (pl, c) -> {
                    pl.closeContainer();
                    ClaimDraft d = rt.session(pl).draft;
                    int[] now = d == null ? pos : d.corner(corner);
                    String command = "/land pos" + corner + " " + now[0] + " " + now[1] + " " + now[2];
                    pl.sendSystemMessage(msg.get("editor-type-chat", "corner", corner).withStyle(style -> style
                            .withClickEvent(new ClickEvent.SuggestCommand(command))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))));
                });
            }
            gui.set(22, Gui.item(Items.MAP, msg.item("editor-facing", "facing", msg.raw("facing-" + facing(p))),
                    msg.lore("editor-facing-lore", "x", p.getBlockX(), "y", p.getBlockY(), "z", p.getBlockZ())));

            gui.set(45, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
            gui.set(47, Gui.item(Items.GOLDEN_SHOVEL, msg.item("editor-tool"), msg.lore("editor-tool-lore")), (pl, c) -> {
                pl.closeContainer();
                if (rt.claimTool.giveTool(pl)) {
                    rt.send(pl, "editor-tool-given");
                }
            });
            gui.set(49, Gui.item(Items.ENDER_EYE, msg.item("editor-preview"), msg.lore("editor-preview-lore")), (pl, c) -> {
                pl.closeContainer();
                rt.borders.show(pl, 0);
                rt.send(pl, "editor-preview-hint");
            });
            gui.set(51, Gui.item(Items.BARRIER, msg.item("editor-cancel"), List.of()), (pl, c) -> {
                pl.closeContainer();
                rt.claimTool.end(pl);
                rt.send(pl, "draft-cancelled");
            });
            ItemStack confirm = Gui.item(ok ? Items.EMERALD : Items.STAINED_GLASS_PANE.gray(),
                    msg.item(ok ? "editor-confirm" : "editor-confirm-blocked"), msg.lore("editor-confirm-lore", "status", status));
            gui.set(SLOT_CONFIRM, ok ? Gui.glow(confirm) : confirm, (pl, c) -> {
                if (rt.claimTool.confirm(pl)) {
                    pl.closeContainer();
                } else {
                    gui.redraw();
                }
            });
        });
        rt.borders.show(player, 0);
    }

    /** Which way the player looks, as a compass direction. */
    static String facing(ServerPlayer player) {
        float yaw = ((player.getYRot() % 360) + 360) % 360;
        if (yaw >= 45 && yaw < 135) {
            return "west";
        }
        if (yaw >= 135 && yaw < 225) {
            return "north";
        }
        if (yaw >= 225 && yaw < 315) {
            return "east";
        }
        return "south";
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
            entries.sort(Comparator.comparing(e -> rt.name(e.getKey()).toLowerCase(Locale.ROOT)));
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
            if (entries.isEmpty()) {
                gui.set(22, Gui.item(Items.PAPER, msg.item("trust-empty"), msg.lore("trust-empty-lore")));
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

    /** Flags sit in the middle row; slot 9 + flag index. */
    static final int FLAGS_FIRST_SLOT = 9;

    static void flags(EstateRuntime rt, ServerPlayer player, Claim claim) {
        Gui.open(player, rt.msg.get("flags-title", "claim", claim.name()), 3, (gui, p) -> {
            Msg msg = rt.msg;
            boolean admin = rt.isAdmin(p);
            int slot = FLAGS_FIRST_SLOT;
            for (Flag flag : Flag.values()) {
                boolean value = rt.manager.flag(claim, flag);
                boolean editable = admin || (rt.config.ownerMayEdit(flag) && claim.owner().equals(p.getUUID()));
                String state = msg.raw(value ? "protected" : "unprotected");
                ItemStack icon = Gui.item(FLAG_ICONS.get(flag), msg.item("flag-" + flag.key, "state", state),
                        msg.lore(editable ? "flag-lore-editable" : "flag-lore-locked", "state", state,
                                "about", msg.raw("flag-" + flag.key + "-about")));
                gui.set(slot++, icon, editable ? (pl, c) -> {
                    Boolean next = c.right() && admin ? null : !rt.manager.flag(claim, flag);
                    rt.manager.setFlag(claim, flag, next, pl.getUUID());
                    gui.redraw();
                } : null);
            }
            gui.set(22, Gui.item(Items.SPECTRAL_ARROW, msg.item("back"), List.of()), (pl, c) -> main(rt, pl));
        });
    }

    // ------------------------------------------------------------------ claim list and unclaim

    static void claimList(EstateRuntime rt, ServerPlayer player, UUID owner) {
        Gui.open(player, rt.msg.get("list-title", "player", rt.name(owner)), 6, (gui, p) -> {
            Msg msg = rt.msg;
            List<Claim> claims = rt.manager.claimsOf(owner);
            for (int i = 0; i < Math.min(45, claims.size()); i++) {
                Claim claim = claims.get(i);
                int[] center = claim.region().middle();
                gui.set(i, Gui.item(Items.GRASS_BLOCK, msg.item("list-entry", "claim", claim.name()),
                        msg.lore("list-entry-lore", "id", claim.id(), "blocks", claim.size(), "x", center[0], "z", center[1],
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

    static void unclaim(EstateRuntime rt, ServerPlayer player, Claim claim) {
        Gui.open(player, rt.msg.get("unclaim-title", "claim", claim.name()), 3, (gui, p) -> {
            Msg msg = rt.msg;
            gui.set(11, Gui.item(Items.STAINED_GLASS_PANE.lime(), msg.item("unclaim-yes", "claim", claim.name()),
                    msg.lore("unclaim-yes-lore", "blocks", claim.share(claim.owner()))), (pl, c) -> {
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
