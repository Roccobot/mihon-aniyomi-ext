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
  versione.
- ⚠️⚠️ **Il titolo si prende dal TESTO della scheda, e gli attributi solo come ripiego**
  (misurato sul dispositivo, 2026-08-19): `title` e `alt` portano il testo SEO del sito
  ('Watch Momone 1 hentai online...'), e leggendo quelli per primi le voci si chiamavano
  davvero `Watch ...`. Peggio: il numero in quella stringa sta **in mezzo**, quindi la
  regola di raggruppamento non trovava niente da togliere e ogni episodio restava una voce a
  se. Un `stripSeo` disfa comunque l'involucro quando l'etichetta arriva da un attributo.
- ⚠️⚠️ **Gli episodi fratelli si riconoscono dallo SLUG, non dal titolo**: `momone-1` e
  `momone-2` condividono la base per costruzione, mentre i titoli possono arrivare avvolti
  nel testo SEO. E' la stessa regola che hai chiesto (parte pre-numero identica), applicata
  al dato pulito.
- ⚠️ **Quando non trova nessun flusso, l'errore dice che cosa il player ha chiesto** (le
  ultime richieste non statiche): senza quell'elenco 'nessun flusso trovato' non e
  correggibile, perche da qui il sito non e osservabile.
- ⚠️ **Il DOM si aspetta con un polling, non con `onPageFinished`**: il sito rende le liste
  **dopo** che la pagina risulta 'finita', quindi quel callback scatta troppo presto.
- **La risoluzione non si chiede**: si prende la piu alta disponibile, con la preferenza del
  pannello in cima a parita di disponibilita.
- ⚠️ **Tutto passa dal thread principale e blocca quello chiamante**: una WebView non si
  tocca da un thread di lavoro, mentre le sorgenti sono chiamate da thread IO. Da qui
  l'`Handler` piu il latch, e il timeout su ogni ingresso.
- **I tre indirizzi delle pagine sono CONFERMATI dall'utente sul sito** (2026-08-19), non
  dedotti: la **home** per gli ultimi arrivi e
  `hanime.tv/search?q=<parola>&order=created_at_desc` per la ricerca. ⚠️ Il parametro e `q`
  e **non** `query`, che e l'ipotesi con cui era nata la prima versione.
- ⚠️ **`hanime.tv/browse` NON va bene per i popolari**, misurato sul dispositivo: e un indice
  di **categorie** e non contiene link a video, quindi l'elenco tornava vuoto. Al suo posto
  la pagina di ricerca ordinata per visualizzazioni, e `views_desc` e il solo valore ancora
  da confermare.
- **Niente paginazione, per ora**: il sito pagina scorrendo, e un numero di pagina che
  questa sorgente non puo verificare sarebbe una promessa non mantenuta.

## Tracciamento: come si vede cosa succede al clic su Play

⚠️ **Il difetto peggiore di questa sorgente e quello SILENZIOSO**: un player che resta fermo
senza errore non dice niente, e da fuori dal telefono il sito non e osservabile. Quindi ogni
passo lascia una traccia in `HanimeLog`, e si legge in tre modi, dal piu comodo al meno:

| come | cosa fare |
|---|---|
| **dalla sorgente** | cerca `debug` in questa sorgente: compare una voce 'Debug log', e la sua **descrizione** e la traccia intera. Nessun cavo, nessun file manager, nessun permesso |
| **da file** | accendi 'Write the debug log to a file' nelle impostazioni della sorgente: il percorso e scritto nel sommario, e la traccia sopravvive al riavvio dell'app |
| **da logcat** | tutte le righe escono anche col tag `HanimeRoccobot` |

- Per svuotarla si cerca `debug clear` nella sorgente. ⚠️ Sta **sullo stesso canale** e non
  nelle impostazioni perche lo stub `Preference` della libreria non ha un costruttore che una
  sorgente possa chiamare (`ListPreference` e `SwitchPreferenceCompat` si, `Preference` no).
- **Che cosa registra**: caricamento e resa di ogni pagina, **ogni richiesta non statica** che
  la pagina fa (con metodo e indirizzo), gli **errori JS del sito** (che spiegano la maggior
  parte dei fallimenti silenziosi: 'non autenticato', handshake rifiutato), l'esito di ogni
  tentativo di avvio del player (elemento trovato, `paused`, `readyState`, numero di iframe) e
  **l'indirizzo che viene passato al player** di Aniyomi.
