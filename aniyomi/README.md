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

## Com'e fatta: la pagina del sito nella WebView dell'app

⚠️⚠️ **L'API del sito NON e utilizzabile da questa estensione, e non e una scelta di
comodo.** Storia breve, perche e la cosa che serve sapere prima di rimetterci mano:

- la prima versione chiamava l'API **v8** (`search.htv-services.com` per il catalogo,
  `hanime.tv/api/v8/video` per il resto), presa da un client di terze parti. Non funziona:
  quei **due host non esistono piu**, e il telefono lo dice con un `NXDOMAIN`;
- l'API attuale e la **v11**, e ha due strati di protezione: le richieste di catalogo
  portano una **firma prodotta da un modulo WASM** che il sito distribuisce dentro il
  bundle del player, e l'handshake dei flussi manda un **token sigillato** con una chiave
  ricavata da quello stesso bundle, rispondendo con un'intestazione cifrata;
- ⚠️ **rifarli e fuori discussione**: vorrebbe dire prendere il modulo compilato del sito e
  le sue chiavi, cioe ricostruire la protezione. Non si fa, con o senza un account.

Quello che si fa invece: **il client del sito gira come previsto**, nella WebView dell'app e
con la sessione dell'utente, e l'estensione legge il risultato (vedi `HanimeWebView.kt`).

| a che serve | come |
|---|---|
| elenco, ultimi arrivi, ricerca, episodi | il **DOM reso** della pagina, letto dalla WebView |
| flusso video | la **richiesta media** che il player della pagina finisce per fare |

- ⚠️⚠️ **Gli elenchi si leggono per INDIRIZZO, mai per classe CSS**: ogni scheda linka a
  `/videos/hentai/<slug>`, che e l'indirizzo pubblico delle pagine e sopravvive a un
  restyling; i nomi di classe no, e indovinarli e l'errore che ha fatto naufragare la prima
  versione. Titolo e copertina si prendono da `title`, `alt` e `img` dentro il link, con
  ripiego sullo slug.
- ⚠️ **Il DOM si aspetta con un polling, non con `onPageFinished`**: il sito rende le liste
  **dopo** che la pagina risulta 'finita', quindi quel callback scatta troppo presto.
- **La risoluzione non si chiede**: si prende la piu alta disponibile, con la preferenza del
  pannello in cima a parita di disponibilita.
- ⚠️ **Tutto passa dal thread principale e blocca quello chiamante**: una WebView non si
  tocca da un thread di lavoro, mentre le sorgenti sono chiamate da thread IO. Da qui
  l'`Handler` piu il latch, e il timeout su ogni ingresso.
- **I tre indirizzi delle pagine sono CONFERMATI dall'utente sul sito** (2026-08-19), non
  dedotti: `hanime.tv/browse` per l'esplorazione, la **home** per gli ultimi arrivi, e
  `hanime.tv/search?q=<parola>&order=created_at_desc` per la ricerca. ⚠️ Il parametro e `q`
  e **non** `query`, che e l'ipotesi con cui era nata la prima versione.
- **Niente paginazione, per ora**: il sito pagina scorrendo, e un numero di pagina che
  questa sorgente non puo verificare sarebbe una promessa non mantenuta.

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
