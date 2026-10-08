package fr.eternom.eterModeration.listeners;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterModeration.Main;
import fr.eternom.eterModeration.module.command.AppealCommand;
import fr.eternom.eterModeration.module.command.ModCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        register(main, "mod", new ModCommand(main.getGui(), EterLib.get().getOnlineNames(), main.getMessages()));
        register(main, "appel", new AppealCommand(main.getGui(), main.getMessages()));
    }

    private static void register(Main main, String name, TabExecutor executor) {
        PluginCommand command = Objects.requireNonNull(main.getCommand(name), "Commande absente du plugin.yml : " + name);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
