package fr.eternom.eterModeration.module.sanction;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Les sanctions. WARN : noté dans l'historique (et compte pour la récidive). KICK : expulsé du réseau sur le moment
 * (le proxy le déconnecte), il peut revenir tout de suite. MUTE : plus de chat ni de messages privés.
 * BAN : refusé à l'entrée du réseau (le proxy y veille), temporaire ou définitif.
 * Donner une sanction : permission eter.mod.<id>.
 */
public enum SanctionType {

    WARN(Material.PAPER),
    KICK(Material.LEATHER_BOOTS),
    MUTE(Material.BARRIER),
    BAN(Material.TNT);

    private final Material icon;

    SanctionType(Material icon) {
        this.icon = icon;
    }

    public Material icon() {
        return icon;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Une sanction qui dure (un avertissement ou une expulsion est fini dès qu'il est donné). */
    public boolean lasts() {
        return this == MUTE || this == BAN;
    }

    public String permission() {
        return "eter.mod." + id();
    }

    public static SanctionType of(String id) {
        for (SanctionType type : values()) {
            if (type.id().equalsIgnoreCase(id)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Le type d'une ligne de la base : l'ancienne prison (JAIL) est un ban ; un type inconnu (une version plus récente
     * l'a écrit) est lu comme un avertissement plutôt que de casser tout l'historique.
     */
    static SanctionType fromDatabase(String name) {
        if ("JAIL".equalsIgnoreCase(name)) {
            return BAN;
        }
        SanctionType type = of(name);
        return type == null ? WARN : type;
    }
}
