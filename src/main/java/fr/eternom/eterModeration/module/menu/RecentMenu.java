package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.sanction.Sanction;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Les 28 dernières sanctions du réseau ; clic : la fiche du joueur. */
class RecentMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final List<Sanction> recent;
    private final Inventory inventory;
    private final Map<Integer, Sanction> sanctionAt = new HashMap<>();

    RecentMenu(ModGui gui, Player viewer, List<Sanction> recent) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.recent = recent;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "recent.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Sanction sanction = sanctionAt.get(slot);
        if (sanction != null) {
            Sounds.page(player);
            gui.openPlayer(player, new Target(sanction.player(), sanction.playerName()));
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openStaff(player);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, ModGui.STAFF_FRAME);
        for (int i = 0; i < recent.size() && i < ModGui.LIST.size(); i++) {
            Sanction sanction = recent.get(i);
            int slot = ModGui.LIST.get(i);
            sanctionAt.put(slot, sanction);
            inventory.setItem(slot, gui.sanctionItem(viewer, sanction, List.of(messages.get(viewer, "recent.click"))));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-staff"), List.of()));
    }
}
