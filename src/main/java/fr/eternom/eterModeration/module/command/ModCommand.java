package fr.eternom.eterModeration.module.command;

import fr.eternom.eterLib.module.player.OnlineNames;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.menu.ModGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /mod : le menu du staff ; /mod <joueur> : sa fiche ; /mod reload : le barème et le filtre relus dans config.yml, sans
 * redémarrage (eter.mod.reload ; le reste de la config demande toujours un redémarrage). Tout le reste se fait dans
 * les menus. /mod filter add|remove <mot>, /mod filter list : les mots interdits ajoutés en jeu, communs au réseau
 * (eter.mod.filter).
 */
public class ModCommand implements TabExecutor {

    public static final String RELOAD_PERMISSION = "eter.mod.reload";
    public static final String FILTER_PERMISSION = "eter.mod.filter";

    private final ModGui gui;
    private final OnlineNames names;
    private final Messages messages;
    private final Runnable reload;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final fr.eternom.eterModeration.module.filter.FilterWords filterWords;

    public ModCommand(ModGui gui, OnlineNames names, Messages messages, Runnable reload,
                      org.bukkit.plugin.java.JavaPlugin plugin, fr.eternom.eterModeration.module.filter.FilterWords filterWords) {
        this.plugin = plugin;
        this.filterWords = filterWords;
        this.gui = gui;
        this.names = names;
        this.messages = messages;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("filter") && sender.hasPermission(FILTER_PERMISSION)) {
            filter(sender, args);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission(RELOAD_PERMISSION)) {
            reload.run();
            messages.send(sender, "reload.done");
        } else if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
        } else if (args.length == 0) {
            gui.openStaff(player);
        } else {
            gui.openPlayer(player, args[0]);
        }
        return true;
    }

    /** /mod filter add|remove <mot...> · /mod filter list. */
    private void filter(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        String word = args.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
        String staff = sender.getName();
        switch (action) {
            case "add", "remove" -> {
                if (word.isBlank()) {
                    messages.send(sender, "filter-admin.usage");
                    return;
                }
                boolean add = action.equals("add");
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean done = add ? filterWords.add(word, staff) : filterWords.remove(word);
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender,
                            add ? (done ? "filter-admin.added" : "filter-admin.already") : (done ? "filter-admin.removed" : "filter-admin.unknown"),
                            "word", word));
                });
            }
            case "list" -> org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                java.util.Map<String, String> words = filterWords.list();
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (words.isEmpty()) {
                        messages.send(sender, "filter-admin.empty");
                        return;
                    }
                    messages.send(sender, "filter-admin.header", "count", String.valueOf(words.size()));
                    words.forEach((key, by) -> messages.send(sender, "filter-admin.line", "word", key, "staff", by));
                });
            });
            default -> messages.send(sender, "filter-admin.usage");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("filter") && sender.hasPermission(FILTER_PERMISSION)) {
            return List.of("add", "remove", "list").stream().filter(sub -> sub.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        List<String> completions = new ArrayList<>(names.complete(args[0]));
        if (sender.hasPermission(FILTER_PERMISSION) && "filter".startsWith(args[0].toLowerCase())) {
            completions.add("filter");
        }
        if (sender.hasPermission(RELOAD_PERMISSION) && "reload".startsWith(args[0].toLowerCase())) {
            completions.add("reload");
        }
        return completions;
    }
}
