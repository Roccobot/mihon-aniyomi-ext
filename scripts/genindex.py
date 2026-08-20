#!/usr/bin/env python3
"""Genera `index.min.json` e `repo.json` per un repository di estensioni.

⚠️ I metadati si LEGGONO dal codice, non si scrivono qui: il nome della sorgente, la lingua e
l'indirizzo base stanno nel sorgente Kotlin, la versione e il nome del pacchetto nel telaio
Gradle. Tenerne una copia in questo script vorrebbe dire due fonti di verità per lo stesso
dato, e prima o poi una delle due mentirebbe. Se un valore non si trova, lo script FALLISCE
invece di inventarne uno: un indice con un dato sbagliato produce un'estensione che l'app
scarica e poi rifiuta, che è molto più difficile da capire di un errore di build.
"""
import hashlib
import json
import pathlib
import re
import sys

# L'app calcola così l'identificativo di una sorgente, e l'indice deve dire lo stesso numero
# o la sorgente risulta sconosciuta. ⚠️ Non è un valore arbitrario e non si inventa: dipende
# solo da nome e lingua, motivo per cui rinominare una sorgente stacca la libreria da quello
# che conteneva, mentre cambiare il nome del pacchetto non la tocca.
VERSION_ID = 1


def source_id(name: str, lang: str) -> str:
    key = f"{name.lower()}/{lang}/{VERSION_ID}"
    digest = hashlib.md5(key.encode()).digest()
    value = 0
    for i in range(8):
        value |= (digest[i] & 0xFF) << (8 * (7 - i))
    return str(value & 0x7FFFFFFFFFFFFFFF)


def leggi(testo: str, pattern: str, dove: str) -> str:
    trovato = re.search(pattern, testo)
    if not trovato:
        raise SystemExit(f"genindex: non trovo {pattern!r} in {dove}")
    return trovato.group(1)


def voce(radice: pathlib.Path, ecosistema: str, modulo: str, apk: pathlib.Path) -> dict:
    gradle = (radice / "src" / "en" / modulo / "build.gradle").read_text()
    config = (radice / "buildSrc/src/main/kotlin/AndroidConfig.kt").read_text()
    sorgenti = sorted((radice / "src" / "en" / modulo / "src").rglob("*.kt"))
    principale = next(
        (f for f in sorgenti if "override val name" in f.read_text()),
        None,
    )
    if principale is None:
        raise SystemExit(f"genindex: nessun sorgente con 'override val name' in {modulo}")
    kt = principale.read_text()

    ext_name = leggi(gradle, r"extName\s*=\s*'([^']+)'", "build.gradle")
    code = int(leggi(gradle, r"extVersionCode\s*=\s*(\d+)", "build.gradle"))
    nsfw = 1 if re.search(r"isNsfw\s*=\s*true", gradle) else 0
    namespace = leggi(config, r'namespace\s*=\s*"([^"]+)"', "AndroidConfig.kt")
    nome = leggi(kt, r'override val name\s*=\s*"([^"]+)"', principale.name)
    lang = leggi(kt, r'override val lang\s*=\s*"([^"]+)"', principale.name)
    base = leggi(kt, r'override val baseUrl\s*=\s*"([^"]+)"', principale.name)

    # Il prefisso del nome visibile è quello che il telaio mette nel manifest: le app lo
    # mostrano così nell'elenco del repository.
    prefisso = "Aniyomi" if ecosistema == "aniyomi" else "Tachiyomi"
    # Il numero di versione lo compone il telaio come `<base>.<code>`, e l'indice deve dire
    # lo stesso: è il valore che l'app confronta per decidere se proporre l'aggiornamento.
    # ⚠️ Il prefisso NON ha lo stesso numero di componenti nei due telai: `14.` per Aniyomi,
    # `1.4.` per Mihon. Un pattern che ne accettasse una sola avrebbe funzionato su un
    # ecosistema e sarebbe esploso sull'altro, che è esattamente com'è andata la prima volta.
    base_version = leggi(
        (radice / "common.gradle").read_text(),
        r'versionName "([\d.]+)\.\$versionCode"',
        "common.gradle",
    )
    return {
        "name": f"{prefisso}: {ext_name}",
        "pkg": f"{namespace}.en.{modulo}",
        "apk": apk.name,
        "lang": lang,
        "code": code,
        "version": f"{base_version}.{code}",
        "nsfw": nsfw,
        "sources": [{"name": nome, "lang": lang, "id": source_id(nome, lang), "baseUrl": base}],
    }


def main() -> None:
    if len(sys.argv) != 6:
        raise SystemExit(
            "uso: genindex.py <radice telaio> <aniyomi|mihon> <modulo> <apk> <cartella di uscita>",
        )
    radice, ecosistema, modulo, apk, uscita = (
        pathlib.Path(sys.argv[1]),
        sys.argv[2],
        sys.argv[3],
        pathlib.Path(sys.argv[4]),
        pathlib.Path(sys.argv[5]),
    )
    impronta = (radice.parent / "scripts" / "fingerprint.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", impronta):
        raise SystemExit("genindex: l'impronta della chiave non è un SHA-256 in minuscolo")

    uscita.mkdir(parents=True, exist_ok=True)
    (uscita / "repo.json").write_text(
        json.dumps(
            {
                "meta": {
                    # ⚠️ Senza il nome dell'ecosistema: ogni app vede un repository solo,
                    # quindi un suffisso che distingua i due non disambigua nulla per chi legge
                    # e aggiunge rumore dove serve un nome.
                    "name": "Roccobot",
                    "website": "https://roccobot.github.io/mihon-aniyomi-ext/",
                    "signingKeyFingerprint": impronta,
                },
            },
            indent=2,
        )
        + "\n",
    )
    indice = [voce(radice, ecosistema, modulo, apk)]
    (uscita / "index.min.json").write_text(json.dumps(indice, separators=(",", ":")) + "\n")
    print(json.dumps(indice[0], indent=1))


if __name__ == "__main__":
    main()
