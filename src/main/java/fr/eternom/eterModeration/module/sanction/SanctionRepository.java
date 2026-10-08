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
 * y lit la prison et le ban à chaque connexion. Une sanction n'est jamais effacée : levée, elle garde qui l'a levée.
 * Bloquant : hors du thread principal.
 */
public class SanctionRepository {

    private static final String TABLE = "sanctions";

    private final Database database;
    private final String table;

    public SanctionRepository(Database database) {
        this.database = database;
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
        this.table = database.table(TABLE);
    }

    public long add(UUID player, String playerName, SanctionType type, String motive, String reason, String staffName,
                    long createdAt, long expiresAt, String server) {
        Map<String, Object> values = new HashMap<>(Map.of("uuid", player, "name", playerName, "type", type.name(),
                "motive", motive, "reason", reason, "staff_name", staffName, "created_at", createdAt, "expires_at", expiresAt,
                "lifted_at", 0L, "server", server));
        database.insert(TABLE, values);
        return database.query("SELECT MAX(id) AS id FROM " + table + " WHERE uuid = ?", player).getFirst().getLong("id");
    }

    /** L'historique du joueur, la plus récente d'abord. */
    public List<Sanction> history(UUID player) {
        return database.query("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY created_at DESC", player).stream()
                .map(SanctionRepository::toSanction).toList();
    }

    /** Ses sanctions en cours (mute, prison, ban). */
    public List<Sanction> active(UUID player, long now) {
        return database.query("SELECT * FROM " + table + " WHERE uuid = ? AND lifted_at = 0 AND type <> 'WARN'"
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

    /** Récidive : sanctions de ce motif déjà reçues (sauf celles levées : une erreur ou un appel accepté). */
    public int count(UUID player, String motive) {
        return database.query("SELECT COUNT(*) AS n FROM " + table + " WHERE uuid = ? AND motive = ? AND lifted_at = 0",
                player, motive).getFirst().getInt("n");
    }

    /** false si elle était déjà levée. */
    public boolean lift(long id, String staffName, long now) {
        return database.execute("UPDATE " + table + " SET lifted_at = ?, lifted_by = ? WHERE id = ? AND lifted_at = 0",
                now, staffName, id) > 0;
    }

    private static Sanction toSanction(Row row) {
        return new Sanction(row.getLong("id"), row.getUUID("uuid"), row.getString("name"), SanctionType.valueOf(row.getString("type")),
                row.getString("motive"), row.getString("reason"), row.getString("staff_name"), row.getLong("created_at"),
                row.getLong("expires_at"), row.getLong("lifted_at"), row.getString("lifted_by"), row.getString("server"));
    }
}
