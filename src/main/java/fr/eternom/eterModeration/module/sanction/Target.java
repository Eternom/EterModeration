package fr.eternom.eterModeration.module.sanction;

import java.util.UUID;

/** Le joueur visé (connecté ou non, sur n'importe quel serveur) : retrouvé par EterLib (table eter_players). */
public record Target(UUID uuid, String name) {
}
