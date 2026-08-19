# Aniyomi: sorgente hanime.tv (`Hanime Roccobot` in app)

Estensione **hanime.tv** per [Aniyomi](https://github.com/aniyomiorg/aniyomi), a **uso personale**:
il repository e privato e l'APK non e distribuito, si installa a mano sul telefono
(procedura nel [README di radice](../README.md)).

## Come si installa

1. Nella scheda **Actions** di questo repository, apri l'ultima build riuscita e scarica
   l'artefatto `aniyomi-hanime-apk` (oppure, se la versione ha un tag, prendi l'APK dalla Release).
2. Apri l'APK sul telefono e installalo come una normale app.
3. Aniyomi chiede di **fidarsi di una firma sconosciuta**: e la richiesta attesa per
   un'estensione che non viene dal repository ufficiale, e si conferma.

## Com'e fatta

Nessuno scraping di HTML: la sorgente legge la stessa API JSON che usa il sito.

| a che serve | endpoint |
|---|---|
| elenco, ultimi arrivi, ricerca | `POST search.htv-services.com/`, tre ordinamenti dello stesso endpoint |
| dettagli, episodi, flussi video | `GET hanime.tv/api/v8/video?id=<slug>` |

- **La risoluzione non si chiede**: si prende la piu alta disponibile, con la preferenza
  del pannello in cima a parita di disponibilita.
- **I flussi riservati agli abbonati restano fuori**: arrivano con l'indirizzo vuoto e
  vengono scartati, non aggirati.
- Il sito sta dietro una sfida anti-bot di Cloudflare, che l'estensione risolve nella
  WebView dell'app (`network.cloudflareClient`), cioe comportandosi come il browser.

## Struttura e crediti

Il telaio di build (`buildSrc`, `core`, `common.gradle`, wrapper Gradle) e ripreso da
[aniyomiorg/aniyomi-extensions](https://github.com/aniyomiorg/aniyomi-extensions), sotto
licenza Apache 2.0: vedi il file [LICENSE](LICENSE). Il codice della sorgente in
`aniyomi/src/en/hanime/` e scritto per questo repository.

La compilazione gira su GitHub Actions (`.github/workflows/aniyomi-hanime.yml` nella radice del repository), perche non serve
un SDK Android in locale.
