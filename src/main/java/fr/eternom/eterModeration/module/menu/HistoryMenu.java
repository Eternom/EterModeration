package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.sanction.Sanction;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** L'historique d'un joueur (les 28 dernières sanctions) ; clic sur une sanction en cours : la lever (eter.mod.lift). */
class HistoryMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Target target;
    private final List<Sanction> history;
    private final Inventory inventory;
    private final Map<Integer, Sanction> sanctionAt = new HashMap<>();

    HistoryMenu(ModGui gui, Player viewer, Target target, List<Sanction> history) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.history = history;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "history.title", "player", target.name(),
                "count", String.valueOf(history.size())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Sanction sanction = sanctionAt.get(slot);
        if (sanction != null && sanction.isActive(System.currentTimeMillis()) && player.hasPermission(SanctionService.LIFT_PERMISSION)) {
            gui.confirm(player, "lift", () -> gui.sanctions().lift(player, sanction, () -> gui.openHistory(player, target)),
                    () -> gui.openHistory(player, target), "player", target.name(),
                    "type", gui.labels().type(player, sanction.type()), "id", String.valueOf(sanction.id()));
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
        long now = System.currentTimeMillis();
        boolean canLift = viewer.hasPermission(SanctionService.LIFT_PERMISSION);
        for (int i = 0; i < history.size() && i < ModGui.LIST.size(); i++) {
            Sanction sanction = history.get(i);
            int slot = ModGui.LIST.get(i);
            sanctionAt.put(slot, sanction);
            inventory.setItem(slot, gui.sanctionItem(viewer, sanction,
                    canLift && sanction.isActive(now) ? List.of(messages.get(viewer, "history.click-lift")) : List.of()));
        }
        if (history.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "history.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-player", "player", target.name()), List.of()));
    }
}
