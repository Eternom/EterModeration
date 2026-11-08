# EterModeration

La **modération** du réseau, côté Paper : sanctions selon un barème, appels, alertes au staff, journal du chat, comptes
liés, notes du staff, filtre du chat. Document développeur, à tenir à jour avec le code. À installer sur **tous** les
serveurs Paper, avec **EterVelocityModeration** sur le proxy : c'est lui qui refuse les bannis à l'entrée.

Il n'y a **pas de prison** (supprimée en 2.0.0) : les sanctions sont l'avertissement, le mute et le ban.

## Prérequis

- **EterLib 1.14.0+** (`depend`) : base (`insertReturningId` pour les sanctions), Redis (bus réseau), invisibilité réseau (Vanish), langues, menus (cadre, Dialogs, bouton Retour), joueurs du réseau.
- **EterVelocityModeration 2.0.0+** sur le proxy (même base) : le ban, appliqué tout de suite.
- **EterTab 1.1.13+** : les invisibles retirés de la liste Tab du réseau.

## Les sanctions

Trois types (`SanctionType`) :

| Type | Effet | Appliqué par |
|---|---|---|
| `warn` | noté dans l'historique, compte pour la récidive | — |
| `mute` | plus de chat ni de messages privés (`private-commands`) | ce plugin (`MuteGuard`) |
| `ban` | refusé à l'entrée du réseau, temporaire ou définitif | le proxy |

**Barème** (`config.yml > motives`) : chaque motif a ses étapes, de la première fois à la récidive (`mute 10m`,
`ban 3d`, `ban perm`) ; les fautes de chat passent d'abord par le mute, le ban vient ensuite ; au-delà de la dernière, la dernière se répète. La récidive compte les sanctions de ce motif
**non levées**. Le menu affiche la peine **avant** de valider. Les noms des motifs sont dans `lang/` (`motive.<id>`).
Une sanction libre (hors barème) demande `eter.mod.free` et une raison.

Une sanction n'est **jamais effacée** : levée (erreur, appel accepté), elle garde qui l'a levée et quand.

**Fin de la prison (2.0.0)** : au démarrage, les anciennes sanctions `JAIL` deviennent des `BAN` de même fin
(`SanctionRepository`) : une peine en cours continue en ban, l'historique et la récidive restent justes. Une étape
`jail` d'une ancienne config est lue comme `ban` (avec un avertissement dans la console) ; le motif `escape` (évasion)
n'est plus dans la config par défaut, son nom reste dans `lang/` pour l'historique.

### Application immédiate (`Enforcement`)

