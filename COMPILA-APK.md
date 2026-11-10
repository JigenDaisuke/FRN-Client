# Come ottenere l'APK

Io non posso compilarlo nel mio ambiente (manca l'SDK Android e i server di
build sono bloccati). Ecco tre strade; la 1 non richiede di installare nulla.

## 1) Compilazione automatica su GitHub (consigliata, nessun PC da attrezzare)

Il progetto include già il workflow `.github/workflows/android.yml`.

1. Crea un repository su GitHub (anche privato).
2. Carica dentro **tutto il contenuto di questa cartella** `frn-android`
   (via web: "Add file" -> "Upload files"; oppure `git push`).
3. GitHub avvia da solo la compilazione. Vai nella scheda **Actions**,
   apri l'esecuzione "Build APK", e quando è verde scarica in fondo
   l'artefatto **FRN-Lite-APK**: è uno zip con due APK:
   - `app-io-debug.apk`    -> la tua versione (server libero)
   - `app-amici-debug.apk` -> versione amici (server Voip-Italia bloccato)
4. Copia l'APK sul telefono e installalo (abilita "origini sconosciute").

Sono APK **debug**: si installano senza firma, perfetti per le prove.

> Se la prima compilazione segnala errori (è un progetto nuovo mai passato
> dal compilatore Android), mandami il log della scheda Actions e li sistemo.

## 2) Android Studio (se hai un PC)

File -> Open su questa cartella; scegli la variante `ioDebug` o `amiciDebug`;
Build -> Build APK(s). Dettagli in `LEGGIMI.md`.

## 3) Fammi compilare direttamente io

Se colleghi il tuo account GitHub a Claude, in una prossima richiesta posso
creare il repository, avviare la compilazione, leggere eventuali errori e
correggerli finché l'APK non è pronto, poi darti il link per scaricarlo.
