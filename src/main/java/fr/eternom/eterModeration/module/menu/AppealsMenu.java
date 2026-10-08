package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.menu.ModGui.OpenAppeal;
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
 * Les appels à traiter, le plus ancien d'abord : la sanction visée et le texte du joueur. Clic gauche : accepter
 * (la sanction est levée), clic droit : refuser ; une réponse au joueur dans les deux cas. Shift : sa fiche.
 */
class AppealsMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final List<OpenAppeal> appeals;
    private final Inventory inventory;
    private final Map<Integer, OpenAppeal> appealAt = new HashMap<>();

    AppealsMenu(ModGui gui, Player viewer, List<OpenAppeal> appeals) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.appeals = appeals;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "appeals.title", "count", String.valueOf(appeals.size())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        OpenAppeal open = appealAt.get(slot);
        if (open != null) {
            if (click.isShiftClick()) {
                Sounds.page(player);
                gui.openPlayer(player, new Target(open.appeal().player(), open.appeal().playerName()));
                return;
            }
            boolean accept = click.isLeftClick();
            Sounds.click(player);
            gui.askText(player, accept ? "accept" : "refuse", 255,
                    answer -> gui.appeals().answer(player, open.appeal(), accept, answer, () -> gui.openAppeals(player)),
                    () -> gui.openAppeals(player), "player", open.appeal().playerName(),
                    "type", gui.labels().type(player, open.sanction().type()));
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
        for (int i = 0; i < appeals.size() && i < ModGui.LIST.size(); i++) {
            OpenAppeal open = appeals.get(i);
            int slot = ModGui.LIST.get(i);
            appealAt.put(slot, open);
            inventory.setItem(slot, gui.sanctionItem(viewer, open.sanction(), List.of(
                    messages.get(viewer, "appeals.text", "date", Labels.date(open.appeal().createdAt()), "text", open.appeal().text()),
                    messages.get(viewer, "appeals.click"))));
        }
        if (appeals.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "appeals.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-staff"), List.of()));
    }
}
