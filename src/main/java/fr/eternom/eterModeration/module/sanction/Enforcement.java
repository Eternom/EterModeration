package fr.eternom.eterModeration.module.sanction;

import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faire respecter une sanction tout de suite, où que soit le joueur.
 * - Mute : gardé en mémoire pour les joueurs connectés ici (MuteGuard le lit à chaque message).
 * - Prison, ban, libération : c'est le PROXY qui décide (EterVelocityModeration). Le serveur où est le joueur lui envoie
 *   « relis ses sanctions » par le canal eter:moderation, à travers la connexion du joueur : seul un serveur peut
 *   écrire sur ce canal (un joueur ne peut pas se libérer lui-même), et le proxy relit tout en base.
 * Un changement fait ailleurs arrive par le bus réseau (« refresh »), et le serveur du joueur fait le reste.
 */
public class Enforcement {

    public static final String CHANNEL = "eter:moderation";
    private static final String REFRESH = "refresh";

    private final JavaPlugin plugin;
    private final SanctionRepository sanctions;
    private final NetworkBus bus;
    /** Mute en cours des joueurs connectés ici. */
    private final Map<UUID, Sanction> mutes = new ConcurrentHashMap<>();

    public Enforcement(JavaPlugin plugin, SanctionRepository sanctions, NetworkBus bus) {
        this.plugin = plugin;
        this.sanctions = sanctions;
        this.bus = bus;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        bus.on(REFRESH, data -> apply(UUID.fromString(data.get("player").getAsString())));
    }

    /** Thread principal : les sanctions du joueur ont changé (ici ou ailleurs) : relues, puis le proxy est prévenu. */
    public void changed(UUID player) {
        apply(player);
        JsonObject data = new JsonObject();
        data.addProperty("player", player.toString());
        bus.publish(REFRESH, data);
    }

    /** Connexion : son mute éventuel (le proxy a déjà décidé de la prison et du ban à l'entrée). */
    public void join(Player player) {
        load(player.getUniqueId());
    }

    public void quit(Player player) {
        mutes.remove(player.getUniqueId());
    }

    /** Mute en cours (et pas encore fini) du joueur connecté ici. */
    public Optional<Sanction> mute(UUID player) {
        Sanction mute = mutes.get(player);
        if (mute != null && !mute.isActive(System.currentTimeMillis())) {
            mutes.remove(player);
            return Optional.empty();
        }
        return Optional.ofNullable(mute);
    }

    /** Seulement si le joueur est ici : son mute relu, et le proxy prévenu. */
    private void apply(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        load(uuid);
        player.sendPluginMessage(plugin, CHANNEL, refreshMessage(uuid));
    }

    private void load(UUID uuid) {
        Tasks.async(plugin, () -> {
            long now = System.currentTimeMillis();
            Optional<Sanction> mute = sanctions.active(uuid, now).stream().filter(sanction -> sanction.type() == SanctionType.MUTE)
                    .findFirst();
            Bukkit.getScheduler().runTask(plugin, () -> mute.ifPresentOrElse(found -> mutes.put(uuid, found), () -> mutes.remove(uuid)));
        }, "Mute de " + uuid + " non relu");
    }

    private static byte[] refreshMessage(UUID player) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(REFRESH);
            out.writeUTF(player.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e); // impossible en mémoire
        }
        return bytes.toByteArray();
    }
}
