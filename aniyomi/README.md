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
  WebView dell'app: nessun `client` sovrascritto, perche quello predefinito lo fa gia
  (`network.cloudflareClient` compila ma e deprecato proprio per questo).

## Un'opera e una SERIE, non un video

hanime.tv da a ogni episodio una pagina e uno slug propri, quindi senza raggruppamento una
serie da otto episodi comparirebbe come otto voci. La regola e quella della vecchia
estensione: **stesso titolo una volta tolto il numero finale, stessa serie**. Cosi
`Anime Titolo III 01/02/03` diventa la voce unica `Anime Titolo III` coi suoi tre episodi.

- ⚠️ **Si toglie solo il numero ARABO in coda**, e la ragione e sostanziale: i titoli usano
  i **numerali romani** come parte del nome, quindi un'espressione piu avida fonderebbe
  `Titolo III` e `Titolo IV` in un'unica opera. Verificato sui casi limite: `Titolo IV 01`
  resta una serie a se, `Titolo 2022 12` si raggruppa come `Titolo 2022`, e un one-shot
  senza numero passa intatto.
- ⚠️⚠️ **L'indirizzo dell'opera si CALCOLA, non si prende dal primo risultato utile**: e
  l'indirizzo la chiave con cui Aniyomi tiene la voce in libreria, quindi usare lo slug
  dell'episodio capitato per primo in una pagina di risultati archivierebbe la stessa serie
  sotto due voci diverse a seconda della ricerca che l'ha trovata. Si usa **l'episodio 1**
  come rappresentante fisso del gruppo, perche su questo sito la numerazione parte da 1.
- ⚠️ **La franchise del sito NON e la serie**: contiene anche sequel e spin-off, quindi la
  lista episodi la filtra sul titolo del gruppo. Prendendola intera, `Titolo IV` finirebbe
  dentro `Titolo III`.
- **Limite noto, dichiarato**: la deduplica agisce su **una pagina** di risultati alla
  volta, perche e tutto quello che l'API restituisce per volta. Se due episodi della stessa
  serie cadono a cavallo di due pagine, in esplorazione la voce puo comparire due volte; in
  **libreria no**, perche le due voci hanno lo stesso indirizzo calcolato.

## Struttura e crediti

Il telaio di build (`buildSrc`, `core`, `common.gradle`, wrapper Gradle) e ripreso da
[aniyomiorg/aniyomi-extensions](https://github.com/aniyomiorg/aniyomi-extensions), sotto
licenza Apache 2.0: vedi il file [LICENSE](LICENSE). Il codice della sorgente in
`aniyomi/src/en/hanime/` e scritto per questo repository.

La compilazione gira su GitHub Actions (`.github/workflows/aniyomi-hanime.yml` nella radice del repository), perche non serve
un SDK Android in locale.
