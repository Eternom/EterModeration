package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.appeal.AppealService;
import fr.eternom.eterModeration.module.report.ReportService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.util.List;

/** /mod : chercher un joueur, les appels et les signalements à traiter, les dernières sanctions du réseau. */
class StaffMenu implements Menu {

    private static final int SEARCH = 10;
    private static final int APPEALS = 12;
    private static final int REPORTS = 14;
    private static final int RECENT = 16;
    private static final int CLOSE = 22;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final ModGui.Counts counts;
    private final Inventory inventory;

    StaffMenu(ModGui gui, Player viewer, ModGui.Counts counts) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.counts = counts;
        this.inventory = Bukkit.createInventory(this, 27, messages.get(viewer, "staff.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        switch (slot) {
            case SEARCH -> {
                Sounds.click(player);
                gui.askText(player, "search", 16, name -> {
                    if (!name.isEmpty()) {
                        gui.openPlayer(player, name);
                    }
                }, () -> gui.openStaff(player));
            }
            case APPEALS -> {
                Sounds.page(player);
                gui.openAppeals(player);
            }
            case REPORTS -> {
                Sounds.page(player);
                gui.openReports(player);
            }
            case RECENT -> {
                Sounds.page(player);
                gui.openRecent(player);
            }
            case CLOSE -> player.closeInventory();
            default -> {
            }
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        Frame.draw(inventory, ModGui.STAFF_FRAME);
        inventory.setItem(SEARCH, Items.item(Material.SPYGLASS, messages.get(viewer, "staff.search.name"),
                List.of(messages.get(viewer, "staff.search.lore"))));
        boolean appeals = viewer.hasPermission(AppealService.PERMISSION);
        inventory.setItem(APPEALS, Items.item(appeals ? Material.WRITABLE_BOOK : Material.GRAY_DYE,
                messages.get(viewer, "staff.appeals.name", "count", String.valueOf(counts.appeals())),
                List.of(messages.get(viewer, appeals ? "staff.appeals.lore" : "menu.locked")), appeals && counts.appeals() > 0));
        boolean reports = viewer.hasPermission(ReportService.PERMISSION);
        inventory.setItem(REPORTS, Items.item(reports ? Material.BELL : Material.GRAY_DYE,
                messages.get(viewer, "staff.reports.name", "count", String.valueOf(counts.reports())),
                List.of(messages.get(viewer, reports ? "staff.reports.lore" : "menu.locked")), reports && counts.reports() > 0));
        boolean history = viewer.hasPermission(ModGui.HISTORY);
        inventory.setItem(RECENT, Items.item(history ? Material.CLOCK : Material.GRAY_DYE, messages.get(viewer, "staff.recent.name"),
                List.of(messages.get(viewer, history ? "staff.recent.lore" : "menu.locked"))));
        inventory.setItem(CLOSE, Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of()));
    }
}
