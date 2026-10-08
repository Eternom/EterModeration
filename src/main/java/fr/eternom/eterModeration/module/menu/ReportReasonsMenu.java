package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /report : pourquoi signaler ce joueur (reports.reasons), puis un détail facultatif dans une fenêtre. */
class ReportReasonsMenu implements Menu {

    private static final int HEAD = 4;
    private static final int CLOSE = 31;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Target target;
    private final Inventory inventory;
    private final Map<Integer, String> reasonAt = new HashMap<>();

    ReportReasonsMenu(ModGui gui, Player viewer, Target target) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, 36, messages.get(viewer, "report.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        String reason = reasonAt.get(slot);
        if (reason != null) {
            Sounds.click(player);
            gui.askText(player, "report", 255,
                    details -> gui.reports().report(player, target, reason, details, () -> { }),
                    () -> player.openInventory(new ReportReasonsMenu(gui, player, target).getInventory()),
                    "player", target.name(), "reason", messages.plain(player, "report-reason." + reason));
        } else if (slot == CLOSE) {
            player.closeInventory();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        inventory.setItem(HEAD, Items.head(ModGui.profile(target), messages.get(viewer, "report.head", "player", target.name()),
                List.of(messages.get(viewer, "report.head-lore"))));
        List<Integer> slots = List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
        int i = 0;
        for (Map.Entry<String, Material> reason : gui.reports().reasons().entrySet()) {
            if (i >= slots.size()) {
                break;
            }
            reasonAt.put(slots.get(i), reason.getKey());
            inventory.setItem(slots.get(i++), Items.item(reason.getValue(),
                    messages.get(viewer, "report.reason", "reason", messages.plain(viewer, "report-reason." + reason.getKey())),
                    List.of(messages.get(viewer, "report.click"))));
        }
        inventory.setItem(CLOSE, Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of()));
    }
}
