# Aniyomi: sorgente hanime.tv (`Hanime Roccobot` in app)

Estensione **hanime.tv** per [Aniyomi](https://github.com/aniyomiorg/aniyomi), a **uso personale**:
il repository è privato e l'APK non è distribuito, si installa a mano sul telefono
(procedura nel [README di radice](../README.md)).

✅ **Stato: la catena funziona dal vivo, provata dall'utente sul telefono** (2026-08-19,
`extVersionCode` 21). Il video parte, e questo chiude la sola cosa che restava da accertare: fino
a quel momento ogni pezzo era misurato ma nessuno aveva mai visto l'insieme arrivare al player.
Le note qui sotto smettono quindi di essere ipotesi di lavoro e diventano la descrizione di come
la sorgente funziona.

## Come si installa

1. Vai nella scheda **Releases** del repository e apri **`latest`**: è aggiornata a ogni build e
   porta sempre lo stesso allegato, `aniyomi-hanime.apk`, scaricabile con un tocco. ⚠️ Non ci sono più artefatti di
   Actions: `actions/upload-artifact` gira ancora su Node 20 anche alla v5 ed era la sola
   origine dell'avviso di deprecazione, e un artefatto chiede l'accesso a GitHub, arriva in uno
   zip e scade dopo 90 giorni.
2. Apri l'APK sul telefono e installalo come una normale app.
3. Aniyomi chiede di **fidarsi di una firma sconosciuta**: è la richiesta attesa per
   un'estensione che non viene dal repository ufficiale, e si conferma.

## Com'è fatta: la pagina del sito nella WebView dell'app

⚠️⚠️ **L'API del sito NON è utilizzabile da questa estensione, e non è una scelta di
comodo.** Storia breve, perché è la cosa che serve sapere prima di rimetterci mano:

- la prima versione chiamava l'API **v8** (`search.htv-services.com` per il catalogo,
  `hanime.tv/api/v8/video` per il resto), presa da un client di terze parti. Non funziona:
  quei **due host non esistono più**, e il telefono lo dice con un `NXDOMAIN`;
- l'API attuale è la **v11**, e ha due strati di protezione: le richieste di catalogo
  portano una **firma prodotta da un modulo WASM** che il sito distribuisce dentro il
  bundle del player, e l'handshake dei flussi manda un **token sigillato** con una chiave
  ricavata da quello stesso bundle, rispondendo con un'intestazione cifrata;
- ⚠️ **rifarli è fuori discussione**: vorrebbe dire prendere il modulo compilato del sito e
  le sue chiavi, cioè ricostruire la protezione. Non si fa, con o senza un account.

Quello che si fa invece: **il client del sito gira come previsto**, nella WebView dell'app e
con la sessione dell'utente, e l'estensione legge il risultato (vedi `HanimeWebView.kt`).

| a che serve | come |
|---|---|
| elenco, ultimi arrivi, ricerca, episodi | il **DOM reso** della pagina, letto dalla WebView |
| flusso video | la **richiesta media** che il player della pagina finisce per fare |

- ⚠️⚠️ **Gli elenchi si leggono per INDIRIZZO, mai per classe CSS**: ogni scheda linka a
  `/videos/hentai/<slug>`, che è l'indirizzo pubblico delle pagine e sopravvive a un
  restyling; i nomi di classe no, e indovinarli è l'errore che ha fatto naufragare la prima
  versione.
- ⚠️⚠️ **Il titolo si prende dal TESTO della scheda, e gli attributi solo come ripiego**
  (misurato sul dispositivo, 2026-08-19): `title` e `alt` portano il testo SEO del sito
  ('Watch Momone 1 hentai online...'), e leggendo quelli per primi le voci si chiamavano
  davvero `Watch ...`. Peggio: il numero in quella stringa sta **in mezzo**, quindi la
  regola di raggruppamento non trovava niente da togliere e ogni episodio restava una voce a
  sé. Un `stripSeo` disfa comunque l'involucro quando l'etichetta arriva da un attributo.
