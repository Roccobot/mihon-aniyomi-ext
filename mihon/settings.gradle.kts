apply(from = "repositories.gradle.kts")

// Single-extension repository: no chunking, no theme modules. The upstream project discovers
// modules by scanning `src/`, which is worth its complexity with hundreds of sources; here an
// explicit list is shorter and says what is built.
include(":core")
include(":src:en:nhentai")