1. La sanction est écrite en base (`etermod_sanctions`, seule source de vérité).
2. Le serveur où elle est donnée prévient les autres (bus réseau `etermoderation`, message `refresh`).
3. Le serveur où **est** le joueur relit son mute, et envoie au proxy « relis ses sanctions » sur le canal
   `eter:moderation`, **à travers la connexion du joueur** : seul un serveur peut écrire sur ce canal (le proxy refuse
   ce qui vient d'un client), et le message ne porte que l'UUID ; le proxy relit tout en base. Un joueur ne peut donc
   ni se débannir ni sanctionner quelqu'un.

Un joueur hors ligne n'a besoin de rien : le proxy lit ses sanctions à la connexion.

## Interface

Tout se fait dans les menus ; saisies et confirmations par des fenêtres (Dialogs).

- **`/mod`** (staff) : chercher un joueur, les appels et les signalements à traiter, les dernières sanctions du réseau.
- **`/mod <joueur>`** : sa fiche (connecté où, sanctions en cours) → sanctionner (motifs + sanctions libres),
  historique (lever), notes du staff, derniers messages (affichés dans le chat), comptes liés ; s'il est connecté :
  aller le voir, le geler. Et, demandés aux autres plugins par leurs API : son **clan** (EterClan), ses
  **homes** (EterHome) et son **inventaire** en lecture seule (EterSync), chacun avec ses propres permissions.
- **`/appel`** (joueurs) : ses sanctions en cours et ses avertissements, faire appel (une fois par sanction), l'état
  et la réponse du staff. Le joueur ne voit pas qui l'a sanctionné.
- **`/report [joueur]`** (joueurs) : le motif dans un menu (`reports.reasons`), un détail facultatif ; un signalement
  par `reports.cooldown-seconds` (Redis, réseau). Le staff est alerté ; l'auteur est prévenu quand c'est traité.
  Le bouton Retour de ces deux menus lance `menus.back-command` (« menu social » : le sous-menu Social de `/menu`).
- **`/staff`** : le mode staff, sur tout le réseau (voir `staff`).

## Permissions : une par action

Chaque grade du staff reçoit exactement ce qu'il peut faire (LuckPerms). `eter.mod.*` donne tout.

| Permission | Action |
|---|---|
| `eter.mod.use` | ouvrir `/mod` et la fiche d'un joueur |
| `eter.mod.warn` / `.mute` / `.ban` | donner ce type de sanction |
| `eter.mod.motive.<motif>` (ou `eter.mod.motive.*`) | utiliser ce motif (en plus du type de l'étape) |
| `eter.mod.free` | sanction libre (hors barème) |
| `eter.mod.permanent` | une sanction définitive : étape `perm` du barème ou sanction libre (sans elle, le menu la montre verrouillée) |
| `eter.mod.lift` | lever une sanction en cours |
| `eter.mod.appeals` | traiter les appels |
| `eter.mod.history` / `.notes` / `.chatlog` / `.alts` | historique et dernières sanctions / notes / messages / comptes liés |
| `eter.mod.alerts` | recevoir les alertes du réseau |
| `eter.mod.staff` | `/staff`, aller voir un joueur depuis sa fiche |
| `eter.mod.freeze` | geler un joueur |
| `eter.mod.freeze.exempt` | ne peut pas être gelé (donnée avec `eter.mod.staff`) |
| `eter.mod.reports` | traiter les signalements |
| `eter.vanish.see` (EterLib) | voir les invisibles (donné par `eter.mod.*`) |
| `eter.mod.bypass.filter` | pas de filtre du chat |
| `eter.mod.appeal` | `/appel` (tout le monde) |
| `eter.mod.report` | `/report` (tout le monde) |

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
- **`staff`** :
  - **mode staff** (`StaffMode`, `/staff`) : l'inventaire part en base (`etermod_staff_inventories`) AVANT d'être vidé, et
    il est repris (lu puis effacé) avant d'être rendu : rien de perdu ni de rendu deux fois. Pendant l'aller-retour en base,
    le joueur ne peut ni jeter, ni poser, ni manger, ni ranger, ni ramasser (sinon l'objet serait à la fois dans le
    monde et dans la photo). Invisible sur tout le
    réseau (EterLib `Vanish`), vol, invulnérable, pas de faim, pas de ramassage (objets, expérience), pas de monstres qui le
    ciblent ni qui apparaissent autour de lui ; il ne déclenche ni plaques de pression, ni fils, ni capteurs de sculk, ni
    cultures piétinées. Ses téléportations ne font ni bruit ni particules pour les autres (EterLib 1.18.1). Outils
    (marqués, ni posés, ni jetés, ni rangés ailleurs) : fiche (clic droit sur un joueur), geler, joueur au hasard du réseau
    (pas sur `staff.blocked-servers`), visible/invisible, signalements, quitter. L'état (Redis `etermod:staff`) suit le joueur : à l'arrivée
    sur un serveur, la protection (vol, invulnérabilité) revient tout de suite (sinon il tombe), et les outils manquants
    sont redonnés 3 s après (le temps qu'EterSync rende l'inventaire). Pas d'entrée ni de sortie sur
    `staff.blocked-servers` (vide par défaut : un serveur sans EterSync, où l'inventaire rendu resterait).
  - **journal des blocs** (`BlockLog`, 1.5.0) : chaque bloc posé ou cassé est noté dans **Redis seulement**
    (`etermod:blocks:<serveur>:<monde>:<cx>:<cz>:<jour>`, hash `x,y,z` -> action, joueur, heure, bloc ; la dernière action
    du jour par bloc), écrit par paquets toutes les secondes ; chaque clé expire après `block-log.days` (7) + 1 jours.
    Outil **Inspecteur** du mode staff (longue-vue, case 4) : clic droit sur un bloc = qui l'a posé ou cassé ces derniers
    jours. Pour démasquer un build provocateur sans base de données ni nettoyage.
  - **geler** (`Freeze`) : plus de déplacement (la tête seulement), de blocs, de coups ; seules les commandes de
    `private-commands`. Redis `etermod:frozen` + bus : appliqué sur le serveur du joueur, et après un changement de
    serveur ou une reconnexion. Une déconnexion pendant le gel alerte le staff. Le staff (`eter.mod.freeze.exempt`)
    ne peut pas être gelé : refusé tout de suite s'il est sur le même serveur, sinon son serveur annule le gel et
    alerte le staff ; un ancien gel est effacé à sa connexion.
- **`report`** : `etermod_reports`, ouverts jusqu'à ce qu'un membre du staff les traite.
- **`menu`** : `ModGui` (chargement en tâche de fond puis ouverture, fenêtres) et les menus. Cadre rouge pour le staff,
  orange pour `/appel`.

## Tables (`etermod_`)

`sanctions` (lue aussi par le proxy : `uuid`, `type` en majuscules, `expires_at` 0 = définitive, `lifted_at` 0 = en
cours), `appeals`, `notes`, `chat`, `links`, `settings`, `reports`, `staff_inventories`.

## API (pour les autres plugins)

`fr.eternom.eterModeration.api.ModerationApi` (`ModerationApi.get()`) : personne d'autre ne lit les tables `etermod_*`
ni ses clés Redis (`etermod:frozen`, `etermod:staff`...). Seule exception : EterVelocityModeration, sa moitié proxy, qui
lit `etermod_sanctions` (le proxy ne peut pas appeler un plugin Paper au moment où un joueur se connecte).

- `activeSanctions(uuid)`, `isMuted`, `isBanned`, `isFrozen`, `isInStaffMode` (bloquant) ; `isJailed` est supprimé
  (2.0.0) ;
- `give(uuid, pseudo, type, durée, raison, auteur)` : une sanction hors barème (`warn`, `mute`, `ban`) au nom d'un autre
  plugin (ex : un anti-triche), appliquée et annoncée comme les autres.

Ce qu'EterModeration demande aux autres : clan (`ClanApi`), homes (`HomeApi`), inventaire (`SyncApi`) pour la fiche ;
chacune n'est appelée que si son plugin tourne sur ce serveur (`Integrations`).
