package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Journal des blocs, dans Redis seulement et pour un temps (block-log.days, 7 par défaut) : qui a posé ou cassé chaque
 * bloc, versé de la lave, mis le feu, allumé de la TNT, écrit un panneau (et quoi), tué un animal, un villageois ou un
 * animal apprivoisé, cassé un cadre, un tableau ou un support d'armure. Une clé par chunk et par jour
 * (etermod:blocks:serveur:monde:cx:cz:jour) qui expire d'elle-même : la mémoire reste bornée, rien à nettoyer.
 * Champs : « x,y,z » -> dernière action du jour sur ce bloc ; « E|x,y,z|heure » -> une entité tuée ou cassée là.
 * Valeur : action|joueur|heure|matière ou entité|texte. Les écritures partent groupées toutes les secondes.
 * Lu par l'outil Inspecteur du mode staff : clic droit = ce bloc ; accroupi = tout ce qui s'est passé autour (rayon 6).
 */
public class BlockLog implements Listener {

    private static final String PREFIX = "etermod:blocks:";
    private static final int AREA_RADIUS = 6;
    private static final int AREA_LINES = 20;

    /** Une écriture en attente : clé du jour, position, action. */
    private record Entry(String key, String field, String value) {
    }

    /** Une action lue : où, et sa valeur. */
    private record Found(int x, int y, int z, String[] parts) {

