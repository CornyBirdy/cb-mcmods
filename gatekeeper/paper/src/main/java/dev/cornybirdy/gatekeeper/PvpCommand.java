package dev.cornybirdy.gatekeeper;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Everyone (gatekeeper.pvp):
 * <pre>
 * /pvp [on|off|toggle]          turn your own PvP on or off
 * /pvp allow|unallow (player)   fight someone even with PvP off, once they allow you too
 * /pvp list                     who you can fight with PvP off
 * </pre>
 */
final class PvpCommand implements BasicCommand {

    private final Gatekeeper plugin;
    private final Pvp pvp;

    PvpCommand(Gatekeeper plugin, Pvp pvp) {
        this.plugin = plugin;
        this.pvp = pvp;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(plugin.message("pvp-players-only"));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on", "enable", "true" -> set(player, true);
            case "off", "disable", "false" -> set(player, false);
            case "toggle" -> set(player, !pvp.enabled(player));
            case "allow", "unallow" -> {
                if (args.length < 2) {
                    status(player);
                } else {
                    allow(player, args[1], sub.equals("allow"));
                }
            }
            case "list" -> list(player);
            default -> status(player);
        }
    }

    private void status(Player player) {
        player.sendMessage(plugin.message("pvp-status", pvp.enabled(player)));
        Boolean lock = pvp.forced(player.getUniqueId());
        if (lock != null) {
            player.sendMessage(plugin.message("pvp-forced", lock));
        }
    }

    private void set(Player player, boolean on) {
        Pvp.Result result = pvp.set(player, on);
        var seconds = Placeholder.unparsed("seconds", String.valueOf(result.secondsLeft()));
        switch (result.outcome()) {
            case CHANGED -> player.sendMessage(plugin.message(on ? "pvp-now-on" : "pvp-now-off"));
            case UNCHANGED -> player.sendMessage(plugin.message("pvp-already", on));
            case COOLDOWN -> player.sendMessage(plugin.message("pvp-cooldown", seconds));
            case IN_COMBAT -> player.sendMessage(plugin.message("pvp-in-combat", seconds));
            case FORCED -> player.sendMessage(plugin.message("pvp-forced", pvp.enabled(player)));
        }
    }

    /** Both players have to allow each other before it counts. */
    private void allow(Player player, String name, boolean allow) {
        OfflinePlayer other = find(plugin, name);
        TagResolver who = Placeholder.unparsed("player", other == null || other.getName() == null ? name : other.getName());
        if (other == null) {
            player.sendMessage(plugin.message("pvp-unknown-player", who));
            return;
        }
        UUID me = player.getUniqueId();
        if (other.getUniqueId().equals(me)) {
            player.sendMessage(plugin.message("pvp-allow-self"));
            return;
        }
        Player otherOnline = other.getPlayer();
        TagResolver byMe = Placeholder.unparsed("player", player.getName());
        if (allow) {
            if (!pvp.setAllowed(me, other.getUniqueId(), true)) {
                player.sendMessage(plugin.message("pvp-already-allowed", who));
            } else if (pvp.mutual(me, other.getUniqueId())) {
                player.sendMessage(plugin.message("pvp-allowed-mutual", who));
                if (otherOnline != null) {
                    otherOnline.sendMessage(plugin.message("pvp-allowed-mutual", byMe));
                }
            } else {
                player.sendMessage(plugin.message("pvp-allowed", who));
                if (otherOnline != null) {
                    otherOnline.sendMessage(plugin.message("pvp-allow-request", byMe));
                }
            }
            return;
        }
        boolean wasMutual = pvp.mutual(me, other.getUniqueId());
        if (wasMutual && pvp.inCombat(me)) {
            player.sendMessage(plugin.message("pvp-in-combat",
                    Placeholder.unparsed("seconds", String.valueOf(pvp.combatSecondsLeft(me)))));
            return;
        }
        if (!pvp.setAllowed(me, other.getUniqueId(), false)) {
            player.sendMessage(plugin.message("pvp-not-allowed", who));
            return;
        }
        player.sendMessage(plugin.message("pvp-unallowed", who));
        if (wasMutual && otherOnline != null) {
            otherOnline.sendMessage(plugin.message("pvp-unallowed-other", byMe));
        }
    }

    private void list(Player player) {
        UUID me = player.getUniqueId();
        Set<UUID> mine = pvp.allowedBy(me);
        Set<UUID> theirs = pvp.allowing(me);
        if (mine.isEmpty() && theirs.isEmpty()) {
            player.sendMessage(plugin.message("pvp-list-empty"));
            return;
        }
        List<String> mutual = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        for (UUID id : mine) {
            (theirs.contains(id) ? mutual : waiting).add(name(plugin, id));
        }
        for (UUID id : theirs) {
            if (!mine.contains(id)) {
                requests.add(name(plugin, id));
            }
        }
        String nobody = plugin.getConfig().getString("messages.nobody", "nobody");
        player.sendMessage(plugin.message("pvp-list",
                Placeholder.unparsed("mutual", mutual.isEmpty() ? nobody : String.join(", ", mutual)),
                Placeholder.unparsed("waiting", waiting.isEmpty() ? nobody : String.join(", ", waiting)),
                Placeholder.unparsed("requests", requests.isEmpty() ? nobody : String.join(", ", requests))));
    }

    /** A player by name: online, or anyone the server has seen. */
    static OfflinePlayer find(Gatekeeper plugin, String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        return online != null ? online : plugin.getServer().getOfflinePlayerIfCached(name);
    }

    static String name(Gatekeeper plugin, UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return List.of("on", "off", "toggle", "allow", "unallow", "list").stream().filter(o -> o.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("allow") || args[0].equalsIgnoreCase("unallow"))) {
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "gatekeeper.pvp";
    }
}
