package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.record.Records.ChatLine;
import fr.eternom.eterModeration.module.sanction.Motives.Motive;
import fr.eternom.eterModeration.module.sanction.Motives.Step;
import fr.eternom.eterModeration.module.sanction.PendingBans.Request;
import fr.eternom.eterModeration.module.staff.StaffLog;
import fr.eternom.eterModeration.module.staff.StaffLog.Action;
import fr.eternom.eterModeration.module.staff.StaffRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Donner et lever les sanctions. Chaque action a sa permission : le type (eter.mod.warn...), puis le motif
 * (eter.mod.motive.<motif>) ou, pour une sanction libre, eter.mod.free ; une sanction définitive (barème ou libre)
 * demande aussi eter.mod.permanent ; lever : eter.mod.lift ; changer la durée : eter.mod.change. Après l'écriture en
 * base : le joueur est prévenu où qu'il soit, le staff alerté, et la sanction appliquée tout de suite (Enforcement :
 * mute ici, ban et expulsion par le proxy).
 * Garde-fous : un membre du staff protégé (StaffRegistry) n'est sanctionné qu'avec eter.mod.sanction.staff ; un ban
 * définitif d'un membre du staff est d'abord temporaire, jusqu'à ce qu'un second le valide (permanent-approval) ; les
 * motifs de evidence.motives emportent les derniers messages du joueur comme preuve.
 */
public class SanctionService {

    public static final String FREE_PERMISSION = "eter.mod.free";
    public static final String PERMANENT_PERMISSION = "eter.mod.permanent";
    public static final String LIFT_PERMISSION = "eter.mod.lift";
    public static final String CHANGE_PERMISSION = "eter.mod.change";
    /** Redis : bannis à qui rappeler les règles à leur retour, et avertissements à faire accepter (uuid -> numéro). */
    public static final String RETURNING_KEY = "etermod:returning";
    public static final String WARN_ACK_KEY = "etermod:warn-ack";

    private final JavaPlugin plugin;
    private final SanctionRepository repository;
    private final Enforcement enforcement;
    private final StaffAlerts alerts;
    private final NetworkBus bus;
    private final Messages messages;
    private final Labels labels;
    private final String server;
    private final StaffRegistry registry;
    private final StaffLog staffLog;
    private final Records records;
    private final RedisCache redis;
    private final PendingBans pending;
    private final Set<String> evidenceMotives;
    private final int evidenceLines;
    private final boolean approval;
    private final Duration interim;

    public SanctionService(JavaPlugin plugin, SanctionRepository repository, Enforcement enforcement, StaffAlerts alerts,
                           NetworkBus bus, Messages messages, Labels labels, String server, StaffRegistry registry,
                           StaffLog staffLog, Records records, RedisCache redis, PendingBans pending, ConfigurationSection config) {
        this.plugin = plugin;
        this.repository = repository;
        this.enforcement = enforcement;
        this.alerts = alerts;
        this.bus = bus;
        this.messages = messages;
        this.labels = labels;
        this.server = server;
        this.registry = registry;
        this.staffLog = staffLog;
        this.records = records;
        this.redis = redis;
        this.pending = pending;
        this.evidenceMotives = config.getStringList("evidence.motives").stream().map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        this.evidenceLines = Math.max(0, config.getInt("evidence.lines", 10));
        this.approval = config.getBoolean("permanent-approval.enabled", true);
        Duration parsed = Motives.parseDuration(config.getString("permanent-approval.interim", "7d"));
        this.interim = parsed == null ? Duration.ofDays(7) : parsed;
    }

    /**
     * Le staff peut-il utiliser ce motif à cette étape ? (permission du motif et du type de sanction ; une étape
     * définitive, comme « ban perm », demande aussi eter.mod.permanent : un modérateur bannit pour un temps seulement)
     */
    public static boolean canUse(CommandSender staff, Motive motive, Step step) {
        return staff.hasPermission(motive.permission()) && staff.hasPermission(step.type().permission())
                && (!step.type().lasts() || step.duration() != null || staff.hasPermission(PERMANENT_PERMISSION));
    }

