package fr.eternom.eterModeration.module.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterLib.module.player.PlayerDirectory.NetworkPlayer;
import fr.eternom.eterLib.module.teleport.Destination;
import fr.eternom.eterModeration.module.appeal.AppealRepository.Appeal;
import fr.eternom.eterModeration.module.appeal.AppealService;
import fr.eternom.eterModeration.module.record.Records;
import fr.eternom.eterModeration.module.report.ReportService;
import fr.eternom.eterModeration.module.staff.Freeze;
import fr.eternom.eterModeration.module.staff.StaffMode;
import fr.eternom.eterModeration.module.staff.StaffLog;
import fr.eternom.eterModeration.module.alert.JoinWatch;
import java.time.Duration;
import fr.eternom.eterModeration.module.record.Records.ChatLine;
import fr.eternom.eterModeration.module.sanction.Labels;
import fr.eternom.eterModeration.module.sanction.Motives;
import fr.eternom.eterModeration.module.sanction.Motives.Motive;
import fr.eternom.eterModeration.module.sanction.Sanction;
import fr.eternom.eterModeration.module.sanction.SanctionRepository;
import fr.eternom.eterModeration.module.sanction.SanctionService;
import fr.eternom.eterModeration.module.sanction.Target;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Toute l'interface de modération. Staff : /mod (appels, dernières sanctions, chercher un joueur), /mod <joueur>
 * (sa fiche : sanctionner, historique, notes, chat, comptes liés). Joueur : /appeal (ses sanctions, faire appel).
 * Chaque menu charge ses données en tâche de fond, puis s'ouvre ; les saisies et confirmations passent par des
 * fenêtres (Dialogs), Annuler rouvre le menu d'où l'on vient. Chaque bouton vérifie sa permission.
 */
public class ModGui {

    public static final String USE = "eter.mod.use";
    static final String HISTORY = "eter.mod.history";
    static final String NOTES = "eter.mod.notes";
    static final String CHATLOG = "eter.mod.chatlog";
    static final String ALTS = "eter.mod.alts";
    static final String INVSEE = "eter.mod.invsee";
    static final String WATCH = "eter.mod.watch";
    static final String BAN_ALTS = "eter.mod.ban.alts";
    static final String STAFF_STATS = "eter.mod.staffstats";
    private static final int CHAT_LINES = 20;
    /** Les cases d'une liste (menus de 54) : 4 lignes de 7, dans le cadre. */
    static final List<Integer> LIST = IntStream.rangeClosed(1, 4)
            .flatMap(row -> IntStream.rangeClosed(row * 9 + 1, row * 9 + 7)).boxed().toList();
    /** Cadre des menus du staff (vue admin) ; celui des joueurs est orange. */
    static final Material STAFF_FRAME = Material.RED_STAINED_GLASS_PANE;

    /** Ce que montre la fiche d'un joueur : aussi son gel en cours et sa surveillance (« staff;raison »). */
    record Profile(Target target, Optional<NetworkPlayer> network, List<Sanction> active, Optional<String> clan,
                   Optional<Freeze.Info> frozen, Optional<String> watch) {
    }

    /** Les compteurs du menu /mod. */
    record Counts(int appeals, int reports, int pending) {
    }

    /** Un appel ouvert et la sanction visée. */
    record OpenAppeal(Appeal appeal, Sanction sanction) {
    }

    private final JavaPlugin plugin;
    private final SanctionService sanctions;
    private final AppealService appeals;
    private final Records records;
    private final Motives motives;
    private final PlayerDirectory players;
    private final Messages messages;
    private final Labels labels;
    private final ReportService reports;
    private final Freeze freeze;
    private final StaffLog staffLog;
    private final JoinWatch watch;
    private final InventoryCompare compare;

    public ModGui(JavaPlugin plugin, SanctionService sanctions, AppealService appeals, Records records, Motives motives,
                  PlayerDirectory players, Messages messages, Labels labels, ReportService reports, Freeze freeze,
                  StaffLog staffLog, JoinWatch watch, InventoryCompare compare) {
        this.reports = reports;
        this.freeze = freeze;
        this.staffLog = staffLog;
        this.watch = watch;
        this.compare = compare;
        this.plugin = plugin;
        this.sanctions = sanctions;
        this.appeals = appeals;
        this.records = records;
        this.motives = motives;
        this.players = players;
        this.messages = messages;
        this.labels = labels;
    }

    // ---------- Staff ----------