- ⚠️⚠️ **Gli episodi fratelli si riconoscono dallo SLUG, non dal titolo**: `momone-1` e
  `momone-2` condividono la base per costruzione, mentre i titoli possono arrivare avvolti
  nel testo SEO. È la stessa regola che hai chiesto (parte pre-numero identica), applicata
  al dato pulito.
- ⚠️ **Quando non trova nessun flusso, l'errore dice che cosa il player ha chiesto** (le
  ultime richieste non statiche): senza quell'elenco 'nessun flusso trovato' non è
  correggibile, perché da qui il sito non è osservabile.
- ⚠️ **Il DOM si aspetta con un polling, non con `onPageFinished`**: il sito rende le liste
  **dopo** che la pagina risulta 'finita', quindi quel callback scatta troppo presto.
- ⚠️⚠️ **La qualità è FISSA a 720p, e non è una preferenza** (decisione dell'utente,
  2026-08-19: *non servono opzioni per l'estensione*): il 1080p su questo sito appartiene agli
  abbonati, quindi offrirlo sarebbe una promessa che la sorgente non può mantenere, e le altre
  risoluzioni non sono una scelta che valga un'impostazione. Se il 720p non c'è, vince **la
  prima utile**, dalla più alta in giù. Nelle impostazioni resta il solo interruttore del
  registro di debug, che è diagnostica e nasce spento.
- ⚠️⚠️ **Le credenziali NON stanno nel codice, e non è una dimenticanza**: l'accesso si fa
  **dalla WebView dell'app** (tasto `WebView` nella scheda), e da quel momento il cookie di
  sessione vive nel browser di Aniyomi e l'estensione lo eredita. Una parola d'ordine scritta
  nel sorgente, anche di un repo privato, resterebbe **nella storia git per sempre**,
  viaggerebbe in ogni clone e in ogni artefatto della CI, e sopravvivrebbe a un cambio di
  password. L'utente aveva offerto di cablarla: è stata rifiutata per questo.
- ⚠️ **Tutto passa dal thread principale e blocca quello chiamante**: una WebView non si
  tocca da un thread di lavoro, mentre le sorgenti sono chiamate da thread IO. Da qui
  l'`Handler` più il latch, e il timeout su ogni ingresso.
- **I tre indirizzi delle pagine sono CONFERMATI dall'utente sul sito** (2026-08-19), non
  dedotti: la **home** per gli ultimi arrivi e
  `hanime.tv/search?q=<parola>&order=created_at_desc` per la ricerca. ⚠️ Il parametro è `q`
  e **non** `query`, che è l'ipotesi con cui era nata la prima versione.
- ⚠️ **`hanime.tv/browse` NON va bene per i popolari**, misurato sul dispositivo: è un indice
  di **categorie** e non contiene link a video, quindi l'elenco tornava vuoto. Al suo posto
  la pagina di ricerca ordinata per visualizzazioni. ✅ **`views_desc` è confermato** dalla
  traccia del 2026-08-19: la pagina risponde con 17 voci ordinate per visualizzazioni, e la
  home ne dà 63.
- **Niente paginazione, per ora**: il sito pagina scorrendo, e un numero di pagina che
  questa sorgente non può verificare sarebbe una promessa non mantenuta.

## Tracciamento: come si vede cosa succede al clic su Play

⚠️ **Il difetto peggiore di questa sorgente è quello SILENZIOSO**: un player che resta fermo
senza errore non dice niente, e da fuori dal telefono il sito non è osservabile. Quindi ogni
passo lascia una traccia in `HanimeLog`, e si legge in tre modi, dal più comodo al meno:

| come | cosa fare |
|---|---|
| **dalla sorgente** | cerca `debug` in questa sorgente: compare una voce 'Debug log', e la sua **descrizione** è la traccia intera. Nessun cavo, nessun file manager, nessun permesso |
| **da file** | accendi 'Write the debug log to a file' nelle impostazioni della sorgente: il percorso è scritto nel sommario, e la traccia sopravvive al riavvio dell'app |
| **da logcat** | tutte le righe escono anche col tag `HanimeRoccobot` |

- Per svuotarla si cerca `debug clear` nella sorgente. ⚠️ Sta **sullo stesso canale** e non
  nelle impostazioni perché lo stub `Preference` della libreria non ha un costruttore che una
  sorgente possa chiamare (`ListPreference` e `SwitchPreferenceCompat` sì, `Preference` no).
- ⚠️⚠️ **Ma svuotare NON serve più, ed è stato un difetto di progetto**: l'utente non trovava
  `debug clear` (2026-08-19: *non riesco a capire dov'è*), e aveva ragione, perché un comando
  che vive in un campo di ricerca è invisibile. Ora ogni tentativo di riproduzione apre una
  **riga separatore** e la traccia mostra **l'ultimo tentativo in cima**, col resto sotto un
  `===== earlier =====`. Il rito prima di ogni prova è caduto.
- **Che cosa registra**: caricamento e resa di ogni pagina, **ogni richiesta non statica** che
  la pagina fa (con metodo e indirizzo), gli **errori JS del sito** (che spiegano la maggior
  parte dei fallimenti silenziosi: 'non autenticato', handshake rifiutato), l'esito di ogni
  tentativo di avvio del player (elemento trovato, `paused`, `readyState`, numero di iframe) e
  **l'indirizzo che viene passato al player** di Aniyomi.
- ⚠️⚠️ **I clic su Play NON si fanno più quando la pagina non ha un player, e su questo sito
  non ce l'ha mai** (dalla `extVersionCode` 22, deciso sulla traccia del 2026-08-19). È
  l'**inventario** a decidere: se conta zero `video`, zero `iframe` e zero `canvas` e trova il
  tasto di download, si va dritti al flusso di download. Il ramo coi clic resta per le pagine
  che un player ce l'hanno davvero.
  - **Quanto costavano, misurato**: nella traccia il primo clic parte a `28.3`, il download
    parte a `36.3` e l'indirizzo è in mano a `39.8`. Otto secondi su quattordici e mezzo
    servivano ad aspettare il terzo tentativo di una cosa che non poteva riuscire.
  - ⚠️ **E non erano innocui**: senza nessun pulsante Play da agganciare, il filtro cadeva su un
    contenitore generico il cui testo conteneva la parola, e lo cliccava
    (`clicked=DIV.relative h-[280px] w-dvw overflow-y-auto`, tre volte). La nota che diceva
    'nessun clic di ripiego su contenitori generici' descriveva l'intenzione del codice, non
    quello che faceva: il ripiego esplicito era stato tolto, ma il filtro per parola lo
    rimetteva dalla finestra.
  - ⚠️ **Il riconoscimento si fa sui TRE contatori insieme** (`video=0 iframe=0 canvas=0`), mai
    sul solo `video=0`: quella stringa ricompare più avanti nella stessa riga, nell'elenco degli
    shadow root, dove ogni `iconify-icon` dichiara `video=0`. Col confronto largo si leggerebbe
    'nessun player' su una pagina che ce l'ha.
- **Dove i clic servono ancora, si ritentano tre volte** a distanza di 4 secondi: il player
  viene montato dopo che la pagina si è assestata, quindi un tentativo solo arriva troppo presto.
- ⚠️⚠️ **`[class*=play]` NON si usa per trovare il pulsante Play**, e la traccia lo ha
  dimostrato: quel selettore combacia anche con **`playlist`**, di cui la pagina è piena,
  quindi i cinque tentativi cliccavano una voce della playlist **dichiarando successo**. Ora
  i candidati si filtrano su una parola intera (`play`, `watch`, `guarda`, `riproduci`) con
  `playlist` escluso, e la traccia riporta **su cosa** ha cliccato, non solo che ha cliccato.
- ⚠️ **Prima di cliccare si registra un INVENTARIO della pagina**: quanti `video`, `iframe`
  e `canvas`, gli elementi con classe o id da player, le etichette dei primi pulsanti e
  l'inizio del testo visibile. È la misura che dice se un player esiste, invece di
  dedurlo.
- ⚠️ **La traccia è un anello di 400 righe** e non una lista che cresce: questo oggetto vive
  quanto l'app, e un log illimitato sarebbe una perdita di memoria. Scrivere su file non può
  far cadere la riproduzione: se la scrittura fallisce, si prosegue.

## ⚠️⚠️ IL FLUSSO È UN LINK ESTERNO, non qualcosa che il player espone

Fatto riferito dall'utente e verificato (2026-08-19), ed è la forma di tutta la sorgente: a un
account **con la sessione attiva** la pagina dell'episodio linka un file su un **host di terze
parti** (nel caso misurato `pixeldrain.net/u/<id>`), e da lì il download è un clic normale.
Niente handshake, niente intercettazione, niente da ricostruire: **l'indirizzo sta nella
pagina**.

- ⚠️ **Da disconnessi quel link NON C'È**, e questo spiega tutti i tentativi andati a vuoto:
  cercavano un player che quella pagina non ha.
- ⚠️ **La pagina `/u/<id>` non si passa al player**, che riceverebbe HTML: pixeldrain serve i
  byte a **`/api/file/<id>`**. Misurato su quell'esempio: `200`, `content-type: video/mp4`,
  185 MB, `Accept-Ranges: bytes` e `206` su una richiesta di intervallo, quindi **si riproduce
  in streaming** e non va scaricato tutto; il nome del file dichiara `720p`.
- La ricerca del link parte dalla **pagina resa senza cliccare niente** (la via più economica);
  la vecchia strada della WebView coi clic resta solo come **ripiego**, per il caso in cui il
  link compaia dopo l'uso del controllo di download.
- Se il link manca, l'errore lo dice in chiaro: **accedi dal tasto WebView**, perché è la
  sessione a fare comparire l'indirizzo.
- **Alcuni titoli sono soggetti a RESTRIZIONE** e non hanno né player né link: al posto del
  player la pagina *mostra* un `RestrictedVideoNotice`. Misurato su `uchi-no-otouto-...-1`, i
  cui soli link esterni erano Discord e un banner, e la cui pagina aveva appena chiesto
  `country_code` al sito.
- ⚠️⚠️ **MA quella verifica NON si fa sull'HTML, ed è un difetto che è costato una release**:
  `RestrictedVideoNotice` sta nel markup di **ogni** pagina, nascosto dal CSS, e viene solo
  *mostrato* quando il titolo non è disponibile. Cercarne la stringa nell'html dichiarava
  bloccata ogni pagina, `the-pianist-1` compreso, che l'utente aveva verificato funzionante. La
  domanda si risolve solo sulla **visibilità**, dentro la pagina (`restrictedVisible` nella
  traccia), e per questo il blocco preventivo è stato tolto: si prosegue e si guardano i link.
- ⚠️ **Un link aperto in una SCHEDA NUOVA non è un'ancora nel DOM**: se il controllo di
  download passa l'host esterno a `window.open`, dal codice non si vedrebbe nulla. Quella
  funzione è agganciata per **registrare** l'indirizzo e poi lasciarla proseguire: si osserva,
  non si altera.

## Il percorso vero del download, misurato sul dispositivo (2026-08-19)

L'utente ha fotografato i passaggi, e sono **tre**, non uno:

1. `Download` apre **nella pagina stessa** un elenco di opzioni: `Premium MP4 1080p` con la
   corona, poi `Pixeldrain MP4` a 720p, 480p e 360p, ognuna con l'icona 'apri in una scheda
   nuova';
2. toccandone una compare un pannello **'Leaving hanime.tv'** che mostra l'indirizzo esterno
   **come TESTO** (`https://pixeldrain.net/u/<id>`), con 'Continue to External Site' e 'Cancel';
3. solo il terzo tocco esce davvero dal sito, e su pixeldrain il file si riproduce o si scarica.

- ⚠️⚠️ **La sorgente si ferma al passo DUE**: l'indirizzo si legge dal testo, senza acconsentire
  a niente e senza toccare il flusso del sito.
- **I due secondi di attesa fra il primo e il secondo clic HANNO TENUTO**, misurato: nella
  traccia del 2026-08-19 l'opzione giusta è stata presa al primo colpo
  (`clicked=Pixeldrain MP4720p`), e l'indirizzo è comparso 1,5 secondi dopo. ⚠️ **Ma di quanto
  margine, non si sa**: la traccia dice che a 2 secondi l'elenco era pronto, non quando lo è
  diventato. Il segnale da cercare se un domani il sito rallenta resta `noPixeldrainOption`, e
  la risposta è allungare quell'attesa.
- ⚠️⚠️ **Ecco perché tutte le tracce precedenti tornavano a mani vuote**: quelle opzioni sono
  **pulsanti senza `href`**, quindi una scansione delle ancore non poteva vederle, e il pannello
  non è un `dialog`, quindi nemmeno la ricerca di dialoghi lo trovava. Non mancava un tentativo:
  mancava un **secondo clic**.
- ⚠️ **Mai la voce `Premium MP4 1080p`**, quella con la corona: su un account gratuito è un muro
  di pagamento, e l'estensione non finge di essere abbonata. L'ordine è 720p, 480p, 360p.
- ⚠️ **L'avviso del sito sul blocco di Pixeldrain è GENERICO e nel nostro caso FALSO**, e vale
  saperlo per non dare la colpa alla causa sbagliata al primo intoppo: il pannello dice 'il tuo
  Paese ha bloccato Pixeldrain, il link probabilmente non funzionerà', ma due misure
  indipendenti lo smentiscono, cioè pixeldrain che riproduce sul dispositivo dell'utente **senza
  VPN** (screenshot, 2026-08-19) e un `curl` da questo ambiente che scarica il file
  regolarmente. Il sito sembra mostrarlo a tutti gli indirizzi italiani senza verificare nulla.
- Ⓘ Pixeldrain ha un **tetto di traffico giornaliero** (nella prova: 642 MB usati su 6 GB), e lo
  streaming lo consuma come un download: con episodi da ~185 MB sono una trentina di visioni al
  giorno.

## Che cosa si sa del player, misurato (2026-08-19)

Tracce raccolte sul dispositivo. Un fatto positivo prima di tutto: **l'accesso funziona e
arriva fino all'estensione** (cookie `htv_session` nel barattolo condiviso, `signInOffered=false`,
menu con `Sign Out`, `My Channel` e `Account Settings`, e un `keep-alive` su
`auth.hanime.tv`). Poi:

- **nella pagina di un episodio non compare NESSUN elemento `video`, `iframe` o `canvas`**,
  nemmeno da autenticato: gli unici elementi custom sono `astro-island` e `iconify-icon`, e
  gli shadow root sono quelli delle icone;
- ⚠️⚠️ **ma la prima misura era INQUINATA DA UN MIO DIFETTO, e va rifatta**: `onPageFinished`
  scatta a **ogni transizione** di questa app Astro, e ogni scatto avviava una nuova sequenza
  di inventario e clic. Nella traccia del 2026-08-19 si contano **318 clic** in un solo
  tentativo, e la console del sito dice cosa producevano: `Transition was aborted because of
  timeout in DOM update` e `Throttling navigation to prevent the browser from hanging`. Cioè
  era il mio stesso rumore a impedire alla pagina di assestarsi. Da qui la guardia
  `started`, l'attesa prima di toccare la pagina, tre tentativi invece di cinque, e
  **nessun clic di ripiego** su poster o contenitori generici;
- il sito è ora un'app **Astro** (`/_astro/*.js`), e fra i suoi componenti carica un
  `HTVPlayerPromotePremiumModal`: può essere quel modale a prendere il posto del player per
  chi non è autenticato o non è abbonato;
- ⚠️ **il catalogo passa da un host nuovo**, `guest.freeanimehentai.net/api/v11/search_hvs`,
  con un **GET**: il `guest.` nel nome dice che esiste una via da ospite, e questo era
  sconosciuto quando la sorgente è stata scritta.
- ⚠️⚠️ **Il tasto `MP4Download` della pagina ESISTE e fa partire la sequenza giusta**
  (traccia pulita del 2026-08-19, ad accesso fatto): il clic porta il sito a chiedere
  `country_code` e poi a fare **da sé** `OPTIONS` e `POST` su `auth.hanime.tv/api/v11/handshake`.
  Nessuna richiesta media segue, e insieme all'handshake il sito carica le icone
  `crown-rounded`, `block`, `upgrade`, `full-hd-rounded` e `warning-rounded`, cioè quelle di un
  **modale che promuove l'abbonamento**. Da qui la lettura del modale (`PLAY modal`), che è
  l'unica cosa che distingue un muro commerciale da un secondo passo da fare.
- ⚠️ **Nella traccia NON devono finire dati personali**, ed erano finiti: l'etichetta del menu
  utente portava nome utente e indirizzo email dell'utente, in un file che si incolla in chat.
  Ora sono oscurati.
- ⚠️ **'Autenticato' si legge da `Sign Out`, non dall'assenza di `Sign In`**: il menu offre
  entrambi a chi ha la sessione attiva, quindi il vecchio controllo diceva l'opposto del vero.

## Il workflow e le azioni che GitHub segnala

Le annotazioni giallo su una build verde sono avvisi, non errori, e valeva correggerle:

- ⚠️ **`build-root-directory` NON esiste in `setup-gradle`**, e veniva ignorato in silenzio
  (`Unexpected input(s)` nel run): la cartella di lavoro la fissa `defaults.run`, che è ciò
  che conta davvero. Toglierlo evita di credere che quel parametro faccia qualcosa.
- Le azioni sono passate alla **v5** (`checkout`, `setup-java`, `setup-gradle`,
  `upload-artifact`): la v4 gira su un runtime Node che GitHub ha dismesso e che per ora
  esegue forzatamente su quello nuovo.

## Accesso: la sessione della WebView, e il cookie da passare al player

Il **download della pagina è riservato agli utenti registrati**, anche non paganti (fatto
riferito dall'utente, 2026-08-19), e su questa sorgente può essere l'unica via al flusso.
Quindi l'accesso si fa **dalla WebView dell'app** e da lì la sessione si propaga.

- ⚠️⚠️ **Il cookie va passato ESPLICITAMENTE al player, e questo era il pezzo mancante**: il
  player di Aniyomi non parla attraverso la WebView, usa il client http dell'estensione, che
  non sa nulla della sessione creata accedendo. Un indirizzo riservato ai registrati,
  consegnato senza cookie, risponde 403. Ora l'intestazione `Cookie` viene aggiunta al video
  se non c'è già.
- ⚠️ **Il download NON arriva come sottorisorsa**: un link che scarica passa dal
  `DownloadListener` della WebView, non da `shouldInterceptRequest`. Senza quel listener
  l'unico indirizzo che il sito offre davvero non si vedrebbe mai.
- I cookie di sessione si accettano esplicitamente (`setAcceptCookie`,
  `setAcceptThirdPartyCookies`): sono le due righe che fanno vedere a questa WebView la
  sessione creata nell'altra.
- ⚠️⚠️ **Nella traccia finiscono i NOMI dei cookie, mai i valori**: il registro si incolla in
  chat, e un token di sessione lì dentro sarebbe una password regalata. I nomi bastano a
  rispondere alla sola domanda utile, cioè se una sessione esiste. L'inventario dice anche
  `signInOffered`, che è la risposta più corta a 'questo browser è autenticato?'.

## Nomi degli episodi: solo il numero

**Gli episodi si chiamano `01`, `02`, `03`...** (scelta dell'utente, 2026-08-19: *si può anche
semplificare rinominandoli semplicemente come 01, 02, 03; l'importante è che siano in fila*).

- ⚠️ **Non è pigrizia, è la via che smette di rincorrere il sito**: il testo delle schede
  episodio è un impasto di durata, studio e badge di stato (`Now Playing30:27 Master Piece
  1Pink P...`), e ogni ripulitura mirata a un caso ne lascia scoperto un altro. Il numero
  invece si ricava dallo slug, che è pulito per costruzione.
- **La descrizione della serie viene SCARTATA quando è testo SEO** (`Watch X 1 latest hentai
  online free download HD...`): non è una sinossi, e una versione ripulita a metà sembrerebbe
  una descrizione vera senza dire niente.

## Un'opera e una SERIE, non un video

hanime.tv dà a ogni episodio una pagina e uno slug propri, quindi senza raggruppamento una
serie da otto episodi comparirebbe come otto voci. La regola è quella della vecchia
estensione: **stesso titolo una volta tolto il numero finale, stessa serie**. Così
`Anime Titolo III 01/02/03` diventa la voce unica `Anime Titolo III` coi suoi tre episodi.

- ⚠️ **Si toglie solo il numero ARABO in coda**, e la ragione è sostanziale: i titoli usano
  i **numerali romani** come parte del nome, quindi un'espressione più avida fonderebbe
  `Titolo III` e `Titolo IV` in un'unica opera. Verificato sui casi limite: `Titolo IV 01`
  resta una serie a sé, `Titolo 2022 12` si raggruppa come `Titolo 2022`, e un one-shot
  senza numero passa intatto.
- ⚠️⚠️ **L'indirizzo dell'opera si CALCOLA, non si prende dal primo risultato utile**: è
  l'indirizzo la chiave con cui Aniyomi tiene la voce in libreria, quindi usare lo slug
  dell'episodio capitato per primo in una pagina di risultati archivierebbe la stessa serie
  sotto due voci diverse a seconda della ricerca che l'ha trovata. Si usa **l'episodio 1**
  come rappresentante fisso del gruppo, perché su questo sito la numerazione parte da 1.
- ⚠️ **La franchise del sito NON è la serie**: contiene anche sequel e spin-off, quindi la
  lista episodi la filtra sulla **base dello slug** del gruppo. Prendendola intera,
  `Titolo IV` finirebbe dentro `Titolo III`.
- **Limite noto, dichiarato**: la deduplica vede **una schermata** per volta, cioè i link
  presenti nel DOM di quella pagina. Se due episodi della stessa serie non ci stanno insieme,
  in esplorazione la voce può comparire due volte; in **libreria no**, perché le due voci
  hanno lo stesso indirizzo calcolato.

## Struttura e crediti

Il telaio di build (`buildSrc`, `core`, `common.gradle`, wrapper Gradle) è ripreso da
[aniyomiorg/aniyomi-extensions](https://github.com/aniyomiorg/aniyomi-extensions), sotto
licenza Apache 2.0: vedi il file [LICENSE](LICENSE). Il codice della sorgente in
`aniyomi/src/en/hanime/` è scritto per questo repository.

La compilazione gira su GitHub Actions (`.github/workflows/aniyomi-hanime.yml` nella radice del repository), perché non serve
un SDK Android in locale.
