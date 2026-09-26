package dev.ide.android.support.templates

import dev.ide.model.template.ProjectScaffold

/**
 * The Gradle wrapper binaries, bundled under `resources/gradle-wrapper/`.
 *
 * `gradlew` and `gradle-wrapper.jar` are binaries: a template can only emit them by writing bytes, and
 * a project that has the Gradle scripts but no wrapper cannot be built at all — `./gradlew` is how the
 * whole Android build is entered. Previously the only way they arrived was a compile-time repair that
 * shelled out to `flutter create` and copied whatever the SDK's own template happened to contain, which
 * meant a freshly created project had no wrapper until its first build, and the tool's wrapper-bind
 * fallback (which needs `android/gradlew` to already exist to stage an executable copy of) had nothing
 * to bind.
 *
 * So the wrapper ships with the IDE and is written at project-creation time, like the Gradle scripts.
 * The distribution it resolves is whatever `gradle/wrapper/gradle-wrapper.properties` asks for — the
 * jar is a launcher, not a pinned Gradle.
 *
 * Note the executable bit is deliberately not part of this: the wrapper usually lands on external
 * storage, and sdcardfs does not preserve it. Execution is handled by staging a copy on internal
 * storage and bind-mounting it over the project's `android/gradlew`, not by a mode bit here.
 */
object GradleWrapperAssets {

    private const val BASE = "/gradle-wrapper"

    /** `gradlew`, `gradlew.bat` and `gradle-wrapper.jar`, keyed by their path under `gradle/`. */
    private val FILES: Map<String, String> = mapOf(
        "gradlew" to "$BASE/gradlew",
        "gradlew.bat" to "$BASE/gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar" to "$BASE/gradle-wrapper.jar",
    )

    /** True when every bundled wrapper file is present on the classpath. */
    fun available(): Boolean = FILES.values.all { read(it) != null }

    /**
     * Write the wrapper into `<androidDir>` of [scaffold]. Returns the wrapper paths that were written,
     * empty when the assets are missing — a caller that needs a runnable wrapper should check
     * [available] first rather than assume success.
     */
    fun write(scaffold: ProjectScaffold, androidDir: String): List<String> {
        val written = mutableListOf<String>()
        for ((relPath, resource) in FILES) {
            val bytes = read(resource) ?: continue
            scaffold.writeBytes("$androidDir/$relPath", bytes)
            written += relPath
        }
        return written
    }

    private fun read(resource: String): ByteArray? =
        GradleWrapperAssets::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
}
