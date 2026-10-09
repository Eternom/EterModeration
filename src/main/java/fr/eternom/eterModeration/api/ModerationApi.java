package fr.eternom.eterModeration.api;

import org.bukkit.Bukkit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterModeration offre aux autres plugins : les sanctions en cours d'un joueur, son état (muet, en prison, gelé,
 * en mode staff), et donner une sanction (ex : la prison qui allonge une peine). Personne d'autre ne lit les tables
 * etermod_* ni ses clés Redis : on demande ici. (Exception : EterVelocityModeration, sa moitié proxy, lit les sanctions.)
 * <pre>
 *     // compileOnly("com.github.Eternom:EterModeration:&lt;tag&gt;") ; plugin.yml : softdepend: [EterModeration]
 * </pre>
 */
public interface ModerationApi {

    /** Une sanction en cours. type : WARN, MUTE, JAIL ou BAN ; expiresAt 0 = définitive. */
    record SanctionInfo(long id, String type, String motive, String reason, String staffName, long createdAt, long expiresAt) {
    }

    /** L'API d'EterModeration si le plugin tourne sur ce serveur. */
    static Optional<ModerationApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(ModerationApi.class));
    }

    /** Ses sanctions en cours (mute, prison, ban). Bloquant (base) : hors du thread principal. */
    List<SanctionInfo> activeSanctions(UUID player);

    /** Muet en ce moment. Bloquant (base). */
    boolean isMuted(UUID player);

    /** En prison en ce moment. Bloquant (base). */
    boolean isJailed(UUID player);

    /** Gelé par le staff. Bloquant (Redis). */
    boolean isFrozen(UUID player);

    /** En mode staff. Bloquant (Redis). */
    boolean isInStaffMode(UUID player);

    /**
     * Donne une sanction hors barème au nom de author (« Prison », « Anti-triche »...) : type WARN, MUTE, JAIL ou BAN,
     * duration null = définitive. Appliquée tout de suite partout (proxy compris), le joueur prévenu, le staff alerté.
     * Bloquant (base). Rend son numéro.
     */
    long give(UUID player, String playerName, String type, Duration duration, String reason, String author);
}
