package fr.eternom.eterModeration.listeners;

import fr.eternom.eterModeration.Main;
import org.bukkit.event.Listener;

public class Events {

    public Events(Main main) {
        register(main, main.getMuteGuard());
        register(main, main.getFilterListener());
        register(main, main.getRecordListener());
    }

    private static void register(Main main, Listener listener) {
        main.getServer().getPluginManager().registerEvents(listener, main);
    }
}
