# EterModeration

La **modération** du réseau, côté Paper : sanctions selon un barème, appels, alertes au staff, journal du chat, comptes
liés, notes du staff, filtre du chat. Document développeur, à tenir à jour avec le code. À installer sur **tous** les
serveurs Paper, avec **EterVelocityModeration** sur le proxy : c'est lui qui refuse les bannis à l'entrée.

Il n'y a **pas de prison** (supprimée en 2.0.0) : les sanctions sont l'avertissement, l'expulsion, le mute et le ban.

## Prérequis

- **EterLib 1.14.0+** (`depend`) : base (`insertReturningId` pour les sanctions), Redis (bus réseau), invisibilité réseau (Vanish), langues, menus (cadre, Dialogs, bouton Retour), joueurs du réseau.
- **EterVelocityModeration 2.1.0+** sur le proxy (même base) : le ban et l'expulsion, appliqués tout de suite.
- **EterSync 1.2.1+** (facultatif) : ender chest et inventaire complet d'un joueur hors ligne, comparaison avec la veille.
- **EterChat 1.4.0+** (facultatif) : un clic sur un message du chat le signale (`/report`).
- **EterTab 1.1.13+** : les invisibles retirés de la liste Tab du réseau.

## Les sanctions

Quatre types (`SanctionType`) :

| Type | Effet | Appliqué par |
|---|---|---|
| `warn` | noté dans l'historique, compte pour la récidive ; à accepter à la connexion (« J'ai compris », `PlayerNotices`) | — |
| `kick` | déconnecté du réseau, peut revenir tout de suite | le proxy |
| `mute` | plus de chat, de messages privés (`private-commands`), de panneaux, de livres ni de noms à l'enclume | ce plugin (`MuteGuard`) |
| `ban` | refusé à l'entrée du réseau, temporaire ou définitif ; au retour, un rappel des règles | le proxy |

**Garde-fous** (`SanctionService`) :
- **staff protégé** (`StaffRegistry`, `eter.mod.protected`, donnée avec `eter.mod.staff`) : ni gelé, ni sanctionné sans
  `eter.mod.sanction.staff`. Chaque serveur note à la connexion qui est du staff (Redis `etermod:protected`), pour le
  savoir même s'il est ailleurs ou hors ligne ;
- **ban définitif à deux** (`permanent-approval`) : demandé, c'est d'abord un ban de `interim` (7 j) ; un second membre
  du staff avec `eter.mod.permanent` le valide ou le refuse (`/mod > Bans à valider`, `PendingBans`) ;
- **casier qui s'efface** : la récidive ne compte que les `recidivism-months` (6) derniers mois ;
- **preuves** : pour les motifs de `evidence.motives`, les derniers messages du joueur sont joints (colonne `evidence`,
  historique > touche 1-9) ;
- **durée modifiable** (`eter.mod.change`, historique > clic droit) : la nouvelle durée totale, depuis le début.

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

- **`/mod`** (staff) : chercher un joueur, les bans définitifs à valider, les appels et les signalements à traiter, les
  statistiques du staff (30 jours, `StaffLog`), les dernières sanctions du réseau. **`/mod reload`** : le barème et le
  filtre relus sans redémarrage (`eter.mod.reload`).
