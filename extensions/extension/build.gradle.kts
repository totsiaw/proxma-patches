extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "io.github.nai64.extension"
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // androidx.work is compileOnly on purpose: Simosa 3.3.5 already ships work-runtime (2.8+, its
    // ExistingPeriodicWorkPolicy has UPDATE) and auto-initialises it through androidx.startup, so
    // the app's own copy is what must load at runtime. Bundling a second copy into the extension
    // dex would shadow it (and add megabytes); compiling against it only types DailyCheckinWorker.
    compileOnly("androidx.work:work-runtime:2.9.0")
}
