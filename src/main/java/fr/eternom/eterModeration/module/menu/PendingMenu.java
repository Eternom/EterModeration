package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.SanctionService.Pending;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les bans définitifs à valider : demandés par un membre du staff, ils sont d'abord temporaires. Un second (avec
 * eter.mod.permanent, jamais celui qui l'a demandé) : clic gauche, le rendre définitif ; clic droit, le refuser (le ban
 * temporaire reste) ; touche 1-9 : les preuves dans le chat.
 */
class PendingMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final List<Pending> list;
    private final Inventory inventory;
    private final Map<Integer, Pending> pendingAt = new HashMap<>();

    PendingMenu(ModGui gui, Player viewer, List<Pending> list) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.list = list;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "pending.title", "count", String.valueOf(list.size())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Pending pending = pendingAt.get(slot);
        if (pending != null) {
            if (click == ClickType.NUMBER_KEY) {
                player.closeInventory();
                gui.showEvidence(player, pending.sanction().playerName(), pending.sanction().evidence());
                return;
            }
            boolean accept = !click.isRightClick();
            gui.confirm(player, accept ? "approve" : "refuse",
                    () -> gui.sanctions().decide(player, pending, accept, () -> gui.openPending(player)),
                    () -> gui.openPending(player), "player", pending.sanction().playerName(),
                    "staff", pending.request().requestedBy(), "id", String.valueOf(pending.sanction().id()));
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
        for (int i = 0; i < list.size() && i < ModGui.LIST.size(); i++) {
            Pending pending = list.get(i);
            int slot = ModGui.LIST.get(i);
            pendingAt.put(slot, pending);
            inventory.setItem(slot, gui.sanctionItem(viewer, pending.sanction(), List.of(
                    messages.get(viewer, "pending.requested", "staff", pending.request().requestedBy(),
                            "date", Labels.date(pending.request().createdAt())),
                    messages.get(viewer, "pending.click"))));
        }
        if (list.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "pending.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-staff"), List.of()));
    }
}
