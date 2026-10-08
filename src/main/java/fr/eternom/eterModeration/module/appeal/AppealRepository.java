package fr.eternom.eterModeration.module.appeal;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Les appels (etermod_appeals) : un par sanction au plus tant qu'il est ouvert. Accepté, la sanction est levée ;
 * refusé, le joueur reçoit la réponse du staff. Bloquant : hors du thread principal.
 */
public class AppealRepository {

    public enum Status { OPEN, ACCEPTED, REFUSED }

    public record Appeal(long id, long sanctionId, UUID player, String playerName, String text, long createdAt, Status status,
                         String staffName, String answer) {
    }

    private static final String TABLE = "appeals";

    private final Database database;
    private final String table;

    public AppealRepository(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("sanction_id", Column.Type.LONG).notNull(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("text", Column.Type.STRING).length(512).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull(),
                Column.of("status", Column.Type.STRING).length(8).notNull(),
                Column.of("staff_name", Column.Type.STRING).length(32),
                Column.of("answer", Column.Type.STRING).length(255));
        this.table = database.table(TABLE);
    }

    /** false si un appel est déjà ouvert pour cette sanction. */
    public boolean open(long sanctionId, UUID player, String playerName, String text) {
        if (!database.query("SELECT id FROM " + table + " WHERE sanction_id = ? AND status = 'OPEN'", sanctionId).isEmpty()) {
            return false;
        }
        database.insert(TABLE, Map.of("sanction_id", sanctionId, "uuid", player, "name", playerName, "text", text,
                "created_at", System.currentTimeMillis(), "status", Status.OPEN.name()));
        return true;
    }

    public List<Appeal> openAppeals() {
        return database.query("SELECT * FROM " + table + " WHERE status = 'OPEN' ORDER BY created_at").stream()
                .map(AppealRepository::toAppeal).toList();
    }

    /** Les appels du joueur, le plus récent d'abord. */
    public List<Appeal> ofPlayer(UUID player) {
        return database.query("SELECT * FROM " + table + " WHERE uuid = ? ORDER BY created_at DESC", player).stream()
                .map(AppealRepository::toAppeal).toList();
    }

    /** Déjà fait appel de cette sanction (ouvert ou traité) : un seul appel par sanction. */
    public boolean hasAppealed(long sanctionId) {
        return !database.query("SELECT id FROM " + table + " WHERE sanction_id = ?", sanctionId).isEmpty();
    }

    public Optional<Appeal> byId(long id) {
        return database.getFirst(TABLE, Map.of("id", id)).map(AppealRepository::toAppeal);
    }

    /** Traite l'appel s'il est encore ouvert (false si un autre staff l'a fait). */
    public boolean answer(long id, Status status, String staffName, String answer) {
        return database.execute("UPDATE " + table + " SET status = ?, staff_name = ?, answer = ? WHERE id = ? AND status = 'OPEN'",
                status.name(), staffName, answer, id) > 0;
    }

    private static Appeal toAppeal(Row row) {
        return new Appeal(row.getLong("id"), row.getLong("sanction_id"), row.getUUID("uuid"), row.getString("name"),
                row.getString("text"), row.getLong("created_at"), Status.valueOf(row.getString("status")),
                row.getString("staff_name"), row.getString("answer"));
    }
}
