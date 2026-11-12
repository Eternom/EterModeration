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
import java.util.regex.Pattern;

/**
 * Le filtre du chat (config.yml > filter) : trop de messages, le même message répété, trop de majuscules, mots
 * interdits. Un mot interdit est cherché en mot entier, sans accents ni chiffres à la place des lettres (« c0nn4rd »).
 * Appelé depuis le fil du chat (asynchrone) : tout est thread-safe.
 */
public class ChatFilter {

    public enum Verdict { OK, SPAM, REPEAT, CAPS, WORD, ADVERT }

    /** Une adresse IP (avec ou sans port). */
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}[.,]){3}\\d{1,3}(?::\\d{2,5})?\\b");
    /** Un nom de domaine : mot.extension (play.serveur.net, serveur.fr...) ; pas de « .me », « .ca »... : trop de faux positifs en français. */
    private static final Pattern DOMAIN = Pattern.compile("\\b[a-z0-9-]{2,}(?:\\.[a-z0-9-]{2,})*\\."
            + "(?:net|com|fr|org|eu|gg|io|xyz|club|fun|online|world|uk|mc|tk|ml|ga|cf|gq|link|site|space|store|tv)\\b");

    private record History(Deque<Long> times, String last) {
    }

    private volatile boolean enabled;
    private volatile int maxMessages;
    private volatile long windowMillis;
    private volatile boolean blockRepeat;
    private volatile int capsMinLength;
    private volatile int capsMaxPercent;
    private volatile List<String> words;
    /** Mots ajoutés en jeu (/mod filter), communs au réseau. */
    private volatile List<String> extraWords = List.of();
    private volatile boolean blockAdverts;
    /** Adresses permises (le site, le Discord du serveur), retirées du texte avant la recherche de pub. */
    private volatile List<String> allowedDomains;
    private final Map<UUID, History> history = new ConcurrentHashMap<>();

    public ChatFilter(ConfigurationSection config) {
        reload(config);
    }

    /** /mod reload : les réglages du filtre relus. */
    public void reload(ConfigurationSection config) {
        this.enabled = config != null && config.getBoolean("enabled", true);
        this.maxMessages = config == null ? 4 : config.getInt("spam.max-messages", 4);
        this.windowMillis = (config == null ? 6 : config.getInt("spam.seconds", 6)) * 1000L;
        this.blockRepeat = config == null || config.getBoolean("spam.block-repeat", true);
        this.capsMinLength = config == null ? 8 : config.getInt("caps.min-length", 8);
        this.capsMaxPercent = config == null ? 70 : config.getInt("caps.max-percent", 70);
        this.words = config == null ? List.of() : config.getStringList("words").stream().map(ChatFilter::normalize)
                .filter(word -> !word.isBlank()).toList();
        this.blockAdverts = config == null || config.getBoolean("adverts.enabled", true);
        this.allowedDomains = config == null ? List.of() : config.getStringList("adverts.allowed").stream()
                .map(domain -> domain.toLowerCase(Locale.ROOT)).toList();
    }

    /** Les mots ajoutés en jeu (FilterWords), en plus de ceux de config.yml. */
    public void setExtraWords(java.util.Collection<String> extra) {
        this.extraWords = extra.stream().map(ChatFilter::normalize).filter(word -> !word.isBlank()).toList();
    }

    /** Un texte écrit ailleurs que dans le chat (panneau, livre, enclume) : seulement les mots interdits et la pub. */
    public Verdict scan(String text) {
        if (!enabled) {
            return Verdict.OK;
        }
        if (hasWord(text)) {
            return Verdict.WORD;
        }
        return isAdvert(text) ? Verdict.ADVERT : Verdict.OK;
    }

    public Verdict check(UUID player, String message) {
        if (!enabled) {
            return Verdict.OK;
        }
        Verdict scanned = scan(message);
        if (scanned != Verdict.OK) {
            return scanned;
        }
        String normalized = normalize(message);
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

    /** Un mot interdit, en mot entier ; aussi écrit lettre par lettre (« c o n n a r d »). */
    boolean hasWord(String text) {
        String normalized = normalize(text);
        String padded = " " + normalized + " ";
        String joined = " " + joinSingleLetters(normalized) + " ";
        for (String word : java.util.stream.Stream.concat(words.stream(), extraWords.stream()).toList()) {
            if (padded.contains(" " + word + " ") || joined.contains(" " + word + " ")) {
                return true;
            }
        }
        return false;
    }

    /** Une adresse de serveur ou de site (IP, domaine), hors des adresses permises. */
    boolean isAdvert(String text) {
        if (!blockAdverts) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String allowed : allowedDomains) {
            lower = lower.replace(allowed, " ");
        }
        return IP.matcher(lower).find() || DOMAIN.matcher(lower).find();
    }

    /** « c o n n a r d ici » -> « connard ici » : les suites de lettres seules sont recollées. */
    static String joinSingleLetters(String normalized) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        for (String token : normalized.split(" ")) {
            if (token.length() == 1) {
                run.append(token);
                continue;
            }
            if (!run.isEmpty()) {
                out.append(run).append(' ');
                run.setLength(0);
            }
            out.append(token).append(' ');
        }
        out.append(run);
        return out.toString().trim();
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
