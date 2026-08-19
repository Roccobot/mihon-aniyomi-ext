# Estensioni Mihon e Aniyomi di Roccobot

Repository **privato**, a **uso personale**: le estensioni non sono distribuite e l'APK si
installa a mano sul telefono.

| cartella | ecosistema | che cosa contiene |
|---|---|---|
| [`aniyomi/`](aniyomi/) | [Aniyomi](https://github.com/aniyomiorg/aniyomi) (anime) | sorgente **hanime.tv**, che in app si chiama `Hanime Roccobot` |
| `mihon/` | [Mihon](https://github.com/mihonapp/mihon) (manga) | ancora niente |

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

1. Scheda **Releases**, apri **`latest`**: è aggiornata a ogni build e porta sempre lo stesso
   allegato, `aniyomi-hanime.apk`, scaricabile con un tocco. Le versioni con un tag `v*` hanno una Release propria,
   che resta. ⚠️ Gli artefatti di Actions non si usano più: chiedono l'accesso a GitHub,
   arrivano in uno zip, scadono dopo 90 giorni, e l'azione che li carica gira ancora su Node 20.
2. Apri l'APK sul telefono e installalo come una normale app.
3. L'app chiede di **fidarsi di una firma sconosciuta**: è la richiesta attesa per
   un'estensione che non viene dal repository ufficiale, e si conferma.

Il dettaglio di com'è fatta ogni sorgente sta nel README della sua cartella.
