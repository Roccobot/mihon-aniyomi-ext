object AndroidConfig {
    const val compileSdk = 32
    const val minSdk = 21
    const val targetSdk = 32

    // ⚠️ This is where the two ecosystems part company: a Mihon extension declares
    // `eu.kanade.tachiyomi.extension`, an Aniyomi one `...animeextension`. The app reads the
    // class from the manifest under a matching key, so a wrong namespace here produces an APK
    // that installs and is then never recognised as a source.
    const val namespace = "eu.kanade.tachiyomi.extension"
    const val coreNamespace = "eu.kanade.tachiyomi.lib.core"
    const val multisrcNamespace = "eu.kanade.tachiyomi.lib.themesources"
}
