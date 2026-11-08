package fr.eternom.eterModeration.module.sanction;


import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le barème : lecture des étapes et des durées, récidive, ancienne prison. */
class MotivesTest {

    @Test
    void durations() {
        assertEquals(Duration.ofSeconds(30), Motives.parseDuration("30s"));
        assertEquals(Duration.ofMinutes(10), Motives.parseDuration("10m"));
        assertEquals(Duration.ofHours(2), Motives.parseDuration("2h"));
        assertEquals(Duration.ofDays(7), Motives.parseDuration("7d"));
        assertEquals(Duration.ofDays(14), Motives.parseDuration("2w"));
        assertNull(Motives.parseDuration("0d"));
        assertNull(Motives.parseDuration("-1d"));
        assertNull(Motives.parseDuration("abc"));
        assertNull(Motives.parseDuration("d"));
        assertNull(Motives.parseDuration(null));
    }

    @Test
    void steps() {
        assertEquals(new Motives.Step(SanctionType.WARN, Duration.ZERO), Motives.parseStep("warn"));
        assertEquals(new Motives.Step(SanctionType.KICK, Duration.ZERO), Motives.parseStep("kick"));
        assertEquals(new Motives.Step(SanctionType.MUTE, Duration.ofMinutes(10)), Motives.parseStep("mute 10m"));
        assertEquals(new Motives.Step(SanctionType.BAN, null), Motives.parseStep("ban perm"));
        assertNull(Motives.parseStep("ban"));
        assertNull(Motives.parseStep("prison 3d"));
    }

    @Test
    void oldJailIsReadAsBan() {
        assertEquals(new Motives.Step(SanctionType.BAN, Duration.ofDays(3)), Motives.parseStep("jail 3d"));
        assertEquals(SanctionType.BAN, SanctionType.fromDatabase("JAIL"));
        assertEquals(SanctionType.WARN, SanctionType.fromDatabase("SOMETHING_NEW"));
    }

    @Test
    void lastStepRepeats() {
        // L'icône (Material.matchMaterial) demande un serveur : le motif est construit directement
        Motives.Motive grief = new Motives.Motive("grief", null, List.of(Motives.parseStep("ban 1d"), Motives.parseStep("ban 7d"),
                Motives.parseStep("ban perm")));
        assertEquals(Duration.ofDays(1), grief.step(0).duration());
        assertEquals(Duration.ofDays(7), grief.step(1).duration());
        assertNull(grief.step(2).duration());
        assertNull(grief.step(10).duration());
    }

    @Test
    void lastingTypes() {
        assertTrue(SanctionType.MUTE.lasts());
        assertTrue(SanctionType.BAN.lasts());
        assertFalse(SanctionType.WARN.lasts());
        assertFalse(SanctionType.KICK.lasts());
    }
}