        long time() {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
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
        record(event.getPlayer(), event.getBlockPlaced(), "P", event.getBlockPlaced().getType().name(), "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        record(event.getPlayer(), event.getBlock(), "B", event.getBlock().getType().name(), "");
    }

    /** Lave ou eau versée (le grief à la lave ne pose aucun bloc). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        Material bucket = event.getBucket();
        String content = bucket == Material.LAVA_BUCKET ? "LAVA" : bucket == Material.WATER_BUCKET ? "WATER" : bucket.name();
        record(event.getPlayer(), event.getBlock(), "L", content, "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() != null) {
            record(event.getPlayer(), event.getBlock(), "F", "FIRE", "");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTnt(TNTPrimeEvent event) {
        if (event.getPrimingEntity() instanceof Player player) {
            record(player, event.getBlock(), "T", "TNT", "");
        }
    }

    /** Un panneau écrit : et son texte (un contenu choquant se prouve). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        String text = event.lines().stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .filter(line -> !line.isBlank()).collect(Collectors.joining(" / "));
        if (!text.isBlank()) {
            record(event.getPlayer(), event.getBlock(), "S", event.getBlock().getType().name(), text.replace("|", "/"));
        }
    }

    /** Un animal, un villageois, un animal apprivoisé ou une entité nommée tués par un joueur. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        Player killer = event.getEntity().getKiller();
        if (killer != null && (entity instanceof Animals || entity instanceof Villager || entity instanceof Tameable
                || entity instanceof ArmorStand || entity.customName() != null)) {
            recordEntity(killer, entity.getLocation(), "K", entity.getType().name());
        }
    }

    /** Un cadre, un tableau... cassé par un joueur. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            Hanging hanging = event.getEntity();
            recordEntity(player, hanging.getLocation(), "H", hanging.getType().name());
        }
    }

    private void record(Player player, Block block, String action, String what, String text) {
        pending.add(new Entry(key(block.getWorld(), block.getX(), block.getZ(), today()), field(block.getX(), block.getY(), block.getZ()),
                String.join("|", action, player.getName(), String.valueOf(System.currentTimeMillis()), what, text)));
    }

    private void recordEntity(Player player, Location location, String action, String what) {
        long now = System.currentTimeMillis();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        pending.add(new Entry(key(location.getWorld(), x, z, today()), "E|" + field(x, y, z) + "|" + now,
                String.join("|", action, player.getName(), String.valueOf(now), what, "")));
    }

    /** Ce qu'on sait du bloc ces derniers jours, du plus récent au plus ancien (une action par jour : la dernière). */
    public void inspect(Player staff, Block block) {
        long today = today();
        String field = field(block.getX(), block.getY(), block.getZ());
        Tasks.async(plugin, staff, () -> {
            List<String> found = new ArrayList<>();
            for (long day = today; day >= today - days; day--) {
                redis.getHashField(key(block.getWorld(), block.getX(), block.getZ(), day), field).ifPresent(found::add);
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
                show(staff, value.split("\\|", 5), "");
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Tout ce qui s'est passé autour du bloc (rayon 6), du plus récent au plus ancien : 20 lignes au plus. */
    public void inspectArea(Player staff, Block center) {
        long today = today();
        World world = center.getWorld();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        Tasks.async(plugin, staff, () -> {
            List<Found> found = new ArrayList<>();
            for (int chunkX = (cx - AREA_RADIUS) >> 4; chunkX <= (cx + AREA_RADIUS) >> 4; chunkX++) {
                for (int chunkZ = (cz - AREA_RADIUS) >> 4; chunkZ <= (cz + AREA_RADIUS) >> 4; chunkZ++) {
                    for (long day = today; day >= today - days; day--) {
                        String key = PREFIX + server + ":" + world.getName() + ":" + chunkX + ":" + chunkZ + ":" + day;
                        redis.getHash(key).forEach((field, value) -> {
                            String position = field.startsWith("E|") ? field.split("\\|")[1] : field;
                            String[] xyz = position.split(",");
                            if (xyz.length != 3) {
                                return;
                            }
                            int x = Integer.parseInt(xyz[0]);
                            int y = Integer.parseInt(xyz[1]);
                            int z = Integer.parseInt(xyz[2]);
                            if (Math.abs(x - cx) <= AREA_RADIUS && Math.abs(y - cy) <= AREA_RADIUS && Math.abs(z - cz) <= AREA_RADIUS) {
                                found.add(new Found(x, y, z, value.split("\\|", 5)));
                            }
                        });
                    }
                }
            }
            found.sort(Comparator.comparingLong(Found::time).reversed());
            return found.size() > AREA_LINES ? found.subList(0, AREA_LINES) : found;
        }, found -> {
            messages.send(staff, "block-log.area-header", "x", String.valueOf(cx), "y", String.valueOf(cy), "z", String.valueOf(cz),
                    "radius", String.valueOf(AREA_RADIUS));
            if (found.isEmpty()) {
                messages.send(staff, "block-log.empty", "days", String.valueOf(days));
            }
            for (Found entry : found) {
                show(staff, entry.parts(), entry.x() + " " + entry.y() + " " + entry.z());
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Une ligne : action, joueur, quand, quoi (et le texte d'un panneau) ; where = la position (vue de zone). */
    private void show(Player staff, String[] parts, String where) {
        if (parts.length < 4) {
            return;
        }
        Material material = Material.matchMaterial(parts[3]);
        Component name = material == null ? Component.text(parts[3].toLowerCase().replace('_', ' ')) : Component.translatable(material);
        String key = switch (parts[0]) {
            case "P" -> "block-log.placed";
            case "L" -> "block-log.poured";
            case "F" -> "block-log.fire";
            case "T" -> "block-log.tnt";
            case "S" -> "block-log.sign";
            case "K" -> "block-log.killed";
            case "H" -> "block-log.hanging";
            default -> "block-log.broken";
        };
        String text = parts.length > 4 ? parts[4] : "";
        long time;
        try {
            time = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }
        Component line = messages.get(staff, key, Placeholder.component("block", name), "player", parts[1],
                "ago", ago(staff, time), "text", text);
        staff.sendMessage(where.isEmpty() ? line : messages.get(staff, "block-log.at", "where", where).append(line));
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

    private String key(World world, int x, int z, long day) {
        return PREFIX + server + ":" + world.getName() + ":" + (x >> 4) + ":" + (z >> 4) + ":" + day;
    }

    private static String field(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static long today() {
        return LocalDate.now(ZoneOffset.UTC).toEpochDay();
    }
}
