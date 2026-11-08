package fr.eternom.eterModeration.module.filter;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.filter.ChatFilter.Verdict;
import fr.eternom.eterModeration.module.sanction.MuteGuard;
import fr.eternom.eterModeration.module.sanction.Motives;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.Target;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Le filtre appliqué : message bloqué et le joueur prévenu. Un mot interdit (chat ou message privé) est sanctionné
 * tout de suite (filter.word-motive) ; spam, répétition et majuscules donnent un avertissement du filtre, et au
 * filter.strikes-ième en filter.strikes-minutes, la sanction filter.spam-motive. Le barème décide de la peine
 * (récidive comprise). Dispense : eter.mod.bypass.filter.
 */
public class FilterListener implements Listener {

    public static final String BYPASS = "eter.mod.bypass.filter";

    private final JavaPlugin plugin;
    private final ChatFilter filter;
    private final SanctionService sanctions;
    private final Motives motives;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final Set<String> privateCommands;
    private final String wordMotive;
    private final String spamMotive;
    private final String advertMotive;
    private final int strikes;
    private final long strikesWindowMillis;
    private final String staffName;
    private final Map<UUID, Deque<Long>> strikesOf = new ConcurrentHashMap<>();

    public FilterListener(JavaPlugin plugin, ChatFilter filter, SanctionService sanctions, Motives motives, StaffAlerts alerts,
                          Messages messages, Set<String> privateCommands, ConfigurationSection config) {
        this.plugin = plugin;
        this.filter = filter;
        this.sanctions = sanctions;
        this.motives = motives;
        this.alerts = alerts;
        this.messages = messages;
        this.privateCommands = privateCommands;
        this.wordMotive = config == null ? "" : config.getString("word-motive", "");
        this.spamMotive = config == null ? "" : config.getString("spam-motive", "");
        this.advertMotive = config == null ? "" : config.getString("adverts.motive", "");
        this.strikes = config == null ? 3 : Math.max(1, config.getInt("strikes", 3));
        this.strikesWindowMillis = (config == null ? 10 : config.getInt("strikes-minutes", 10)) * 60_000L;
        this.staffName = config == null ? "Filter" : config.getString("staff-name", "Filter");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Verdict verdict = filter.check(player.getUniqueId(), message);
        if (verdict != Verdict.OK) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> blocked(player, verdict, message));
        }
    }

    /** Messages privés : seulement les mots interdits (une menace ou une insulte en privé reste une menace). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!privateCommands.contains(MuteGuard.command(event.getMessage())) || player.hasPermission(BYPASS)) {
            return;
        }
        Verdict verdict = filter.scan(event.getMessage());
        if (verdict != Verdict.OK) {
            event.setCancelled(true);
            blocked(player, verdict, event.getMessage());
        }
    }

    /** Panneaux : mots interdits et pub (un panneau se lit par tout le monde, longtemps). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        String text = event.lines().stream().map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .collect(Collectors.joining(" "));
        if (written(event.getPlayer(), text)) {
            event.setCancelled(true);
        }
    }

    /** Livres : chaque page, et le titre à la signature. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        BookMeta book = event.getNewBookMeta();
        StringBuilder text = new StringBuilder(book.getTitle() == null ? "" : book.getTitle());
        book.pages().forEach(page -> text.append(' ').append(PlainTextComponentSerializer.plainText().serialize(page)));
        if (written(event.getPlayer(), text.toString())) {
            event.setCancelled(true);
        }
    }

    /** Enclume : le nouveau nom de l'objet (sans résultat, rien à prendre). */
    @EventHandler(priority = EventPriority.LOW)
    public void onAnvil(PrepareAnvilEvent event) {
        String name = event.getView().getRenameText();
        if (event.getResult() == null || name == null || name.isBlank() || !(event.getView().getPlayer() instanceof Player player)
                || player.hasPermission(BYPASS)) {
            return;
        }
        if (filter.scan(name) != Verdict.OK) {
            event.setResult(null);
        }
    }

    /** Un texte écrit ailleurs que dans le chat : true s'il est bloqué (prévenu et sanctionné comme dans le chat). */
    private boolean written(Player player, String text) {
        if (text.isBlank() || player.hasPermission(BYPASS)) {
            return false;
        }
        Verdict verdict = filter.scan(text);
        if (verdict == Verdict.OK) {
            return false;
        }
        blocked(player, verdict, text);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        filter.forget(event.getPlayer().getUniqueId());
    }

    /** Thread principal. */
    private void blocked(Player player, Verdict verdict, String message) {
        if (!player.isOnline()) {
            return;
        }
        String kind = verdict.name().toLowerCase(Locale.ROOT);
        messages.send(player, "filter." + kind);
        if (verdict == Verdict.WORD) {
            alerts.alert("alert.filter", "player", player.getName(), "message", message);
            punish(player, wordMotive, message);
        } else if (verdict == Verdict.ADVERT) {
            alerts.alert("alert.filter-advert", "player", player.getName(), "message", message);
            punish(player, advertMotive, message);
        } else if (strike(player.getUniqueId())) {
            punish(player, spamMotive, message);
        }
    }

    /** Un avertissement du filtre de plus : true quand il y en a assez pour sanctionner (et le compte repart à zéro). */
    private boolean strike(UUID player) {
        long now = System.currentTimeMillis();
        Deque<Long> times = strikesOf.computeIfAbsent(player, uuid -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > strikesWindowMillis) {
            times.pollFirst();
        }
        times.addLast(now);
        if (times.size() >= strikes) {
            times.clear();
            return true;
        }
        return false;
    }

    private void punish(Player player, String motive, String message) {
        motives.get(motive).ifPresent(found -> sanctions.giveAuto(new Target(player.getUniqueId(), player.getName()), found,
                message.length() > 255 ? message.substring(0, 255) : message, staffName));
    }
}
