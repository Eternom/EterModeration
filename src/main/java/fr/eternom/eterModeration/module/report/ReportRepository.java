package fr.eternom.eterModeration.module.report;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Les signalements des joueurs (etermod_reports) : ouverts jusqu'à ce qu'un membre du staff les traite. Bloquant. */
public class ReportRepository {

    public record Report(long id, UUID reporter, String reporterName, UUID target, String targetName, String reason, String details,
                         String server, long createdAt) {
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
        this.table = database.table(TABLE);
    }

    public void add(UUID reporter, String reporterName, UUID target, String targetName, String reason, String details, String server) {
        database.insert(TABLE, Map.of("reporter", reporter, "reporter_name", reporterName, "target", target, "target_name", targetName,
                "reason", reason, "details", details, "server", server, "created_at", System.currentTimeMillis(), "closed_at", 0L));
    }

    /** Les signalements ouverts, le plus récent d'abord. */
    public List<Report> open(int limit) {
        return database.query("SELECT * FROM " + table + " WHERE closed_at = 0 ORDER BY created_at DESC LIMIT ?", limit).stream()
                .map(ReportRepository::toReport).toList();
    }

    public int countOpen() {
        return database.query("SELECT COUNT(*) AS n FROM " + table + " WHERE closed_at = 0").getFirst().getInt("n");
    }

    /** false si un autre membre du staff l'a déjà traité. */
    public boolean close(long id, String staffName) {
        return database.execute("UPDATE " + table + " SET closed_by = ?, closed_at = ? WHERE id = ? AND closed_at = 0",
                staffName, System.currentTimeMillis(), id) > 0;
    }

    private static Report toReport(Row row) {
        return new Report(row.getLong("id"), row.getUUID("reporter"), row.getString("reporter_name"), row.getUUID("target"),
                row.getString("target_name"), row.getString("reason"), row.getString("details"), row.getString("server"),
                row.getLong("created_at"));
    }
}
