package fr.eternom.eterModeration.module.menu;

import fr.eternom.eterLib.helper.gui.Frame;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterModeration.module.menu.ModGui.Profile;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Sanction;
import fr.eternom.eterModeration.module.sanction.Target;
import fr.eternom.eterModeration.module.staff.Freeze;
import fr.eternom.eterModeration.module.staff.StaffMode;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * La fiche d'un joueur : sa tête (connecté ou non, où, sanctions en cours), puis sanctionner, son historique, les
 * notes du staff, ses derniers messages, ses comptes liés. Un bouton grisé = permission manquante.
 */
class PlayerMenu implements Menu {

    private static final int HEAD = 13;
    private static final int GOTO = 28;
    private static final int SANCTION = 29;
    private static final int HISTORY = 30;
    private static final int NOTES = 31;
    private static final int CHAT = 32;
    private static final int ALTS = 33;
    private static final int FREEZE = 34;
    private static final int HOMES = 39;
    private static final int INVENTORY = 41;
    private static final int BACK = 49;

    private final ModGui gui;
    private final Messages messages;
    private final Player viewer;
    private final Profile profile;
    private final Inventory inventory;

    PlayerMenu(ModGui gui, Player viewer, Profile profile) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.profile = profile;
        this.inventory = Bukkit.createInventory(this, 54, messages.get(viewer, "player.title", "player", profile.target().name()));
        render();
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        Target target = profile.target();
        switch (slot) {
            case SANCTION -> {
                Sounds.page(player);
                gui.openMotives(player, target);
            }
            case HISTORY -> {
                Sounds.page(player);
                gui.openHistory(player, target);
            }
            case NOTES -> {
                Sounds.page(player);
                gui.openNotes(player, target);
            }
            case CHAT -> gui.showChat(player, target);
            case ALTS -> {
                Sounds.page(player);
                gui.openAlts(player, target);
            }
            case GOTO -> gui.goTo(player, target);
            case HOMES -> Integrations.openHomes(player, target);
            case INVENTORY -> Integrations.openInventory(player, target);
            case FREEZE -> {
                if (online()) {
                    gui.freeze().toggle(player, target);
                    gui.openPlayer(player, target);
                }
            }
            case BACK -> {
                Sounds.page(player);
                gui.openStaff(player);
            }
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
        long now = System.currentTimeMillis();
        Labels labels = gui.labels();
        List<Component> lore = new ArrayList<>();
        profile.network().ifPresentOrElse(network -> {
            lore.add(network.isOnline()
                    ? messages.get(viewer, "player.online", "server", String.valueOf(network.server()))
                    : messages.get(viewer, "player.offline", "date", Labels.date(network.lastSeen())));
            lore.add(messages.get(viewer, "player.first-seen", "date", Labels.date(network.firstSeen())));
        }, () -> lore.add(messages.get(viewer, "player.never-seen")));
        profile.clan().ifPresent(clan -> lore.add(messages.get(viewer, "player.clan", "clan", clan)));
        if (profile.active().isEmpty()) {
            lore.add(messages.get(viewer, "player.clean"));
        }
        for (Sanction sanction : profile.active()) {
            lore.add(messages.get(viewer, "player.active", "type", labels.type(viewer, sanction.type()),
                    "time", labels.remaining(viewer, sanction, now)));
        }
        inventory.setItem(HEAD, Items.head(ModGui.profile(profile.target()),
                messages.get(viewer, "player.name", "player", profile.target().name()), lore));
        inventory.setItem(SANCTION, button(Material.IRON_SWORD, "player.sanction", ModGui.USE));
        inventory.setItem(HISTORY, button(Material.BOOK, "player.history", ModGui.HISTORY));
        inventory.setItem(NOTES, button(Material.WRITABLE_BOOK, "player.notes", ModGui.NOTES));
        inventory.setItem(CHAT, button(Material.OAK_SIGN, "player.chat", ModGui.CHATLOG));
        inventory.setItem(ALTS, button(Material.PLAYER_HEAD, "player.alts", ModGui.ALTS));
        if (Integrations.has("EterHome")) {
            inventory.setItem(HOMES, Items.item(Material.RED_BED, messages.get(viewer, "player.homes.name"),
                    List.of(messages.get(viewer, "player.homes.lore"))));
        }
        if (Integrations.has("EterSync")) {
            inventory.setItem(INVENTORY, Items.item(Material.CHEST, messages.get(viewer, "player.inventory.name"),
                    List.of(messages.get(viewer, "player.inventory.lore"))));
        }
        if (online()) {
            inventory.setItem(GOTO, button(Material.ENDER_PEARL, "player.goto", StaffMode.PERMISSION));
            inventory.setItem(FREEZE, button(Material.PACKED_ICE, "player.freeze", Freeze.PERMISSION));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, messages.get(viewer, "menu.back-to-staff"), List.of()));
    }

    private boolean online() {
        return profile.network().map(NetworkPlayer::isOnline).orElse(false);
    }

    private ItemStack button(Material icon, String key, String permission) {
        boolean can = viewer.hasPermission(permission);
        return Items.item(can ? icon : Material.GRAY_DYE, messages.get(viewer, key + ".name"),
                List.of(messages.get(viewer, can ? key + ".lore" : "menu.locked")));
    }
}
