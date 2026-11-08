package fr.eternom.eterModeration.module.filter;

import fr.eternom.eterModeration.module.filter.ChatFilter.Verdict;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Le filtre : mots interdits (accents, l33t, lettres espacées), publicité, adresses permises, faux positifs. */
class ChatFilterTest {

    private static ChatFilter filter() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("words", List.of("connard", "fdp", "nique ta mere"));
        config.set("adverts.allowed", List.of("etercraft.fr", "discord.gg/eternom"));
        return new ChatFilter(config);
    }

    @Test
    void forbiddenWords() {
        ChatFilter filter = filter();
        assertTrue(filter.hasWord("espèce de connard"));
        assertTrue(filter.hasWord("c0nn4rd"));
        assertTrue(filter.hasWord("CONNARD !"));
        assertTrue(filter.hasWord("c o n n a r d"));
        assertTrue(filter.hasWord("f d p"));
        assertTrue(filter.hasWord("nique ta mère"));
        assertFalse(filter.hasWord("connardise"));
        assertFalse(filter.hasWord("il y a un problème"));
    }

    @Test
    void adverts() {
        ChatFilter filter = filter();
        assertTrue(filter.isAdvert("venez sur play.monserveur.net"));
        assertTrue(filter.isAdvert("rejoins monserveur.fr"));
        assertTrue(filter.isAdvert("ip : 51.38.12.4:25565"));
        assertFalse(filter.isAdvert("le site etercraft.fr et discord.gg/eternom"));
        assertFalse(filter.isAdvert("c'est net, je viens. Me voilà"));
        assertFalse(filter.isAdvert("j'ai vu.Me voilà"));
        assertFalse(filter.isAdvert("ok, ça marche"));
    }

    @Test
    void verdicts() {
        ChatFilter filter = filter();
        UUID player = UUID.randomUUID();
        assertEquals(Verdict.OK, filter.check(player, "salut tout le monde"));
        assertEquals(Verdict.REPEAT, filter.check(player, "salut tout le monde"));
        assertEquals(Verdict.WORD, filter.check(player, "connard"));
        assertEquals(Verdict.ADVERT, filter.scan("play.autre.fr"));
        assertEquals(Verdict.CAPS, filter.check(UUID.randomUUID(), "ARRÊTEZ DE CRIER SVP"));
    }

    @Test
    void singleLettersAreJoined() {
        assertEquals("connard ici", ChatFilter.joinSingleLetters("c o n n a r d ici"));
        assertEquals("ok go", ChatFilter.joinSingleLetters("ok go"));
    }
}