- **`/mod <joueur>`** : sa fiche (connecté où, sanctions en cours, gelé par qui et depuis quand, surveillé) →
  sanctionner (motifs + sanctions libres, expulsion comprise), historique (lever, changer la durée, preuves), notes du
  staff, derniers messages (affichés dans le chat), comptes liés (étendre son ban à eux : `eter.mod.ban.alts`),
  **surveiller** (le staff est alerté à chacune de ses arrivées : `JoinWatch`), **coffre de l'Ender** et **inventaire
  complet** en lecture seule (en direct s'il est ici, sinon sa dernière sauvegarde EterSync), **comparer avec la
  veille** (`InventoryCompare` : une photo par jour à la connexion, Redis 4 jours) ; s'il est connecté : aller le voir,
  le geler. Et, demandés aux autres plugins par leurs API : son **clan** (EterClan), ses **homes** (EterHome) et
  l'historique de ses **sauvegardes** (EterSync), chacun avec ses propres permissions.
- **Signalements** (`/mod`) : gauche, aller le voir ; droit, fondé (l'auteur est prévenu) ; shift + droit, **faux**
  (au-delà de `reports.false-limit` faux en 30 jours, l'auteur ne peut plus signaler) ; Q, le **prendre en charge** ;
  1-9, les **preuves** (derniers messages du joueur signalé, pour `reports.evidence-reasons`).
- **`/appeal`** (joueurs) : ses sanctions en cours et ses avertissements, faire appel (une fois par sanction), l'état
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
| `eter.mod.warn` / `.kick` / `.mute` / `.ban` | donner ce type de sanction |
| `eter.mod.change` | changer la durée d'une sanction en cours |
| `eter.mod.sanction.staff` | sanctionner un membre du staff protégé |
| `eter.mod.ban.alts` | étendre le ban d'un joueur à ses comptes liés |
| `eter.mod.invsee` | ender chest, inventaire complet, comparaison avec la veille |
| `eter.mod.watch` | mettre un joueur sous surveillance |
| `eter.mod.spectate` | suivre un joueur en spectateur (mode staff) |
| `eter.mod.staffstats` / `eter.mod.reload` | statistiques du staff / `/mod reload` |
| `eter.mod.bypass.xray` | pas d'alerte de minage suspect |
| `eter.mod.motive.<motif>` (ou `eter.mod.motive.*`) | utiliser ce motif (en plus du type de l'étape) |
| `eter.mod.free` | sanction libre (hors barème) |
| `eter.mod.permanent` | une sanction définitive : étape `perm` du barème ou sanction libre (sans elle, le menu la montre verrouillée) |
| `eter.mod.lift` | lever une sanction en cours |
| `eter.mod.appeals` | traiter les appels |
| `eter.mod.history` / `.notes` / `.chatlog` / `.alts` | historique et dernières sanctions / notes / messages / comptes liés |
| `eter.mod.alerts` | recevoir les alertes du réseau |
| `eter.mod.staff` | `/staff`, aller voir un joueur depuis sa fiche |
| `eter.mod.freeze` | geler un joueur |
| `eter.mod.protected` | staff protégé : ni gelé ni sanctionné sans `eter.mod.sanction.staff` (donnée avec `eter.mod.staff`) |
| `eter.mod.reports` | traiter les signalements |
| `eter.vanish.see` (EterLib) | voir les invisibles (donné par `eter.mod.*`) |
| `eter.mod.bypass.filter` | pas de filtre du chat |
| `eter.mod.appeal` | `/appeal` (tout le monde) |
| `eter.mod.report` | `/report` (tout le monde) |

Les permissions des motifs sont créées au démarrage d'après la config (enfants de `eter.mod.motive.*`).

## Modules

- **`sanction`** : `SanctionRepository` (table), `Motives` (barème), `SanctionService` (donner, lever, permissions),
  `Enforcement` (application, canal proxy), `MuteGuard` (bloque chat et messages privés au plus tôt, `LOWEST`),
  `Labels` (textes : type, motif, durée, date).
- **`appeal`** : `etermod_appeals`, un appel par sanction ; accepté = sanction levée ; la réponse va au joueur où qu'il
  soit, et reste visible dans `/appeal`.
- **`alert`** : `StaffAlerts`, alertes à `eter.mod.alerts` sur tout le réseau (sanction donnée ou levée, appel, mot
  interdit...), dans la console, et sur un salon Discord (`discord.webhook-url`, alertes de `discord.alerts`, sans
  mention possible) ; `MiningAlerts` (minage suspect : trop de filons de diamant ou de débris pour la pierre cassée en
  une heure, `xray`) ; `JoinWatch` (à l'arrivée sur le réseau : joueur surveillé, compte lié à un banni).
- **`record`** :
  - **journal du chat** (`etermod_chat`) : chat et messages privés réellement envoyés (après mute et filtre),
    gardés `chat-log.days` jours (purge quotidienne) ;
  - **comptes liés** (`etermod_links`) : l'adresse IP n'est **jamais** gardée ni affichée, seulement son empreinte
    SHA-256 avec un sel secret tiré au hasard une fois (`etermod_settings`, commun au réseau). Deux comptes qui partagent
    une empreinte sont liés : un indice, pas une preuve (famille, colocation). L'IP vient du proxy (forwarding Velocity) ;
  - **notes** (`etermod_notes`) : remarques du staff, invisibles pour le joueur.
- **`filter`** : `ChatFilter` (spam, répétition, majuscules, mots interdits en mot entier sans accents ni « l33t »,
  même lettre par lettre « c o n n a r d », publicité : IP et noms de domaine hors `filter.adverts.allowed`),
  `FilterListener` (bloque, prévient ; un mot interdit → `filter.word-motive`, une pub → `filter.adverts.motive` tout de
  suite ; spam/majuscules → `filter.spam-motive` au `filter.strikes`-ième en `filter.strikes-minutes`). Les messages
  privés, les **panneaux**, les **livres** et les **noms à l'enclume** ne sont filtrés que pour les mots interdits et
  la pub. Tests : `gradlew test` (`ChatFilterTest`, `MotivesTest`).
- **`staff`** :
  - **mode staff** (`StaffMode`, `/staff`) : l'inventaire part en base (`etermod_staff_inventories`) AVANT d'être vidé, et
    il est repris (lu puis effacé) avant d'être rendu : rien de perdu ni de rendu deux fois. Pendant l'aller-retour en base,
    le joueur ne peut ni jeter, ni poser, ni manger, ni ranger, ni ramasser (sinon l'objet serait à la fois dans le
    monde et dans la photo). Invisible sur tout le
    réseau (EterLib `Vanish`), vol, invulnérable, pas de faim, pas de ramassage (objets, expérience), pas de monstres qui le
    ciblent ni qui apparaissent autour de lui ; il ne déclenche ni plaques de pression, ni fils, ni capteurs de sculk, ni
    cultures piétinées. Ses téléportations ne font ni bruit ni particules pour les autres (EterLib 1.18.1). Outils
    (marqués, ni posés, ni jetés, ni rangés ailleurs) : fiche (clic droit sur un joueur ; accroupi : le suivre en
    spectateur, `eter.mod.spectate`), geler, joueur au hasard du réseau (pas sur `staff.blocked-servers`), inspecteur,
    visible/invisible, **loupe à coffres** (clic droit sur un conteneur : une copie en lecture seule, sans ouvrir le vrai,
    les shulkers s'ouvrent aussi ; accroupi : le journal du coffre), **options** (vision de nuit ; accroupi : vitesse de
    vol), signalements, quitter. L'état (Redis `etermod:staff`) suit le joueur : à l'arrivée
    sur un serveur, la protection (vol, invulnérabilité) revient tout de suite (sinon il tombe), et les outils manquants
    sont redonnés 3 s après (le temps qu'EterSync rende l'inventaire). Pas d'entrée ni de sortie sur
    `staff.blocked-servers` (vide par défaut : un serveur sans EterSync, où l'inventaire rendu resterait).
  - **journal des blocs** (`BlockLog`, 1.5.0) : chaque bloc posé ou cassé est noté dans **Redis seulement**
    (`etermod:blocks:<serveur>:<monde>:<cx>:<cz>:<jour>`, hash `x,y,z` -> action, joueur, heure, bloc ; la dernière action
    du jour par bloc), écrit par paquets toutes les secondes ; chaque clé expire après `block-log.days` (7) + 1 jours.
    Aussi (2.1.0) : lave et eau versées, feu, TNT allumée, panneaux (avec leur texte), animaux, villageois, entités
    nommées tués, cadres et tableaux cassés (champ `E|x,y,z|heure`). Outil **Inspecteur** du mode staff (case 3) : clic
    droit sur un bloc = son histoire ces derniers jours ; accroupi = tout ce qui s'est passé autour (rayon 6).
  - **journal des coffres** (`ContainerLog`, 2.1.0) : le contenu d'un conteneur est photographié à l'ouverture et comparé
    à la fermeture ; la différence est notée dans Redis comme le journal des blocs (`etermod:containers:...`, champ
    `x,y,z|heure|joueur` -> `+3 BREAD,-12 DIAMOND`). Le vol devient prouvable.
  - **geler** (`Freeze`) : plus de déplacement (la tête seulement), de véhicule, d'élytres, de téléportation de son
    fait (perle...), de blocs, de coups ; seules les commandes de `private-commands` ; son chat ne va qu'au staff
    (alerte `[Gel]`, gardé dans le journal). Un titre à l'écran. Le staff qui gèle est téléporté près de lui
    (`freeze.teleport-staff`). Redis `etermod:frozen` (`staff;depuis`) + bus : appliqué sur le serveur du joueur, et
    après un changement de serveur ou une reconnexion. Fin automatique après `freeze.max-minutes`. Quitter le réseau
    pendant un gel : alerte, et la sanction `freeze.quit-motive` après `freeze.quit-grace-seconds` (un changement de
    serveur ne compte pas). Chaque gel et dégel va dans les notes du joueur. Le staff protégé ne peut pas être gelé.
  - **journal du staff** (`StaffLog`, `etermod_staff_log`, `staff-log.days`) : sanctions, levées, gels, inventaires et
    coffres vus, téléportations, mode staff, spectateur, signalements traités ; statistiques dans `/mod`.
- **`report`** : `etermod_reports`, ouverts jusqu'à ce qu'un membre du staff les traite (`claimed_by`, `false_report`,
  `evidence`).
- **`menu`** : `ModGui` (chargement en tâche de fond puis ouverture, fenêtres) et les menus. Cadre rouge pour le staff,
  orange pour `/appeal`.

## Tables (`etermod_`)

`sanctions` (lue aussi par le proxy : `uuid`, `type` en majuscules, `expires_at` 0 = définitive, `lifted_at` 0 = en
cours, `evidence` ; index sur `uuid` et `created_at`), `appeals`, `notes`, `chat`, `links`, `settings`, `reports`,
`staff_inventories`, `staff_log`, `pending_bans`.

Redis : `etermod:frozen`, `etermod:staff`, `etermod:protected`, `etermod:watch`, `etermod:warn-ack`,
`etermod:returning`, `etermod:invsnap:<uuid>`, `etermod:seen:<uuid>`, `etermod:blocks:...`, `etermod:containers:...`.

## API (pour les autres plugins)

`fr.eternom.eterModeration.api.ModerationApi` (`ModerationApi.get()`) : personne d'autre ne lit les tables `etermod_*`
ni ses clés Redis (`etermod:frozen`, `etermod:staff`...). Seule exception : EterVelocityModeration, sa moitié proxy, qui
lit `etermod_sanctions` (le proxy ne peut pas appeler un plugin Paper au moment où un joueur se connecte).

- `activeSanctions(uuid)`, `isMuted`, `isBanned`, `isFrozen`, `isInStaffMode` (bloquant) ; `isJailed` est supprimé
  (2.0.0) ;
- `give(uuid, pseudo, type, durée, raison, auteur)` : une sanction hors barème (`warn`, `kick`, `mute`, `ban`) au nom d'un autre
  plugin (ex : un anti-triche), appliquée et annoncée comme les autres.

Ce qu'EterModeration demande aux autres : clan (`ClanApi`), homes (`HomeApi`), inventaire (`SyncApi`) pour la fiche ;
chacune n'est appelée que si son plugin tourne sur ce serveur (`Integrations`).