    public void openStaff(Player staff) {
        load(staff, () -> new Counts(appeals.repository().openAppeals().size(), reports.repository().countOpen(),
                        sanctions.countPending()),
                counts -> new StaffMenu(this, staff, counts).getInventory());
    }

    /** Les bans définitifs à valider (eter.mod.permanent). */
    void openPending(Player staff) {
        if (allowed(staff, SanctionService.PERMANENT_PERMISSION)) {
            load(staff, sanctions::pendingBans, list -> new PendingMenu(this, staff, list).getInventory());
        }
    }

    /** Statistiques du staff sur 30 jours (eter.mod.staffstats), dans le chat : sanctions, signalements, gels... */
    void showStaffStats(Player staff) {
        if (!allowed(staff, STAFF_STATS)) {
            return;
        }
        staff.closeInventory();
        long since = System.currentTimeMillis() - 30L * 86_400_000L;
        Tasks.async(plugin, staff, () -> staffLog.stats(since), stats -> {
            messages.send(staff, stats.isEmpty() ? "staff-stats.empty" : "staff-stats.header");
            stats.forEach((name, actions) -> {
                String detail = actions.entrySet().stream()
                        .map(entry -> messages.plain(staff, "staff-action." + entry.getKey().name().toLowerCase(java.util.Locale.ROOT))
                                + " " + entry.getValue())
                        .collect(java.util.stream.Collectors.joining(", "));
                messages.send(staff, "staff-stats.line", "staff", name, "detail", detail);
            });
        }, () -> messages.send(staff, "error.generic"));
    }

