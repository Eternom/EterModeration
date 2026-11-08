package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.Arrays;

/**
 * Une copie en lecture seule (coffre, tonneau, four, shulker, ender chest, inventaire d'un joueur) : on regarde, on ne
 * prend rien (un menu : chaque clic est annulé). Un clic sur une shulker de la copie ouvre son contenu, en lecture seule
 * aussi. Rien n'est touché dans le vrai conteneur : ni animation, ni son pour les joueurs autour.
 */
public final class ReadOnlyView implements Menu {

    private final Inventory inventory;
    private final ItemStack[] contents;

    private ReadOnlyView(Component title, ItemStack[] contents) {
        int size = Math.min(54, Math.max(9, (contents.length + 8) / 9 * 9));
        this.inventory = Bukkit.createInventory(this, size, title);
        this.contents = Arrays.copyOf(contents, Math.min(contents.length, size));
        for (int i = 0; i < this.contents.length; i++) {
            ItemStack item = this.contents[i];
            if (item != null && !item.isEmpty()) {
                inventory.setItem(i, item.clone());
            }
        }
    }

    /** Ouvre une copie de contents (copiée ici : le vrai conteneur peut changer ensuite sans effet). */
    public static void open(Player viewer, Component title, ItemStack[] contents) {
        viewer.openInventory(new ReadOnlyView(title, contents).getInventory());
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot < 0 || slot >= contents.length) {
            return;
        }
        ItemStack item = contents[slot];
        if (item != null && item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            Sounds.page(player);
            open(player, item.effectiveName(), box.getInventory().getContents());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
