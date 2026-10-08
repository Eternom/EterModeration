package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.record.Records.Link;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les comptes liés : ceux qui se sont connectés depuis la même adresse (jamais affichée ni gardée en clair) ;
 * clic : leur fiche. Même adresse ne veut pas dire même personne (famille, colocation) : un indice, pas une preuve.
 */
class AltsMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Target target;
    private final List<Link> links;
    private final Inventory inventory;
    private final Map<Integer, Link> linkAt = new HashMap<>();

    AltsMenu(ModGui gui, Player viewer, Target target, List<Link> links) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.links = links;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "alts.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Link link = linkAt.get(slot);
        if (link != null) {
            Sounds.page(player);
            gui.openPlayer(player, new Target(link.player(), link.name()));
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openPlayer(player, target);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, ModGui.STAFF_FRAME);
        for (int i = 0; i < links.size() && i < ModGui.LIST.size(); i++) {
            Link link = links.get(i);
            int slot = ModGui.LIST.get(i);
            linkAt.put(slot, link);
            inventory.setItem(slot, Items.head(Bukkit.createProfile(link.player(), link.name()),
                    messages.get(viewer, "alts.name", "player", link.name()), List.of(
                            messages.get(viewer, "alts.last-seen", "date", Labels.date(link.lastSeen())),
                            messages.get(viewer, "alts.click"))));
        }
        if (links.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "alts.empty"), List.of()));
        }
        inventory.setItem(47, Items.item(Material.BOOK, messages.get(viewer, "alts.info.name"), List.of(messages.get(viewer, "alts.info.lore"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-player", "player", target.name()), List.of()));
    }
}