- **Il clic su Play si ritenta cinque volte** a distanza di 2,5 secondi: il player viene
  montato dopo che la pagina si e assestata, quindi un tentativo solo arriva troppo presto.
- ⚠️⚠️ **`[class*=play]` NON si usa per trovare il pulsante Play**, e la traccia lo ha
  dimostrato: quel selettore combacia anche con **`playlist`**, di cui la pagina e piena,
  quindi i cinque tentativi cliccavano una voce della playlist **dichiarando successo**. Ora
  i candidati si filtrano su una parola intera (`play`, `watch`, `guarda`, `riproduci`) con
  `playlist` escluso, e la traccia riporta **su cosa** ha cliccato, non solo che ha cliccato.
- ⚠️ **Prima di cliccare si registra un INVENTARIO della pagina**: quanti `video`, `iframe`
  e `canvas`, gli elementi con classe o id da player, le etichette dei primi pulsanti e
  l'inizio del testo visibile. E' la misura che dice se un player esiste, invece di
  dedurlo.
- ⚠️ **La traccia e un anello di 400 righe** e non una lista che cresce: questo oggetto vive
  quanto l'app, e un log illimitato sarebbe una perdita di memoria. Scrivere su file non puo
  far cadere la riproduzione: se la scrittura fallisce, si prosegue.

## Che cosa si sa del player, misurato (2026-08-19)

Prima traccia raccolta sul dispositivo, e nega l'ipotesi da cui era partita la via WebView:

- **nella pagina di un episodio non compare NESSUN elemento `video` e nessun `iframe`** (in
  cinque tentativi a 2,5 secondi di distanza), quindi il player non si monta affatto e non
  c'e nulla da avviare;
- il sito e ora un'app **Astro** (`/_astro/*.js`), e fra i suoi componenti carica un
  `HTVPlayerPromotePremiumModal`: puo essere quel modale a prendere il posto del player per
  chi non e autenticato o non e abbonato;
- ⚠️ **il catalogo passa da un host nuovo**, `guest.freeanimehentai.net/api/v11/search_hvs`,
  con un **GET**: il `guest.` nel nome dice che esiste una via da ospite, e questo era
  sconosciuto quando la sorgente e stata scritta.

## Nomi degli episodi: solo il numero

**Gli episodi si chiamano `01`, `02`, `03`...** (scelta dell'utente, 2026-08-19: *si puo anche
semplificare rinominandoli semplicemente come 01, 02, 03; l'importante e che siano in fila*).

- ⚠️ **Non e pigrizia, e la via che smette di rincorrere il sito**: il testo delle schede
  episodio e un impasto di durata, studio e badge di stato (`Now Playing30:27 Master Piece
  1Pink P...`), e ogni ripulitura mirata a un caso ne lascia scoperto un altro. Il numero
  invece si ricava dallo slug, che e pulito per costruzione.
- **La descrizione della serie viene SCARTATA quando e testo SEO** (`Watch X 1 latest hentai
  online free download HD...`): non e una sinossi, e una versione ripulita a meta sembrerebbe
  una descrizione vera senza dire niente.

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
  lista episodi la filtra sulla **base dello slug** del gruppo. Prendendola intera,
  `Titolo IV` finirebbe dentro `Titolo III`.
- **Limite noto, dichiarato**: la deduplica vede **una schermata** per volta, cioe i link
  presenti nel DOM di quella pagina. Se due episodi della stessa serie non ci stanno insieme,
  in esplorazione la voce puo comparire due volte; in **libreria no**, perche le due voci
  hanno lo stesso indirizzo calcolato.

## Struttura e crediti

Il telaio di build (`buildSrc`, `core`, `common.gradle`, wrapper Gradle) e ripreso da
[aniyomiorg/aniyomi-extensions](https://github.com/aniyomiorg/aniyomi-extensions), sotto
licenza Apache 2.0: vedi il file [LICENSE](LICENSE). Il codice della sorgente in
`aniyomi/src/en/hanime/` e scritto per questo repository.

La compilazione gira su GitHub Actions (`.github/workflows/aniyomi-hanime.yml` nella radice del repository), perche non serve
un SDK Android in locale.
