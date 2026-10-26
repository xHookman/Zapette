# Zapette

Une application Android TV qui fait tourner l'extension Kodi **Catch-up TV & More**, sans Kodi.

## Pourquoi

J'utilisais Catch-up TV & More dans Kodi pour regarder la TV. L'extension marche bien, mais Kodi est lourd sur les box pas très puissantes, et pas pratique quand on veut juste regarder la télé.

J'ai donc voulu faire une application qui ne fait que ça : regarder les chaînes en direct et en replay, plus fluide, avec une interface pensée pour la télécommande. C'est la même extension derrière, donc les mêmes chaînes. Il n'y a que l'interface qui change.

Pour être honnête : cette application est vibecodée. Je l'ai faite avec une IA, faute de temps et de connaissances en développement Android. Je ne prétends pas que le code soit parfait. Elle m'est très utile au quotidien, alors je la partage telle quelle, au cas où elle serve à d'autres.

## Ce que ça fait

- Le direct, avec les chaînes numérotées comme sur la TNT. CH+ / CH− pour zapper, on tape le numéro pour aller sur une chaîne.
- Au lancement, l'application reprend la dernière chaîne regardée.
- Le guide TV d'aujourd'hui et de demain, qui sert aussi de liste des chaînes. Sur les chaînes qui le permettent, choisir une émission déjà passée la reprend à son début.
- Le replay, avec toute l'arborescence de l'extension.
- Un mini-lecteur : en faisant Retour, la chaîne continue dans un coin pendant qu'on navigue.
- Les mises à jour de l'application et de l'extension se font depuis les réglages.

## Les comptes, c'est obligatoire pour certaines chaînes

C'est la principale contrainte, et elle vient des chaînes, pas de l'application. TF1+, M6+ et quelques autres ne donnent pas leurs vidéos sans compte. Il faut donc :

1. créer un compte (gratuit) sur leur site ou leur application ;
2. le renseigner dans **Réglages → Comptes** dans Zapette.

Sans ça, ces chaînes ne se lancent pas. Si le même identifiant sert pour plusieurs services, Zapette permet de le copier de l'un à l'autre.

## Installer

Téléchargez et envoyez le fichier `.apk` de la [dernière version](https://github.com/xHookman/Zapette/releases/latest) sur la TV (via LocalSend par exemple)

## Touches

| Touche | Effet |
|---|---|
| CH+ / CH− | Chaîne suivante / précédente |
| 1 … 9 | Aller à une chaîne |
| 0 | Revenir à la chaîne précédente |
| ▲ / ▼ | Infos sur le programme en cours |
| OK | Infos sur le programme, puis liste des chaînes |
| OK maintenu | En direct : fiche du programme · dans les menus : options d'une vidéo (favoris…) |
| ◀ / ▶ | Reculer / avancer dans le direct (maintenir pour aller plus vite) |
| Lecture/Pause | Mettre le direct en pause |
| ▼ deux fois | Revenir au direct |
| Retour | Quitter le plein écran (la chaîne continue dans un coin) |
| OK deux fois | Revenir en plein écran sur cette chaîne |

## Si une chaîne ne marche plus

En général, c'est que la chaîne a changé quelque chose sur son site et que l'extension doit être mise à jour : **Réglages → Rechercher une mise à jour**. Les correctifs viennent des développeurs de [Catch-up TV & More](https://github.com/Catch-up-TV-and-More/plugin.video.catchuptvandmore). Zapette les récupère sans avoir besoin d'une nouvelle version de l'application.

## Licence

GPL-2.0, comme Catch-up TV & More. Le code est libre : on peut l'utiliser, le modifier et le partager. Si on distribue une version modifiée, son code doit rester ouvert sous la même licence.

Zapette n'est lié à aucune chaîne ni à l'équipe de Kodi. Les vidéos viennent directement des sites des chaînes.
