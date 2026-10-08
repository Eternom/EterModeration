package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterLib.module.teleport.Destination;
import fr.eternom.eterLib.module.vanish.Vanish;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.menu.ModGui;
import fr.eternom.eterModeration.module.sanction.Target;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Le mode staff (/staff, eter.mod.staff), sur tout le réseau : l'inventaire est mis de côté en base
 * (StaffInventories) et rendu à la sortie, le joueur devient invisible (EterLib Vanish), vole, ne prend pas de coups,
 * n'est pas ciblé par les monstres et ne ramasse rien. Une barre d'outils : fiche d'un joueur, geler, joueur au hasard,
 * visible/invisible, signalements, quitter. L'état est gardé dans Redis (etermod:staff) : il suit le joueur d'un serveur
 * à l'autre (outils rendus par EterSync, ou redonnés s'ils manquent) et d'une connexion à l'autre.
 * On n'entre ni ne sort du mode sur un serveur de staff.blocked-servers (ex : la prison, sans EterSync : l'inventaire
 * rendu y resterait).
 */
public class StaffMode implements Listener {

    public static final String PERMISSION = "eter.mod.staff";
    private static final String KEY = "etermod:staff";
    /** Après l'arrivée : laisser EterSync rendre l'inventaire avant de vérifier les outils. */
    private static final long JOIN_DELAY_TICKS = 60;

    /** Les outils et leur case dans la barre. */
    private enum Tool {
        PROFILE(0, Material.BOOK), FREEZE(1, Material.STICK), RANDOM(2, Material.COMPASS), VANISH(4, Material.GRAY_DYE),
        REPORTS(7, Material.PAPER), LEAVE(8, Material.RED_DYE);

        final int slot;
        final Material icon;

        Tool(int slot, Material icon) {
            this.slot = slot;
            this.icon = icon;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final JavaPlugin plugin;
    private final RedisCache redis;
    private final StaffInventories inventories;
    private final Freeze freeze;
    private final ModGui gui;
    private final StaffAlerts alerts;
    private final Messages messages;
    private final List<String> blockedServers;
    private final NamespacedKey toolKey;
    /** Membres du staff en mode staff connectés ici. */
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** Entrée ou sortie en cours (base) : un second clic attend. */
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public StaffMode(JavaPlugin plugin, RedisCache redis, StaffInventories inventories, Freeze freeze, ModGui gui, StaffAlerts alerts,
                     Messages messages, List<String> blockedServers) {
        this.plugin = plugin;
        this.redis = redis;
        this.inventories = inventories;
        this.freeze = freeze;
        this.gui = gui;
        this.alerts = alerts;
        this.messages = messages;
        this.blockedServers = blockedServers.stream().map(prefix -> prefix.toLowerCase(Locale.ROOT)).toList();
        this.toolKey = new NamespacedKey(plugin, "staff_tool");
    }

    public boolean isActive(UUID player) {
        return active.contains(player);
    }

    public void toggle(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            messages.send(player, "sanction.no-permission");
            return;
        }
        String server = EterLib.get().getServerName().toLowerCase(Locale.ROOT);
        if (blockedServers.stream().anyMatch(server::startsWith)) {
            messages.send(player, "staff.not-here");
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            return;
        }
        if (active.contains(player.getUniqueId())) {
            disable(player);
        } else {
            enable(player);
        }
    }

    /** L'inventaire part en base AVANT d'être vidé : rien n'est perdu si le serveur s'arrête entre les deux. */
    private void enable(Player player) {
        UUID uuid = player.getUniqueId();
        byte[] contents = ItemStack.serializeItemsAsBytes(Arrays.stream(player.getInventory().getContents())
                .map(item -> item == null ? ItemStack.empty() : item).toList());
        Tasks.async(plugin, player, () -> {
            inventories.save(uuid, contents);
            redis.setHashField(KEY, uuid.toString(), "1");
            return true;
        }, saved -> {
            busy.remove(uuid);
            player.getInventory().clear();
            apply(player);
            messages.send(player, "staff.on");
            alerts.alert("alert.staff-on", "staff", player.getName());
        }, () -> {
            busy.remove(uuid);
            messages.send(player, "error.generic");
        });
    }

