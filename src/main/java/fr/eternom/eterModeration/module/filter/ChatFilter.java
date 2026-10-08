package fr.eternom.eterModeration.module.filter;

import org.bukkit.configuration.ConfigurationSection;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le filtre du chat (config.yml > filter) : trop de messages, le même message répété, trop de majuscules, mots
 * interdits. Un mot interdit est cherché en mot entier, sans accents ni chiffres à la place des lettres (« c0nn4rd »).
 * Appelé depuis le fil du chat (asynchrone) : tout est thread-safe.
 */
public class ChatFilter {

    public enum Verdict { OK, SPAM, REPEAT, CAPS, WORD }

    private record History(Deque<Long> times, String last) {
    }

    private final boolean enabled;
    private final int maxMessages;
    private final long windowMillis;
    private final boolean blockRepeat;
    private final int capsMinLength;
    private final int capsMaxPercent;
    private final List<String> words;
    private final Map<UUID, History> history = new ConcurrentHashMap<>();

    public ChatFilter(ConfigurationSection config) {
        this.enabled = config != null && config.getBoolean("enabled", true);
        this.maxMessages = config == null ? 4 : config.getInt("spam.max-messages", 4);
        this.windowMillis = (config == null ? 6 : config.getInt("spam.seconds", 6)) * 1000L;
        this.blockRepeat = config == null || config.getBoolean("spam.block-repeat", true);
        this.capsMinLength = config == null ? 8 : config.getInt("caps.min-length", 8);
        this.capsMaxPercent = config == null ? 70 : config.getInt("caps.max-percent", 70);
        this.words = config == null ? List.of() : config.getStringList("words").stream().map(ChatFilter::normalize)
                .filter(word -> !word.isBlank()).toList();
    }

    public Verdict check(UUID player, String message) {
        if (!enabled) {
            return Verdict.OK;
        }
        String normalized = normalize(message);
        String padded = " " + normalized + " ";
        for (String word : words) {
            if (padded.contains(" " + word + " ")) {
                return Verdict.WORD;
            }
        }
        if (tooManyCaps(message)) {
            return Verdict.CAPS;
        }
        long now = System.currentTimeMillis();
        Verdict[] verdict = {Verdict.OK};
        history.compute(player, (uuid, old) -> {
            Deque<Long> times = old == null ? new ArrayDeque<>() : old.times();
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            if (blockRepeat && old != null && normalized.equals(old.last())) {
                verdict[0] = Verdict.REPEAT;
            } else if (times.size() >= maxMessages) {
                verdict[0] = Verdict.SPAM;
            }
            if (verdict[0] == Verdict.OK) {
                times.addLast(now);
                return new History(times, normalized);
            }
            return new History(times, old == null ? normalized : old.last());
        });
        return verdict[0];
    }

    public void forget(UUID player) {
        history.remove(player);
    }

    private boolean tooManyCaps(String message) {
        int letters = 0;
        int caps = 0;
        for (char c : message.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    caps++;
                }
            }
        }
        return letters >= capsMinLength && caps * 100 > letters * capsMaxPercent;
    }

    /** Minuscules, sans accents, chiffres et symboles remis en lettres, le reste en espaces. */
    static String normalize(String text) {
        String plain = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder out = new StringBuilder(plain.length());
        for (char c : plain.toCharArray()) {
            out.append(switch (c) {
                case '0' -> 'o';
                case '1', '!' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> Character.isLetterOrDigit(c) ? c : ' ';
            });
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }
}
