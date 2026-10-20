# Safeword

Un mot suffit. Tes proches sont prévenus.

Safeword écoute en arrière-plan, même écran éteint ou pendant un jeu. Quand tu dis ton mot-clé, il envoie un SMS avec ta position à tes proches, directement depuis ta carte SIM.

## Obtenir l'app Android

1. Crée un dépôt GitHub et envoie-y tout ce dossier.
2. Onglet **Actions** : « Construire l'APK » se lance tout seul (5 à 10 minutes).
3. Onglet **Releases** > « Dernière version » > télécharge `Safeword.apk` depuis ton téléphone.
4. Installe-le (Android demande d'autoriser les sources inconnues pour ton navigateur).

## Premier lancement

1. Touche **Tout autoriser** (micro, SMS, position, notifications), puis **Autoriser en arrière-plan**.
2. **SMS bloqué ?** Android 13+ bloque les SMS pour les apps installées hors Play Store.
   Réglages de l'app > ⋮ (en haut à droite) > **Autoriser les paramètres restreints**, puis reviens.
3. Onglet **Proches** : ajoute un proche. Onglet **Réglages** : choisis ton mot.
4. **Réglages > Envoyer un SMS test** : vérifie que le SMS part vraiment.
5. Touche le micro. Ferme l'app, lance un jeu, dis ton mot.

## Version web (Netlify)

Le dossier `web/` est un site complet (`netlify.toml` est déjà prêt). La version web ne peut pas écouter en arrière-plan : c'est une limite de tous les navigateurs. Son envoi automatique passe par `netlify/functions/send-sms.js` et Twilio (variables `SAFEWORD_CODE`, `TWILIO_SID`, `TWILIO_TOKEN`, `TWILIO_FROM`).

## Limites à connaître

- Si une autre app garde le micro (appel vocal en jeu), Android peut couper l'écoute de Safeword.
- Samsung, Xiaomi, Huawei : verrouille Safeword dans la liste des apps récentes pour éviter qu'il soit fermé.
- Les SMS partent avec ton forfait. Vérifie que tu as du crédit.
- Safeword est un complément, il ne remplace pas le 112.
