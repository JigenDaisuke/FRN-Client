
# Come compilare APK ONLINE senza Android Studio

## Metodo 1: GitHub Actions (CONSIGLIATO - 2 minuti)

1. Vai su https://github.com e crea un account gratis
2. Crea un nuovo repository (es. FRN-Client)
3. Carica TUTTI i file di questa cartella (drag & drop)
4. Vai su tab "Actions" -> vedrai "Build APK - FRN Nazionale" in esecuzione
5. Aspetta 2-3 minuti che finisce
6. Clicca sul build -> in basso trovi "Artifacts" -> scarica "FRN-Nazionale-APK"
7. Dentro c'è app-debug.apk pronta da installare sul telefono!

## Metodo 2: Gitpod.io (IDE online)

1. Vai su https://gitpod.io
2. Loggati con GitHub
3. Incolla l'URL del tuo repo GitHub
4. Si apre Android Studio nel browser
5. Terminale: ./gradlew assembleDebug

## Metodo 3: Replit

1. https://replit.com -> New Repl -> Import from GitHub
2. Aggiungi Android SDK: nel shell `sdkmanager --install`
3. `gradle assembleDebug`

## Metodo 4: Appetize / CodeMagic / AppCircle

Siti che compilano APK se gli dai il repo GitHub:
- https://codemagic.io (gratis 500 min/mese)
- https://appcircle.io
