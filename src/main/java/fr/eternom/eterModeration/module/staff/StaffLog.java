package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;
import fr.eternom.eterLib.helper.task.Tasks;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Le journal des actions du staff (etermod_staff_log) : qui a gelé, regardé un inventaire ou un coffre, s'est
 * téléporté, est passé en mode staff, a sanctionné ou levé... Pour contrôler le staff lui-même, et ses statistiques
 * (/mod > Statistiques du staff). Gardé staff-log.days jours.
 */
public class StaffLog {

    /** Les actions notées (leur nom affiché : lang/ > staff-action.<id>). */
    public enum Action { SANCTION, LIFT, CHANGE, FREEZE, UNFREEZE, INVSEE, CHEST, TELEPORT, STAFF_MODE, SPECTATE, REPORT, APPEAL }

    /** Une ligne du journal. */
    public record Entry(String staffName, Action action, String target, String server, long createdAt) {
    }

    private static final String TABLE = "staff_log";

    private final JavaPlugin plugin;
    private final Database database;
    private final String table;
    private final String server;

    public StaffLog(JavaPlugin plugin, Database database, String server) {
        this.plugin = plugin;
        this.database = database;
        this.server = server;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("staff_name", Column.Type.STRING).length(32).notNull(),
                Column.of("action", Column.Type.STRING).length(16).notNull(),
                Column.of("target", Column.Type.STRING).length(64).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
        this.table = database.table(TABLE);
        database.execute("CREATE INDEX IF NOT EXISTS idx_created ON " + table + " (created_at)");
    }

    /** N'importe quel thread : écrit en tâche de fond. */
    public void log(String staffName, Action action, String target) {
        Tasks.async(plugin, () -> database.insert(TABLE, Map.of("staff_name", staffName, "action", action.name(),
                "target", target == null ? "" : target, "server", server, "created_at", System.currentTimeMillis())),
                "Action du staff non notée");
    }

    /** Bloquant : par membre du staff, le nombre de chaque action depuis since. */
    public Map<String, Map<Action, Integer>> stats(long since) {
        Map<String, Map<Action, Integer>> stats = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Row row : database.query("SELECT staff_name, action, COUNT(*) AS n FROM " + table + " WHERE created_at >= ?"
                + " GROUP BY staff_name, action", since)) {
            Action action;
            try {
                action = Action.valueOf(row.getString("action"));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            stats.computeIfAbsent(row.getString("staff_name"), name -> new TreeMap<>()).put(action, row.getInt("n"));
        }
        return stats;
    }

    /** Bloquant : les dernières actions d'un membre du staff. */
    public List<Entry> last(String staffName, int limit) {
        return database.query("SELECT * FROM " + table + " WHERE staff_name = ? ORDER BY created_at DESC LIMIT ?", staffName, limit)
                .stream().map(row -> {
                    Action action;
                    try {
                        action = Action.valueOf(row.getString("action"));
                    } catch (IllegalArgumentException unknown) {
                        action = Action.SANCTION;
                    }
                    return new Entry(row.getString("staff_name"), action, row.getString("target"), row.getString("server"),
                            row.getLong("created_at"));
                }).toList();
    }

    /** Bloquant : efface ce qui est plus vieux que keepDays jours. */
    public void purge(int keepDays) {
        database.execute("DELETE FROM " + table + " WHERE created_at < ?", System.currentTimeMillis() - keepDays * 86_400_000L);
    }
}
