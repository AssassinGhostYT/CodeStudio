package dev.ide.android.support.templates

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a CodeStudio project as a **plain, standard project folder inside a normal `.zip`** — the same
 * shape Android Studio / AndroidIDE use — so any IDE (PC or mobile) can unzip it and open it directly.
 * No CodeStudio-specific package, no manifest.json, no `.platform/` metadata.
 *
 * Two cases:
 *  - the project already carries Gradle scripts (the `android-app` / `android-material-you` /
 *    `compose-app` templates) → the scripts and sources are packed verbatim (plus a `gradlew`-less
 *    wrapper descriptor); Android Studio opens it as-is.
 *  - a native project (`android-library`, the Compose sample games — only `module.toml` + sources) →
 *    a minimal, conventional Gradle skeleton is synthesized **inside the zip** (never touching the
 *    original project): root `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, the wrapper
 *    descriptor, and a `build.gradle.kts` per module (application vs library decided from its manifest,
 *    Kotlin/Compose detected from the sources).
 *
 * Everything that is not source-of-truth is dropped: `.platform/`, `module.toml`, caches, build outputs,
 * `.gradle/`, `build/`, keystores.
 */
object PortableProjectExport {

    /** Write [projectDir] as a portable project `.zip` at [out] and return [out]. */
    fun writeZip(projectDir: Path, out: Path): Path {
        val rootScripts = projectDir.resolve("settings.gradle").takeIf { Files.exists(it) }
            ?: projectDir.resolve("settings.gradle.kts").takeIf { Files.exists(it) }
        out.parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(out)).use { zip ->
            if (rootScripts != null) {
                packProjectFiles(zip, projectDir)
            } else {
                val modules = moduleDirs(projectDir)
                val skeleton = Skeleton(projectName(projectDir), modules.map { ModuleModel.from(projectDir, it) })
                skeleton.write(zip)
                packProjectFiles(zip, projectDir, excludeModuleToml = true)
            }
        }
        return out
    }

    /** Source-of-truth files under [projectDir], sorted, excluding anything regenerable or host-specific. */
    private fun packProjectFiles(zip: ZipOutputStream, projectDir: Path, excludeModuleToml: Boolean = false) {
        Files.walk(projectDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { !isSourceOfTruthExcluded(projectDir.relativize(it).toString().replace(File.separatorChar, '/'), it.fileName.toString(), excludeModuleToml) }
                .sorted()
                .forEach { file ->
                    val rel = projectDir.relativize(file).toString().replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry(rel))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
        }
    }

    private fun isSourceOfTruthExcluded(rel: String, fileName: String, excludeModuleToml: Boolean): Boolean {
        if (excludeModuleToml && fileName == "module.toml") return true
        if (fileName == "android.jar" || fileName == "debug.keystore") return true
        val segments = rel.split('/')
        return segments.any { it == ".platform" || it == ".gradle" || it == "build" || it == "exports" ||
            it == ".scratch" || it == ".idea" || it == ".git" || it == "caches" }
    }

    /** Candidate module dirs: top-level folders holding a manifest (`src/main/AndroidManifest.xml`) or a
     *  `module.toml`. Falls back to `app` when nothing hints a module layout. */
    private fun moduleDirs(projectDir: Path): List<String> {
        val dirs = Files.list(projectDir).use { s ->
            s.filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .collect(Collectors.toList())
        }
            .filter { dir ->
                val module = projectDir.resolve(dir)
                Files.isRegularFile(module.resolve("src/main/AndroidManifest.xml")) ||
                    Files.isRegularFile(module.resolve("module.toml"))
            }
        if (dirs.isNotEmpty()) return dirs.sorted().let { list ->
            // Put the conventional app module first.
            list.sortedBy { if (it == "app") -1 else if (it == "lib") 1 else 0 }
        }
        return listOf("app")
    }

    private fun projectName(projectDir: Path): String {
        val name = projectDir.fileName?.toString()?.takeIf { it.isNotBlank() } ?: "project"
        return name.ifBlank { "project" }
    }

    private class ModuleModel(val dir: String, val namespace: String, val isApp: Boolean, val kotlin: Boolean, val compose: Boolean) {
        companion object {
            fun from(projectDir: Path, dir: String): ModuleModel {
                val root = projectDir.resolve(dir)
                val manifest = root.resolve("src/main/AndroidManifest.xml")
                val manifestText = if (Files.isRegularFile(manifest)) runCatching { Files.readString(manifest) }.getOrNull() ?: "" else ""
                val namespace = manifestPackage(manifestText)
                    ?: firstSourcePackage(root)
                    ?: sanitizePackageName(dir)
                val isApp = manifestText.contains("<application")
                val kotlin = hasExtension(root, ".kt")
                val compose = hasExtension(root, ".kt") && anyFileContains(root, ".kt", "androidx.compose")
                return ModuleModel(dir, namespace, isApp, kotlin, compose)
            }
        }
    }

    /** The classic Gradle skeleton for an otherwise-native project — scripts written straight into the zip. */
    private class Skeleton(val projectName: String, val modules: List<ModuleModel>) {
        fun write(zip: ZipOutputStream) {
            putText(zip, "settings.gradle.kts", settings)
            putText(zip, "build.gradle.kts", rootBuild)
            putText(zip, "gradle.properties", gradleProperties)
            putText(zip, "gradle/wrapper/gradle-wrapper.properties", wrapperProperties)
            for (m in modules) putText(zip, "${m.dir}/build.gradle.kts", moduleBuild(m))
        }

        private val settings: String
            get() {
                val includes = modules.joinToString(", ") { "\"${it.dir}\"" }
                return """
                    import org.gradle.api.initialization.resolve.RepositoriesMode

                    pluginManagement {
                        repositories {
                            google()
                            mavenCentral()
                            gradlePluginPortal()
                        }
                    }

                    dependencyResolutionManagement {
                        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                        repositories {
                            google()
                            mavenCentral()
                        }
                    }

                    rootProject.name = "$projectName"
                    include($includes)
                """.trimIndent()
            }

        private val rootBuild: String
            get() = """
                // Top-level build file where you can add configuration options common to all sub-projects/modules.
                plugins {
                    id("com.android.application") version "${GradleScaffold.AGP_VERSION}" apply false
                    id("com.android.library") version "${GradleScaffold.AGP_VERSION}" apply false
                    id("org.jetbrains.kotlin.android") version "${GradleScaffold.KGP_VERSION}" apply false
                }

                tasks.register<Delete>("clean") {
                    delete(rootProject.layout.buildDirectory)
                }
            """.trimIndent()

        private val gradleProperties: String
            get() = """
                # Project-wide Gradle settings.
                org.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8
                # When configured, Gradle will run in incubating parallel mode.
                org.gradle.parallel=true
                android.useAndroidX=true
                android.nonTransitiveRClass=true
                kotlin.code.style=official
            """.trimIndent()

        private val wrapperProperties: String
            get() = """
                # Gradle wrapper configuration — the distribution the wrapper would download.
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=https\\://services.gradle.org/distributions/gradle-${GradleScaffold.GRADLE_DISTRIBUTION}-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
            """.trimIndent()

        private fun moduleBuild(m: ModuleModel): String {
            val sb = StringBuilder()
            fun line(indent: Int, text: String) { sb.append(" ".repeat(indent)).append(text).append('\n') }
            fun blank() = sb.append('\n')

            line(0, "plugins {")
            line(4, if (m.isApp) "id(\"com.android.application\")" else "id(\"com.android.library\")")
            if (m.kotlin) line(4, "id(\"org.jetbrains.kotlin.android\")")
            line(0, "}")
            blank()

            line(0, "android {")
            line(4, "namespace = \"${m.namespace}\"")
            line(4, "compileSdk = 36")
            blank()
            if (m.isApp) {
                line(4, "defaultConfig {")
                line(8, "applicationId = \"${m.namespace}\"")
                line(8, "minSdk = 21")
                line(8, "targetSdk = 35")
                line(8, "versionCode = 1")
                line(8, "versionName = \"1.0\"")
                line(4, "}")
                blank()
                line(4, "buildTypes {")
                line(8, "release {")
                line(12, "isMinifyEnabled = false")
                line(12, "proguardFiles(")
                line(16, "getDefaultProguardFile(\"proguard-android-optimize.txt\"),")
                line(16, "\"proguard-rules.pro\",")
                line(12, ")")
                line(8, "}")
                line(4, "}")
                blank()
            }
            line(4, "compileOptions {")
            line(8, "sourceCompatibility = JavaVersion.VERSION_17")
            line(8, "targetCompatibility = JavaVersion.VERSION_17")
            line(4, "}")
            if (m.kotlin) {
                line(4, "kotlinOptions {")
                line(8, "jvmTarget = \"17\"")
                line(4, "}")
            }
            line(4, "buildFeatures {")
            line(8, if (m.compose) "compose = true" else "viewBinding = true")
            line(4, "}")
            if (m.compose) {
                line(4, "composeOptions {")
                line(8, "kotlinCompilerExtensionVersion = \"${GradleScaffold.COMPOSE_COMPILER_EXTENSION}\"")
                line(4, "}")
            }
            line(0, "}")
            blank()

            line(0, "dependencies {")
            if (m.compose) {
                line(4, "val composeBom = platform(\"androidx.compose:compose-bom:2024.05.00\")")
                line(4, "implementation(composeBom)")
                line(4, "implementation(\"androidx.compose.ui:ui\")")
                line(4, "implementation(\"androidx.compose.material3:material3\")")
                line(4, "implementation(\"androidx.activity:activity-compose:1.9.0\")")
                line(4, "debugImplementation(\"androidx.compose.ui:ui-tooling\")")
            } else {
                line(4, "implementation(\"androidx.core:core-ktx:1.12.0\")")
                line(4, "implementation(\"androidx.appcompat:appcompat:1.6.1\")")
                line(4, "implementation(\"com.google.android.material:material:1.12.0\")")
                line(4, "implementation(\"androidx.constraintlayout:constraintlayout:2.1.4\")")
            }
            line(4, "testImplementation(\"junit:junit:4.13.2\")")
            line(0, "}")
            blank()
            return sb.toString()
        }
    }

    // --- helpers ---

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    /** The `package` attribute of a `<manifest>`, or null. */
    private fun manifestPackage(manifest: String): String? =
        Regex("""package\s*=\s*"([\w.]+)""").find(manifest)?.groupValues?.get(1)

    /** Package inferred from the first .kt/.java file under src/main/{kotlin,java}, or null. */
    private fun firstSourcePackage(module: Path): String? {
        val srcRoot = module.resolve("src/main")
        val base = when {
            Files.isDirectory(srcRoot.resolve("kotlin")) -> srcRoot.resolve("kotlin")
            Files.isDirectory(srcRoot.resolve("java")) -> srcRoot.resolve("java")
            else -> return null
        }
        Files.walk(base).use { s ->
            val first = s.filter { Files.isRegularFile(it) && (it.fileName.toString().endsWith(".kt") || it.fileName.toString().endsWith(".java")) }
                .sorted()
                .findFirst()
                .orElse(null) ?: return null
            val rel = base.relativize(first.parent)
            if (rel.parent == null) return null
            val pkg = rel.toString().replace(File.separatorChar, '.').trim('.')
            return if (pkg.matches(Regex("[a-zA-Z_][\\w.]*"))) pkg else null
        }
    }

    private fun sanitizePackageName(name: String): String {
        val cleaned = name.lowercase().replace(Regex("[^a-z0-9.]"), "").trim('.')
        return if (cleaned.isBlank()) "com.example.project" else cleaned
    }

    private fun hasExtension(module: Path, ext: String): Boolean {
        if (!Files.isDirectory(module.resolve("src"))) return false
        Files.walk(module.resolve("src")).use { s ->
            if (s.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(ext) }) return true
        }
        return false
    }

    private fun anyFileContains(module: Path, ext: String, needle: String): Boolean {
        if (!Files.isDirectory(module.resolve("src"))) return false
        Files.walk(module.resolve("src")).use { s ->
            return s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(ext) }
                .anyMatch { f -> runCatching { Files.readString(f).contains(needle) }.getOrDefault(false) }
        }
    }
}