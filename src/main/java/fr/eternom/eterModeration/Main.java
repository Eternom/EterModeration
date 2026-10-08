package fr.eternom.eterModeration;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.NetworkBus;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterModeration.listeners.Commands;
import fr.eternom.eterModeration.listeners.Events;
import fr.eternom.eterModeration.module.alert.StaffAlerts;
import fr.eternom.eterModeration.module.appeal.AppealRepository;
import fr.eternom.eterModeration.module.appeal.AppealService;
import fr.eternom.eterModeration.module.filter.ChatFilter;
import fr.eternom.eterModeration.module.filter.FilterListener;
import fr.eternom.eterModeration.module.menu.ModGui;
import fr.eternom.eterModeration.module.record.RecordListener;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.sanction.Enforcement;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Motives;
import fr.eternom.eterModeration.module.sanction.MuteGuard;
import fr.eternom.eterModeration.module.sanction.SanctionRepository;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.Motives.Motive;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;


/**
 * La modération du réseau, sur chaque serveur Paper : sanctions selon un barème (avertissement, mute, prison, ban),
 * appels, alertes au staff, journal du chat, comptes liés, notes, filtre du chat. La prison et le ban sont appliqués
 * par le proxy (EterVelocityModeration), qui lit les mêmes tables.
 */
public final class Main extends JavaPlugin {

    /** Version minimale d'EterLib : Redis obligatoire (bus réseau) depuis 1.8.0. */
    private static final String REQUIRED_ETERLIB = "1.8.0";
    /** Préfixe des tables : etermod_sanctions, etermod_appeals, etermod_notes, etermod_chat, etermod_links, etermod_settings. */
    private static final String TABLE_PREFIX = "etermod_";
    private static final long DAY_TICKS = 20L * 60 * 60 * 24;

    private Messages messages;
    private ModGui gui;
    private MuteGuard muteGuard;
    private FilterListener filterListener;
    private RecordListener recordListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // En premier : vérifie la version d'EterLib (un EterLib < 1.3.0 n'a pas requireVersion, d'où le catch)
        try {
            if (!EterLib.requireVersion(this, REQUIRED_ETERLIB)) {
                return;
            }
        } catch (LinkageError tooOld) {
            getLogger().severe("EterLib " + REQUIRED_ETERLIB + " ou plus récent est nécessaire.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");
        Database database = lib.database(TABLE_PREFIX);
        NetworkBus bus = lib.network(this, "etermoderation", messages);
        String server = lib.getServerName();

        Motives motives = Motives.load(getConfig().getConfigurationSection("motives"), getLogger());
        registerMotivePermissions(motives);
        Labels labels = new Labels(messages);
        SanctionRepository sanctionRepository = new SanctionRepository(database);
        Records records = new Records(database);
        StaffAlerts alerts = new StaffAlerts(bus, messages);
        Enforcement enforcement = new Enforcement(this, sanctionRepository, bus);
        SanctionService sanctions = new SanctionService(this, sanctionRepository, enforcement, alerts, bus, messages, labels, server);
        AppealService appeals = new AppealService(this, new AppealRepository(database), sanctions, alerts, bus, messages, labels);
        gui = new ModGui(this, sanctions, appeals, records, motives, lib.getPlayers(), messages, labels);

        Set<String> privateCommands = lower(getConfig().getStringList("private-commands"));
        muteGuard = new MuteGuard(enforcement, messages, labels, privateCommands);
        filterListener = new FilterListener(this, new ChatFilter(getConfig().getConfigurationSection("filter")), sanctions, motives,
                alerts, messages, privateCommands, getConfig().getConfigurationSection("filter"));
        recordListener = new RecordListener(this, records, server, privateCommands);

        int keepDays = Math.max(1, getConfig().getInt("chat-log.days", 30));
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                records.purgeChat(keepDays);
            } catch (RuntimeException e) {
                getLogger().warning("Journal du chat non purgé : " + e.getMessage());
            }
        }, 20L * 60, DAY_TICKS);

        new Commands(this);
        new Events(this);
    }

    /** eter.mod.motive.<motif> pour chaque motif de la config, tous enfants de eter.mod.motive.* (donc de eter.mod.*). */
    private void registerMotivePermissions(Motives motives) {
        PluginManager manager = getServer().getPluginManager();
        Permission all = manager.getPermission("eter.mod.motive.*");
        for (Motive motive : motives.all()) {
            if (manager.getPermission(motive.permission()) == null) {
                manager.addPermission(new Permission(motive.permission(), PermissionDefault.OP));
            }
            if (all != null) {
                all.getChildren().put(motive.permission(), true);
            }
        }
        if (all != null) {
            all.recalculatePermissibles();
        }
    }

    private static Set<String> lower(Iterable<String> values) {
        Set<String> set = new HashSet<>();
        values.forEach(value -> set.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(set);
    }

    public Messages getMessages() {
        return messages;
    }

    public ModGui getGui() {
        return gui;
    }

    public MuteGuard getMuteGuard() {
        return muteGuard;
    }

    public FilterListener getFilterListener() {
        return filterListener;
    }

    public RecordListener getRecordListener() {
        return recordListener;
    }
}