    public static boolean canGiveFree(CommandSender staff, SanctionType type, Duration duration) {
        return staff.hasPermission(FREE_PERMISSION) && staff.hasPermission(type.permission())
                && (!type.lasts() || duration != null || staff.hasPermission(PERMANENT_PERMISSION));
    }

    /** Sanction du barème : step est l'étape affichée au staff (selon ses sanctions passées pour ce motif). */
    public void give(Player staff, Target target, Motive motive, Step step, String reason, Runnable then) {
        if (!canUse(staff, motive, step)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        give(staff, target, step.type(), step.duration(), motive.id(), reason, then);
    }

    /** Sanction libre (hors barème) : duration null = définitive. */
    public void giveFree(Player staff, Target target, SanctionType type, Duration duration, String reason, Runnable then) {
        if (!canGiveFree(staff, type, duration)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        if (reason.isBlank()) {
            messages.send(staff, "sanction.reason-required");
            return;
        }
        give(staff, target, type, duration, "", reason, then);
    }

    private void give(Player staff, Target target, SanctionType type, Duration duration, String motive, String reason, Runnable then) {
        if (staff.getUniqueId().equals(target.uuid())) {
            messages.send(staff, "sanction.self");
            return;
        }
        String staffName = staff.getName();
        boolean override = staff.hasPermission(StaffRegistry.OVERRIDE);
        // Un ban définitif attend un second avis : d'ici là, un ban temporaire
        boolean needsApproval = approval && type == SanctionType.BAN && duration == null;
        Tasks.async(plugin, staff, () -> {
            if (!override && registry.isProtected(target.uuid())) {
                return Optional.<Sanction>empty();
            }
            Sanction sanction = record(target, type, needsApproval ? interim : duration, motive, reason, staffName);
            if (needsApproval) {
                pending.add(sanction.id(), staffName);
            }
            return Optional.of(sanction);
        }, given -> {
            if (given.isEmpty()) {
                messages.send(staff, "sanction.protected", "player", target.name());
                return;
            }
            Sanction sanction = given.get();
            announce(sanction, staff);
            staffLog.log(staffName, Action.SANCTION, target.name() + " #" + sanction.id());
            messages.send(staff, "sanction.given", "player", target.name(), "type", labels.type(staff, type),
                    "motive", labels.motive(staff, motive));
            if (needsApproval) {
                messages.send(staff, "sanction.approval-requested", "time", labels.time(staff, interim.toMillis()));
                alerts.alert("alert.approval-needed", "staff", staffName, "player", target.name(), "id", String.valueOf(sanction.id()));
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /**
     * Sanction automatique (filtre du chat, déconnexion pendant un gel) au nom de staffName : l'étape du motif selon la
     * récidive. Thread principal.
     */
    public void giveAuto(Target target, Motive motive, String reason, String staffName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Step step = motive.step(repository.count(target.uuid(), motive.id()));
                Sanction sanction = record(target, step.type(), step.duration(), motive.id(), reason, staffName);
                Bukkit.getScheduler().runTask(plugin, () -> announce(sanction, Bukkit.getConsoleSender()));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Sanction automatique de " + target.name() + " non enregistrée : " + e.getMessage());
            }
        });
    }

    /**
     * Sanction donnée par un autre plugin (API) au nom de author, hors barème : enregistrée tout de suite, puis appliquée
     * et annoncée sur le thread principal. Bloquant (base) : hors du thread principal. Rend son numéro.
     */
    public long giveFromPlugin(Target target, SanctionType type, Duration duration, String reason, String author) {
        Sanction sanction = record(target, type, duration, "", reason, author);
        Bukkit.getScheduler().runTask(plugin, () -> announce(sanction, Bukkit.getConsoleSender()));
        return sanction.id();
    }

    /** Bloquant : écrite en base, avec ses preuves ; un ban prépare le rappel des règles, un avertissement son accusé. */
    private Sanction record(Target target, SanctionType type, Duration duration, String motive, String reason, String staffName) {
        long now = System.currentTimeMillis();
        long expiresAt = !type.lasts() ? now : duration == null ? 0 : now + duration.toMillis();
        String evidence = evidence(target.uuid(), motive);
        long id = repository.add(target.uuid(), target.name(), type, motive, reason, staffName, now, expiresAt, server, evidence);
        if (type == SanctionType.BAN) {
            redis.setHashField(RETURNING_KEY, target.uuid().toString(), String.valueOf(id));
        } else if (type == SanctionType.WARN) {
            redis.setHashField(WARN_ACK_KEY, target.uuid().toString(), String.valueOf(id));
        }
        return new Sanction(id, target.uuid(), target.name(), type, motive, reason, staffName, now, expiresAt, 0, null, server, evidence);
    }

    /** Bloquant : les derniers messages du joueur, pour un motif du chat (evidence.motives). */
    private String evidence(UUID player, String motive) {
        if (evidenceLines == 0 || !evidenceMotives.contains(motive)) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (ChatLine line : records.lastMessages(player, evidenceLines).reversed()) {
            lines.add("[" + Labels.date(line.createdAt()) + " " + line.server() + "] " + line.message());
        }
        return String.join("\n", lines);
    }

    /** Thread principal : appliquée, le joueur prévenu, le staff alerté (textes de l'auteur pour l'alerte). */
    private void announce(Sanction sanction, CommandSender author) {
        enforcement.changed(sanction.player());
        long now = System.currentTimeMillis();
        String reason = sanction.reason().isBlank() ? "" : "« " + sanction.reason() + " »";
        bus.notify(sanction.player(), "notify." + sanction.type().id(), true, "motive", labels.motive(author, sanction.motive()),
                "reason", reason, "time", sanction.type().lasts() ? labels.remaining(author, sanction, now) : "");
        alerts.alert("alert.given", "staff", sanction.staffName(), "player", sanction.playerName(),
                "type", labels.type(author, sanction.type()), "motive", labels.motive(author, sanction.motive()),
                "time", labels.length(author, sanction), "reason", reason);
    }

    /** Lever une sanction en cours (erreur, peine allégée) : permission eter.mod.lift. */
    public void lift(Player staff, Sanction sanction, Runnable then) {
        if (!staff.hasPermission(LIFT_PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        String staffName = staff.getName();
        Tasks.async(plugin, staff, () -> {
            boolean lifted = repository.lift(sanction.id(), staffName, System.currentTimeMillis());
            if (lifted) {
                pending.remove(sanction.id());
            }
            return lifted;
        }, lifted -> {
            if (lifted) {
                lifted(sanction, staffName, staff);
                staffLog.log(staffName, Action.LIFT, sanction.playerName() + " #" + sanction.id());
                messages.send(staff, "sanction.lifted", "player", sanction.playerName());
            } else {
                messages.send(staff, "sanction.already-lifted");
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /**
     * Changer la durée d'une sanction en cours (eter.mod.change) : duration est sa nouvelle durée totale, depuis le
     * moment où elle a été donnée (null = définitive : eter.mod.permanent, et un second avis si permanent-approval).
     */
    public void change(Player staff, Sanction sanction, Duration duration, Runnable then) {
        if (!staff.hasPermission(CHANGE_PERMISSION) || duration == null && !staff.hasPermission(PERMANENT_PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        String staffName = staff.getName();
        boolean needsApproval = approval && duration == null && sanction.type() == SanctionType.BAN;
        Tasks.async(plugin, staff, () -> {
            long now = System.currentTimeMillis();
            if (needsApproval) {
                pending.add(sanction.id(), staffName);
                return true;
            }
            return repository.changeExpiry(sanction.id(), duration == null ? 0 : sanction.createdAt() + duration.toMillis(), now);
        }, changed -> {
            if (!changed) {
                messages.send(staff, "sanction.already-lifted");
            } else if (needsApproval) {
                alerts.alert("alert.approval-needed", "staff", staffName, "player", sanction.playerName(), "id", String.valueOf(sanction.id()));
                messages.send(staff, "sanction.approval-requested", "time", labels.remaining(staff, sanction, System.currentTimeMillis()));
            } else {
                enforcement.changed(sanction.player());
                staffLog.log(staffName, Action.CHANGE, sanction.playerName() + " #" + sanction.id());
                String length = duration == null ? messages.plain(staff, "length.permanent") : labels.time(staff, duration.toMillis());
                messages.send(staff, "sanction.changed", "player", sanction.playerName(), "time", length);
                alerts.alert("alert.changed", "staff", staffName, "player", sanction.playerName(),
                        "type", labels.type(staff, sanction.type()), "time", length);
                bus.notify(sanction.player(), "notify.changed", true, "type", labels.type(staff, sanction.type()), "time", length);
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Les demandes de ban définitif à valider, avec leur sanction (bloquant). */
    public List<Pending> pendingBans() {
        List<Pending> list = new ArrayList<>();
        for (Request request : pending.all()) {
            repository.byId(request.sanctionId()).ifPresent(sanction -> list.add(new Pending(request, sanction)));
        }
        return list;
    }

    /** Une demande de ban définitif et sa sanction. */
    public record Pending(Request request, Sanction sanction) {
    }

    public int countPending() {
        return pending.count();
    }

    /**
     * Valider (définitif) ou refuser (le ban temporaire reste) un ban définitif demandé par un autre membre du staff :
     * eter.mod.permanent, jamais celui qui l'a demandé.
     */
    public void decide(Player staff, Pending request, boolean accept, Runnable then) {
        if (!staff.hasPermission(PERMANENT_PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        if (staff.getName().equalsIgnoreCase(request.request().requestedBy())) {
            messages.send(staff, "sanction.approval-self");
            return;
        }
        String staffName = staff.getName();
        Sanction sanction = request.sanction();
        Tasks.async(plugin, staff, () -> {
            if (!pending.remove(sanction.id())) {
                return false;
            }
            if (accept) {
                repository.changeExpiry(sanction.id(), 0, System.currentTimeMillis());
            }
            return true;
        }, done -> {
            if (!done) {
                messages.send(staff, "sanction.approval-done");
            } else {
                if (accept) {
                    enforcement.changed(sanction.player());
                    staffLog.log(staffName, Action.CHANGE, sanction.playerName() + " #" + sanction.id());
                }
                alerts.alert(accept ? "alert.approved" : "alert.refused", "staff", staffName, "player", sanction.playerName(),
                        "requester", request.request().requestedBy(), "id", String.valueOf(sanction.id()));
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Bloquant, hors du thread principal (appel accepté) : false si elle était déjà levée. */
    public boolean liftBlocking(Sanction sanction, String staffName) {
        boolean lifted = repository.lift(sanction.id(), staffName, System.currentTimeMillis());
        if (lifted) {
            pending.remove(sanction.id());
        }
        return lifted;
    }

    /** Thread principal : une sanction vient d'être levée. */
    public void lifted(Sanction sanction, String staffName, CommandSender author) {
        enforcement.changed(sanction.player());
        bus.notify(sanction.player(), "notify.lifted", true, "type", labels.type(author, sanction.type()));
        alerts.alert("alert.lifted", "staff", staffName, "player", sanction.playerName(), "type", labels.type(author, sanction.type()));
    }

    public SanctionRepository repository() {
        return repository;
    }

    public StaffLog staffLog() {
        return staffLog;
    }
}
