package fr.eternom.eterModeration.module.command;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.player.OnlineNames;
import fr.eternom.eterModeration.module.menu.ModGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /report [joueur] : signaler un joueur (le motif dans un menu, le détail dans une fenêtre). */
public class ReportCommand implements TabExecutor {

    private final ModGui gui;
    private final OnlineNames names;
    private final Messages messages;

    public ReportCommand(ModGui gui, OnlineNames names, Messages messages) {
        this.gui = gui;
        this.names = names;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
        } else if (args.length == 0) {
            gui.askReport(player);
        } else {
            gui.openReport(player, args[0]);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? names.complete(args[0]) : List.of();
    }
}
