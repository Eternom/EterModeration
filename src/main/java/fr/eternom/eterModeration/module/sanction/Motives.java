package fr.eternom.eterModeration.module.sanction;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Le barème (config.yml > motives) : chaque motif a ses étapes, de la première fois à la récidive (« mute 10m »,
 * « jail 3d », « ban perm »...) ; au-delà de la dernière, la dernière se répète. Utiliser un motif : permission
 * eter.mod.motive.<motif> (et celle du type de sanction). Le nom affiché est dans lang/ (motive.<motif>).
 */
public final class Motives {

    /** Une étape : le type et la durée (null = définitive ; pour un avertissement, sans objet). */
    public record Step(SanctionType type, Duration duration) {
    }

    /** Un motif du barème. */
    public record Motive(String id, Material icon, List<Step> steps) {

        /** L'étape pour la count-ième sanction de ce motif (0 = la première). */
        public Step step(int count) {
            return steps.get(Math.min(count, steps.size() - 1));
        }

        public String permission() {
            return "eter.mod.motive." + id;
        }
    }

    private final Map<String, Motive> motives;

    private Motives(Map<String, Motive> motives) {
        this.motives = motives;
    }

    public static Motives load(ConfigurationSection section, Logger logger) {
        Map<String, Motive> motives = new LinkedHashMap<>();
        if (section != null) {
            for (String id : section.getKeys(false)) {
                List<Step> steps = new ArrayList<>();
                for (String text : section.getStringList(id + ".steps")) {
                    Step step = parseStep(text);
                    if (step == null) {
                        logger.warning("motives." + id + " : étape « " + text + " » ignorée (ex : « mute 1h », « jail perm »)");
                    } else {
                        steps.add(step);
                    }
                }
                Material icon = Material.matchMaterial(section.getString(id + ".icon", "PAPER"));
                if (!steps.isEmpty()) {
                    motives.put(id.toLowerCase(Locale.ROOT), new Motive(id.toLowerCase(Locale.ROOT),
                            icon == null || !icon.isItem() ? Material.PAPER : icon, List.copyOf(steps)));
                }
            }
        }
        return new Motives(motives);
    }

    public List<Motive> all() {
        return List.copyOf(motives.values());
    }

    public Optional<Motive> get(String id) {
        return Optional.ofNullable(id == null ? null : motives.get(id.toLowerCase(Locale.ROOT)));
    }

    /** « mute 10m », « jail 3d », « ban perm », « warn ». */
    static Step parseStep(String text) {
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        SanctionType type = SanctionType.of(parts[0]);
        if (type == null) {
            return null;
        }
        if (!type.lasts()) {
            return new Step(type, Duration.ZERO);
        }
        if (parts.length < 2) {
            return null;
        }
        if (parts[1].equals("perm")) {
            return new Step(type, null);
        }
        Duration duration = parseDuration(parts[1]);
        return duration == null ? null : new Step(type, duration);
    }

    /** « 30s », « 10m », « 2h », « 7d », « 4w » ; null si illisible. */
    public static Duration parseDuration(String text) {
        if (text == null || text.length() < 2) {
            return null;
        }
        try {
            long amount = Long.parseLong(text.substring(0, text.length() - 1));
            if (amount <= 0) {
                return null;
            }
            return switch (text.charAt(text.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'd' -> Duration.ofDays(amount);
                case 'w' -> Duration.ofDays(amount * 7);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
