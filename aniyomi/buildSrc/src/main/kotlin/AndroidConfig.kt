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
    const val namespace = "eu.kanade.tachiyomi.animeextension"
    const val coreNamespace = "eu.kanade.tachiyomi.lib.core"
    const val multisrcNamespace = "eu.kanade.tachiyomi.lib.themesources"
}
