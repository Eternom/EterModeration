package fr.eternom.eterModeration.module.report;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les signalements des joueurs (etermod_reports) : ouverts jusqu'à ce qu'un membre du staff les traite. Un membre du
 * staff peut d'abord le prendre en charge (claimed_by : les autres voient qui s'en occupe). Traité, il est « fondé »
 * ou « faux » (false_report) : les faux signalements d'un joueur allongent son délai entre deux signalements.
 * evidence : les derniers messages du joueur signalé (motifs du chat). Bloquant.
 */
public class ReportRepository {

    public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName, String reason, String details,
                         String server, long createdAt, String claimedBy, String evidence) {
    }

    private static final String TABLE = "reports";

    private final Database database;
    private final String table;

    public ReportRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("reporter", Column.Type.UUID).notNull(),
                Column.of("reporter_name", Column.Type.STRING).length(16).notNull(),
                Column.of("target", Column.Type.UUID).notNull(),
                Column.of("target_name", Column.Type.STRING).length(16).notNull(),
                Column.of("reason", Column.Type.STRING).length(32).notNull(),
                Column.of("details", Column.Type.STRING).length(255).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull(),
                Column.of("closed_by", Column.Type.STRING).length(32),
                Column.of("closed_at", Column.Type.LONG).notNull());
        database.addColumn(TABLE, Column.of("claimed_by", Column.Type.STRING).length(32));
        database.addColumn(TABLE, Column.of("false_report", Column.Type.BOOLEAN));
        database.addColumn(TABLE, Column.of("evidence", Column.Type.TEXT));
        this.table = database.table(TABLE);
    }

    public void add(UUID reporter, String reporterName, UUID target, String targetName, String reason, String details, String server,
                    String evidence) {
        Map<String, Object> values = new HashMap<>(Map.of("reporter", reporter, "reporter_name", reporterName, "target", target,
                "target_name", targetName, "reason", reason, "details", details, "server", server,
                "created_at", System.currentTimeMillis(), "closed_at", 0L));
        if (evidence != null && !evidence.isBlank()) {
            values.put("evidence", evidence);
        }
        database.insert(TABLE, values);
    }

    /** Les signalements ouverts, le plus récent d'abord. */
    public List<Report> open(int limit) {
        return database.query("SELECT * FROM " + table + " WHERE closed_at = 0 ORDER BY created_at DESC LIMIT ?", limit).stream()
                .map(ReportRepository::toReport).toList();
    }

    public int countOpen() {
        return database.query("SELECT COUNT(*) AS n FROM " + table + " WHERE closed_at = 0").getFirst().getInt("n");
    }

    /** Prendre en charge (ou lâcher, staffName null) ; false s'il est déjà traité. */
    public boolean claim(long id, String staffName) {
        return database.execute("UPDATE " + table + " SET claimed_by = ? WHERE id = ? AND closed_at = 0", staffName, id) > 0;
    }

    /** Traité (falseReport : le signalement était faux) ; false si un autre membre du staff l'a déjà traité. */
    public boolean close(long id, String staffName, boolean falseReport) {
        return database.execute("UPDATE " + table + " SET closed_by = ?, closed_at = ?, false_report = ? WHERE id = ? AND closed_at = 0",
                staffName, System.currentTimeMillis(), falseReport, id) > 0;
    }

    /** Faux signalements de ce joueur depuis since. */
    public int countFalse(UUID reporter, long since) {
        return database.query("SELECT COUNT(*) AS n FROM " + table + " WHERE reporter = ? AND false_report = TRUE AND closed_at >= ?",
                reporter, since).getFirst().getInt("n");
    }

    /** Signalements traités par chaque membre du staff depuis since (statistiques). */
    public Map<String, Integer> countClosedByStaff(long since) {
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : database.query("SELECT closed_by, COUNT(*) AS n FROM " + table + " WHERE closed_at >= ? AND closed_by IS NOT NULL"
                + " GROUP BY closed_by", since)) {
            counts.put(row.getString("closed_by"), row.getInt("n"));
        }
        return counts;
    }

    private static Report toReport(Row row) {
        String evidence = row.getString("evidence");
        return new Report(row.getLong("id"), row.getUUID("reporter"), row.getString("reporter_name"), row.getUUID("target"),
                row.getString("target_name"), row.getString("reason"), row.getString("details"), row.getString("server"),
                row.getLong("created_at"), row.getString("claimed_by"), evidence == null ? "" : evidence);
    }
}
