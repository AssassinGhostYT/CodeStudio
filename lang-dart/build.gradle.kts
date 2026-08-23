plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// lang-dart — the Dart & Flutter LanguageBackend.
// Provides tolerant parsing, syntax highlighting, completion, and Flutter widget support.
dependencies {
    api(project(":language-api"))
    implementation(project(":index-api"))
    implementation(project(":analysis-api"))
    implementation(project(":plugin-api"))
    implementation(project(":android-support"))
    testImplementation(libs.kotlinx.coroutines.test)
}
