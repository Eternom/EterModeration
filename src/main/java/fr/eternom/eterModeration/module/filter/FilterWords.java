package fr.eternom.eterModeration.module.filter;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mots interdits ajoutés en jeu (/mod filter add|remove|list), en plus de filter.words de config.yml : dans Redis
 * (hash mot -> membre du staff qui l'a ajouté), communs à tous les serveurs, pris en compte tout de suite partout
 * (bus réseau), sans reload ni redémarrage. Appels bloquants hors du thread principal.
 */
public class FilterWords {

    private static final String KEY = "etermoderation:filter-words";
    private static final String CHANGED = "filter-words";

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final NetworkBus bus;
    private final ChatFilter filter;

    public FilterWords(JavaPlugin plugin, RedisCache redis, NetworkBus bus, ChatFilter filter) {
        this.plugin = plugin;
        this.redis = redis;
        this.bus = bus;
        this.filter = filter;
    }

    public void start() {
        bus.on(CHANGED, data -> reload());
        reload();
    }

    private void reload() {
        Tasks.async(plugin, () -> filter.setExtraWords(redis.getHash(KEY).keySet()), "Mots interdits du réseau non relus");
    }

    /** Bloquant. Le mot (en minuscules) est ajouté ; false s'il y était déjà. */
    public boolean add(String word, String staff) {
        String key = word.toLowerCase(Locale.ROOT).strip();
        if (redis.getHashField(KEY, key).isPresent()) {
            return false;
        }
        redis.setHashField(KEY, key, staff);
        changed();
        return true;
    }

    /** Bloquant. false si le mot n'avait pas été ajouté en jeu (ceux de config.yml ne se retirent pas ici). */
    public boolean remove(String word) {
        String key = word.toLowerCase(Locale.ROOT).strip();
        if (redis.getHashField(KEY, key).isEmpty()) {
            return false;
        }
        redis.deleteHashField(KEY, key);
        changed();
        return true;
    }

    /** Bloquant : mot -> qui l'a ajouté, triés. */
    public Map<String, String> list() {
        return new TreeMap<>(redis.getHash(KEY));
    }

    private void changed() {
        filter.setExtraWords(redis.getHash(KEY).keySet());
        bus.publish(CHANGED, new JsonObject());
    }
}
