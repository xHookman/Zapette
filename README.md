# Zapette

**La télé française sur Android TV, propulsée par Catch-up TV & More — sans Kodi.**

Zapette exécute l'extension Kodi [Catch-up TV & More](https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore)
*telle quelle* dans une application Android TV native, avec une interface pensée pour la télécommande :
on allume, on tombe sur sa dernière chaîne, on zappe avec ▲▼, on tape « 2 » pour France 2.

## Ce que ça fait

| | |
|---|---|
| **Direct** | Les 70+ chaînes françaises numérotées comme sur la TNT (1 TF1, 2 France 2… 27 Culturebox), zapping avec CH+ CH−, saisie du numéro au pavé, retour à la chaîne précédente (« 0 » ou touche *Last*), bandeau « maintenant / à suivre » avec progression, liste des chaînes en surimpression (◀ ou OK), fiche du programme (▶). |
| **Accueil** | « En ce moment » (vignette du programme diffusé sur chaque chaîne), « Reprendre la lecture », « Ce soir à la télé », accès rapide aux services de replay. |
| **Guide TV** | Programme complet d'aujourd'hui et de demain, chaîne par chaîne, issu des fichiers XMLTV de Catch-up TV & More. |
| **Replay / Explorer** | Toute l'arborescence de l'extension (France TV, TF1+, 6play, Arte, sites web, autres pays…) en grilles de vignettes, pages suivantes chargées automatiquement, recherche, menus contextuels (appui long sur OK). |
| **Lecteur** | Media3/ExoPlayer : HLS, DASH, Smooth Streaming, Widevine/PlayReady/ClearKey avec les en-têtes et le format de licence de *inputstream.adaptive*, choix audio / sous-titres, reprise de lecture. |
| **Réglages** | Les réglages de l'extension (comptes TF1+/M6+, qualité, langues…) générés depuis son `settings.xml`, copie d'un compte vers plusieurs chaînes et vérification des identifiants dès la saisie, sous-titres activables à tout moment, mini-lecteur en quittant le direct, mise à jour de l'application depuis les Releases GitHub, + mise à jour de l'extension depuis GitHub **sans mettre à jour l'application**. |

## Comment ça marche

```
┌──────────────── Zapette (Kotlin · Compose for TV) ────────────────┐
│  Accueil · Chaînes · Guide · Replay · Explorer · Réglages · Lecteur│
└───────────────▲──────────────────────────────────┬─────────────────┘
                │ JSON (PluginResult, chaînes, EPG) │ plugin://… url
┌───────────────┴──────────────────────────────────▼─────────────────┐
│  Python embarqué (Chaquopy)            zapette_kodi/bridge.py       │
│   ├─ Kodi émulé : xbmc · xbmcgui · xbmcplugin · xbmcaddon · xbmcvfs │
│   │               inputstreamhelper · YDStreamExtractor (yt-dlp)    │
│   ├─ script.module.codequick  (framework de l'extension)            │
│   └─ plugin.video.catchuptvandmore  ← exécutée sans modification    │
└─────────────────────────────────────────────────────────────────────┘
```

Kodi lance une extension en lui passant `sys.argv = [url, handle, query]` puis récupère ce qu'elle
envoie via `xbmcplugin.addDirectoryItem` (un dossier) ou `xbmcplugin.setResolvedUrl` (un flux).
Zapette fait exactement la même chose : `bridge.invoke(url)` exécute l'extension, enregistre ces
appels et renvoie un JSON que l'interface affiche. Les dialogues de l'extension (`Dialog().select`,
clavier, oui/non…) sont transmis à l'interface et la réponse est renvoyée à Python.

Les propriétés `inputstream.adaptive.*` d'un flux résolu (type de manifeste, en-têtes,
`license_key = url|en-têtes|R{SSM}|JBlicense`…) sont traduites pour ExoPlayer
(`player/KodiLicenseCallback.kt`).

Fichiers principaux :

- `app/src/main/python/` : la couche Kodi émulée et le pont (`zapette_kodi/bridge.py`)
- `app/src/main/assets/addons/` : Catch-up TV & More et codequick embarqués (zip)
- `app/src/main/java/fr/zapette/tv/engine/` : démarrage de Python, installation et mise à jour de l'extension
- `app/src/main/java/fr/zapette/tv/player/` : ExoPlayer + licences DRM façon Kodi
- `app/src/main/java/fr/zapette/tv/ui/` : l'interface (écrans, lecteur, composants)
- `tools/test_bridge.py` : test du moteur sur ordinateur, avec la vraie extension

