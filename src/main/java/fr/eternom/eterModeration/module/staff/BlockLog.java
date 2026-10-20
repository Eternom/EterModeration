package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Journal des blocs, dans Redis seulement et pour un temps (block-log.days, 7 par défaut) : qui a posé ou cassé chaque
 * bloc, pour démasquer un build provocateur. Une clé par chunk et par jour (etermod:blocks:serveur:monde:cx:cz:jour,
 * hash « x,y,z » -> dernière action du jour) qui expire d'elle-même : la mémoire reste bornée, rien à nettoyer. Les
 * écritures partent groupées toutes les secondes. Lu par l'outil Inspecteur du mode staff (clic droit sur un bloc).
 */
public class BlockLog implements Listener {

    private static final String PREFIX = "etermod:blocks:";

    /** Une écriture en attente : clé du jour, position, action. */
    private record Entry(String key, String field, String value) {
    }

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final Messages messages;
    private final String server;
    private final int days;
    private final Duration ttl;
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();

    public BlockLog(JavaPlugin plugin, RedisCache redis, Messages messages, String server, int days) {
        this.plugin = plugin;
        this.redis = redis;
        this.messages = messages;
        this.server = server;
        this.days = days;
        this.ttl = Duration.ofDays(days + 1L);
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 20, 20);
    }

    /** Bloquant : les écritures en attente, puis la durée de vie de chaque clé touchée. */
    public void flush() {
        Set<String> touched = new HashSet<>();
        Entry entry;
        while ((entry = pending.poll()) != null) {
            redis.setHashField(entry.key(), entry.field(), entry.value());
            touched.add(entry.key());
        }
        touched.forEach(key -> redis.expire(key, ttl));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        record(event.getPlayer(), event.getBlockPlaced(), "P");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        record(event.getPlayer(), event.getBlock(), "B");
    }

    private void record(Player player, Block block, String action) {
        pending.add(new Entry(key(block, today()), field(block), String.join("|", action, player.getName(),
                String.valueOf(System.currentTimeMillis()), block.getType().name())));
    }

    /** Ce qu'on sait du bloc ces derniers jours, du plus récent au plus ancien (une action par jour : la dernière). */
    public void inspect(Player staff, Block block) {
        long today = today();
        String field = field(block);
        Tasks.async(plugin, staff, () -> {
            List<String> found = new ArrayList<>();
            for (long day = today; day >= today - days; day--) {
                redis.getHashField(key(block, day), field).ifPresent(found::add);
            }
            return found;
        }, found -> {
            messages.send(staff, "block-log.header", "x", String.valueOf(block.getX()), "y", String.valueOf(block.getY()),
                    "z", String.valueOf(block.getZ()));
            if (found.isEmpty()) {
                messages.send(staff, "block-log.empty", "days", String.valueOf(days));
                return;
            }
            for (String value : found) {
                String[] parts = value.split("\\|", 4);
                if (parts.length < 4) {
                    continue;
                }
                Material material = Material.matchMaterial(parts[3]);
                Component name = material == null ? Component.text(parts[3]) : Component.translatable(material);
                staff.sendMessage(messages.get(staff, parts[0].equals("P") ? "block-log.placed" : "block-log.broken",
                        Placeholder.component("block", name), "player", parts[1], "ago", ago(staff, Long.parseLong(parts[2]))));
            }
        }, () -> messages.send(staff, "error.generic"));
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

    private String key(Block block, long day) {
        return PREFIX + server + ":" + block.getWorld().getName() + ":" + (block.getX() >> 4) + ":" + (block.getZ() >> 4) + ":" + day;
    }

    private static String field(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }
}
