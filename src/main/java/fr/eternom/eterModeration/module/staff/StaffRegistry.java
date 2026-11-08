package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Le staff protégé (eter.mod.protected, donnée à tout le staff) : on ne peut ni le geler ni le sanctionner, sauf avec
 * eter.mod.sanction.staff (pour la sanction). Une permission ne se lit que sur un joueur connecté ICI ; pour savoir
 * qu'un joueur ailleurs ou hors ligne est du staff, chaque serveur note à la connexion qui l'est, dans Redis
 * (etermod:protected, uuid -> pseudo) : la liste suit les changements de grade à la connexion suivante.
 */
public class StaffRegistry implements Listener {

    public static final String PROTECTED = "eter.mod.protected";
    /** Sanctionner quand même un membre du staff protégé (superviseurs). */
    public static final String OVERRIDE = "eter.mod.sanction.staff";
    private static final String KEY = "etermod:protected";

    private final JavaPlugin plugin;
    private final RedisCache redis;

    public StaffRegistry(JavaPlugin plugin, RedisCache redis) {
        this.plugin = plugin;
        this.redis = redis;
    }

    /** Bloquant (Redis). */
    public boolean isProtected(UUID player) {
        return redis.getHashField(KEY, player.toString()).isPresent();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean staff = player.hasPermission(PROTECTED);
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        Tasks.async(plugin, () -> {
            if (staff) {
                redis.setHashField(KEY, uuid, name);
            } else {
                redis.deleteHashField(KEY, uuid);
            }
        }, "Staff protégé non noté pour " + name);
    }
}
