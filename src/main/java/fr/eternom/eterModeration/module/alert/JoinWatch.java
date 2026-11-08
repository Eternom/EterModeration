package fr.eternom.eterModeration.module.alert;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.record.Records.Link;
import fr.eternom.eterModeration.module.sanction.SanctionRepository;
import fr.eternom.eterModeration.module.sanction.SanctionType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * À l'arrivée d'un joueur sur le réseau (pas à chaque changement de serveur : seulement s'il n'était connecté nulle part
 * depuis 2 minutes) :
 * - surveillé (liste « à surveiller » de la fiche, Redis etermod:watch, uuid -> « staff;raison ») : le staff est alerté ;
 * - un de ses comptes liés est banni en ce moment : le staff est alerté (contournement possible), avec le compte banni.
 */
public class JoinWatch implements Listener {

    public static final String WATCH_KEY = "etermod:watch";
    private static final String SEEN_KEY = "etermod:seen:";
    private static final java.time.Duration SEEN_TTL = java.time.Duration.ofMinutes(2);

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Records records;
    private final SanctionRepository sanctions;
    private final StaffAlerts alerts;

    public JoinWatch(JavaPlugin plugin, RedisCache redis, Records records, SanctionRepository sanctions, StaffAlerts alerts) {
        this.plugin = plugin;
        this.redis = redis;
        this.records = records;
        this.sanctions = sanctions;
        this.alerts = alerts;
        // Toutes les minutes : les joueurs d'ici sont toujours sur le réseau (la marque expire 2 min après leur départ)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
            Tasks.async(plugin, () -> online.forEach(uuid -> redis.set(SEEN_KEY + uuid, "1", SEEN_TTL)), "Présence non rafraîchie");
        }, 20L * 60, 20L * 60);
    }

    /** Bloquant : surveillé, et par qui / pourquoi. */
    public Optional<String> watch(UUID player) {
        return redis.getHashField(WATCH_KEY, player.toString());
    }

    /** Bloquant : met sous surveillance (reason) ou l'en retire ; rend true s'il est maintenant surveillé. */
    public boolean toggle(UUID player, String staffName, String reason) {
        if (watch(player).isPresent()) {
            redis.deleteHashField(WATCH_KEY, player.toString());
            return false;
        }
        redis.setHashField(WATCH_KEY, player.toString(), staffName + ";" + reason.replace(";", ","));
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Tasks.async(plugin, () -> {
            // Un changement de serveur n'est pas une arrivée : la marque dure le temps d'un passage d'un serveur à l'autre
            boolean arriving = redis.setIfAbsent(SEEN_KEY + uuid, "1", SEEN_TTL);
            if (!arriving) {
                return;
            }
            Optional<String> watched = watch(uuid);
            List<String> bannedAlts = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Link link : records.links(uuid)) {
                if (sanctions.active(link.player(), now).stream().anyMatch(sanction -> sanction.type() == SanctionType.BAN)) {
                    bannedAlts.add(link.name());
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                watched.ifPresent(value -> {
                    String[] parts = value.split(";", 2);
                    alerts.alert("alert.watch-join", "player", name, "staff", parts[0], "reason", parts.length > 1 ? parts[1] : "");
                });
                if (!bannedAlts.isEmpty()) {
                    alerts.alert("alert.alt-of-banned", "player", name, "alts", String.join(", ", bannedAlts));
                }
            });
        }, "Surveillance de l'arrivée de " + name + " non faite");
    }
}
