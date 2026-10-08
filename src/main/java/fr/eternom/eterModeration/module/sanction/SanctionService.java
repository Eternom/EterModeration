package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.sanction.Motives.Motive;
import fr.eternom.eterModeration.module.sanction.Motives.Step;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/**
 * Donner et lever les sanctions. Chaque action a sa permission : le type (eter.mod.warn...), puis le motif
 * (eter.mod.motive.<motif>) ou, pour une sanction libre, eter.mod.free (et eter.mod.permanent si elle est définitive) ;
 * lever : eter.mod.lift. Après l'écriture en base : le joueur est prévenu où qu'il soit, le staff alerté, et la
 * sanction appliquée tout de suite (Enforcement : mute ici, prison et ban par le proxy).
 */
public class SanctionService {

    public static final String FREE_PERMISSION = "eter.mod.free";
    public static final String PERMANENT_PERMISSION = "eter.mod.permanent";
    public static final String LIFT_PERMISSION = "eter.mod.lift";

    private final JavaPlugin plugin;
    private final SanctionRepository repository;
    private final Enforcement enforcement;
    private final StaffAlerts alerts;
    private final NetworkBus bus;
    private final Messages messages;
    private final Labels labels;
    private final String server;

    public SanctionService(JavaPlugin plugin, SanctionRepository repository, Enforcement enforcement, StaffAlerts alerts,
                           NetworkBus bus, Messages messages, Labels labels, String server) {
        this.plugin = plugin;
        this.repository = repository;
        this.enforcement = enforcement;
        this.alerts = alerts;
        this.bus = bus;
        this.messages = messages;
        this.labels = labels;
        this.server = server;
    }

    /** Le staff peut-il utiliser ce motif à cette étape ? (permission du motif et du type de sanction) */
    public static boolean canUse(CommandSender staff, Motive motive, Step step) {
        return staff.hasPermission(motive.permission()) && staff.hasPermission(step.type().permission());
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
        Tasks.async(plugin, staff, () -> record(target, type, duration, motive, reason, staffName), sanction -> {
            announce(sanction, staff);
            messages.send(staff, "sanction.given", "player", target.name(), "type", labels.type(staff, type),
                    "motive", labels.motive(staff, motive));
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /**
     * Sanction automatique (filtre du chat) au nom de staffName : l'étape du motif selon la récidive. Thread principal.
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

    private Sanction record(Target target, SanctionType type, Duration duration, String motive, String reason, String staffName) {
        long now = System.currentTimeMillis();
        long expiresAt = !type.lasts() ? now : duration == null ? 0 : now + duration.toMillis();
        long id = repository.add(target.uuid(), target.name(), type, motive, reason, staffName, now, expiresAt, server);
        return new Sanction(id, target.uuid(), target.name(), type, motive, reason, staffName, now, expiresAt, 0, null, server);
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
        Tasks.async(plugin, staff, () -> repository.lift(sanction.id(), staffName, System.currentTimeMillis()), lifted -> {
            if (lifted) {
                lifted(sanction, staffName, staff);
                messages.send(staff, "sanction.lifted", "player", sanction.playerName());
            } else {
                messages.send(staff, "sanction.already-lifted");
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Bloquant, hors du thread principal (appel accepté) : false si elle était déjà levée. */
    public boolean liftBlocking(Sanction sanction, String staffName) {
        return repository.lift(sanction.id(), staffName, System.currentTimeMillis());
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
}
