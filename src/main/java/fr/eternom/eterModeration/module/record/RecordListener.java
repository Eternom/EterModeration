package fr.eternom.eterModeration.module.record;

import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.sanction.MuteGuard;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.util.Set;

/**
 * Ce qui est gardé pour le staff : chaque message du chat et chaque message privé (après le mute et le filtre :
 * seulement ce qui a vraiment été envoyé), et l'empreinte de la connexion à l'arrivée (comptes liés).
 */
public class RecordListener implements Listener {

    private final JavaPlugin plugin;
    private final Records records;
    private final String server;
    private final Set<String> privateCommands;

    public RecordListener(JavaPlugin plugin, Records records, String server, Set<String> privateCommands) {
        this.plugin = plugin;
        this.records = records;
        this.server = server;
        this.privateCommands = privateCommands;
    }

    // Avant EterChat (HIGH), qui annule l'événement pour envoyer le message lui-même
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        log(event.getPlayer(), PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (privateCommands.contains(MuteGuard.command(event.getMessage()))) {
            log(event.getPlayer(), event.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return;
        }
        String ip = address.getAddress().getHostAddress();
        Tasks.async(plugin, () -> records.recordConnection(player.getUniqueId(), player.getName(), ip),
                "Connexion de " + player.getName() + " non enregistrée");
    }

    private void log(Player player, String message) {
        Tasks.async(plugin, () -> records.logChat(player.getUniqueId(), server, message), "Message de " + player.getName() + " non gardé");
    }
}