    /** Les preuves jointes (derniers messages du joueur), dans le chat du staff. */
    void showEvidence(Player staff, String playerName, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            messages.send(staff, "evidence.empty");
            return;
        }
        messages.send(staff, "evidence.header", "player", playerName);
        for (String line : evidence.split("\n")) {
            messages.send(staff, "evidence.line", "line", line);
        }
    }

    /** Son coffre de l'Ender, en lecture seule : en direct s'il est ici, sinon sa dernière sauvegarde (EterSync). */
    void openEnderChest(Player staff, Target target) {
        if (!allowed(staff, INVSEE)) {
            return;
        }
        staffLog.log(staff.getName(), StaffLog.Action.INVSEE, target.name() + " (ender)");
        Player local = Bukkit.getPlayer(target.uuid());
        if (local != null) {
            ReadOnlyView.open(staff, messages.get(staff, "view.ender", "player", target.name()), local.getEnderChest().getContents());
            return;
        }
        Tasks.async(plugin, staff, () -> Integrations.lastEnderChest(target), contents -> contents.ifPresentOrElse(
                items -> ReadOnlyView.open(staff, messages.get(staff, "view.ender", "player", target.name()), items),
                () -> messages.send(staff, "view.none", "player", target.name())), () -> messages.send(staff, "error.generic"));
    }

    /** Son inventaire complet (armure et seconde main comprises), en lecture seule : en direct s'il est ici, sinon sauvegardé. */
    void openFullInventory(Player staff, Target target) {
        if (!allowed(staff, INVSEE)) {
            return;
        }
        staffLog.log(staff.getName(), StaffLog.Action.INVSEE, target.name());
        Player local = Bukkit.getPlayer(target.uuid());
        if (local != null) {
            ReadOnlyView.open(staff, messages.get(staff, "view.inventory", "player", target.name()), local.getInventory().getContents());
            return;
        }
        Tasks.async(plugin, staff, () -> Integrations.lastInventory(target), contents -> contents.ifPresentOrElse(
                items -> ReadOnlyView.open(staff, messages.get(staff, "view.inventory", "player", target.name()), items),
                () -> messages.send(staff, "view.none", "player", target.name())), () -> messages.send(staff, "error.generic"));
    }

    /** Comparer son contenu avec celui de la veille (duplication), dans le chat. */
    void compareInventory(Player staff, Target target) {
        if (allowed(staff, INVSEE)) {
            staff.closeInventory();
            compare.compare(staff, target);
        }
    }

    /** Le mettre sous surveillance (avec une raison) ou l'en retirer (eter.mod.watch). */
    void toggleWatch(Player staff, Target target, boolean watched) {
        if (!allowed(staff, WATCH)) {
            return;
        }
        String staffName = staff.getName();
        if (watched) {
            Tasks.async(plugin, staff, () -> watch.toggle(target.uuid(), staffName, ""), now -> {
                messages.send(staff, "watch.off", "player", target.name());
                openPlayer(staff, target);
            }, () -> messages.send(staff, "error.generic"));
            return;
        }
        askText(staff, "watch", 100, reason -> Tasks.async(plugin, staff, () -> watch.toggle(target.uuid(), staffName, reason), now -> {
            messages.send(staff, "watch.on", "player", target.name());
            records.addNote(target.uuid(), staffName, messages.plain(staff, "watch.note", "reason", reason));
            openPlayer(staff, target);
        }, () -> messages.send(staff, "error.generic")), () -> openPlayer(staff, target), "player", target.name());
    }

    /** Changer la durée d'une sanction en cours (eter.mod.change) : la nouvelle durée totale, « perm » = définitive. */
    void askChange(Player staff, Target target, Sanction sanction) {
        askText(staff, "change", 8, text -> {
            Duration duration = text.equalsIgnoreCase("perm") ? null : Motives.parseDuration(text.toLowerCase(java.util.Locale.ROOT));
            if (duration == null && !text.equalsIgnoreCase("perm")) {
                messages.send(staff, "sanction.bad-duration");
                return;
            }
            sanctions.change(staff, sanction, duration, () -> openHistory(staff, target));
        }, () -> openHistory(staff, target), "player", target.name(), "type", labels.type(staff, sanction.type()),
                "id", String.valueOf(sanction.id()));
    }

    /**
     * Étendre son ban aux comptes liés (eter.mod.ban.alts) : chacun reçoit un ban de même fin, motif « evasion » (ou
     * libre), raison « compte lié à ... ».
     */
    void banAlts(Player staff, Target target, List<Records.Link> links) {
        if (!allowed(staff, BAN_ALTS) || links.isEmpty()) {
            return;
        }
        Tasks.async(plugin, staff, () -> repository().active(target.uuid(), System.currentTimeMillis()).stream()
                .filter(sanction -> sanction.type() == fr.eternom.eterModeration.module.sanction.SanctionType.BAN).findFirst(), ban -> {
            if (ban.isEmpty()) {
                messages.send(staff, "alts.not-banned", "player", target.name());
                return;
            }
            Duration duration = ban.get().isPermanent() ? null : Duration.ofMillis(ban.get().remainingMillis(System.currentTimeMillis()));
            String reason = messages.plain(staff, "alts.ban-reason", "player", target.name());
            confirm(staff, "ban-alts", () -> {
                for (Records.Link link : links) {
                    sanctions.giveFree(staff, new Target(link.player(), link.name()),
                            fr.eternom.eterModeration.module.sanction.SanctionType.BAN, duration, reason, () -> { });
                }
                openAlts(staff, target);
            }, () -> openAlts(staff, target), "player", target.name(), "count", String.valueOf(links.size()));
        }, () -> messages.send(staff, "error.generic"));
    }

    /** /mod <joueur> : le dernier joueur vu sous ce nom, sur tout le réseau. */
    public void openPlayer(Player staff, String name) {
        Tasks.async(plugin, staff, () -> players.find(name), found -> found.ifPresentOrElse(
                player -> openPlayer(staff, new Target(player.uuid(), player.name())),
                () -> messages.send(staff, "player.unknown", "player", name)), () -> messages.send(staff, "error.generic"));
    }

    public void openPlayer(Player staff, Target target) {
        load(staff, () -> new Profile(target, players.get(target.uuid()), repository().active(target.uuid(), System.currentTimeMillis()),
                        Integrations.clan(target), freeze.info(target.uuid()), watch.watch(target.uuid())),
                profile -> new PlayerMenu(this, staff, profile).getInventory());
    }

    void openMotives(Player staff, Target target) {
        load(staff, () -> {
            Map<String, Integer> counts = new HashMap<>();
            for (Motive motive : motives.all()) {
                counts.put(motive.id(), repository().count(target.uuid(), motive.id()));
            }
            return counts;
        }, counts -> new MotivesMenu(this, staff, target, counts).getInventory());
    }

    void openHistory(Player staff, Target target) {
        if (allowed(staff, HISTORY)) {
            load(staff, () -> repository().history(target.uuid()), history -> new HistoryMenu(this, staff, target, history).getInventory());
        }
    }

    void openNotes(Player staff, Target target) {
        if (allowed(staff, NOTES)) {
            load(staff, () -> records.notes(target.uuid()), notes -> new NotesMenu(this, staff, target, notes).getInventory());
        }
    }

    void openAlts(Player staff, Target target) {
        if (allowed(staff, ALTS)) {
            load(staff, () -> records.links(target.uuid()), links -> new AltsMenu(this, staff, target, links).getInventory());
        }
    }

    void openRecent(Player staff) {
        if (allowed(staff, HISTORY)) {
            load(staff, () -> repository().recent(28), recent -> new RecentMenu(this, staff, recent).getInventory());
        }
    }

    void openAppeals(Player staff) {
        if (allowed(staff, AppealService.PERMISSION)) {
            load(staff, () -> {
                List<OpenAppeal> list = new ArrayList<>();
                for (Appeal appeal : appeals.repository().openAppeals()) {
                    repository().byId(appeal.sanctionId()).ifPresent(sanction -> list.add(new OpenAppeal(appeal, sanction)));
                }
                return list;
            }, list -> new AppealsMenu(this, staff, list).getInventory());
        }
    }

    /** Les signalements ouverts (eter.mod.reports). */
    public void openReports(Player staff) {
        if (allowed(staff, ReportService.PERMISSION)) {
            load(staff, () -> reports.repository().open(28), list -> new ReportsMenu(this, staff, list).getInventory());
        }
    }

    /** Aller voir un joueur connecté, où qu'il soit (eter.mod.staff). */
    void goTo(Player staff, Target target) {
        if (!allowed(staff, StaffMode.PERMISSION)) {
            return;
        }
        staff.closeInventory();
        staffLog.log(staff.getName(), StaffLog.Action.TELEPORT, target.name());
        Tasks.async(plugin, staff, () -> players.getServer(target.uuid()), server -> server.ifPresentOrElse(
                name -> EterLib.get().getTeleports().teleportNow(staff, Destination.toPlayer(target.uuid(), name, target.name())),
                () -> messages.send(staff, "staff.offline", "player", target.name())), () -> messages.send(staff, "error.generic"));
    }

    // ---------- Joueur ----------

    /** /report : le joueur à signaler (fenêtre), puis le motif. */
    public void askReport(Player reporter) {
        askText(reporter, "report-who", 16, name -> {
            if (!name.isEmpty()) {
                openReport(reporter, name);
            }
        }, () -> { });
    }

    /** /report <joueur> : le motif (menu), puis le détail (fenêtre). */
    public void openReport(Player reporter, String name) {
        Tasks.async(plugin, reporter, () -> players.find(name), found -> found.ifPresentOrElse(
                target -> reporter.openInventory(new ReportReasonsMenu(this, reporter, new Target(target.uuid(), target.name())).getInventory()),
                () -> messages.send(reporter, "player.unknown", "player", name)), () -> messages.send(reporter, "error.generic"));
    }

    // ---------- Staff (suite) ----------

    /** Les derniers messages du joueur, dans le chat du staff (on peut les copier). */
    void showChat(Player staff, Target target) {
        if (!allowed(staff, CHATLOG)) {
            return;
        }
        staff.closeInventory();
        Tasks.async(plugin, staff, () -> records.lastMessages(target.uuid(), CHAT_LINES), lines -> {
            messages.send(staff, lines.isEmpty() ? "chatlog.empty" : "chatlog.header", "player", target.name());
            for (ChatLine line : lines.reversed()) {
                staff.sendMessage(messages.get(staff, "chatlog.line", "date", Labels.date(line.createdAt()),
                        "server", line.server(), "message", line.message()));
            }
        }, () -> messages.send(staff, "error.generic"));
    }

    // ---------- Joueur ----------

    /** /appeal : ses sanctions (en cours, et les avertissements) et ses appels. */
    public void openMyAppeals(Player player) {
        load(player, () -> new MyAppealsMenu.Data(repository().history(player.getUniqueId()),
                        appeals.repository().ofPlayer(player.getUniqueId())),
                data -> new MyAppealsMenu(this, player, data).getInventory());
    }

    // ---------- Fenêtres ----------

    /** Saisie d'un texte (dialogs.<key>.title / .body / .label) ; Annuler rouvre onCancel. */
    void askText(Player player, String key, int maxLength, Consumer<String> action, Runnable onCancel, String... placeholders) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "dialogs." + key + ".title", placeholders))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "dialogs." + key + ".body", placeholders))))
                .inputs(List.of(DialogInput.text("value", messages.get(player, "dialogs." + key + ".label"))
                        .maxLength(maxLength).build()))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "dialogs." + key + ".button", placeholders),
                messages.get(player, "dialog.cancel"), response -> action.accept(text(response.getText("value"))), onCancel);
    }

    /** Sanction libre : durée (sauf avertissement) et raison. */
    void askFree(Player player, boolean withDuration, Consumer<String[]> action, Runnable onCancel, String... placeholders) {
        player.closeInventory();
        List<DialogInput> inputs = new ArrayList<>();
        if (withDuration) {
            inputs.add(DialogInput.text("duration", messages.get(player, "dialogs.free.duration")).initial("1d").maxLength(8).build());
        }
        inputs.add(DialogInput.text("reason", messages.get(player, "dialogs.free.reason")).maxLength(255).build());
        DialogBase base = DialogBase.builder(messages.get(player, "dialogs.free.title", placeholders))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "dialogs.free.body", placeholders))))
                .inputs(inputs)
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "dialogs.free.button", placeholders), messages.get(player, "dialog.cancel"),
                response -> action.accept(new String[]{withDuration ? text(response.getText("duration")) : "",
                        text(response.getText("reason"))}), onCancel);
    }

    /** Confirmation (confirm.<key>.title / .body / .button) ; Annuler rouvre onCancel. */
    void confirm(Player player, String key, Runnable action, Runnable onCancel, String... placeholders) {
        player.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(player, "confirm." + key + ".title", placeholders))
                .body(List.of(DialogBody.plainMessage(messages.get(player, "confirm." + key + ".body", placeholders))))
                .build();
        Dialogs.show(plugin, player, base, messages.get(player, "confirm." + key + ".button", placeholders),
                messages.get(player, "dialog.cancel"), response -> action.run(), onCancel);
    }

    // ---------- Accès pour les menus ----------

    /** L'icône d'une sanction : type et numéro, motif, raison, auteur, durée, état. */
    ItemStack sanctionItem(Player viewer, Sanction sanction, List<Component> extra) {
        long now = System.currentTimeMillis();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(viewer, "sanction.motive", "motive", labels.motive(viewer, sanction.motive())));
        if (!sanction.reason().isBlank()) {
            lore.add(messages.get(viewer, "sanction.reason", "reason", sanction.reason()));
        }
        if (viewer.hasPermission(USE)) {
            lore.add(messages.get(viewer, "sanction.by", "staff", sanction.staffName(), "date", Labels.date(sanction.createdAt()),
                    "server", sanction.server()));
        } else {
            lore.add(messages.get(viewer, "sanction.date", "date", Labels.date(sanction.createdAt())));
        }
        if (sanction.type().lasts()) {
            lore.add(messages.get(viewer, "sanction.length", "time", labels.length(viewer, sanction)));
            if (sanction.liftedAt() != 0) {
                lore.add(messages.get(viewer, "sanction.status-lifted", "staff", String.valueOf(sanction.liftedBy())));
            } else if (sanction.isActive(now)) {
                lore.add(messages.get(viewer, "sanction.status-active", "time", labels.remaining(viewer, sanction, now)));
            } else {
                lore.add(messages.get(viewer, "sanction.status-over"));
            }
        } else if (sanction.liftedAt() != 0) {
            lore.add(messages.get(viewer, "sanction.status-lifted", "staff", String.valueOf(sanction.liftedBy())));
        }
        lore.addAll(extra);
        return Items.item(sanction.type().icon(), messages.get(viewer, "sanction.name", "type", labels.type(viewer, sanction.type()),
                "player", sanction.playerName(), "id", String.valueOf(sanction.id())), lore, sanction.isActive(now));
    }

    boolean allowed(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        messages.send(player, "sanction.no-permission");
        return false;
    }

    ReportService reports() {
        return reports;
    }

    Freeze freeze() {
        return freeze;
    }

    SanctionService sanctions() {
        return sanctions;
    }

    AppealService appeals() {
        return appeals;
    }

    Records records() {
        return records;
    }

    Motives motives() {
        return motives;
    }

    Messages messages() {
        return messages;
    }

    /** Retour des menus des joueurs (/appeal, /report) : config.yml > menus.back-command (vide : menu central). */
    BackButton back() {
        return EterLib.get().backButton(plugin.getConfig().getString("menus.back-command", "menu social"));
    }

    Labels labels() {
        return labels;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    private SanctionRepository repository() {
        return sanctions.repository();
    }

    /** Données lues en tâche de fond, puis le menu ouvert sur le thread principal. */
    private <T> void load(Player player, Supplier<T> data, Function<T, Inventory> menu) {
        Tasks.async(plugin, player, data, result -> player.openInventory(menu.apply(result)),
                () -> messages.send(player, "error.generic"));
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static PlayerProfile profile(Target target) {
        return Bukkit.createProfile(target.uuid(), target.name());
    }
}
