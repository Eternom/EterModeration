package fr.eternom.eterModeration.module.staff;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * L'inventaire d'un membre du staff mis de côté pendant son mode staff (etermod_staff_inventories) : en base, pour
 * survivre à un changement de serveur ou à un arrêt. Repris une seule fois : lu puis effacé avant d'être rendu
 * (jamais deux fois le même inventaire). Bloquant : hors du thread principal.
 */
public class StaffInventories {

    private static final String TABLE = "staff_inventories";

    private final Database database;

    public StaffInventories(Database database) {
        this.database = database;
        database.createTable(TABLE,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("contents", Column.Type.BLOB).notNull(),
                Column.of("saved_at", Column.Type.LONG).notNull());
    }

    public void save(UUID player, byte[] contents) {
        database.set(TABLE, Map.of("uuid", player, "contents", contents, "saved_at", System.currentTimeMillis()), "uuid");
    }

    /** L'inventaire gardé, effacé de la base ; vide s'il n'y en a pas (ou si un autre serveur l'a déjà repris). */
    public Optional<byte[]> take(UUID player) {
        Optional<byte[]> contents = database.getFirst(TABLE, Map.of("uuid", player)).map(row -> row.getBytes("contents"));
        if (contents.isPresent() && database.execute("DELETE FROM " + database.table(TABLE) + " WHERE uuid = ?", player) == 0) {
            return Optional.empty();
        }
        return contents;
    }
}
