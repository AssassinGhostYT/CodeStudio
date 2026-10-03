import java.io.File
import org.gradle.api.tasks.bundling.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// android-support — the Android plugin. It is a pure kotlin("jvm") module with NO
// Android SDK on its own compile classpath: it contributes the `AndroidFacet`, the android-app/-lib
// module types, the variant model (build types x product flavors), and the native Android build system
// (the aapt2 -> R/compile -> D8 dex -> package -> sign task DAG) over build-engine's generic engine.
// The actual SDK tools (aapt2/zipalign native; D8/apksigner pure-Java) are invoked at *runtime* via
// subprocess behind injectable ports, so the core never links android.jar.
dependencies {
    api(project(":build-api"))                 // BuildSystem SPI + Task engine contracts (brings project-model-api)
    implementation(project(":build-engine"))   // TaskGraphImpl / TaskInputsImpl / TaskOutputsImpl + neutral tasks
    implementation(project(":jvm-build"))       // JavaPlugin.registerModule for plain java-lib modules in an Android project
    implementation(project(":lang-jdt"))        // JdtBatchCompiler — the Android compile tasks drive ecj directly
    implementation(project(":lang-kotlin"))     // IncrementalKotlinCompiler + ComposeCompilerPlugin — drive K2 directly
    implementation(project(":project-model-impl")) // FacetCodec / FacetCodecRegistry / ModuleTypeRegistry
    implementation(project(":language-api"))   // SyntheticClassProvider — the light `R` class for completion/analysis
    implementation(project(":lang-xml"))        // XmlTreeParser — error-tolerant PSI recovery for a malformed values file
    implementation(project(":index-api"))       // resource-declaration IndexExtension
    implementation(libs.ow2.asm)                 // ClassReader — scan the classpath for custom View subclasses (no class loading)
    implementation(libs.kotlinx.coroutines.core)

    // D8 (r8) + apksigner (apksig) are invoked IN-PROCESS by the on-device wiring, so they're statically
    // linked where needed. Here they're compileOnly — the in-process impls compile against the API, but
    // hosts that only use the subprocess wiring (desktop) don't drag the ~megabytes in. The Android
    // launcher adds them as `implementation` so they dex into the app and run on ART.
    compileOnly(libs.android.r8)
    compileOnly(libs.android.apksig)
    // bundletool builds the .aab in-process (BundletoolInProcess). Not an SDK build-tool, so it is bundled
    // by the hosts that produce bundles (:ide-android, :ide-desktop); here it is compileOnly + test. Its deps
    // are runtime-scoped in its POM, so guava (ImmutableList) must be added explicitly for compilation.
    compileOnly(libs.android.bundletool)
    compileOnly(libs.guava)
    // Bouncy Castle generates a keypair + self-signed cert for in-process keystore creation (no keytool on
    // ART). compileOnly here (only KeystoreCrypto.create touches it); the launchers bundle it at runtime.
    compileOnly(libs.bouncycastle.pkix)

    // The end-to-end APK test compiles real Java (R.java + an Activity) through the JDT batch compiler
    // (lang-jdt, now a main dependency) and exercises the in-process tools.
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.android.r8)
    testImplementation(libs.android.apksig)
    testImplementation(libs.android.bundletool)
    testImplementation(libs.bouncycastle.pkix) // KeystoreCrypto.create round-trip test runs on the desktop JVM
}

// The core-library-desugaring test needs the desugar runtime + config jars as real files (L8 dexes the
// runtime; D8/R8 read the config). Resolve them into a dedicated configuration and hand the paths to the
// test JVM, so it does not depend on a particular Gradle-cache layout.
val desugarLibTest: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies {
    desugarLibTest(libs.desugar.jdk.libs)
    desugarLibTest(libs.desugar.jdk.libs.configuration)
}

// AppLogInjectTest needs the compiled :applog-runtime jar as a real file to hand to the build system as the
// injectable log-bridge runtime. Resolve the module's default artifact into its own configuration (its only
// dependency is the compileOnly android stub, which does not reach this runtime classpath, so this is just
// the tiny runtime jar).
val appLogRuntimeTest: Configuration by configurations.creating { isCanBeConsumed = false }
dependencies {
    appLogRuntimeTest(project(":applog-runtime"))
}

tasks.test {
    val desugarFiles = desugarLibTest
    val appLogFiles = appLogRuntimeTest
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Ddesugar.lib.path=${desugarFiles.asPath}",
            "-Dapplog.runtime.jar=${appLogFiles.asPath}",
        )
    })
}

// --- Bundled plugin SPI (offline Maven repository) ------------------------------------------------
// A project scaffolded by CodeStudioPluginTemplate declares `compileOnly("dev.ide:plugin-api:1.0.0")`. The
// IDE resolves dependencies itself (there is no real Gradle on device) against repositories it cannot be
// assumed to reach offline, which surfaced as "Couldn't reach the repositories" when building a plugin.
// So the SPI ships INSIDE the app: plugin-api and platform-core are staged here, as a Maven-layout
// repository under this module's classpath resources, and the engine's resolver serves it before the
// network (see BundledSpiArtifactFetcher). Keep the coordinates/version in sync with plugin-api /
// platform-core build files and PLUGIN_SPI_VERSION.
val pluginApiJar = project(":plugin-api").tasks.named<Jar>("jar")
val platformCoreJar = project(":platform-core").tasks.named<Jar>("jar")

val pluginApiPom = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>
      <groupId>dev.ide</groupId>
      <artifactId>plugin-api</artifactId>
      <version>1.0.0</version>
      <packaging>jar</packaging>
      <dependencies>
        <dependency>
          <groupId>dev.ide</groupId>
          <artifactId>platform-core</artifactId>
          <version>1.0.0</version>
          <scope>compile</scope>
        </dependency>
      </dependencies>
    </project>
""".trimIndent()

val platformCorePom = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project xmlns="http://maven.apache.org/POM/4.0.0"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
      <modelVersion>4.0.0</modelVersion>
      <groupId>dev.ide</groupId>
      <artifactId>platform-core</artifactId>
      <version>1.0.0</version>
      <packaging>jar</packaging>
    </project>
""".trimIndent()

val bundlePluginSpiRepo = tasks.register("bundlePluginSpiRepo") {
    description = "Stage the plugin SPI (plugin-api + platform-core) as a Maven repo under android-support resources."
    dependsOn(pluginApiJar, platformCoreJar)
    val outDir = layout.buildDirectory.dir("plugin-spi-repo")
    inputs.files(pluginApiJar, platformCoreJar)
    outputs.dir(outDir)
    doLast {
        val root = outDir.get().asFile.resolve("spi-repo")
        fun stage(group: String, artifact: String, version: String, jar: File, pom: String) {
            val dir = root.resolve("${group.replace('.', '/')}/$artifact/$version")
            dir.mkdirs()
            jar.copyTo(dir.resolve("$artifact-$version.jar"), overwrite = true)
            dir.resolve("$artifact-$version.pom").writeText(pom)
        }
        stage("dev.ide", "plugin-api", "1.0.0", pluginApiJar.get().archiveFile.get().asFile, pluginApiPom)
        stage("dev.ide", "platform-core", "1.0.0", platformCoreJar.get().archiveFile.get().asFile, platformCorePom)
    }
}

// The staged repo becomes part of android-support's resources, hence of the app's classpath resources.
sourceSets.named("main") { resources.srcDir(bundlePluginSpiRepo) }
