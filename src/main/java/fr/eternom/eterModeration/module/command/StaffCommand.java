package fr.eternom.eterModeration.module.command;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.staff.StaffMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** /staff : entrer dans le mode staff ou en sortir. */
public class StaffCommand implements TabExecutor {

    private final StaffMode staff;
    private final Messages messages;

    public StaffCommand(StaffMode staff, Messages messages) {
        this.staff = staff;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player) {
            staff.toggle(player);
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