    /** L'inventaire est repris en base (et effacé) avant d'être rendu : jamais rendu deux fois. */
    private void disable(Player player) {
        UUID uuid = player.getUniqueId();
        Tasks.async(plugin, player, () -> {
            redis.deleteHashField(KEY, uuid.toString());
            return inventories.take(uuid);
        }, contents -> {
            busy.remove(uuid);
            active.remove(uuid);
            player.getInventory().clear();
            contents.ifPresent(bytes -> player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(bytes)));
            if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
                player.setFlying(false);
                player.setAllowFlight(false);
            }
            player.setInvulnerable(false);
            EterLib.get().getVanish().set(player, false);
            messages.send(player, "staff.off");
            alerts.alert("alert.staff-off", "staff", player.getName());
        }, () -> {
            busy.remove(uuid);
            messages.send(player, "error.generic");
        });
    }

    /** Thread principal : invisible, vol, invulnérable, outils (seulement ceux qui manquent). */
    private void apply(Player player) {
        active.add(player.getUniqueId());
        EterLib.get().getVanish().set(player, true);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setInvulnerable(true);
        for (Tool tool : Tool.values()) {
            ItemStack current = player.getInventory().getItem(tool.slot);
            if (tool(current) != tool) {
                player.getInventory().setItem(tool.slot, toolItem(player, tool));
            }
        }
    }

    private ItemStack toolItem(Player player, Tool tool) {
        Material icon = tool == Tool.VANISH && !EterLib.get().getVanish().isVanished(player.getUniqueId()) ? Material.LIME_DYE : tool.icon;
        ItemStack item = Items.item(icon, messages.get(player, "staff-tool." + tool.id() + ".name"),
                List.of(messages.get(player, "staff-tool." + tool.id() + ".lore")));
        item.editPersistentDataContainer(data -> data.set(toolKey, PersistentDataType.STRING, tool.name()));
        return item;
    }

    private Tool tool(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        String id = item.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        try {
            return id == null ? null : Tool.valueOf(id);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    // ---------- Arrivée, départ ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                Tasks.async(plugin, player, () -> redis.getHashField(KEY, uuid).isPresent(), staff -> {
                    if (staff && player.hasPermission(PERMISSION)) {
                        apply(player);
                    }
                }, () -> { });
            }
        }, JOIN_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        busy.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Outils ----------

    /** Clic droit sur un joueur : sa fiche, ou le geler. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player staff = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || !active.contains(staff.getUniqueId())
                || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        Tool tool = tool(staff.getInventory().getItemInMainHand());
        if (tool == Tool.PROFILE) {
            event.setCancelled(true);
            gui.openPlayer(staff, new Target(target.getUniqueId(), target.getName()));
        } else if (tool == Tool.FREEZE) {
            event.setCancelled(true);
            freeze.toggle(staff, new Target(target.getUniqueId(), target.getName()));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player staff = event.getPlayer();
        Tool tool = tool(event.getItem());
        if (tool == null) {
            return;
        }
        // Un outil ne se pose ni ne s'utilise jamais (même hors du mode staff)
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND || !active.contains(staff.getUniqueId())
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        switch (tool) {
            case RANDOM -> randomPlayer(staff);
            case VANISH -> {
                Vanish vanish = EterLib.get().getVanish();
                vanish.set(staff, !vanish.isVanished(staff.getUniqueId()));
                staff.getInventory().setItem(Tool.VANISH.slot, toolItem(staff, Tool.VANISH));
                messages.actionBar(staff, vanish.isVanished(staff.getUniqueId()) ? "staff.vanished" : "staff.visible");
            }
            case REPORTS -> gui.openReports(staff);
            case LEAVE -> toggle(staff);
            default -> {
            }
        }
    }

    /** Un joueur au hasard sur tout le réseau (ni soi-même ni un invisible), et on y va. */
    private void randomPlayer(Player staff) {
        Tasks.async(plugin, staff, () -> EterLib.get().getPlayers().listOnline().stream()
                .filter(player -> !player.uuid().equals(staff.getUniqueId())).toList(), players -> {
            if (players.isEmpty()) {
                messages.actionBar(staff, "staff.nobody");
                return;
            }
            NetworkPlayer target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
            EterLib.get().getTeleports().teleportNow(staff, Destination.toPlayer(target.uuid(), target.server(), target.name()));
        }, () -> messages.send(staff, "error.generic"));
    }

    // ---------- Protection ----------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (tool(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    /** Un outil ne quitte pas l'inventaire du joueur (coffre, four...). */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getClickedInventory() != event.getWhoClicked().getInventory() && tool(event.getCursor()) != null
                || event.isShiftClick() && tool(event.getCurrentItem()) != null
                || event.getHotbarButton() >= 0 && event.getClickedInventory() != event.getWhoClicked().getInventory()
                && tool(event.getWhoClicked().getInventory().getItem(event.getHotbarButton())) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (active.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && active.contains(event.getTarget().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (active.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
