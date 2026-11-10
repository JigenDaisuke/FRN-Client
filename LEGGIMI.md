# FRN Client Lite — Android (nativo Kotlin)

App Android nativa per la **Free Radio Network**, compatibile con i server
AlterFRN. Riusa la stessa logica di protocollo e lo stesso codec audio della
versione desktop, riscritti in Kotlin/Java nativi.

## Stato del progetto

- ✅ **Codec GSM 06.10 (WAV49)** — portato in Java e **verificato bit-per-bit**
  contro il codec Python/libgsm già validato (vedi `tools/`). L'audio su
  Android è identico a quello degli altri client FRN.
- ✅ **Protocollo FRN** — login, stanze, utenti, chat, messaggi privati,
  audio RX/TX, comandi di amministrazione (mute/block/admin, restrizioni,
  lista d'accesso, messaggio di benvenuto), riconnessione. Porting fedele
  del motore Python verificato sul server AlterFRN r7348.
- ✅ **Audio nativo** — `AudioRecord`/`AudioTrack` a 8 kHz, pacchetti da 200 ms.
- ✅ **Interfaccia** Jetpack Compose: stato, stanze, utenti, chat, PTT a
  schermo, menù di amministrazione, impostazioni.
- ✅ **Servizio in primo piano** per restare connessi a schermo spento.
- ✅ **Due varianti** (server bloccato / libero) come la versione desktop.

> ⚠️ **Da compilare in Android Studio.** Questo progetto è stato scritto e
> verificato per la parte audio (codec) su una JDK, ma **non** è stato
> compilato contro l'SDK Android (non disponibile nell'ambiente di sviluppo).
> Alla prima apertura in Android Studio potrebbe servire qualche piccolo
> allineamento di versione (plugin Gradle/SDK). È normale per un progetto
> nativo nuovo: la logica e il codec sono quelli giusti.

## Come compilare l'APK

1. Installa **Android Studio** (Giraffe o successivo).
2. **File → Open** e scegli questa cartella (`frn-android`). Android Studio
   scarica da solo Gradle, l'SDK e le dipendenze, e crea il Gradle wrapper.
3. In alto, scegli la **variante di build** (Build → Select Build Variant):
   - `ioDebug` / `ioRelease` → **la tua** (server liberamente modificabile).
   - `amiciDebug` / `amiciRelease` → **per gli amici** (server Voip-Italia
     fisso, modificabile solo con la password `FRISIETEDELLESOLE`).
4. **Build → Build APK(s)**. L'APK esce in
   `app/build/outputs/apk/<variante>/`.
5. Copia l'APK sul telefono e installalo abilitando «origini sconosciute»
   (è un'app fuori dal Play Store).

Da riga di comando (con l'SDK Android configurato):

```sh
./gradlew assembleIoRelease      # versione tua
./gradlew assembleAmiciRelease   # versione amici
```

Per un APK release installabile va **firmato** (Build → Generate Signed
Bundle/APK, oppure una `signingConfig` nel `build.gradle`).

## Verifica del codec (già fatta)

Nella cartella `tools/` c'è il collaudo del codec: `GsmTest.java` confronta
l'uscita del codec Java con i vettori prodotti dal codec Python
(`vectors.txt`). Risultato: **tutti i pacchetti bit-per-bit identici**, sia in
codifica che in decodifica. Per rieseguirlo serve solo una JDK:

```sh
javac -d out app/src/main/java/net/voipitalia/frnlite/gsm/Gsm.java tools/GsmTest.java
java -cp out GsmTest tools/vectors.txt      # -> ALL BIT-EXACT
```

## Cosa cambia rispetto al desktop

- Niente gateway radio su seriale (un telefono non ha porte COM): restano
  ricezione/trasmissione con PTT a schermo, stanze, utenti, chat e admin.
- L'audio usa il microfono/altoparlante del telefono (sorgente
  `VOICE_COMMUNICATION`, con cancellazione d'eco quando il telefono la offre).

## Struttura

```
app/src/main/java/net/voipitalia/frnlite/
  gsm/Gsm.java        codec GSM 06.10 WAV49 (Java, verificato bit-exact)
  net/Protocol.kt     client FRN (login, liste, chat, audio, admin)
  audio/AudioEngine.kt  AudioRecord/AudioTrack a 8 kHz
  Controller.kt       logica: PTT, codifica, stato per la UI
  Settings.kt         impostazioni (SharedPreferences) + blocco server
  Brand.kt            variante build (LOCKED) e server fisso Voip-Italia
  FrnService.kt       servizio in primo piano
  MainActivity.kt     avvio + permessi
  ui/FrnApp.kt        interfaccia Compose
  ui/Dialogs.kt       impostazioni e amministrazione
tools/                collaudo del codec (JDK)
```
