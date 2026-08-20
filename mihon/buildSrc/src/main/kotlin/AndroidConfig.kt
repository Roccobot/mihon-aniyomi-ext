object AndroidConfig {
    // ⚠️⚠️ NON scendere sotto il 34, e la ragione non è il compilatore ma il TELEFONO: Play
    // Protect blocca l'installazione con 'app non sicura' quando il target è più di due livelli
    // sotto l'API del dispositivo. Con 32 l'avviso scatta già su Android 15 (API 35), e chi
    // installa deve passare per 'installa comunque' ogni volta. Il 34 copre anche Android 16.
    // ⚠️ È anche il massimo che regge AGP 8.2.1: per il 35 servirebbe AGP 8.6+, cioè muovere
    // il telaio, e questo è il tetto raggiungibile senza toccare nient'altro.
    const val compileSdk = 34
    const val minSdk = 21
    const val targetSdk = 34

    // ⚠️ This is where the two ecosystems part company: a Mihon extension declares
    // `eu.kanade.tachiyomi.extension`, an Aniyomi one `...animeextension`. The app reads the
    // class from the manifest under a matching key, so a wrong namespace here produces an APK
    // that installs and is then never recognised as a source.
    const val namespace = "eu.kanade.tachiyomi.extension"
    const val coreNamespace = "eu.kanade.tachiyomi.lib.core"
    const val multisrcNamespace = "eu.kanade.tachiyomi.lib.themesources"
}
