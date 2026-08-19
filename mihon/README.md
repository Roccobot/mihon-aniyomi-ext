# Mihon: sorgente nhentai (`nhentai Roccobot` in app)

Estensione **nhentai.net** per [Mihon](https://github.com/mihonapp/mihon), a **uso personale**: il
repository è privato e l'APK non è distribuito, si installa a mano sul telefono (procedura nel
[README di radice](../README.md)).

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
| indirizzi dei server immagine | `cdn` |

### ⚠️ Trappole

- ⚠️⚠️ **L'identificativo della scheda e quello del media sono DUE NUMERI DIVERSI**, ed è l'errore
  che costa un pomeriggio: gli indirizzi delle immagini si costruiscono col secondo, che compare
  dentro il percorso di ogni pagina. La sorgente non lo compone a mano proprio per questo: prende
  il percorso già pronto che l'API restituisce e ci antepone il server.
- ⚠️ **I quattro server immagine NON sono partizioni**: la stessa identica immagine risponde
  `206 image/webp` su tutti e quattro (misurato). Distribuire le pagine fra loro è cortesia verso
  il sito, non una necessità, e nessuna pagina si rompe se un domani ne restasse uno solo.
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

- La libreria è **`com.github.mihonapp:extensions-lib`**, versione `1.6.0`. ⚠️ Su JitPack ne
  esistono altre due che sembrano equivalenti e non lo sono: `tachiyomiorg` si ferma alla `1.4.4` e
  `keiyoushi` alla `1.4.2.1`, entrambe dell'era precedente al fork. La scelta è stata fatta
  leggendo i metadati pubblicati, non a memoria.
- Il telaio è quello di `aniyomi/`, adattato: stesso Gradle, stesso `core`, stesse convenzioni.
  Quello che cambia è elencato qui sopra, e nient'altro.
