package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les sanctions (etermod_sanctions), seule source de vérité du réseau : le proxy (EterVelocityModeration)
 * y lit le ban et l'expulsion. Une sanction n'est jamais effacée : levée, elle garde qui l'a levée.
 * Bloquant : hors du thread principal.
 */
public class SanctionRepository {

    private static final String TABLE = "sanctions";
    private static final long MONTH_MILLIS = 30L * 86_400_000L;

    private final Database database;
    private final String table;
    /** Récidive : une sanction plus vieille que ce nombre de mois ne compte plus (0 = toujours). */
    private final int recidivismMonths;

    public SanctionRepository(Database database, int recidivismMonths) {
        this.database = database;
        this.recidivismMonths = Math.max(0, recidivismMonths);
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("type", Column.Type.STRING).length(8).notNull(),
                Column.of("motive", Column.Type.STRING).length(32).notNull(),
                Column.of("reason", Column.Type.STRING).length(255).notNull(),
                Column.of("staff_name", Column.Type.STRING).length(32).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull(),
                Column.of("expires_at", Column.Type.LONG).notNull(),
                Column.of("lifted_at", Column.Type.LONG).notNull(),
                Column.of("lifted_by", Column.Type.STRING).length(32),
                Column.of("server", Column.Type.STRING).length(64).notNull());
        database.addColumn(TABLE, Column.of("evidence", Column.Type.TEXT));
        this.table = database.table(TABLE);
        // Le proxy lit les sanctions d'un joueur à chaque connexion, et les nouvelles toutes les quelques secondes
        database.execute("CREATE INDEX IF NOT EXISTS idx_uuid ON " + table + " (uuid)");
        database.execute("CREATE INDEX IF NOT EXISTS idx_created ON " + table + " (created_at)");
        // La prison n'existe plus : ses sanctions deviennent des bans de même fin (une peine en cours continue, en ban ;
        // l'historique et la récidive restent justes). Une fois fait, ne touche plus aucune ligne.
        database.execute("UPDATE " + table + " SET type = 'BAN' WHERE type = 'JAIL'");
    }

    public long add(UUID player, String playerName, SanctionType type, String motive, String reason, String staffName,
                    long createdAt, long expiresAt, String server, String evidence) {
        Map<String, Object> values = new HashMap<>(Map.of("uuid", player, "name", playerName, "type", type.name(),
                "motive", motive, "reason", reason, "staff_name", staffName, "created_at", createdAt, "expires_at", expiresAt,
                "lifted_at", 0L, "server", server));
        if (evidence != null && !evidence.isBlank()) {
            values.put("evidence", evidence);
        }
        return database.insertReturningId(TABLE, values);
    }

    /** L'historique du joueur, la plus récente d'abord. */
    public List<Sanction> history(UUID player) {
        return database.query("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY created_at DESC", player).stream()
                .map(SanctionRepository::toSanction).toList();
    }

    /** Ses sanctions en cours (mute, ban). */
    public List<Sanction> active(UUID player, long now) {
        return database.query("SELECT * FROM " + table + " WHERE uuid = ? AND lifted_at = 0 AND type IN ('MUTE', 'BAN')"
                + " AND (expires_at = 0 OR expires_at > ?) ORDER BY created_at DESC", player, now).stream()
                .map(SanctionRepository::toSanction).toList();
    }

    public Optional<Sanction> byId(long id) {
        return database.getFirst(TABLE, Map.of("id", id)).map(SanctionRepository::toSanction);
    }

    /** Les dernières sanctions données sur le réseau. */
    public List<Sanction> recent(int limit) {
        return database.query("SELECT * FROM " + table + " ORDER BY created_at DESC LIMIT ?", limit).stream()
                .map(SanctionRepository::toSanction).toList();
    }

    /**
     * Récidive : sanctions de ce motif déjà reçues (sauf celles levées : une erreur ou un appel accepté), et plus
     * récentes que recidivism-months mois : un vieux casier s'efface.
     */
    public int count(UUID player, String motive) {
        long since = recidivismMonths == 0 ? 0 : System.currentTimeMillis() - recidivismMonths * MONTH_MILLIS;
        return database.query("SELECT COUNT(*) AS n FROM " + table + " WHERE uuid = ? AND motive = ? AND lifted_at = 0"
                + " AND created_at >= ?", player, motive, since).getFirst().getInt("n");
    }

    /** false si elle était déjà levée. */
    public boolean lift(long id, String staffName, long now) {
        return database.execute("UPDATE " + table + " SET lifted_at = ?, lifted_by = ? WHERE id = ? AND lifted_at = 0",
                now, staffName, id) > 0;
    }

    /** Nouvelle fin (0 = définitive) d'une sanction en cours ; false si elle est levée ou finie. */
    public boolean changeExpiry(long id, long expiresAt, long now) {
        return database.execute("UPDATE " + table + " SET expires_at = ? WHERE id = ? AND lifted_at = 0"
                + " AND (expires_at = 0 OR expires_at > ?)", expiresAt, id, now) > 0;
    }

    /** Sanctions données par chaque membre du staff depuis since (statistiques du staff). */
    public Map<String, Integer> countByStaff(long since) {
        Map<String, Integer> counts = new HashMap<>();
        for (Row row : database.query("SELECT staff_name, COUNT(*) AS n FROM " + table + " WHERE created_at >= ?"
                + " GROUP BY staff_name", since)) {
            counts.put(row.getString("staff_name"), row.getInt("n"));
        }
        return counts;
    }

    private static Sanction toSanction(Row row) {
        String evidence = row.getString("evidence");
        return new Sanction(row.getLong("id"), row.getUUID("uuid"), row.getString("name"), SanctionType.fromDatabase(row.getString("type")),
                row.getString("motive"), row.getString("reason"), row.getString("staff_name"), row.getLong("created_at"),
                row.getLong("expires_at"), row.getLong("lifted_at"), row.getString("lifted_by"), row.getString("server"),
                evidence == null ? "" : evidence);
    }
}
