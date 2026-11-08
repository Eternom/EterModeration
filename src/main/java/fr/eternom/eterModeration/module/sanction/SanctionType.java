package fr.eternom.eterModeration.module.sanction;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Les sanctions. WARN : noté dans l'historique (et compte pour la récidive). MUTE : plus de chat ni de messages privés.
 * BAN : refusé à l'entrée du réseau (le proxy y veille), temporaire ou définitif.
 * Donner une sanction : permission eter.mod.<id>.
 */
public enum SanctionType {

    WARN(Material.PAPER),
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

    /** Une sanction qui dure (un avertissement est fini dès qu'il est donné). */
    public boolean lasts() {
        return this != WARN;
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
}
