package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Motives;
import fr.eternom.eterModeration.module.sanction.Motives.Motive;
import fr.eternom.eterModeration.module.sanction.Motives.Step;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.SanctionType;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanctionner : un motif du barème, et la peine qu'il donnera (selon le nombre de fois déjà sanctionné pour ce motif)
 * est affichée AVANT de valider. En bas, les sanctions libres (eter.mod.free) : durée et raison au choix.
 */
class MotivesMenu implements Menu {

    private static final Map<Integer, SanctionType> FREE = Map.of(47, SanctionType.WARN, 48, SanctionType.MUTE,
            50, SanctionType.JAIL, 51, SanctionType.BAN);
    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Target target;
    private final Map<String, Integer> counts;
    private final Inventory inventory;
    private final Map<Integer, Motive> motiveAt = new HashMap<>();

    MotivesMenu(ModGui gui, Player viewer, Target target, Map<String, Integer> counts) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.counts = counts;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "motives.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable back = () -> gui.openMotives(player, target);
        Labels labels = gui.labels();
        Motive motive = motiveAt.get(slot);
        if (motive != null) {
            Step step = step(motive);
            if (!SanctionService.canUse(player, motive, step)) {
                messages.send(player, "sanction.no-permission");
                return;
            }
            Sounds.click(player);
            gui.askText(player, "give", 255, reason -> gui.sanctions().give(player, target, motive, step, reason,
                            () -> gui.openPlayer(player, target)), back,
                    "player", target.name(), "type", labels.type(player, step.type()),
                    "motive", labels.motive(player, motive.id()), "time", labels.length(player, step.type(), step.duration()));
        } else if (FREE.containsKey(slot)) {
            SanctionType type = FREE.get(slot);
            if (!player.hasPermission(SanctionService.FREE_PERMISSION) || !player.hasPermission(type.permission())) {
                messages.send(player, "sanction.no-permission");
                return;
            }
            Sounds.click(player);
            gui.askFree(player, type.lasts(), values -> {
                Duration duration = null;
                if (type.lasts() && !values[0].equalsIgnoreCase("perm")) {
                    duration = Motives.parseDuration(values[0].toLowerCase());
                    if (duration == null) {
                        messages.send(player, "sanction.bad-duration");
                        back.run();
                        return;
                    }
                }
                gui.sanctions().giveFree(player, target, type, duration, values[1], () -> gui.openPlayer(player, target));
            }, back, "player", target.name(), "type", labels.type(player, type));
        } else if (slot == BACK) {
            Sounds.page(player);
            gui.openPlayer(player, target);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private Step step(Motive motive) {
        return motive.step(counts.getOrDefault(motive.id(), 0));
    }

    private void render() {
        Frame.draw(inventory, ModGui.STAFF_FRAME);
        Labels labels = gui.labels();
        List<Motive> motives = gui.motives().all();
        for (int i = 0; i < motives.size() && i < ModGui.LIST.size(); i++) {
            Motive motive = motives.get(i);
            Step step = step(motive);
            int slot = ModGui.LIST.get(i);
            motiveAt.put(slot, motive);
            boolean can = SanctionService.canUse(viewer, motive, step);
            inventory.setItem(slot, Items.item(can ? motive.icon() : Material.GRAY_DYE,
                    messages.get(viewer, "motives.name", "motive", labels.motive(viewer, motive.id())), List.of(
                            messages.get(viewer, "motives.next", "type", labels.type(viewer, step.type()),
                                    "time", labels.length(viewer, step.type(), step.duration())),
                            messages.get(viewer, "motives.count", "count", String.valueOf(counts.getOrDefault(motive.id(), 0))),
                            messages.get(viewer, can ? "motives.click" : "menu.locked"))));
        }
        boolean free = viewer.hasPermission(SanctionService.FREE_PERMISSION);
        FREE.forEach((slot, type) -> {
            boolean can = free && viewer.hasPermission(type.permission());
            inventory.setItem(slot, Items.item(can ? type.icon() : Material.GRAY_DYE,
                    messages.get(viewer, "motives.free.name", "type", labels.type(viewer, type)),
                    List.of(messages.get(viewer, can ? "motives.free.lore" : "menu.locked"))));
        });
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-player", "player", target.name()), List.of()));
    }
}
