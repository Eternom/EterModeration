package fr.eternom.eterModeration.module.command;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.menu.ModGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /appel : ses sanctions et ses appels (tout joueur). */
public class AppealCommand implements TabExecutor {

    private final ModGui gui;
    private final Messages messages;

    public AppealCommand(ModGui gui, Messages messages) {
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            gui.openMyAppeals(player);
        } else {
            messages.send(sender, "command.players-only");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
