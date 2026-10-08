package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.report.ReportRepository.Report;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Target;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les signalements ouverts, le plus récent d'abord (28 au plus) : le joueur signalé, le motif, le détail, l'auteur.
 * Clic gauche : aller le voir ; clic droit : traité (l'auteur est prévenu) ; shift : sa fiche.
 */
class ReportsMenu implements Menu {

    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final List<Report> reports;
    private final Inventory inventory;
    private final Map<Integer, Report> reportAt = new HashMap<>();

    ReportsMenu(ModGui gui, Player viewer, List<Report> reports) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.reports = reports;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "reports.title", "count", String.valueOf(reports.size())));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Report report = reportAt.get(slot);
        if (report != null) {
            Target target = new Target(report.target(), report.targetName());
            if (click.isShiftClick()) {
                Sounds.page(player);
                gui.openPlayer(player, target);
            } else if (click.isRightClick()) {
                Sounds.click(player);
                gui.reports().close(player, report, () -> gui.openReports(player));
            } else {
                gui.goTo(player, target);
            }
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
        for (int i = 0; i < reports.size() && i < ModGui.LIST.size(); i++) {
            Report report = reports.get(i);
            int slot = ModGui.LIST.get(i);
            reportAt.put(slot, report);
            List<Component> lore = new ArrayList<>();
            lore.add(messages.get(viewer, "reports.reason", "reason", messages.plain(viewer, "report-reason." + report.reason())));
            if (!report.details().isBlank()) {
                lore.add(messages.get(viewer, "reports.details", "text", report.details()));
            }
            lore.add(messages.get(viewer, "reports.by", "player", report.reporterName(), "date", Labels.date(report.createdAt()),
                    "server", report.server()));
            lore.add(messages.get(viewer, "reports.click"));
            inventory.setItem(slot, Items.head(Bukkit.createProfile(report.target(), report.targetName()),
                    messages.get(viewer, "reports.name", "player", report.targetName()), lore));
        }
        if (reports.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "reports.empty"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-staff"), List.of()));
    }
}