## Obtenir l'APK

### Depuis les Releases GitHub (le plus simple)
Chaque push sur `main` compile l'application et publie une **Release** `v1.<n° de build>` avec les APK
directement téléchargeables (pas de zip) :
`zapette-1.N-arm64-v8a.apk` pour la plupart des box (Chromecast Google TV, Shield, Freebox Pop, Bouygues 4K…),
`zapette-1.N-universal.apk` en cas de doute.

Une fois Zapette installée, les versions suivantes s'installent depuis l'application :
**Réglages → Mettre à jour Zapette** (Android demande, la première fois, d'autoriser Zapette à installer des applications).
Toutes les versions sont signées avec la même clé (`app/zapette-release.jks`) pour que la mise à jour soit acceptée.
Pour utiliser votre propre clé, fournissez `ZAPETTE_KEYSTORE`, `ZAPETTE_KEYSTORE_PASSWORD`, `ZAPETTE_KEY_ALIAS`,
`ZAPETTE_KEY_PASSWORD` au build (désinstallation nécessaire une fois en changeant de clé).

Lancez le workflow à la main (*Actions → Build APK → Run workflow*) pour embarquer la toute dernière version de l'extension.

### Avec Android Studio
Ouvrez le dossier, installez **Python 3.11** sur votre machine (requis par Chaquopy pour préparer
les paquets Python), puis *Build → Build APK(s)*. En ligne de commande : `./gradlew :app:assembleRelease`.


### Installer sur la TV
Avec l'application *Downloader* (ou *Send Files to TV*), ou en ADB :
`adb connect <ip-de-la-tv> && adb install app-arm64-v8a-release.apk`

## Télécommande

| Touche | En direct | En replay |
|---|---|---|
| CH+ CH− | chaîne suivante / précédente | — |
| ▲ ▼ | affiche le bandeau (ne zappe pas) | afficher les commandes · ▼ audio & sous-titres |
| 0-9 | aller à la chaîne n° (aussi depuis les menus) · « 0 » = chaîne précédente | — |
| OK | bandeau → liste des chaînes | lecture / pause |
| ◀ | liste des chaînes | −10 s (maintenir : −30 s, −60 s) |
| ▶ | fiche du programme (OK : audio & sous-titres) | +10 s (maintenir : +30 s, +60 s) |
| Menu | audio & sous-titres | audio & sous-titres |
| Sous-titres | active / désactive les sous-titres | active / désactive les sous-titres |
| Retour | ferme le panneau ouvert, puis quitte le plein écran : la chaîne continue dans le mini-lecteur en haut à droite | quitte (la position est mémorisée) |
| Lecture (menus) | revient en plein écran sur la chaîne du mini-lecteur (aussi : bouton « ▶ chaîne » en haut, appui long pour le fermer) | — |
| OK long (menus) | — | menu contextuel de l'extension (favoris, masquer…) |

## Limites à connaître

- **Les contenus viennent des chaînes** : l'application ne fait qu'exécuter l'extension. Si une chaîne
  change son site, l'extension doit être mise à jour (Réglages → *Rechercher une mise à jour*).
- **Comptes** : TF1+ et M6+ (6play) demandent un compte gratuit, à saisir dans *Réglages → Comptes*.
- **Géo-restriction** : la plupart des flux ne sont accessibles que depuis la France.
- **DRM** : les flux Widevine fonctionnent sur les appareils Android TV certifiés ; certaines chaînes
  limitent la définition sur les appareils Widevine L3.
- **Chaînes britanniques Channel 4 / My5** : elles ont besoin de `pycryptodome` (bibliothèque native),
  non embarquée pour garder une compilation simple.
- **Non disponible** : téléchargements, OpenVPN, intégration IPTV Manager (spécifiques à Kodi).

## Licence et crédits

Zapette est distribué sous **GPL-2.0-or-later**, comme les composants qu'il embarque :
[Catch-up TV & More](https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore) (GPL-2.0)
et [codequick](https://github.com/willforde/script.module.codequick) (GPL-2.0).
Logos et images des chaînes : [resource.images.catchuptvandmore](https://github.com/Catch-up-TV-and-More/resource.images.catchuptvandmore),
guide TV : [Catch-up-TV-and-More/xmltv](https://github.com/Catch-up-TV-and-More/xmltv).
Zapette n'est affilié à aucune chaîne ni à l'équipe Kodi.
