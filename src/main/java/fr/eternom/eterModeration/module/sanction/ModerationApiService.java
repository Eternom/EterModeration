package fr.eternom.eterModeration.module.sanction;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterModeration.api.ModerationApi;
import fr.eternom.eterModeration.module.staff.Freeze;
import fr.eternom.eterModeration.module.staff.StaffMode;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** L'API d'EterModeration (ModerationApi) : le plugin lui-même, vu de l'extérieur. */
public class ModerationApiService implements ModerationApi {

    private final SanctionService sanctions;
    private final RedisCache redis;

    public ModerationApiService(SanctionService sanctions, RedisCache redis) {
        this.sanctions = sanctions;
        this.redis = redis;
    }

    @Override
    public List<SanctionInfo> activeSanctions(UUID player) {
        return sanctions.repository().active(player, System.currentTimeMillis()).stream()
                .map(found -> new SanctionInfo(found.id(), found.type().name(), found.motive(), found.reason(), found.staffName(),
                        found.createdAt(), found.expiresAt())).toList();
    }

    @Override
    public boolean isMuted(UUID player) {
        return has(player, SanctionType.MUTE);
    }

    @Override
    public boolean isJailed(UUID player) {
        return has(player, SanctionType.JAIL);
    }

    @Override
    public boolean isFrozen(UUID player) {
        return redis.getHashField(Freeze.KEY, player.toString()).isPresent();
    }

    @Override
    public boolean isInStaffMode(UUID player) {
        return redis.getHashField(StaffMode.KEY, player.toString()).isPresent();
    }

    @Override
    public long give(UUID player, String playerName, String type, Duration duration, String reason, String author) {
        SanctionType sanctionType = SanctionType.of(type);
        if (sanctionType == null) {
            throw new IllegalArgumentException("Type de sanction inconnu : " + type + " (warn, mute, jail, ban)");
        }
        return sanctions.giveFromPlugin(new Target(player, playerName), sanctionType, duration, reason == null ? "" : reason, author);
    }

    private boolean has(UUID player, SanctionType type) {
        return sanctions.repository().active(player, System.currentTimeMillis()).stream().anyMatch(found -> found.type() == type);
    }
}
