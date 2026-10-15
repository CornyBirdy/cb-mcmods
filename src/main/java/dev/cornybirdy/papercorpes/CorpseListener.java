package dev.cornybirdy.papercorpes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class CorpseListener implements Listener {

    private final PaperCorpes plugin;
    private final CorpseManager manager;

    CorpseListener(PaperCorpes plugin, CorpseManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops.stream().allMatch(ItemCodec::isEmpty)) {
            return;
        }

        Player player = event.getEntity();
        Location spot = CorpseVisuals.safeLocation(player.getLocation());
        List<ItemStack> overflow = new ArrayList<>();
        ItemStack[] contents = CorpseLayout.fromDeath(player.getInventory().getContents(), drops, overflow);
        drops.clear();
        drops.addAll(overflow);

        int xp = 0;
        if (plugin.settings().storeXp()) {
            xp = event.getDroppedExp();
            event.setDroppedExp(0);
        }

        Component deathMessage = event.deathMessage();
        String cause = deathMessage == null ? "" : PlainTextComponentSerializer.plainText().serialize(deathMessage);
        manager.create(player, spot, contents, xp, cause);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        UUID id = manager.visuals().corpseId(clicked);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Corpse corpse = manager.get(id);
        if (corpse == null) {
            clicked.remove();
            return;
        }
        Player player = event.getPlayer();
        boolean allowed = plugin.settings().allowAnyoneLoot()
                || player.getUniqueId().equals(corpse.ownerId())
                || player.hasPermission("papercorpes.admin");
        if (!allowed) {
            player.sendMessage(plugin.message("not-yours"));
            return;
        }
        manager.open(player, corpse);
    }

    /** Corpse bodies are invulnerable, but creative players and /damage can still hit them. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (manager.visuals().corpseId(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            manager.entityLoaded(entity);
        }
    }
}
