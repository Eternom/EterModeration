package fr.eternom.eterModeration.module.record;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce que le staff peut consulter sur un joueur, en plus des sanctions (bloquant : hors du thread principal) :
 * - notes (etermod_notes) : remarques du staff, visibles du staff seulement ;
 * - derniers messages du chat (etermod_chat), gardés chat-log.days jours : sanctionner sur preuve ;
 * - comptes liés (etermod_links) : l'adresse IP n'est JAMAIS gardée, seulement son empreinte (SHA-256 avec un sel secret
 *   tiré au hasard une fois, etermod_settings) ; deux comptes qui partagent une empreinte sont liés.
 */
public class Records {

    public record Note(long id, String staffName, String text, long createdAt) {
    }

    public record ChatLine(String server, String message, long createdAt) {
    }

    /** Un compte lié : même connexion qu'un autre, vu pour la dernière fois à lastSeen. */
    public record Link(UUID player, String name, long lastSeen) {
    }

    private static final String NOTES = "notes";
    private static final String CHAT = "chat";
    private static final String LINKS = "links";
    private static final String SETTINGS = "settings";

    private final Database database;
    private final String pepper;

    public Records(Database database) {
        this.database = database;
        database.createTable(NOTES,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("staff_name", Column.Type.STRING).length(32).notNull(),
                Column.of("text", Column.Type.STRING).length(255).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
        database.createTable(CHAT,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("message", Column.Type.STRING).length(512).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull());
        database.createTable(LINKS,
                Column.of("uuid", Column.Type.UUID).primaryKey(),
                Column.of("hash", Column.Type.STRING).length(64).primaryKey(),
                Column.of("name", Column.Type.STRING).length(16).notNull(),
                Column.of("last_seen", Column.Type.LONG).notNull());
        database.createTable(SETTINGS,
                Column.of("name", Column.Type.STRING).length(32).primaryKey(),
                Column.of("value", Column.Type.STRING).length(128).notNull());
        this.pepper = pepper();
    }

    // ---------- Notes ----------

    public void addNote(UUID player, String staffName, String text) {
        database.insert(NOTES, Map.of("uuid", player, "staff_name", staffName, "text", text, "created_at", System.currentTimeMillis()));
    }

    public List<Note> notes(UUID player) {
        return database.query("SELECT * FROM " + database.table(NOTES) + " WHERE uuid = ? ORDER BY created_at DESC", player).stream()
                .map(row -> new Note(row.getLong("id"), row.getString("staff_name"), row.getString("text"), row.getLong("created_at")))
                .toList();
    }

    public void deleteNote(long id) {
        database.delete(NOTES, Map.of("id", id));
    }

    // ---------- Chat ----------

    public void logChat(UUID player, String server, String message) {
        database.insert(CHAT, Map.of("uuid", player, "server", server,
                "message", message.length() > 512 ? message.substring(0, 512) : message, "created_at", System.currentTimeMillis()));
    }

    public List<ChatLine> lastMessages(UUID player, int limit) {
        return database.query("SELECT * FROM " + database.table(CHAT) + " WHERE uuid = ? ORDER BY created_at DESC LIMIT ?",
                        player, limit).stream()
                .map(row -> new ChatLine(row.getString("server"), row.getString("message"), row.getLong("created_at")))
                .toList();
    }

    /** Messages plus vieux que keepDays jours effacés. */
    public void purgeChat(int keepDays) {
        database.execute("DELETE FROM " + database.table(CHAT) + " WHERE created_at < ?",
                System.currentTimeMillis() - keepDays * 86_400_000L);
    }

    // ---------- Comptes liés ----------

    public void recordConnection(UUID player, String name, String address) {
        database.set(LINKS, Map.of("uuid", player, "hash", hash(address), "name", name, "last_seen", System.currentTimeMillis()),
                "uuid", "hash");
    }

    /** Les autres comptes qui ont partagé une connexion avec ce joueur. */
    public List<Link> links(UUID player) {
        String links = database.table(LINKS);
        return database.query("SELECT other.uuid, other.name, MAX(other.last_seen) AS last_seen FROM " + links + " mine JOIN "
                        + links + " other ON other.hash = mine.hash AND other.uuid <> mine.uuid WHERE mine.uuid = ?"
                        + " GROUP BY other.uuid, other.name ORDER BY last_seen DESC", player).stream()
                .map(row -> new Link(row.getUUID("uuid"), row.getString("name"), row.getLong("last_seen")))
                .toList();
    }

    private String hash(String address) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((pepper + address).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 existe dans toute JVM
        }
    }

    /** Sel secret commun au réseau, tiré au hasard la première fois (INSERT IGNORE : un seul serveur le fixe). */
    private String pepper() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        database.execute("INSERT IGNORE INTO " + database.table(SETTINGS) + " (name, value) VALUES ('ip-pepper', ?)",
                HexFormat.of().formatHex(random));
        return database.getFirst(SETTINGS, Map.of("name", "ip-pepper")).orElseThrow().getString("value");
    }
}
