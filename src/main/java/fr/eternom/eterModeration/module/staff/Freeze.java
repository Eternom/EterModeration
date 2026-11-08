package fr.eternom.eterModeration.module.staff;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterLib.module.teleport.Destination;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.sanction.Motives;
import fr.eternom.eterModeration.module.sanction.MuteGuard;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.Target;
import fr.eternom.eterModeration.module.staff.StaffLog.Action;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geler un joueur pour lui parler (soupçon de triche...) : il ne bouge plus (la tête seulement), ne monte dans aucun
 * véhicule, ne vole pas en élytres, ne se téléporte pas lui-même (perle, fruit...), ne casse ni ne pose, ne frappe pas
 * et ne prend pas de coups, et n'a plus que les messages privés comme commandes. Son chat ne va qu'au staff (un canal à
 * part, gardé dans le journal du chat). Sur tout le réseau : l'état est gardé dans Redis (etermod:frozen, uuid ->
 * « staff;depuis »), et le serveur où est le joueur l'applique (bus réseau) ; il le reste en changeant de serveur ou en
 * se reconnectant.
 * - Le staff protégé (StaffRegistry) ne peut pas être gelé ; un ancien gel est effacé à sa connexion.
 * - Un gel prend fin seul au bout de freeze.max-minutes (un oubli du staff ne bloque pas un joueur pour toujours).
 * - Quitter le réseau pendant un gel : le staff est alerté, et la sanction freeze.quit-motive est donnée.
 * - Chaque gel et dégel est noté dans les notes du joueur et dans le journal du staff.
 * Permission : eter.mod.freeze.
 */
public class Freeze implements Listener {

    public static final String PERMISSION = "eter.mod.freeze";
    public static final String KEY = "etermod:frozen";
    private static final String CHANGED = "freeze";

    /** Qui a gelé le joueur, et depuis quand. */
    public record Info(String staffName, long since) {

        static Info parse(String value) {
            int split = value.lastIndexOf(';');
            if (split < 0) {
                return new Info(value, System.currentTimeMillis());
            }
            try {
                return new Info(value.substring(0, split), Long.parseLong(value.substring(split + 1)));
            } catch (NumberFormatException e) {
                return new Info(value.substring(0, split), System.currentTimeMillis());
            }
        }

        String value() {
            return staffName + ";" + since;
        }
    }

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final NetworkBus bus;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final Set<String> privateCommands;
    private final StaffRegistry registry;
    private final Records records;
    private final StaffLog staffLog;
    private final PlayerDirectory players;
    private final SanctionService sanctions;
    private final Motives motives;
    private final long maxMillis;
    private final boolean teleportStaff;
    private final String quitMotive;
    private final long quitGraceTicks;
    /** Joueurs gelés connectés ici, et leur gel. */
    private final Map<UUID, Info> frozen = new ConcurrentHashMap<>();

