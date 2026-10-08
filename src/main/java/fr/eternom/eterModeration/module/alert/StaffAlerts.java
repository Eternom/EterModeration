package fr.eternom.eterModeration.module.alert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Alertes au staff (permission eter.mod.alerts), sur tout le réseau : sanctions données ou levées, appels, filtre du
 * chat. Thread principal : affichées ici, puis envoyées aux autres serveurs (bus réseau, canal etermoderation).
 */
public class StaffAlerts {

    public static final String PERMISSION = "eter.mod.alerts";
    private static final String ALERT = "alert";

    private final NetworkBus bus;
    private final Messages messages;

    public StaffAlerts(NetworkBus bus, Messages messages) {
        this.bus = bus;
        this.messages = messages;
        bus.on(ALERT, data -> {
            JsonArray values = data.getAsJsonArray("values");
            String[] placeholders = new String[values.size()];
            int i = 0;
            for (JsonElement value : values) {
                placeholders[i++] = value.getAsString();
            }
            show(data.get("key").getAsString(), placeholders);
        });
    }

    public void alert(String key, String... placeholders) {
        show(key, placeholders);
        JsonObject data = new JsonObject();
        data.addProperty("key", key);
        JsonArray values = new JsonArray();
        for (String value : placeholders) {
            values.add(value);
        }
        data.add("values", values);
        bus.publish(ALERT, data);
    }

    private void show(String key, String... placeholders) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERMISSION)) {
                player.sendMessage(messages.get(player, "alert.prefix").append(messages.get(player, key, placeholders)));
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 0.8f);
            }
        }
        Bukkit.getConsoleSender().sendMessage(messages.get(Bukkit.getConsoleSender(), "alert.prefix")
                .append(messages.get(Bukkit.getConsoleSender(), key, placeholders)));
    }
}
