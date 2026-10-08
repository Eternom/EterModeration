package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.appeal.AppealRepository.Appeal;
import fr.eternom.eterModeration.module.appeal.AppealService;
import fr.eternom.eterModeration.module.sanction.Sanction;
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
import java.util.Optional;

/**
 * /appel : les sanctions du joueur qui comptent encore (en cours, avertissements) et l'état de son appel. Clic sur une
 * sanction sans appel : écrire son appel (une seule fois par sanction). Le joueur ne voit ni les notes ni le staff.
 */
class MyAppealsMenu implements Menu {

    record Data(List<Sanction> history, List<Appeal> appeals) {
    }

    private static final int CLOSE = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Data data;
    private final Inventory inventory;
    private final Map<Integer, Sanction> sanctionAt = new HashMap<>();

    MyAppealsMenu(ModGui gui, Player viewer, Data data) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "my.title"));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Sanction sanction = sanctionAt.get(slot);
        if (sanction != null && appeal(sanction).isEmpty()) {
            Sounds.click(player);
            gui.askText(player, "appeal", 512, text -> gui.appeals().submit(player, sanction, text, () -> gui.openMyAppeals(player)),
                    () -> gui.openMyAppeals(player), "type", gui.labels().type(player, sanction.type()));
        } else if (slot == CLOSE) {
            player.closeInventory();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Optional<Appeal> appeal(Sanction sanction) {
        return data.appeals().stream().filter(appeal -> appeal.sanctionId() == sanction.id()).findFirst();
    }

    private void render() {
        Frame.draw(inventory, Material.ORANGE_STAINED_GLASS_PANE);
        long now = System.currentTimeMillis();
        List<Sanction> shown = data.history().stream()
                .filter(sanction -> AppealService.appealable(sanction, now) || appeal(sanction).isPresent()).toList();
        for (int i = 0; i < shown.size() && i < ModGui.LIST.size(); i++) {
            Sanction sanction = shown.get(i);
            int slot = ModGui.LIST.get(i);
            List<Component> extra = new ArrayList<>();
            appeal(sanction).ifPresentOrElse(appeal -> {
                extra.add(messages.get(viewer, "my.appeal-" + appeal.status().name().toLowerCase()));
                if (appeal.answer() != null && !appeal.answer().isBlank()) {
                    extra.add(messages.get(viewer, "my.answer", "answer", appeal.answer()));
                }
            }, () -> {
                if (AppealService.appealable(sanction, now)) {
                    sanctionAt.put(slot, sanction);
                    extra.add(messages.get(viewer, "my.click"));
                }
            });
            inventory.setItem(slot, gui.sanctionItem(viewer, sanction, extra));
        }
        if (shown.isEmpty()) {
            inventory.setItem(22, Items.item(Material.LIME_DYE, messages.get(viewer, "my.empty"), List.of()));
        }
        inventory.setItem(CLOSE, Items.item(Material.BARRIER, messages.get(viewer, "menu.close"), List.of()));
    }
}
