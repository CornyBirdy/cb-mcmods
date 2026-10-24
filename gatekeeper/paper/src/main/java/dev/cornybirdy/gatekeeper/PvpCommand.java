package dev.cornybirdy.gatekeeper;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** {@code /pvp [on|off|toggle]}: players turn their own PvP on or off (gatekeeper.pvp, everyone has it). */
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
        if (args.length == 0) {
            player.sendMessage(plugin.message("pvp-status", pvp.enabled(player)));
            return;
        }
        boolean on;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "enable", "true" -> on = true;
            case "off", "disable", "false" -> on = false;
            case "toggle" -> on = !pvp.enabled(player);
            default -> {
                player.sendMessage(plugin.message("pvp-status", pvp.enabled(player)));
                return;
            }
        }
        Pvp.Result result = pvp.set(player, on);
        var seconds = Placeholder.unparsed("seconds", String.valueOf(result.secondsLeft()));
        switch (result.outcome()) {
            case CHANGED -> player.sendMessage(plugin.message(on ? "pvp-now-on" : "pvp-now-off"));
            case UNCHANGED -> player.sendMessage(plugin.message("pvp-already", on));
            case COOLDOWN -> player.sendMessage(plugin.message("pvp-cooldown", seconds));
            case IN_COMBAT -> player.sendMessage(plugin.message("pvp-in-combat", seconds));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return args.length <= 1 ? List.of("on", "off", "toggle").stream().filter(o -> o.startsWith(prefix)).toList() : List.of();
    }

    @Override
    public String permission() {
        return "gatekeeper.pvp";
    }
}