    public Freeze(JavaPlugin plugin, RedisCache redis, NetworkBus bus, StaffAlerts alerts, Messages messages, Set<String> privateCommands,
                  StaffRegistry registry, Records records, StaffLog staffLog, PlayerDirectory players, SanctionService sanctions,
                  Motives motives, ConfigurationSection config) {
        this.plugin = plugin;
        this.redis = redis;
        this.bus = bus;
        this.alerts = alerts;
        this.messages = messages;
        this.privateCommands = privateCommands;
        this.registry = registry;
        this.records = records;
        this.staffLog = staffLog;
        this.players = players;
        this.sanctions = sanctions;
        this.motives = motives;
        this.maxMillis = Math.max(0, config == null ? 30 : config.getInt("max-minutes", 30)) * 60_000L;
        this.teleportStaff = config == null || config.getBoolean("teleport-staff", true);
        this.quitMotive = config == null ? "" : config.getString("quit-motive", "");
        this.quitGraceTicks = 20L * Math.max(5, config == null ? 15 : config.getInt("quit-grace-seconds", 15));
        bus.on(CHANGED, data -> {
            UUID uuid = UUID.fromString(data.get("player").getAsString());
            String staff = data.has("staff") ? data.get("staff").getAsString() : "?";
            long since = data.has("since") ? data.get("since").getAsLong() : System.currentTimeMillis();
            applyLocal(uuid, data.get("frozen").getAsBoolean(), new Info(staff, since), false);
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40, 40);
    }

    public boolean isFrozen(UUID player) {
        return frozen.containsKey(player);
    }

    /** Bloquant (Redis) : son gel en cours, où qu'il soit. */
    public Optional<Info> info(UUID player) {
        return redis.getHashField(KEY, player.toString()).map(Info::parse);
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
        String uuid = target.uuid().toString();
        String staffName = staff.getName();
        Info info = new Info(staffName, System.currentTimeMillis());
        // 1 = gelé, 0 = dégelé, -1 = refusé (staff protégé)
        Tasks.async(plugin, staff, () -> {
            boolean freeze = redis.getHashField(KEY, uuid).isEmpty();
            if (freeze && registry.isProtected(target.uuid())) {
                return -1;
            }
            if (freeze) {
                redis.setHashField(KEY, uuid, info.value());
            } else {
                redis.deleteHashField(KEY, uuid);
            }
            records.addNote(target.uuid(), staffName, messages.plain(staff, freeze ? "freeze.note-frozen" : "freeze.note-unfrozen"));
            return freeze ? 1 : 0;
        }, result -> {
            if (result < 0) {
                messages.send(staff, "freeze.exempt", "player", target.name());
                return;
            }
            boolean freeze = result == 1;
            applyLocal(target.uuid(), freeze, info, false);
            publish(target.uuid(), freeze, info);
            staffLog.log(staffName, freeze ? Action.FREEZE : Action.UNFREEZE, target.name());
            messages.send(staff, freeze ? "freeze.done" : "freeze.undone", "player", target.name());
            alerts.alert(freeze ? "alert.frozen" : "alert.unfrozen", "staff", staffName, "player", target.name());
            if (freeze && teleportStaff) {
                goTo(staff, target);
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Le staff qui gèle va près du joueur, où qu'il soit (pour lui parler). */
    private void goTo(Player staff, Target target) {
        Player local = Bukkit.getPlayer(target.uuid());
        if (local != null) {
            if (staff.getLocation().getWorld() != local.getWorld() || staff.getLocation().distanceSquared(local.getLocation()) > 100) {
                staff.teleport(local.getLocation());
            }
            return;
        }
        Tasks.async(plugin, staff, () -> players.getServer(target.uuid()), server -> server.ifPresent(name ->
                EterLib.get().getTeleports().teleportNow(staff, Destination.toPlayer(target.uuid(), name, target.name()))), () -> { });
    }

    private void publish(UUID player, boolean freeze, Info info) {
        JsonObject data = new JsonObject();
        data.addProperty("player", player.toString());
        data.addProperty("frozen", freeze);
        data.addProperty("staff", info.staffName());
        data.addProperty("since", info.since());
        bus.publish(CHANGED, data);
    }

    /**
     * Thread principal. Un membre du staff (gelé depuis un autre serveur, ou un vieux gel retrouvé à sa connexion,
     * fromJoin) n'est jamais gelé : le gel est annulé partout.
     */
    private void applyLocal(UUID uuid, boolean freeze, Info info, boolean fromJoin) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        if (freeze && player.hasPermission(StaffRegistry.PROTECTED)) {
            frozen.remove(uuid);
            Tasks.async(plugin, () -> redis.deleteHashField(KEY, uuid.toString()), "Gel de " + uuid + " non annulé");
            publish(uuid, false, info);
            if (!fromJoin) {
                alerts.alert("alert.freeze-exempt", "staff", info.staffName(), "player", player.getName());
            }
            return;
        }
        if (freeze && frozen.putIfAbsent(uuid, info) == null) {
            player.leaveVehicle();
            player.setGliding(false);
            messages.send(player, "freeze.frozen");
            player.showTitle(Title.title(messages.get(player, "freeze.title"), messages.get(player, "freeze.subtitle")));
        } else if (!freeze && frozen.remove(uuid) != null) {
            messages.send(player, "freeze.unfrozen");
            player.clearTitle();
        }
    }

    /** Toutes les 2 s : la barre d'action, et la fin automatique d'un gel trop long. */
    private void tick() {
        long now = System.currentTimeMillis();
        frozen.forEach((uuid, info) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                return;
            }
            if (maxMillis > 0 && now - info.since() > maxMillis) {
                frozen.remove(uuid);
                messages.send(player, "freeze.unfrozen");
                Tasks.async(plugin, () -> redis.deleteHashField(KEY, uuid.toString()), "Gel de " + uuid + " non levé");
                publish(uuid, false, info);
                alerts.alert("alert.freeze-expired", "player", player.getName(), "staff", info.staffName(),
                        "minutes", String.valueOf(maxMillis / 60_000));
                return;
            }
            messages.actionBar(player, "freeze.bar");
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        Tasks.async(plugin, player, () -> redis.getHashField(KEY, uuid), value -> value.ifPresent(found ->
                applyLocal(player.getUniqueId(), true, Info.parse(found), true)), () -> { });
    }

    /**
     * Départ pendant un gel : un changement de serveur est normal ; s'il n'est plus sur le réseau après
     * freeze.quit-grace-seconds (et toujours gelé), le staff est alerté et freeze.quit-motive donné. Pas pendant l'arrêt
     * du serveur (tout le monde part).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Info info = frozen.remove(player.getUniqueId());
        if (info == null || Bukkit.isStopping()) {
            return;
        }
        Target target = new Target(player.getUniqueId(), player.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> Tasks.async(plugin, () -> {
            boolean left = players.getServer(target.uuid()).isEmpty() && redis.getHashField(KEY, target.uuid().toString()).isPresent();
            if (!left) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                alerts.alert("alert.frozen-quit", "player", target.name());
                motives.get(quitMotive).ifPresent(motive -> sanctions.giveAuto(target, motive,
                        messages.plain(Bukkit.getConsoleSender(), "freeze.quit-reason"), info.staffName()));
            });
        }, "Départ pendant un gel non vérifié"), quitGraceTicks);
    }

    /** Le chat d'un joueur gelé ne va qu'au staff (alertes), et reste dans le journal du chat. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!frozen.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            messages.send(player, "freeze.chat-sent", "message", message);
            alerts.alert("alert.frozen-chat", "player", player.getName(), "message", message);
        });
        Tasks.async(plugin, () -> records.logChat(player.getUniqueId(), EterLib.get().getServerName(), "[gel] " + message),
                "Message d'un joueur gelé non gardé");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!frozen.containsKey(event.getPlayer().getUniqueId()) || !event.hasChangedPosition()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo().clone();
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        event.setTo(to);
    }

    /** Pas de téléportation de son fait (perle, fruit de chorus, portail...) ; celle du staff (commande, plugin) passe. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())
                && event.getCause() != TeleportCause.COMMAND && event.getCause() != TeleportCause.PLUGIN) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent event) {
        if (frozen.containsKey(event.getEntered().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && frozen.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId()) && !privateCommands.contains(MuteGuard.command(event.getMessage()))) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "freeze.no-command");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (frozen.containsKey(event.getEntity().getUniqueId())
                || (event instanceof EntityDamageByEntityEvent byEntity && frozen.containsKey(byEntity.getDamager().getUniqueId()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
