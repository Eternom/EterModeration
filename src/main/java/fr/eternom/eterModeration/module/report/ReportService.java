package fr.eternom.eterModeration.module.report;

import fr.eternom.eterLib.helper.cache.Cooldowns;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.report.ReportRepository.Report;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Signaler un joueur (/report, tout le monde) : un motif de reports.reasons, un détail facultatif ; un signalement
 * par reports.cooldown-seconds (Redis, sur tout le réseau). Le staff (eter.mod.alerts) est alerté ; il les traite dans
 * /mod (eter.mod.reports), et l'auteur est prévenu que le sien a été traité.
 */
public class ReportService {

    public static final String PERMISSION = "eter.mod.reports";

    private final JavaPlugin plugin;
    private final ReportRepository repository;
    private final NetworkBus bus;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final String server;
    private final Cooldowns cooldown;
    /** Motif -> icône, dans l'ordre de la config. */
    private final Map<String, Material> reasons = new LinkedHashMap<>();

    public ReportService(JavaPlugin plugin, ReportRepository repository, RedisCache redis, NetworkBus bus, StaffAlerts alerts,
                         Messages messages, String server, ConfigurationSection config) {
        this.plugin = plugin;
        this.repository = repository;
        this.bus = bus;
        this.alerts = alerts;
        this.messages = messages;
        this.server = server;
        this.cooldown = new Cooldowns(redis, "etermod:report-cooldown",
                Duration.ofSeconds(config == null ? 60 : Math.max(0, config.getInt("cooldown-seconds", 60))));
        ConfigurationSection section = config == null ? null : config.getConfigurationSection("reasons");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                Material icon = Material.matchMaterial(section.getString(id, "PAPER"));
                reasons.put(id.toLowerCase(Locale.ROOT), icon == null || !icon.isItem() ? Material.PAPER : icon);
            }
        }
        if (reasons.isEmpty()) {
            reasons.put("other", Material.PAPER);
        }
    }

    public Map<String, Material> reasons() {
        return reasons;
    }

    public void report(Player reporter, Target target, String reason, String details, Runnable then) {
        if (reporter.getUniqueId().equals(target.uuid())) {
            messages.send(reporter, "report.self");
            return;
        }
        String trimmed = details.length() > 255 ? details.substring(0, 255) : details;
        String reporterName = reporter.getName();
        Tasks.async(plugin, reporter, () -> {
            if (!cooldown.tryStart(reporter.getUniqueId())) {
                return false;
            }
            repository.add(reporter.getUniqueId(), reporterName, target.uuid(), target.name(), reason, trimmed, server);
            return true;
        }, sent -> {
            if (!sent) {
                messages.send(reporter, "report.cooldown");
                return;
            }
            messages.send(reporter, "report.sent", "player", target.name());
            alerts.alert("alert.report", "player", reporterName, "target", target.name(),
                    "reason", messages.plain(reporter, "report-reason." + reason), "text", trimmed.isBlank() ? "" : "« " + trimmed + " »");
            then.run();
        }, () -> messages.send(reporter, "error.generic"));
    }

    /** Traité : fermé, et son auteur prévenu (où qu'il soit). */
    public void close(Player staff, Report report, Runnable then) {
        if (!staff.hasPermission(PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        String staffName = staff.getName();
        Tasks.async(plugin, staff, () -> repository.close(report.id(), staffName), closed -> {
            if (closed) {
                messages.send(staff, "report.closed", "player", report.targetName());
                bus.notify(report.reporter(), "notify.report-closed", true, "player", report.targetName());
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    public ReportRepository repository() {
        return repository;
    }
}
