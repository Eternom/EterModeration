package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterModeration.module.record.Records.Note;
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

/** Les notes du staff sur un joueur (invisibles pour lui) : en ajouter une, clic droit pour en effacer une. */
class NotesMenu implements Menu {

    private static final int ADD = 47;
    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Target target;
    private final List<Note> notes;
    private final Inventory inventory;
    private final Map<Integer, Note> noteAt = new HashMap<>();

    NotesMenu(ModGui gui, Player viewer, Target target, List<Note> notes) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.notes = notes;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "notes.title", "player", target.name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Runnable reopen = () -> gui.openNotes(player, target);
        Note note = noteAt.get(slot);
        if (note != null && click.isRightClick()) {
            gui.confirm(player, "delete-note", () -> Tasks.async(gui.plugin(), player, () -> {
                gui.records().deleteNote(note.id());
                return true;
            }, done -> reopen.run(), () -> messages.send(player, "error.generic")), reopen, "text", note.text());
        } else if (slot == ADD) {
            Sounds.click(player);
            gui.askText(player, "note", 255, text -> {
                if (text.isEmpty()) {
                    reopen.run();
                    return;
                }
                String staffName = player.getName();
                Tasks.async(gui.plugin(), player, () -> {
                    gui.records().addNote(target.uuid(), staffName, text);
                    return true;
                }, done -> reopen.run(), () -> messages.send(player, "error.generic"));
            }, reopen, "player", target.name());
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
        for (int i = 0; i < notes.size() && i < ModGui.LIST.size(); i++) {
            Note note = notes.get(i);
            int slot = ModGui.LIST.get(i);
            noteAt.put(slot, note);
            inventory.setItem(slot, Items.item(Material.PAPER, messages.get(viewer, "notes.name", "staff", note.staffName(),
                    "date", Labels.date(note.createdAt())), List.of(
                    messages.get(viewer, "notes.text", "text", note.text()),
                    messages.get(viewer, "notes.click-delete"))));
        }
        inventory.setItem(ADD, Items.item(Material.WRITABLE_BOOK, messages.get(viewer, "notes.add.name"),
                List.of(messages.get(viewer, "notes.add.lore"))));
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-player", "player", target.name()), List.of()));
    }
}
