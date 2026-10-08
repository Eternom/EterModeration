package fr.eternom.eterModeration.module.appeal;

import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.appeal.AppealRepository.Appeal;
import fr.eternom.eterModeration.module.appeal.AppealRepository.Status;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Sanction;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/**
 * Faire appel d'une sanction (le joueur, /appel) et y répondre (staff, eter.mod.appeals). Un seul appel par sanction ;
 * accepté, la sanction est levée ; refusé, elle reste. Le joueur reçoit la réponse où qu'il soit, et la retrouve
 * dans /appel s'il n'était pas là.
 */
public class AppealService {

    public static final String PERMISSION = "eter.mod.appeals";

    private final JavaPlugin plugin;
    private final AppealRepository appeals;
    private final SanctionService sanctions;
    private final StaffAlerts alerts;
    private final NetworkBus bus;
    private final Messages messages;
    private final Labels labels;

    public AppealService(JavaPlugin plugin, AppealRepository appeals, SanctionService sanctions, StaffAlerts alerts, NetworkBus bus,
                         Messages messages, Labels labels) {
        this.plugin = plugin;
        this.appeals = appeals;
        this.sanctions = sanctions;
        this.alerts = alerts;
        this.bus = bus;
        this.messages = messages;
        this.labels = labels;
    }

    /** On peut faire appel d'une sanction pas levée : en cours, ou un avertissement (qui compte pour la récidive). */
    public static boolean appealable(Sanction sanction, long now) {
        return sanction.liftedAt() == 0 && (sanction.isActive(now) || !sanction.type().lasts());
    }

    public void submit(Player player, Sanction sanction, String text, Runnable then) {
        if (!sanction.player().equals(player.getUniqueId()) || !appealable(sanction, System.currentTimeMillis())) {
            return;
        }
        if (text.length() < 10) {
            messages.send(player, "appeal.too-short");
            return;
        }
        String trimmed = text.length() > 512 ? text.substring(0, 512) : text;
        Tasks.async(plugin, player, () -> !appeals.hasAppealed(sanction.id())
                && appeals.open(sanction.id(), player.getUniqueId(), player.getName(), trimmed), opened -> {
            if (opened) {
                messages.send(player, "appeal.sent");
                alerts.alert("alert.appeal", "player", player.getName(), "type", labels.type(player, sanction.type()));
            } else {
                messages.send(player, "appeal.already");
            }
            then.run();
        }, () -> messages.send(player, "error.generic"));
    }

    /** Accepter (la sanction est levée) ou refuser, avec la réponse du staff. */
    public void answer(Player staff, Appeal appeal, boolean accept, String answer, Runnable then) {
        if (!staff.hasPermission(PERMISSION)) {
            messages.send(staff, "sanction.no-permission");
            return;
        }
        String staffName = staff.getName();
        Tasks.async(plugin, staff, () -> {
            if (!appeals.answer(appeal.id(), accept ? Status.ACCEPTED : Status.REFUSED, staffName, answer)) {
                return Optional.<Optional<Sanction>>empty();
            }
            Optional<Sanction> sanction = sanctions.repository().byId(appeal.sanctionId());
            return Optional.of(accept ? sanction.filter(found -> sanctions.liftBlocking(found, staffName)) : Optional.<Sanction>empty());
        }, done -> {
            if (done.isEmpty()) {
                messages.send(staff, "appeal.already-answered");
            } else {
                done.get().ifPresent(lifted -> sanctions.lifted(lifted, staffName, staff));
                String key = accept ? "accepted" : "refused";
                messages.send(staff, "appeal.answered-" + key, "player", appeal.playerName());
                bus.notify(appeal.player(), "notify.appeal-" + key, true, "answer", answer.isBlank() ? "-" : answer);
                alerts.alert("alert.appeal-" + key, "staff", staffName, "player", appeal.playerName());
            }
            then.run();
        }, () -> messages.send(staff, "error.generic"));
    }

    public AppealRepository repository() {
        return appeals;
    }
}
