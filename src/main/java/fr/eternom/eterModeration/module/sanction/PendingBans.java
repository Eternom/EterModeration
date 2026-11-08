package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.List;
import java.util.Map;

/**
 * Les bans définitifs à valider (etermod_pending_bans) : un ban définitif demandé par un membre du staff est d'abord
 * un ban temporaire (permanent-approval.interim) ; un second membre du staff, avec eter.mod.permanent, le rend
 * définitif ou le refuse (le ban temporaire reste). Bloquant.
 */
public class PendingBans {

    /** Une demande : la sanction visée, qui l'a demandée, quand. */
    public record Request(long sanctionId, String requestedBy, long createdAt) {
    }

    private static final String TABLE = "pending_bans";

    private final Database database;

    public PendingBans(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("sanction_id", Column.Type.LONG).primaryKey(),
                Column.of("requested_by", Column.Type.STRING).length(32).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
    }

    public void add(long sanctionId, String requestedBy) {
        database.set(TABLE, Map.of("sanction_id", sanctionId, "requested_by", requestedBy, "created_at", System.currentTimeMillis()),
                "sanction_id");
    }

    public List<Request> all() {
        return database.query("SELECT * FROM " + database.table(TABLE) + " ORDER BY created_at").stream()
                .map(row -> new Request(row.getLong("sanction_id"), row.getString("requested_by"), row.getLong("created_at"))).toList();
    }

    /** false si un autre membre du staff l'a déjà traitée. */
    public boolean remove(long sanctionId) {
        return database.delete(TABLE, Map.of("sanction_id", sanctionId)) > 0;
    }

    public int count() {
        return database.query("SELECT COUNT(*) AS n FROM " + database.table(TABLE)).getFirst().getInt("n");
    }
}
