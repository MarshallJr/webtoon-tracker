# Webtoon Tracker

Appli Android (Java) qui note automatiquement les chapitres de webtoons que tu lis,
avec la date, dans une base locale + un fichier note Markdown, et affiche un récap.

## Compiler l'APK
1. Android Studio → File → Open → ce dossier `WebtoonTracker`.
2. Laisse le Gradle Sync se terminer (accepte les mises à jour d'AGP proposées si besoin).
3. Build → Build App Bundle(s) / APK(s) → Build APK(s).
4. L'APK est dans `app/build/outputs/apk/debug/app-debug.apk` → installe-le sur ton téléphone.

## Première utilisation
1. Ouvre l'appli → « Activer l'analyse en fond » → Accessibilité → Webtoon Tracker → ON.
   (Android 13+ : si l'option est grisée, va dans Paramètres → Applis → Webtoon Tracker →
   ⋮ → « Autoriser les paramètres restreints », puis réessaie.)
2. Optionnel : « Fichier note » → choisis où créer `webtoons.md` (Documents, Drive, Obsidian…).
3. Lis un chapitre : s'il reste affiché ~6 s, il est noté (petit toast « 📖 … noté »).

## Sources détectées
- App WEBTOON, Mihon/Tachiyomi, Lezhin, Tapas, Piccoma : titre + « Ep./Épisode/Chapitre N » en haut de l'écran.
- Navigateurs (Chrome, Firefox, Brave, Samsung, Edge…) : URL webtoons.com ou URL du type `/serie/chapter-12`.
  Liste modifiable dans `Detector.java` (BROWSERS / READER_APPS).

## Réglages utiles (dans le code)
- `ReadingTrackerService.DWELL_MS` : temps d'affichage avant de compter un chapitre (6 s).
- Si une appli n'est pas bien reconnue : touche « Dernier écran analysé » en bas de l'appli
  pour voir les textes lus à l'écran, et ajuste `Detector.fromScreen`.

Tout reste sur le téléphone : rien n'est envoyé sur Internet (l'appli n'a même pas la permission INTERNET).
