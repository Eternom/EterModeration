# EterModeration

La **modération** du réseau, côté Paper : sanctions selon un barème, appels, alertes au staff, journal du chat, comptes
liés, notes du staff, filtre du chat. Document développeur, à tenir à jour avec le code. À installer sur **tous** les
serveurs Paper, avec **EterVelocityModeration** sur le proxy : c'est lui qui garde les prisonniers sur les serveurs
prison et refuse les bannis à l'entrée.

La vie en prison (travail, remise de peine...) n'est **pas** ici : elle viendra dans un plugin dédié.

## Prérequis

- **EterLib 1.8.0+** (`depend`) : base, Redis (bus réseau), langues, menus (cadre, Dialogs), joueurs du réseau.
- **EterVelocityModeration** sur le proxy (même base) : prison et ban.
- **EterChat 1.1.7+** : chat séparé des serveurs prison (`prison.server-prefix`).

## Les sanctions

Quatre types (`SanctionType`) :

| Type | Effet | Appliqué par |
|---|---|---|
| `warn` | noté dans l'historique, compte pour la récidive | — |
| `mute` | plus de chat ni de messages privés (`private-commands`) | ce plugin (`MuteGuard`) |
| `jail` | seulement les serveurs prison, temporaire ou définitive | le proxy |
| `ban` | refusé à l'entrée du réseau : cas très graves (évasion de prison, contournement) | le proxy |

**Barème** (`config.yml > motives`) : chaque motif a ses étapes, de la première fois à la récidive (`mute 10m`,
`jail 3d`, `ban perm`) ; au-delà de la dernière, la dernière se répète. La récidive compte les sanctions de ce motif
**non levées**. Le menu affiche la peine **avant** de valider. Les noms des motifs sont dans `lang/` (`motive.<id>`).
Une sanction libre (hors barème) demande `eter.mod.free` et une raison.

Une sanction n'est **jamais effacée** : levée (erreur, appel accepté), elle garde qui l'a levée et quand.

### Application immédiate (`Enforcement`)

1. La sanction est écrite en base (`etermod_sanctions`, seule source de vérité).
2. Le serveur où elle est donnée prévient les autres (bus réseau `etermoderation`, message `refresh`).
3. Le serveur où **est** le joueur relit son mute, et envoie au proxy « relis ses sanctions » sur le canal
   `eter:moderation`, **à travers la connexion du joueur** : seul un serveur peut écrire sur ce canal (le proxy refuse
   ce qui vient d'un client), et le message ne porte que l'UUID ; le proxy relit tout en base. Un joueur ne peut donc
   ni se libérer ni sanctionner quelqu'un.

Un joueur hors ligne n'a besoin de rien : le proxy lit ses sanctions à la connexion.

## Interface

Tout se fait dans les menus ; saisies et confirmations par des fenêtres (Dialogs).

- **`/mod`** (staff) : chercher un joueur, les appels à traiter, les dernières sanctions du réseau.
- **`/mod <joueur>`** : sa fiche (connecté où, sanctions en cours) → sanctionner (motifs + sanctions libres),
  historique (lever), notes du staff, derniers messages (affichés dans le chat), comptes liés.
- **`/appel`** (joueurs) : ses sanctions en cours et ses avertissements, faire appel (une fois par sanction), l'état
  et la réponse du staff. Le joueur ne voit pas qui l'a sanctionné.

## Permissions : une par action

Chaque grade du staff reçoit exactement ce qu'il peut faire (LuckPerms). `eter.mod.*` donne tout.

| Permission | Action |
|---|---|
| `eter.mod.use` | ouvrir `/mod` et la fiche d'un joueur |
| `eter.mod.warn` / `.mute` / `.jail` / `.ban` | donner ce type de sanction |
| `eter.mod.motive.<motif>` (ou `eter.mod.motive.*`) | utiliser ce motif (en plus du type de l'étape) |
| `eter.mod.free` | sanction libre ; `eter.mod.permanent` pour qu'elle soit définitive |
| `eter.mod.lift` | lever une sanction en cours |
| `eter.mod.appeals` | traiter les appels |
| `eter.mod.history` / `.notes` / `.chatlog` / `.alts` | historique et dernières sanctions / notes / messages / comptes liés |
| `eter.mod.alerts` | recevoir les alertes du réseau |
| `eter.mod.bypass.filter` | pas de filtre du chat |
| `eter.mod.appeal` | `/appel` (tout le monde) |

Les permissions des motifs sont créées au démarrage d'après la config (enfants de `eter.mod.motive.*`).

## Modules

- **`sanction`** : `SanctionRepository` (table), `Motives` (barème), `SanctionService` (donner, lever, permissions),
  `Enforcement` (application, canal proxy), `MuteGuard` (bloque chat et messages privés au plus tôt, `LOWEST`),
  `Labels` (textes : type, motif, durée, date).
- **`appeal`** : `etermod_appeals`, un appel par sanction ; accepté = sanction levée ; la réponse va au joueur où qu'il
  soit, et reste visible dans `/appel`.
- **`alert`** : `StaffAlerts`, alertes à `eter.mod.alerts` sur tout le réseau (sanction donnée ou levée, appel, mot
  interdit), et dans la console.
- **`record`** :
  - **journal du chat** (`etermod_chat`) : chat et messages privés réellement envoyés (après mute et filtre),
    gardés `chat-log.days` jours (purge quotidienne) ;
  - **comptes liés** (`etermod_links`) : l'adresse IP n'est **jamais** gardée ni affichée, seulement son empreinte
    SHA-256 avec un sel secret tiré au hasard une fois (`etermod_settings`, commun au réseau). Deux comptes qui partagent
    une empreinte sont liés : un indice, pas une preuve (famille, colocation). L'IP vient du proxy (forwarding Velocity) ;
  - **notes** (`etermod_notes`) : remarques du staff, invisibles pour le joueur.
- **`filter`** : `ChatFilter` (spam, répétition, majuscules, mots interdits en mot entier sans accents ni « l33t »),
  `FilterListener` (bloque, prévient ; un mot interdit → `filter.word-motive` tout de suite ; spam/majuscules →
  `filter.spam-motive` au `filter.strikes`-ième en `filter.strikes-minutes`). Les messages privés ne sont filtrés que
  pour les mots interdits.
- **`menu`** : `ModGui` (chargement en tâche de fond puis ouverture, fenêtres) et les menus. Cadre rouge pour le staff,
  orange pour `/appel`.

## Tables (`etermod_`)

`sanctions` (lue aussi par le proxy : `uuid`, `type` en majuscules, `expires_at` 0 = définitive, `lifted_at` 0 = en
cours), `appeals`, `notes`, `chat`, `links`, `settings`.
