package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.message.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Un joueur muet ne parle plus : ni chat, ni messages privés (les commandes de mute.blocked-commands). Bloqué au
 * plus tôt (LOWEST), avant EterChat et le filtre.
 */
public class MuteGuard implements Listener {

    private final Enforcement enforcement;
    private final Messages messages;
    private final Labels labels;
    private final Set<String> blockedCommands;

    public MuteGuard(Enforcement enforcement, Messages messages, Labels labels, Set<String> blockedCommands) {
        this.enforcement = enforcement;
        this.messages = messages;
        this.labels = labels;
        this.blockedCommands = blockedCommands;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (muted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (blockedCommands.contains(command(event.getMessage())) && muted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enforcement.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        enforcement.quit(event.getPlayer());
    }

    private boolean muted(Player player) {
        Optional<Sanction> mute = enforcement.mute(player.getUniqueId());
        mute.ifPresent(sanction -> messages.send(player, "mute.blocked", "time",
                labels.remaining(player, sanction, System.currentTimeMillis()), "motive", labels.motive(player, sanction.motive())));
        return mute.isPresent();
    }

    /** « /Msg bob salut » -> « msg » (sans « plugin: »). */
    public static String command(String message) {
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
    }
}
