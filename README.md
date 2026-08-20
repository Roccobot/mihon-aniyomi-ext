# Estensioni Mihon e Aniyomi di Roccobot

Repository **privato**, a **uso personale**: le estensioni non sono distribuite e l'APK si
installa a mano sul telefono.

| cartella | ecosistema | che cosa contiene |
|---|---|---|
| [`aniyomi/`](aniyomi/) | [Aniyomi](https://github.com/aniyomiorg/aniyomi) (anime) | sorgente **hanime.tv**, che in app si chiama `Hanime Roccobot` |
| [`mihon/`](mihon/) | [Mihon](https://github.com/mihonapp/mihon) (manga) | sorgente **nhentai.net**, che in app si chiama `nhentai Roccobot` |

## Perché due cartelle e non una

⚠️ **I due ecosistemi NON possono condividere lo stesso telaio di build**, e la separazione
serve a questo: hanno librerie di estensione diverse (`extensions-lib` di Aniyomi contro
quella di Mihon) e soprattutto **metadati del manifest diversi**
(`tachiyomi.animeextension.class` contro `tachiyomi.extension.class`). Un solo progetto
Gradle dovrebbe tenere insieme due `core` incompatibili; due cartelle, ognuna col proprio
build completo, non hanno alcun punto di contatto da mantenere allineato.

- Ogni cartella ha il **suo** wrapper Gradle, il suo `settings.gradle.kts` e il suo `core`.
- Ogni cartella ha il **suo** workflow in [`.github/workflows/`](.github/workflows), perché
  GitHub legge i workflow solo dalla radice del repository.

## Come si installa un'estensione

1. Scheda **Releases**, apri **`latest`**: è aggiornata a ogni build e porta **due** allegati
   dal nome stabile, `aniyomi-hanime.apk` e `mihon-nhentai.apk`, scaricabili con un tocco. Le
   versioni con un tag hanno una Release propria, che resta (`v*` per Aniyomi, `m*` per Mihon).
   ⚠️ Gli artefatti di Actions non si usano più: chiedono l'accesso a GitHub, arrivano in uno
   zip, scadono dopo 90 giorni, e l'azione che li carica gira ancora su Node 20.
2. Apri l'APK sul telefono e installalo come una normale app.
3. L'app chiede di **fidarsi di una firma sconosciuta**: è la richiesta attesa per
   un'estensione che non viene dal repository ufficiale, e si conferma.

Il dettaglio di com'è fatta ogni sorgente sta nel README della sua cartella.

## ⚠️⚠️ Quando il difetto non è nell'estensione: i blocchi di rete

Vale per **tutte** le sorgenti di questo repository, e va escluso **prima** di andare a
cercare un difetto nel codice, perché i due casi si somigliano e la diagnosi costa un minuto.

**Come si riconosce.** L'errore arriva **prima di qualunque risposta HTTP**, ed è di
connessione, non di analisi della pagina. Il caso misurato il 2026-08-20 sul telefono:

```
ConnectException: Failed to connect to nhentai.net/[::1]:443
```

⚠️ `[::1]` è **localhost**: quel nome non è stato risolto all'indirizzo del sito ma
all'apparecchio stesso, che è il modo in cui un blocco a livello di DNS fa fallire un dominio.
Nessuna riga di questo repository può produrre quell'esito: la richiesta non esce dal telefono.
Dalla rete di sviluppo, nello stesso momento, lo stesso nome risolveva agli indirizzi veri e
l'API rispondeva `200`.

**Il secondo indizio, sull'altra estensione**, viene dal sito stesso: la pagina di hanime.tv
chiede `/country_code` e poi scrive *'Your country has blocked the file host (Pixeldrain)'*.
Due domini diversi, due estensioni diverse, un fattore comune che non è il codice.

**Che cosa provare, in ordine**, dal più rapido:

1. **Aprire il dominio nel browser del telefono** (o il tasto 'Apri in WebView' dell'app). Se
   non si apre nemmeno lì, il difetto non è nell'estensione e i punti seguenti sono la strada.
2. **Attivare DNS over HTTPS nell'app**: sia Mihon sia Aniyomi ce l'hanno nelle impostazioni
   avanzate. È il rimedio mirato se il blocco è quello che l'errore `[::1]` descrive, perché la
   risoluzione dei nomi smette di passare per il resolver che restituisce localhost. ⚠️ Non
   aiuta se il blocco è per indirizzo invece che per nome, e questo lo dice solo la prova.
3. **Cambiare rete** (dati mobili invece del Wi-Fi, o viceversa): distingue un blocco del
   router o di casa da uno del gestore.

⚠️ **Un DNS che filtra i contenuti va escluso a sua volta**: se l'apparecchio usa un DNS
privato con blocklist, sceglierne uno con la stessa blocklist come DoH lascia le cose come
stanno.

## Quale versione sto scaricando

Il nome degli allegati è **stabile**, quindi da solo non dice nulla sulla versione: è il prezzo
di avere un indirizzo di download che non cambia mai. A dirlo sono le **note della release**, che
ogni workflow riscrive con la propria riga:

```
- **Aniyomi hanime**: `aniyomi-hanime.apk`, da aniyomi-en.hanime-v14.23-debug.apk, pubblicato il 20/08/2026 10:09 CEST (commit `ddbeed1`).
```

- ⚠️ **L'ora è quella ITALIANA, non UTC**, ed è una correzione nata da un caso vero: chi legge la
  pagina della release confronta quell'ora con l'orologio del telefono, e due ore di scarto
  bastano a far credere di aver scaricato il file sbagliato. Chi riferisce una pubblicazione in
  chat usa la stessa ora, per la stessa ragione.
- ⚠️ **Le pull request COMPILANO ma non pubblicano**, per la riga `if: github.event_name !=
  'pull_request'` dei due workflow. Non è un vincolo della piattaforma e si può togliere: il
  repository è privato e i branch sono interni, quindi il token del workflow ha comunque
  accesso in scrittura alla release. È una **scelta**, e la ragione è che `latest` deve
  contenere solo codice mergiato: pubblicando dalle PR, una chiusa senza merge lascerebbe
  installato sul telefono un APK che nel repository non esiste più. Volendo provare una
  versione prima del merge, la strada pulita è una release separata, non `latest`.

### ⚠️⚠️ Due workflow, una sola release: le due trappole

I due workflow scrivono nello **stesso** posto, e questo apre due difetti che non si vedono
finché non capita il caso giusto. Tutti e due sono stati trovati sulla prima pubblicazione col
formato nuovo, e sono corretti: la nota resta perché la prossima estensione li reincontrerebbe.

- **Le note**: si tengono le **sole** righe marcate (`- **`) e ci si aggiunge la propria, invece
  di conservare tutto il corpo tranne la propria. Con un filtro per sola esclusione, il testo
  scritto alla creazione della release non porta alcun marcatore e sopravvive per sempre,
  dichiarando una versione vecchia accanto a quella nuova.
- **Gli allegati**: la pulizia dei file versionati rimasti dai giri precedenti deve nominare il
  **proprio prefisso**, non escludere il proprio nome stabile. Escludendo solo il proprio,
  l'APK dell'altra estensione finisce fra quelli da cancellare. ⚠️ È il difetto che si nasconde
  meglio, perché la coda di concorrenza fa girare i due workflow uno dopo l'altro e il secondo
  ricarica il suo allegato subito dopo che il primo l'ha cancellato: si manifesta solo al primo
  push che tocca una cartella sola.
- La **coda** (`concurrency: release-latest`, senza `cancel-in-progress`) esiste perché i due
  workflow leggono e riscrivono lo stesso corpo di note: senza, l'ultimo a scrivere
  cancellerebbe la riga appena scritta dall'altro.
