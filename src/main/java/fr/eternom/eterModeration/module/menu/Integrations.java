package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterClan.api.ClanApi;
import fr.eternom.eterHome.api.HomeApi;
import fr.eternom.eterModeration.module.sanction.Target;
import fr.eternom.eterSync.api.SyncApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Ce que la fiche d'un joueur demande aux autres plugins (jamais à leurs tables) : son clan (EterClan), ses homes
 * (EterHome), son inventaire en lecture seule (EterSync). Chaque API n'est touchée que si son plugin tourne ici : sinon
 * ses classes n'existent pas (d'où une petite classe par plugin, chargée seulement à ce moment-là).
 */
final class Integrations {

    private Integrations() {
    }

    static boolean has(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin);
    }

    /** « [TAG] Nom du clan », s'il en a un. Bloquant (base). */
    static Optional<String> clan(Target target) {
        return has("EterClan") ? Clans.of(target) : Optional.empty();
    }

    /** Ses homes (EterHome vérifie eterhome.others.view). Thread principal. */
    static void openHomes(Player viewer, Target target) {
        if (has("EterHome")) {
            Homes.open(viewer, target);
        }
    }

    /** Ses sauvegardes d'inventaire, en lecture seule (EterSync vérifie etersync.admin). Thread principal. */
    static void openInventory(Player viewer, Target target) {
        if (has("EterSync")) {
            Inventories.open(viewer, target);
        }
    }

    /** Son dernier inventaire enregistré (41 cases : inventaire, armure, seconde main). Bloquant (base). */
    static Optional<ItemStack[]> lastInventory(Target target) {
        return has("EterSync") ? Inventories.last(target) : Optional.empty();
    }

    /** Son dernier coffre de l'Ender enregistré. Bloquant (base). */
    static Optional<ItemStack[]> lastEnderChest(Target target) {
        return has("EterSync") ? Inventories.lastEnder(target) : Optional.empty();
    }

    private static final class Clans {
        static Optional<String> of(Target target) {
            return ClanApi.get().flatMap(clans -> clans.clanOf(target.uuid())).map(clan -> "[" + clan.tag() + "] " + clan.name());
        }
    }

    private static final class Homes {
        static void open(Player viewer, Target target) {
            HomeApi.get().ifPresent(homes -> homes.openMenu(viewer, target.uuid(), target.name()));
        }
    }

    private static final class Inventories {
        static void open(Player viewer, Target target) {
            SyncApi.get().ifPresent(sync -> sync.openHistory(viewer, target.name()));
        }

        static Optional<ItemStack[]> last(Target target) {
            return SyncApi.get().flatMap(sync -> sync.lastInventory(target.uuid()));
        }

        static Optional<ItemStack[]> lastEnder(Target target) {
            return SyncApi.get().flatMap(sync -> sync.lastEnderChest(target.uuid()));
        }
    }
}
