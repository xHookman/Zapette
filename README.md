# Zapette

**La télé française sur Android TV, propulsée par Catch-up TV & More — sans Kodi.**

Zapette exécute l'extension Kodi [Catch-up TV & More](https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore)
*telle quelle* dans une application Android TV native, avec une interface pensée pour la télécommande :
on allume, on tombe sur sa dernière chaîne, on zappe avec CH+ CH−, on tape « 2 » pour France 2.

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

### Organisation du code

Chaque fichier a un rôle et un seul ; les écrans ne font qu'afficher l'état et appeler des actions.

```
app/src/main/java/fr/zapette/tv/
├─ engine/        AddonEngine   – appels à l'extension (un seul thread, comme Kodi) + cache disque du guide
│                 AddonInstaller– installation / mise à jour de l'extension (zip vérifié, taille bornée)
│                 AppUpdater    – mise à jour de Zapette depuis les Releases (HTTPS, paquet, version, signature)
│                 PythonHost    – dialogues de l'extension (liste, clavier, oui/non) vers l'interface
├─ live/          LiveTv        – chaînes (numérotation TNT), guide TV, horloge
│                 LivePlayback  – le « tuner » : un lecteur unique, zapping, neige, mini-lecteur, reprise après veille
├─ player/        StreamPlayer  – ExoPlayer réglé pour les TV (HLS, DASH, DRM façon inputstream.adaptive)
│                 PlaybackErrors– messages d'erreur en français, correction du format annoncé
│                 ZapHiss       – le souffle pendant la neige (généré en direct)
├─ ui/            AppViewModel  – navigation et actions communes ; délègue à :
│                 Messages      – toasts, choix, saisie, « patientez »
│                 Updates       – mises à jour, cache, vérification des comptes
│                 Navigation    – onglets, pile d'écrans · RemoteKeys – touches de la télécommande
│  ├─ screens/    Accueil, Chaînes, Guide, Replay/Explorer, Réglages, démarrage
│  ├─ player/     direct (LivePlayerScreen + LiveOverlays + LiveVideoLayer), replay (VodPlayerScreen)
│  └─ components/ briques visuelles (cartes, barre d'onglets, dialogues, notifications)
└─ Images.kt      requêtes d'images : logos décodés une seule fois à taille fixe

app/src/main/python/zapette_kodi/
├─ bridge.py      l'API appelée par Kotlin (fine couche sur les modules ci-dessous)
├─ plugin.py      exécution d'une url plugin:// et capture du résultat
├─ convert.py     conversion des objets Kodi en JSON
├─ live.py · settings.py · accounts.py   chaînes/guide, réglages, test des comptes
├─ runtime.py     état d'une exécution, masquage des mots de passe dans les journaux
└─ patches.py     corrections ponctuelles de l'extension sans la modifier
app/src/main/python/xbmc*.py, kodi_six, inputstreamhelper, YDStreamExtractor   le Kodi émulé
```

`tools/test_bridge.py` teste le moteur sur ordinateur, avec la vraie extension (lancé à chaque build).

## Obtenir l'APK

### Depuis les Releases GitHub (le plus simple)
Chaque push sur `main` compile l'application et publie une **Release** `v1.<n° de build>` avec les APK
directement téléchargeables (pas de zip) :
`zapette-1.N-arm64-v8a.apk` pour la plupart des box (Chromecast Google TV, Shield, Freebox Pop, Bouygues 4K…),
`zapette-1.N-universal.apk` en cas de doute.

Une fois Zapette installée, les versions suivantes s'installent depuis l'application :
**Réglages → Mettre à jour Zapette** (Android demande, la première fois, d'autoriser Zapette à installer des applications).
### Signature
Les versions publiées sont signées avec une **clé privée** qui n'est pas dans le dépôt : la CI la lit
dans quatre secrets GitHub (*Settings → Secrets and variables → Actions*) :
`ZAPETTE_KEYSTORE_B64` (le keystore en base64), `ZAPETTE_KEYSTORE_PASSWORD`, `ZAPETTE_KEY_ALIAS`, `ZAPETTE_KEY_PASSWORD`.
Sans ces secrets, le build vérifie le code mais **ne publie rien**. Un build local est signé avec la clé de debug.

Créer une clé : `keytool -genkeypair -keystore zapette.jks -storetype PKCS12 -alias zapette -keyalg RSA -keysize 4096 -validity 36500`
puis `base64 -w0 zapette.jks` pour la valeur de `ZAPETTE_KEYSTORE_B64`. Changer de clé impose de désinstaller Zapette une fois.

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
| Retour | ferme le panneau ouvert, puis quitte le plein écran : la chaîne continue dans le mini-lecteur, dans un coin | quitte (la position est mémorisée) |
| OK deux fois rapidement (menus) | revient en plein écran sur la chaîne du mini-lecteur (la touche Lecture aussi) | — |
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
