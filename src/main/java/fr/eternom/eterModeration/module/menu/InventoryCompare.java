package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.sanction.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repérer une duplication : à la première connexion de chaque jour, le contenu du joueur (inventaire et coffre de
 * l'Ender, par matière) est photographié dans Redis (etermod:invsnap:<uuid>, un champ par jour, 4 jours). La fiche
 * compare son contenu d'aujourd'hui (en direct s'il est ici, sinon sa dernière sauvegarde EterSync) avec la photo la plus
 * récente d'au moins un jour : les plus grosses hausses et baisses, dans le chat du staff.
 */
public class InventoryCompare implements Listener {

    private static final String KEY = "etermod:invsnap:";
    private static final Duration TTL = Duration.ofDays(4);
    /** Après l'arrivée : laisser EterSync rendre l'inventaire. */
    private static final long JOIN_DELAY_TICKS = 20L * 10;
    private static final int LINES = 10;

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;

    public InventoryCompare(JavaPlugin plugin, RedisCache redis, Messages messages) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            String counts = serialize(counts(player.getInventory().getContents(), player.getEnderChest().getContents()));
            String key = KEY + player.getUniqueId();
            String day = String.valueOf(today());
            Tasks.async(plugin, () -> {
                if (redis.getHashField(key, day).isEmpty()) {
                    redis.setHashField(key, day, counts);
                    redis.expire(key, TTL);
                }
            }, "Photo de l'inventaire non prise");
        }, JOIN_DELAY_TICKS);
    }

    /** Thread principal : la comparaison, dans le chat du staff. */
    public void compare(Player staff, Target target) {
        Player local = Bukkit.getPlayer(target.uuid());
        Map<Material, Integer> live = local == null ? null
                : counts(local.getInventory().getContents(), local.getEnderChest().getContents());
        Tasks.async(plugin, staff, () -> {
            Map<Material, Integer> now = live != null ? live : counts(Integrations.lastInventory(target).orElse(new ItemStack[0]),
                    Integrations.lastEnderChest(target).orElse(new ItemStack[0]));
            long today = today();
            Optional<Map.Entry<String, String>> before = redis.getHash(KEY + target.uuid()).entrySet().stream()
                    .filter(entry -> Long.parseLong(entry.getKey()) <= today - 1)
                    .max(Comparator.comparingLong(entry -> Long.parseLong(entry.getKey())));
            return before.map(entry -> new Object[]{today - Long.parseLong(entry.getKey()), diff(parse(entry.getValue()), now)});
        }, result -> {
            if (result.isEmpty()) {
                messages.send(staff, "compare.none", "player", target.name());
                return;
            }
            long days = (long) result.get()[0];
            @SuppressWarnings("unchecked")
            List<Map.Entry<Material, Integer>> changes = (List<Map.Entry<Material, Integer>>) result.get()[1];
            messages.send(staff, "compare.header", "player", target.name(), "days", String.valueOf(days));
            if (changes.isEmpty()) {
                messages.send(staff, "compare.same");
            }
            for (Map.Entry<Material, Integer> change : changes) {
                staff.sendMessage(messages.get(staff, change.getValue() > 0 ? "compare.more" : "compare.less",
                        Placeholder.component("item", Component.translatable(change.getKey())),
                        "amount", String.valueOf(Math.abs(change.getValue()))));
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Les plus grosses différences (en valeur absolue), LINES au plus. */
    private static List<Map.Entry<Material, Integer>> diff(Map<Material, Integer> before, Map<Material, Integer> now) {
        Map<Material, Integer> delta = new HashMap<>();
        now.forEach((material, count) -> delta.merge(material, count, Integer::sum));
        before.forEach((material, count) -> delta.merge(material, -count, Integer::sum));
        return delta.entrySet().stream().filter(entry -> entry.getValue() != 0)
                .sorted(Comparator.comparingInt((Map.Entry<Material, Integer> entry) -> Math.abs(entry.getValue())).reversed())
                .limit(LINES).toList();
    }

    private static Map<Material, Integer> counts(ItemStack[]... inventories) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack[] contents : inventories) {
            for (ItemStack item : contents) {
                if (item != null && !item.isEmpty()) {
                    counts.merge(item.getType(), item.getAmount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    private static String serialize(Map<Material, Integer> counts) {
        return counts.entrySet().stream().map(entry -> entry.getKey().name() + "=" + entry.getValue()).collect(Collectors.joining(";"));
    }

    private static Map<Material, Integer> parse(String value) {
        Map<Material, Integer> counts = new HashMap<>();
        for (String part : value.split(";")) {
            String[] pair = part.split("=", 2);
            Material material = pair.length == 2 ? Material.matchMaterial(pair[0]) : null;
            if (material != null) {
                try {
                    counts.put(material, Integer.parseInt(pair[1]));
                } catch (NumberFormatException ignored) {
                    // ligne abîmée : ignorée
                }
            }
        }
        return counts;
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }
}
