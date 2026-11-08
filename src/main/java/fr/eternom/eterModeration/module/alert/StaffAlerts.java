package fr.eternom.eterModeration.module.alert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * Alertes au staff (permission eter.mod.alerts), sur tout le réseau : sanctions données ou levées, appels, filtre du
 * chat, gels... Thread principal : affichées ici, puis envoyées aux autres serveurs (bus réseau, canal
 * etermoderation). Celles de discord.alerts partent aussi sur le salon Discord du staff (discord.webhook-url), une
 * seule fois : par le serveur où l'alerte est née.
 */
public class StaffAlerts {

    public static final String PERMISSION = "eter.mod.alerts";
    private static final String ALERT = "alert";

    private final JavaPlugin plugin;
    private final NetworkBus bus;
    private final Messages messages;
    private final String webhook;
    /** Les alertes envoyées sur Discord (clés de lang/ sans « alert. ») ; vide = toutes. */
    private final Set<String> discordAlerts;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public StaffAlerts(JavaPlugin plugin, NetworkBus bus, Messages messages) {
        this.plugin = plugin;
        this.bus = bus;
        this.messages = messages;
        this.webhook = plugin.getConfig().getString("discord.webhook-url", "").trim();
        this.discordAlerts = Set.copyOf(plugin.getConfig().getStringList("discord.alerts"));
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
        discord(key, placeholders);
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

    /** Le texte de l'alerte (langue de la console), sur le webhook Discord, en tâche de fond. */
    private void discord(String key, String... placeholders) {
        String id = key.startsWith("alert.") ? key.substring("alert.".length()) : key;
        if (webhook.isEmpty() || !discordAlerts.isEmpty() && !discordAlerts.contains(id)) {
            return;
        }
        String text = messages.plain(Bukkit.getConsoleSender(), key, placeholders);
        JsonObject body = new JsonObject();
        body.addProperty("content", text.length() > 1900 ? text.substring(0, 1900) : text);
        // Pas de mention possible depuis un message de joueur (@everyone dans un motif...)
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> {
            plugin.getLogger().warning("Alerte non envoyée sur Discord (webhook injoignable)");
            return null;
        });
    }
}
