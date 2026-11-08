package fr.eternom.eterModeration.module.report;

import fr.eternom.eterLib.helper.cache.Cooldowns;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.record.Records.ChatLine;
import fr.eternom.eterModeration.module.report.ReportRepository.Report;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Target;
import fr.eternom.eterModeration.module.staff.StaffLog;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Signaler un joueur (/report, tout le monde) : un motif de reports.reasons, un détail facultatif ; un signalement
 * par reports.cooldown-seconds (Redis, sur tout le réseau). Pour un motif du chat (reports.evidence-reasons), les
 * derniers messages du joueur signalé sont joints. Le staff (eter.mod.alerts) est alerté ; il les prend en charge puis
 * les traite dans /mod (eter.mod.reports) : fondé (l'auteur est prévenu) ou faux. Au-delà de reports.false-limit faux
 * signalements en 30 jours, un joueur ne peut plus signaler pendant ce temps.
 */
public class ReportService {

    public static final String PERMISSION = "eter.mod.reports";
    private static final long FALSE_WINDOW_MILLIS = 30L * 86_400_000L;
    private static final int EVIDENCE_LINES = 10;

    /** Ce qu'un signalement a donné. */
    private enum Outcome { SENT, COOLDOWN, BLOCKED }

    private final JavaPlugin plugin;
    private final ReportRepository repository;
    private final NetworkBus bus;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final String server;
    private final Cooldowns cooldown;
    private final Records records;
    private final StaffLog staffLog;
    private final int falseLimit;
    private final Set<String> evidenceReasons;
    /** Motif -> icône, dans l'ordre de la config. */
    private final Map<String, Material> reasons = new LinkedHashMap<>();

    public ReportService(JavaPlugin plugin, ReportRepository repository, RedisCache redis, NetworkBus bus, StaffAlerts alerts,
                         Messages messages, String server, ConfigurationSection config, Records records, StaffLog staffLog) {
        this.plugin = plugin;
        this.repository = repository;
        this.bus = bus;
        this.alerts = alerts;
        this.messages = messages;
        this.server = server;
        this.records = records;
        this.staffLog = staffLog;
        this.cooldown = new Cooldowns(redis, "etermod:report-cooldown",
                Duration.ofSeconds(config == null ? 60 : Math.max(0, config.getInt("cooldown-seconds", 60))));
        this.falseLimit = config == null ? 3 : Math.max(0, config.getInt("false-limit", 3));
        this.evidenceReasons = config == null ? Set.of("insult", "spam") : config.getStringList("evidence-reasons").stream()
                .map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
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
            if (falseLimit > 0 && repository.countFalse(reporter.getUniqueId(), System.currentTimeMillis() - FALSE_WINDOW_MILLIS) >= falseLimit) {
                return Outcome.BLOCKED;
            }
            if (!cooldown.tryStart(reporter.getUniqueId())) {
                return Outcome.COOLDOWN;
            }
            repository.add(reporter.getUniqueId(), reporterName, target.uuid(), target.name(), reason, trimmed, server,
                    evidence(target, reason));
            return Outcome.SENT;
        }, outcome -> {
            switch (outcome) {
                case BLOCKED -> messages.send(reporter, "report.blocked");
                case COOLDOWN -> messages.send(reporter, "report.cooldown");
                case SENT -> {
                    messages.send(reporter, "report.sent", "player", target.name());
                    alerts.alert("alert.report", "player", reporterName, "target", target.name(),
                            "reason", messages.plain(reporter, "report-reason." + reason),
                            "text", trimmed.isBlank() ? "" : "« " + trimmed + " »");
                    then.run();
                }
            }
        }, () -> messages.send(reporter, "error.generic"));
    }

    /** Bloquant : les derniers messages du joueur signalé, pour un motif du chat. */
    private String evidence(Target target, String reason) {
        if (!evidenceReasons.contains(reason)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ChatLine line : records.lastMessages(target.uuid(), EVIDENCE_LINES).reversed()) {
            lines.add("[" + Labels.date(line.createdAt()) + " " + line.server() + "] " + line.message());
        }
        return String.join("\n", lines);
    }

    /** Le prendre en charge, ou le lâcher s'il était à soi (les autres voient qui s'en occupe). */
    public void claim(Player staff, Report report, Runnable then) {
        if (!staff.hasPermission(PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        boolean mine = staff.getName().equalsIgnoreCase(report.claimedBy());
        if (report.claimedBy() != null && !mine) {
            messages.send(staff, "report.claimed-by", "staff", report.claimedBy());
            return;
        }
        Tasks.async(plugin, staff, () -> repository.claim(report.id(), mine ? null : staff.getName()), done -> {
            if (done) {
                messages.send(staff, mine ? "report.unclaimed" : "report.claimed", "player", report.targetName());
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Traité : fondé (son auteur est prévenu, où qu'il soit) ou faux (compte contre l'auteur). */
    public void close(Player staff, Report report, boolean falseReport, Runnable then) {
        if (!staff.hasPermission(PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        String staffName = staff.getName();
        Tasks.async(plugin, staff, () -> repository.close(report.id(), staffName, falseReport), closed -> {
            if (closed) {
                staffLog.log(staffName, StaffLog.Action.REPORT, report.targetName() + (falseReport ? " (faux)" : ""));
                messages.send(staff, falseReport ? "report.closed-false" : "report.closed", "player", report.targetName());
                if (!falseReport) {
                    bus.notify(report.reporter(), "notify.report-closed", true, "player", report.targetName());
                }
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    public ReportRepository repository() {
        return repository;
    }
}
