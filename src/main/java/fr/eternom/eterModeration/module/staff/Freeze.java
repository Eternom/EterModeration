package fr.eternom.eterModeration.module.staff;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.sanction.MuteGuard;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geler un joueur pour lui parler (soupçon de triche...) : il ne bouge plus (la tête seulement), ne casse ni ne pose,
 * ne frappe pas et ne prend pas de coups, et n'a plus que les messages privés comme commandes. Sur tout le réseau :
 * l'état est gardé dans Redis (etermod:frozen), et le serveur où est le joueur l'applique (bus réseau) ; il le
 * reste en changeant de serveur ou en se reconnectant. Une déconnexion pendant le gel est signalée au staff.
 * Permission : eter.mod.freeze. Un membre du staff (eter.mod.freeze.exempt, donné avec eter.mod.staff) ne peut pas être
 * gelé : refusé tout de suite s'il est ici, sinon son serveur annule le gel (et le staff est prévenu), et un vieux gel
 * est effacé à sa connexion.
 */
public class Freeze implements Listener {

    public static final String PERMISSION = "eter.mod.freeze";
    public static final String EXEMPT = "eter.mod.freeze.exempt";
    public static final String KEY = "etermod:frozen";
    private static final String CHANGED = "freeze";

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final NetworkBus bus;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final Set<String> privateCommands;
    /** Joueurs gelés connectés ici. */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public Freeze(JavaPlugin plugin, RedisCache redis, NetworkBus bus, StaffAlerts alerts, Messages messages, Set<String> privateCommands) {
        this.plugin = plugin;
        this.redis = redis;
        this.bus = bus;
        this.alerts = alerts;
        this.messages = messages;
        this.privateCommands = privateCommands;
        bus.on(CHANGED, data -> applyLocal(UUID.fromString(data.get("player").getAsString()), data.get("frozen").getAsBoolean(),
                data.has("staff") ? data.get("staff").getAsString() : "?"));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> frozen.forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                messages.actionBar(player, "freeze.bar");
            }
        }), 40, 40);
    }

    public boolean isFrozen(UUID player) {
        return frozen.contains(player);
    }

    /** Geler ou dégeler (connecté ici ou ailleurs). Thread principal. */
    public void toggle(Player staff, Target target) {
        if (!staff.hasPermission(PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        if (staff.getUniqueId().equals(target.uuid())) {
            messages.send(staff, "sanction.self");
            return;
        }
        // Un membre du staff connecté ici : refusé tout de suite (dégeler reste possible)
        Player local = Bukkit.getPlayer(target.uuid());
        if (local != null && local.hasPermission(EXEMPT) && !frozen.contains(target.uuid())) {
            messages.send(staff, "freeze.exempt", "player", target.name());
            return;
        }
        String uuid = target.uuid().toString();
        String staffName = staff.getName();
        Tasks.async(plugin, staff, () -> {
            boolean freeze = redis.getHashField(KEY, uuid).isEmpty();
            if (freeze) {
                redis.setHashField(KEY, uuid, staffName);
            } else {
                redis.deleteHashField(KEY, uuid);
            }
            return freeze;
        }, freeze -> {
            applyLocal(target.uuid(), freeze, staffName);
            publish(target.uuid(), freeze, staffName);
            messages.send(staff, freeze ? "freeze.done" : "freeze.undone", "player", target.name());
            alerts.alert(freeze ? "alert.frozen" : "alert.unfrozen", "staff", staffName, "player", target.name());
        }, () -> messages.send(staff, "error.generic"));
    }

    private void publish(UUID player, boolean freeze, String staffName) {
        JsonObject data = new JsonObject();
        data.addProperty("player", player.toString());
        data.addProperty("frozen", freeze);
        data.addProperty("staff", staffName);
        bus.publish(CHANGED, data);
    }

    /** Thread principal. Geler un membre du staff connecté ici (gel donné depuis un autre serveur) : annulé partout. */
    private void applyLocal(UUID uuid, boolean freeze, String staffName) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        if (freeze && player.hasPermission(EXEMPT)) {
            frozen.remove(uuid);
            Tasks.async(plugin, () -> redis.deleteHashField(KEY, uuid.toString()), "Gel de " + uuid + " non annulé");
            publish(uuid, false, staffName);
            alerts.alert("alert.freeze-exempt", "staff", staffName, "player", player.getName());
            return;
        }
        if (freeze && frozen.add(uuid)) {
            messages.send(player, "freeze.frozen");
        } else if (!freeze && frozen.remove(uuid)) {
            messages.send(player, "freeze.unfrozen");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        Tasks.async(plugin, player, () -> redis.getHashField(KEY, uuid), by -> by.ifPresent(staffName ->
                applyLocal(player.getUniqueId(), true, staffName)), () -> { });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (frozen.remove(event.getPlayer().getUniqueId())) {
            alerts.alert("alert.frozen-quit", "player", event.getPlayer().getName());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.contains(event.getPlayer().getUniqueId()) || !event.hasChangedPosition()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo().clone();
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        event.setTo(to);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId()) && !privateCommands.contains(MuteGuard.command(event.getMessage()))) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "freeze.no-command");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (frozen.contains(event.getEntity().getUniqueId())
                || (event instanceof EntityDamageByEntityEvent byEntity && frozen.contains(byEntity.getDamager().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
