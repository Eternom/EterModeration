package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le journal des coffres (et de tout conteneur posé : tonneau, shulker, four, trémie...), dans Redis seulement et pour
 * block-log.days jours, comme le journal des blocs : qui a pris ou mis quoi, et quand. Le contenu est photographié à
 * l'ouverture, comparé à la fermeture : seule la différence est notée (« pris 12 diamants, mis 3 pains »). Une clé par
 * chunk et par jour (etermod:containers:serveur:monde:cx:cz:jour, champ « x,y,z|heure|joueur ») qui expire d'elle-même.
 * Lu par l'outil Loupe du mode staff (clic droit accroupi sur un conteneur). Le vol devient prouvable.
 */
public class ContainerLog implements Listener {

    private static final String PREFIX = "etermod:containers:";
    private static final int MAX_LINES = 15;

    /** Ce qu'un joueur a ouvert : le bloc (normalisé : une seule moitié d'un double coffre) et le contenu à l'ouverture. */
    private record Opened(Block block, Map<Material, Integer> counts) {
    }

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;
    private final String server;
    private final int days;
    private final Duration ttl;
    private final Map<UUID, Opened> opened = new ConcurrentHashMap<>();

    public ContainerLog(JavaPlugin plugin, RedisCache redis, Messages messages, String server, int days) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
        this.server = server;
        this.days = days;
        this.ttl = Duration.ofDays(days + 1L);
    }

    /** Le bloc qui représente le conteneur (la même moitié d'un double coffre, quel que soit le côté cliqué), ou null. */
    public static Block containerBlock(InventoryHolder holder, Inventory inventory) {
        if (holder instanceof DoubleChest || holder instanceof Container) {
            Location location = inventory.getLocation();
            return location == null ? null : location.getBlock();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        Block block = containerBlock(inventory.getHolder(false), inventory);
        if (block != null && event.getPlayer() instanceof Player player) {
            opened.put(player.getUniqueId(), new Opened(block, counts(inventory)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Opened before = opened.remove(event.getPlayer().getUniqueId());
        if (before == null) {
            return;
        }
        Map<Material, Integer> after = counts(event.getInventory());
        List<String> changes = new ArrayList<>();
        Map<Material, Integer> all = new HashMap<>(before.counts());
        after.forEach((material, count) -> all.merge(material, 0, Integer::sum));
        all.keySet().stream().sorted(Comparator.comparing(Material::name)).forEach(material -> {
            int delta = after.getOrDefault(material, 0) - before.counts().getOrDefault(material, 0);
            if (delta != 0) {
                changes.add((delta > 0 ? "+" : "-") + Math.abs(delta) + " " + material.name());
            }
        });
        if (changes.isEmpty()) {
            return;
        }
        Block block = before.block();
        String key = key(block, today());
        String field = position(block) + "|" + System.currentTimeMillis() + "|" + event.getPlayer().getName();
        String value = String.join(",", changes);
        Tasks.async(plugin, () -> {
            redis.setHashField(key, field, value);
            redis.expire(key, ttl);
        }, "Journal des coffres non écrit");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        opened.remove(event.getPlayer().getUniqueId());
    }

    /** Ce qui a été pris ou mis dans ce conteneur ces derniers jours, du plus récent au plus ancien (15 au plus). */
    public void inspect(Player staff, Block clicked) {
        if (!(clicked.getState(false) instanceof Container container)) {
            return;
        }
        Block block = containerBlock(container.getInventory().getHolder(false), container.getInventory());
        if (block == null) {
            return;
        }
        long today = today();
        String position = position(block) + "|";
        Tasks.async(plugin, staff, () -> {
            List<String[]> found = new ArrayList<>();
            for (long day = today; day >= today - days; day--) {
                redis.getHash(key(block, day)).forEach((field, value) -> {
                    if (field.startsWith(position)) {
                        String[] parts = field.split("\\|", 3);
                        if (parts.length == 3) {
                            found.add(new String[]{parts[1], parts[2], value});
                        }
                    }
                });
            }
            found.sort(Comparator.comparingLong((String[] entry) -> Long.parseLong(entry[0])).reversed());
            return found.size() > MAX_LINES ? found.subList(0, MAX_LINES) : found;
        }, found -> {
            messages.send(staff, "container-log.header", "x", String.valueOf(block.getX()), "y", String.valueOf(block.getY()),
                    "z", String.valueOf(block.getZ()));
            if (found.isEmpty()) {
                messages.send(staff, "container-log.empty", "days", String.valueOf(days));
            }
            for (String[] entry : found) {
                staff.sendMessage(messages.get(staff, "container-log.line", Placeholder.component("changes", changes(entry[2])),
                        "player", entry[1], "ago", ago(staff, Long.parseLong(entry[0]))));
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    /** « +3 BREAD,-12 DIAMOND » -> « +3 Pain, -12 Diamant » (noms traduits par le client). */
    private static Component changes(String value) {
        Component out = Component.empty();
        boolean first = true;
        for (String change : value.split(",")) {
            String[] parts = change.split(" ", 2);
            if (parts.length < 2) {
                continue;
            }
            Material material = Material.matchMaterial(parts[1]);
            Component name = material == null ? Component.text(parts[1]) : Component.translatable(material);
            out = out.append(Component.text((first ? "" : ", ") + parts[0] + " ")).append(name);
            first = false;
        }
        return out;
    }

    private String ago(Player viewer, long millis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - millis) / 60_000);
        if (minutes < 60) {
            return messages.plain(viewer, "block-log.ago-minutes", "amount", String.valueOf(minutes));
        }
        long hours = minutes / 60;
        return hours < 48
                ? messages.plain(viewer, "block-log.ago-hours", "amount", String.valueOf(hours))
                : messages.plain(viewer, "block-log.ago-days", "amount", String.valueOf(hours / 24));
    }

    private static Map<Material, Integer> counts(Inventory inventory) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.isEmpty()) {
                counts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    private String key(Block block, long day) {
        return PREFIX + server + ":" + block.getWorld().getName() + ":" + (block.getX() >> 4) + ":" + (block.getZ() >> 4) + ":" + day;
    }

    private static String position(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }
}
