# Mihon: sorgente nhentai (`nhentai Roccobot` in app)

Estensione **nhentai.net** per [Mihon](https://github.com/mihonapp/mihon), a **uso personale**:
non è pubblicata in nessun catalogo e si installa dal repository di estensioni del progetto
(procedura nel [README di radice](../README.md)).

## Come si installa

1. Scheda **Releases**, apri **`latest`**: l'allegato di questa estensione si chiama
   `mihon-nhentai.apk` e ha un nome stabile, quindi l'indirizzo di download non cambia mai.
   ⚠️ Nella stessa release vive anche l'APK dell'estensione Aniyomi: sono due allegati distinti.
2. Apri l'APK sul telefono e installalo come una normale app.
3. Mihon chiede di **fidarsi di una firma sconosciuta**: è la richiesta attesa per un'estensione
   che non viene dal repository ufficiale, e si conferma.

## Com'è fatta: si parla con l'API, non con le pagine

⚠️⚠️ **Le pagine HTML sono dietro Cloudflare, l'API no**, ed è la misura su cui poggia tutto il
resto (2026-08-19): la home risponde `403` con l'intestazione `cf-mitigated: challenge`, mentre
ogni endpoint dell'API risponde `200` **senza credenziali**. Da qui tre conseguenze pratiche:

- **niente WebView, niente accesso, nessun cookie da ereditare**, che è l'esatto contrario di
  quanto è servito per la sorgente hanime.tv di questo stesso repository;
- il lavoro sta nel tradurre JSON, non nel leggere un DOM che cambia a ogni restyling;
- se un domani la protezione arrivasse anche sull'API, la strada del ripiego è già scritta e
  collaudata in casa, e sarebbe quella della WebView.

**La v1 dell'API non esiste più**: risponde con una riga di testo che manda alla v2. Lo schema di
quello che questa sorgente usa viene dal documento **OpenAPI pubblicato** dal sito, non da
supposizioni: <https://nhentai.net/api/v2/openapi.json> (89 endpoint, di cui qui ne servono sei).

| a che serve | endpoint |
|---|---|
| popolari | `galleries/popular` |
| ultimi arrivi | `galleries` con `page` e `per_page` |
| ricerca e ordinamento | `search` con `query`, `sort`, `page` |
| scheda, tag e pagine | `galleries/{id}` |
| indirizzi dei server (immagini e miniature) | `cdn` |

### ⚠️ Trappole

- ⚠️⚠️ **L'identificativo della scheda e quello del media sono DUE NUMERI DIVERSI**, ed è l'errore
  che costa un pomeriggio: gli indirizzi delle immagini si costruiscono col secondo, che compare
  dentro il percorso di ogni pagina. La sorgente non lo compone a mano proprio per questo: prende
  il percorso già pronto che l'API restituisce e ci antepone il server.
- ⚠️⚠️ **I server sono DUE INSIEMI, non uno, e non sono intercambiabili**: `image_servers` (i
  quattro `i*`) serve le **pagine da leggere**, `thumb_servers` (i quattro `t*`) serve
  **copertine e miniature**. Chiedere all'insieme sbagliato **non dà un `404`** che indicherebbe
  l'errore: la connessione viene **troncata a metà**, e nell'app si vede come una copertina che
  non arriva senza motivo.
  - **Misurato** su `galleries/4126277` il 2026-08-20: `thumb.webp` risponde `200 image/webp`
    (29 KB) su `t1` e muore su `i1`; `1.webp` risponde `200 image/webp` (243 KB) su `i1` e muore
    su `t1`. Vale anche per la copertina della scheda (`cover.webp.webp`, 33 KB su `t1`).
  - ⚠️ **È il difetto della v1**, trovato quando l'utente ha visto i titoli arrivare e le
    copertine no: la sorgente conosceva un insieme solo e lo usava per tutto. Il sintomo era
    fuorviante perché la parte che funzionava (l'elenco) e quella che non funzionava (le
    immagini) sembravano venire dalla stessa fonte.
- ⚠️ **Dentro un insieme i quattro server SONO intercambiabili**: la stessa identica immagine
  risponde su tutti e quattro (misurato). Distribuire le richieste fra loro è cortesia verso il
  sito, non una necessità, e niente si rompe se un domani ne restasse uno solo.
- ⚠️ **L'endpoint dei popolari non conosce le pagine**: restituisce un lotto fisso. La lista deve
  quindi dichiarare che dopo non c'è altro, o l'app continuerebbe a chiedere pagine successive
  all'infinito.
- ⚠️ **La copertina ha una doppia estensione nel percorso** (`...cover.webp.webp`), che è come il
  sito la serve: non è un refuso da correggere, e correggendolo l'immagine non si troverebbe più.
- ⚠️ **Il campo dell'indirizzo tenuto in libreria è quello LEGGIBILE** (`/g/<id>`), non quello
  dell'API: è la chiave con cui l'app riconosce una voce già in libreria, ed è anche quello che si
  apre col comando 'apri nel browser'. Le richieste lo riscrivono in forma di API quando serve.
- ⚠️ **Una galleria è un'opera finita, quindi un capitolo solo**: l'unica data disponibile è quella
  di caricamento, in secondi, e va portata in millisecondi per l'app.

## Perché una cartella a sé, e non dentro `aniyomi/`

I due ecosistemi **non possono condividere il telaio di build**: hanno librerie di estensione
diverse e soprattutto metadati del manifest diversi. ⚠️ Qui il manifest dichiara
`tachiyomi.extension`, là `tachiyomi.animeextension`: sbagliare quella riga produce un APK che si
installa senza errori e che poi l'app **non riconosce mai** come sorgente, che è il difetto più
difficile da diagnosticare di tutti.

- ⚠️⚠️ **La libreria è `com.github.keiyoushi:extensions-lib` `1.4.2.1`, e NON la più recente**,
  che sarebbe `mihonapp:extensions-lib` `1.6.0`. La ragione è misurata sulla prima build:
  quella libreria è compilata con **Kotlin 2.4**, questo telaio gira a **Kotlin 1.8.22**, e un
  compilatore che non sa leggere i metadati della classe base dichiara **irrisolto ogni membro
  ereditato**. Il risultato è una cascata di decine di errori che sembrano tutti difetti del
  sorgente e non lo sono: l'unico vero è la riga sulla versione incompatibile, che sta in cima.
  - **Perché non si alza Kotlin e basta**: salire alla 2.4 vuol dire muovere insieme Kotlin, AGP
    e il linter, cioè un lavoro a sé con più variabili in gioco, e farebbe divergere questo
    telaio da quello di `aniyomi/`, che resta alla 1.8.22.
  - **Perché la libreria vecchia non è un ripiego scadente**: è quella con cui compila il parco
    di estensioni mantenute per Mihon, ed è dichiarata `compileOnly`, quindi serve solo a
    conoscere le firme in compilazione: **a runtime le classi le fornisce l'app**.
- Il telaio è quello di `aniyomi/`, adattato: stesso Gradle, stesso `core`, stesse convenzioni.
  Quello che cambia è elencato qui sopra, e nient'altro.
