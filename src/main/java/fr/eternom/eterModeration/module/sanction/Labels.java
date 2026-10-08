package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Les textes d'une sanction dans la langue du lecteur : type, motif, durée, date. */
public final class Labels {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final Messages messages;

    public Labels(Messages messages) {
        this.messages = messages;
    }

    public String type(CommandSender reader, SanctionType type) {
        return messages.plain(reader, "type." + type.id());
    }

    /** Le nom du motif (lang/ motive.<id>), ou « sanction libre ». */
    public String motive(CommandSender reader, String motive) {
        return motive == null || motive.isEmpty() ? messages.plain(reader, "motive-free") : messages.plain(reader, "motive." + motive);
    }

    /** « 2 j 3 h », « définitive », ou rien pour un avertissement. */
    public String length(CommandSender reader, SanctionType type, Duration duration) {
        if (!type.lasts()) {
            return "";
        }
        return duration == null ? messages.plain(reader, "length.permanent") : time(reader, duration.toMillis());
    }

    public String length(CommandSender reader, Sanction sanction) {
        return length(reader, sanction.type(), sanction.isPermanent() ? null : Duration.ofMillis(sanction.expiresAt() - sanction.createdAt()));
    }

    /** Ce qu'il reste à purger. */
    public String remaining(CommandSender reader, Sanction sanction, long now) {
        return sanction.isPermanent() ? messages.plain(reader, "length.permanent") : time(reader, sanction.remainingMillis(now));
    }

    public String time(CommandSender reader, long millis) {
        return EterLib.get().formatDuration(reader, Math.max(1, millis / 1000));
    }

    public static String date(long millis) {
        return DATE.format(Instant.ofEpochMilli(millis));
    }
}
