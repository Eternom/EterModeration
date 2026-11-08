package fr.eternom.eterModeration.module.sanction;

import java.util.UUID;

/**
 * Une sanction de l'historique. motive : identifiant du motif du barème (vide = sanction libre). expiresAt : 0 =
 * définitive ; pour un avertissement ou une expulsion, sa date. liftedAt : 0 tant qu'elle n'est pas levée (appel
 * accepté, staff). evidence : les preuves jointes (derniers messages du joueur...), vide s'il n'y en a pas.
 */
public record Sanction(long id, UUID player, String playerName, SanctionType type, String motive, String reason,
                       String staffName, long createdAt, long expiresAt, long liftedAt, String liftedBy, String server,
                       String evidence) {

    public boolean isPermanent() {
        return type.lasts() && expiresAt == 0;
    }

    /** Toujours en cours : pas levée, et pas encore finie. */
    public boolean isActive(long now) {
        return type.lasts() && liftedAt == 0 && (expiresAt == 0 || expiresAt > now);
    }

    public long remainingMillis(long now) {
        return isPermanent() ? Long.MAX_VALUE : Math.max(0, expiresAt - now);
    }
}
