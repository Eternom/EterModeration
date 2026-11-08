package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;

/**
 * Ce que le joueur doit voir à son arrivée (ou tout de suite s'il est connecté) :
 * - un avertissement à accepter : une fenêtre avec le motif et la raison, « J'ai compris » ; « Plus tard » la remontre
 *   à la connexion suivante (Redis etermod:warn-ack, uuid -> numéro) ;
 * - de retour après un ban : un rappel des règles, une fois (Redis etermod:returning).
 */
public class PlayerNotices implements Listener {

    /** Après l'arrivée : laisser le joueur charger le monde avant la fenêtre. */
    private static final long JOIN_DELAY_TICKS = 40;

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final SanctionRepository sanctions;
    private final Messages messages;
    private final Labels labels;

    private record Pending(Optional<Sanction> warn, boolean returning) {
    }

    public PlayerNotices(JavaPlugin plugin, RedisCache redis, SanctionRepository sanctions, Messages messages, Labels labels) {
        this.plugin = plugin;
        this.redis = redis;
        this.sanctions = sanctions;
        this.messages = messages;
        this.labels = labels;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                check(player);
            }
        }, JOIN_DELAY_TICKS);
    }

    /** Thread principal : lit ce qui l'attend, puis le montre. */
    public void check(Player player) {
        String uuid = player.getUniqueId().toString();
        Tasks.async(plugin, player, () -> {
            Optional<Sanction> warn = redis.getHashField(SanctionService.WARN_ACK_KEY, uuid).flatMap(PlayerNotices::id)
                    .flatMap(sanctions::byId).filter(sanction -> sanction.liftedAt() == 0);
            boolean returning = redis.getHashField(SanctionService.RETURNING_KEY, uuid).isPresent()
                    && sanctions.active(player.getUniqueId(), System.currentTimeMillis()).stream()
                    .noneMatch(sanction -> sanction.type() == SanctionType.BAN);
            if (returning) {
                redis.deleteHashField(SanctionService.RETURNING_KEY, uuid);
            }
            if (warn.isEmpty()) {
                redis.deleteHashField(SanctionService.WARN_ACK_KEY, uuid);
            }
            return new Pending(warn, returning);
        }, pending -> {
            if (pending.returning()) {
                messages.send(player, "notice.returning");
            }
            pending.warn().ifPresent(warn -> showWarn(player, warn));
        }, () -> { });
    }

    private void showWarn(Player player, Sanction warn) {
        String reason = warn.reason().isBlank() ? "" : "« " + warn.reason() + " »";
        DialogBase base = DialogBase.builder(messages.get(player, "notice.warn.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "notice.warn.body",
                        "motive", labels.motive(player, warn.motive()), "reason", reason))))
                .canCloseWithEscape(false)
                .build();
        String uuid = player.getUniqueId().toString();
        Dialogs.show(plugin, player, base, messages.get(player, "notice.warn.accept"), messages.get(player, "notice.warn.later"),
                response -> Tasks.async(plugin, () -> redis.deleteHashField(SanctionService.WARN_ACK_KEY, uuid), "Accusé non noté"),
                () -> { });
    }

    private static Optional<Long> id(String text) {
        try {
            return Optional.of(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
