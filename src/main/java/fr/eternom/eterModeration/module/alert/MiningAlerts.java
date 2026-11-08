package fr.eternom.eterModeration.module.alert;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minage suspect (un anti x-ray sans mod côté client) : sur une heure glissante, on compte les FILONS de minerais rares
 * trouvés (diamant, débris antiques : un filon = des blocs à moins de 4 blocs les uns des autres) et la pierre cassée
 * autour. Trop de filons pour trop peu de pierre (xray.max-ratio filons pour 100 blocs de pierre, et au moins
 * xray.min-veins filons) : le staff est alerté, une fois par heure et par joueur. Un indice à vérifier (Inspecteur,
 * mode spectateur), jamais une sanction automatique. Dispense : eter.mod.bypass.xray.
 */
public class MiningAlerts implements Listener {

    public static final String BYPASS = "eter.mod.bypass.xray";
    private static final long WINDOW_MILLIS = 60 * 60_000L;
    private static final Set<Material> RARE = Set.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, Material.ANCIENT_DEBRIS);
    private static final Set<Material> STONE = Set.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.NETHERRACK,
            Material.BASALT, Material.BLACKSTONE, Material.ANDESITE, Material.DIORITE, Material.GRANITE);

    /** Ce qu'un joueur a miné depuis start : filons rares, pierre, dernier bloc rare (pour reconnaître un même filon). */
    private static final class Stats {
        long start = System.currentTimeMillis();
        int veins;
        int stone;
        Location lastRare;
        long alertedAt;
    }

    private final StaffAlerts alerts;
    private final boolean enabled;
    private final int minVeins;
    private final double maxRatio;
    private final Map<UUID, Stats> stats = new ConcurrentHashMap<>();

    public MiningAlerts(StaffAlerts alerts, ConfigurationSection config) {
        this.alerts = alerts;
        this.enabled = config == null || config.getBoolean("enabled", true);
        this.minVeins = config == null ? 6 : Math.max(1, config.getInt("min-veins", 6));
        this.maxRatio = config == null ? 3.0 : config.getDouble("max-ratio", 3.0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material type = event.getBlock().getType();
        if (!enabled || player.getGameMode() != GameMode.SURVIVAL || (!RARE.contains(type) && !STONE.contains(type))
                || player.hasPermission(BYPASS)) {
            return;
        }
        long now = System.currentTimeMillis();
        Stats current = stats.computeIfAbsent(player.getUniqueId(), uuid -> new Stats());
        if (now - current.start > WINDOW_MILLIS) {
            current.start = now;
            current.veins = 0;
            current.stone = 0;
        }
        if (STONE.contains(type)) {
            current.stone++;
            return;
        }
        Location here = event.getBlock().getLocation();
        if (current.lastRare == null || current.lastRare.getWorld() != here.getWorld() || current.lastRare.distanceSquared(here) > 16) {
            current.veins++;
        }
        current.lastRare = here;
        double ratio = current.veins * 100.0 / Math.max(1, current.stone);
        if (current.veins >= minVeins && ratio > maxRatio && now - current.alertedAt > WINDOW_MILLIS) {
            current.alertedAt = now;
            alerts.alert("alert.xray", "player", player.getName(), "veins", String.valueOf(current.veins),
                    "stone", String.valueOf(current.stone));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stats.remove(event.getPlayer().getUniqueId());
    }
}
