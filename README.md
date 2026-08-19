# Estensioni Mihon e Aniyomi di Roccobot

Repository **privato**, a **uso personale**: le estensioni non sono distribuite e l'APK si
installa a mano sul telefono.

| cartella | ecosistema | che cosa contiene |
|---|---|---|
| [`aniyomi/`](aniyomi/) | [Aniyomi](https://github.com/aniyomiorg/aniyomi) (anime) | sorgente **hanime.tv**, che in app si chiama `Hanime Roccobot` |
| `mihon/` | [Mihon](https://github.com/mihonapp/mihon) (manga) | ancora niente |

## Perche due cartelle e non una

⚠️ **I due ecosistemi NON possono condividere lo stesso telaio di build**, e la separazione
serve a questo: hanno librerie di estensione diverse (`extensions-lib` di Aniyomi contro
quella di Mihon) e soprattutto **metadati del manifest diversi**
(`tachiyomi.animeextension.class` contro `tachiyomi.extension.class`). Un solo progetto
Gradle dovrebbe tenere insieme due `core` incompatibili; due cartelle, ognuna col proprio
build completo, non hanno alcun punto di contatto da mantenere allineato.

- Ogni cartella ha il **suo** wrapper Gradle, il suo `settings.gradle.kts` e il suo `core`.
- Ogni cartella ha il **suo** workflow in [`.github/workflows/`](.github/workflows), perche
  GitHub legge i workflow solo dalla radice del repository.

## Come si installa un'estensione

1. Scheda **Actions**, apri l'ultima build riuscita e scarica l'artefatto con l'APK
   (se la versione ha un tag `v*`, l'APK sta anche nella Release).
2. Apri l'APK sul telefono e installalo come una normale app.
3. L'app chiede di **fidarsi di una firma sconosciuta**: e la richiesta attesa per
   un'estensione che non viene dal repository ufficiale, e si conferma.

Il dettaglio di come e fatta ogni sorgente sta nel README della sua cartella.
