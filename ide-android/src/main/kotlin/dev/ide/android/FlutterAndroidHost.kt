package dev.ide.android

import android.content.Context
import dev.ide.android.Terminal.TerminalSetupState
import dev.ide.android.Terminal.UbuntuRuntime
import dev.ide.lang.dart.FlutterHostRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Connects Flutter's Linux toolchain to the Ubuntu/proot terminal instead of Android's bionic linker.
 *
 * `flutter build apk` does not compile anything itself: it hands the project to Gradle, which needs a real JVM and
 * a real Android SDK. Neither ships in `ubuntu-base`, and the SDK Manager only ever installs framework *sources*
 * (see `SdkManagerService.installAndroidPackage`, which rejects every id that is not `sources;…`), so the SDK
 * directory the IDE knows about is not a directory the Flutter tool accepts. That is what produced
 * `[!] No Android SDK found. Try setting the ANDROID_HOME environment variable.`: the tool reads
 * `flutter config --android-sdk` *before* `ANDROID_HOME`, and an unusable configured value is not a fallback — it
 * goes straight to hunting for `aapt`/`adb` in `PATH` and gives up. This host therefore provisions the toolchain
 * inside the prefix (OpenJDK 17 + cmdline-tools + one platform, licenses accepted) and only then points the tool
 * at it, for the commands that actually shell out to Gradle.
 */
object FlutterAndroidHost {

    private const val FLUTTER_BIN = "/root/flutter/bin/flutter"

    /**
     * Google's Linux command-line tools. The zip ships `sdkmanager` as a shell script over a pure-Java classpath,
     * so it runs on arm64 inside the prefix; the build only needs the launcher, because AGP fetches the platform,
     * build-tools and NDK versions the project pins (see `android.builder.sdkDownload` below).
     */
    private const val CMDLINE_TOOLS_URL =
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

    /** Platform pre-fetched when the project pins no literal `compileSdk` (the `flutter.compileSdkVersion` case). */
    private const val DEFAULT_PLATFORM = "android-36"

    fun install(context: Context) {
        val appContext = context.applicationContext
        UbuntuRuntime.init(appContext)
        FlutterHostRunner.runner = { workspaceRoot, workingDir, isFlutter, args, log ->
            if (!isFlutter) -1 else withContext(Dispatchers.IO) {
                UbuntuRuntime.ensureReady { message -> log(message) }
                if (UbuntuRuntime.setup.value !is TerminalSetupState.Ready) {
                    log("No se pudo iniciar el entorno Ubuntu de Flutter.")
                    return@withContext -1
                }
                val command = buildCommand(resolveAndroidSdk(workspaceRoot), workingDir, args)
                UbuntuRuntime.runInPrefix(command, onOutput = log)
            }
        }
    }

    /**
     * Where Gradle should find the Android SDK.
     *
     * The prefix-local directory is the target: the rootfs already lives there and it is real app storage.
     * The SDK Manager's shared directory is only reused when it is a *complete* SDK (platform + build-tools) —
     * a sources-only directory would be accepted by the tool and then fail inside Gradle. The helper mirrors
     * `AndroidSdk.locateAndroidSdk`'s own validity test (`platform-tools` or accepted `licenses`), because that is
     * exactly what decides whether `flutter config --android-sdk` resolves or reports "No Android SDK found".
     */
    private fun resolveAndroidSdk(workspaceRoot: Path): Path {
        val shared = listOfNotNull(
            workspaceRoot.resolve(".platform/android-sdk"),
            workspaceRoot.parent?.resolve(".platform/android-sdk"),
            workspaceRoot.parent?.parent?.resolve(".platform/android-sdk"),
        )
        val guest = UbuntuRuntime.localToolchainDir("android-sdk").toPath()
        val reusable = (listOf(guest) + shared).firstOrNull { isCompleteSdk(it) }
        return reusable ?: guest
    }

    /** Usable by the Flutter tool: exactly what `AndroidSdk.validSdkDirectory` accepts. */
    private fun isUsableSdk(dir: Path): Boolean =
        Files.isDirectory(dir.resolve("platform-tools")) || Files.isDirectory(dir.resolve("licenses"))

    /** Usable by Gradle: a platform with `android.jar` AND build-tools, not just sources. */
    private fun isCompleteSdk(dir: Path): Boolean {
        if (!isUsableSdk(dir)) return false
        val platform = runCatching {
            Files.list(dir.resolve("platforms")).use { s ->
                s.filter { Files.isRegularFile(it.resolve("android.jar")) }.findFirst().orElse(null)
            }
        }.getOrNull() ?: return false
        val buildTools = runCatching {
            Files.list(dir.resolve("build-tools")).use { s -> s.findFirst().orElse(null) }
        }.getOrNull() ?: return false
        return Files.isRegularFile(buildTools.resolve("aapt2")) && Files.isRegularFile(platform)
    }

    /**
     * `flutter build apk|appbundle` and `flutter run` hand off to Gradle; `pub get`, `analyze`, `test` and
     * `compile` never do. Provisioning a JVM plus an SDK is hundreds of megabytes, so only the former pay for it.
     */
    private fun needsAndroidToolchain(args: List<String>): Boolean {
        val verb = args.firstOrNull() ?: return false
        if (verb == "run") return true
        if (verb != "build") return false
        val artifact = args.getOrNull(1)
        return artifact == "apk" || artifact == "appbundle"
    }

    private fun buildCommand(androidSdk: Path, workingDir: File, args: List<String>): String {
        val sdk = shellQuote(androidSdk.toString())
        val cwd = shellQuote(workingDir.absolutePath)
        val flutter = shellQuote(FLUTTER_BIN)
        val commandArgs = args.joinToString(" ") { shellQuote(it) }
        val needsAndroid = if (needsAndroidToolchain(args)) "true" else "false"
        return """
            set -e
            export CI=true
            export PUB_CACHE=/root/.pub-cache
            # A stray Gradle daemon would pin the prefix (and RAM) between builds; the panel expects one-shot runs.
            export GRADLE_OPTS='-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx2g'
            FLUTTER_BIN=$flutter
            ANDROID_SDK_PATH=$sdk
            NEEDS_ANDROID=$needsAndroid
            cd $cwd

            # ── Flutter SDK ───────────────────────────────────────────────────────────────────────────────
            if [ ! -x "${'$'}FLUTTER_BIN" ]; then
              echo 'Descargando Flutter SDK dentro de Ubuntu…'
              apt-get update
              DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates git curl xz-utils unzip zip
              clone_ok=false
              for attempt in 1 2 3; do
                rm -rf /root/flutter
                if git -c http.version=HTTP/1.1 clone --depth 1 --single-branch --branch stable https://github.com/flutter/flutter.git /root/flutter; then
                  clone_ok=true
                  break
                fi
                if [ "${'$'}attempt" -lt 3 ]; then
                  echo "Reintentando descarga del Flutter SDK (intento ${'$'}attempt/3)…"
                  sleep 3
                fi
              done
              if [ "${'$'}clone_ok" != true ]; then
                echo 'No se pudo descargar el Flutter SDK después de 3 intentos.' >&2
                exit 1
              fi
              ${'$'}FLUTTER_BIN config --no-analytics
            fi

            # ── Android toolchain (only for the commands that hand off to Gradle) ────────────────────────
            if [ "${'$'}NEEDS_ANDROID" = true ]; then
              # 1. A JVM. ubuntu-base ships none, and Gradle cannot start without one.
              if [ ! -x /usr/lib/jvm/java-17-openjdk-arm64/bin/java ] && ! command -v java >/dev/null 2>&1; then
                echo 'Instalando OpenJDK 17 para Gradle (primera vez, ~250 MB)…'
                apt-get update -qq || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-17-jdk-headless
              fi
              JDK_HOME=''
              for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/default-java; do
                if [ -x "${'$'}candidate/bin/java" ]; then
                  JDK_HOME=${'$'}candidate
                  break
                fi
              done
              if [ -z "${'$'}JDK_HOME" ] && command -v java >/dev/null 2>&1; then
                JDK_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
              fi
              if [ -z "${'$'}JDK_HOME" ] || [ ! -x "${'$'}JDK_HOME/bin/java" ]; then
                echo 'No hay ninguna JVM disponible en el entorno Ubuntu: Gradle no puede compilar el APK.' >&2
                exit 1
              fi
              export JAVA_HOME=${'$'}JDK_HOME
              export PATH="${'$'}JAVA_HOME/bin:${'$'}PATH"
              ${'$'}JAVA_HOME/bin/java -version

              # 2. An SDK the Flutter tool accepts. `licenses/` (written by --licenses) is the marker it looks
              #    for, and it is also what lets AGP install the remaining components unattended.
              if [ ! -d "${'$'}ANDROID_SDK_PATH/cmdline-tools/latest" ]; then
                echo 'Descargando las herramientas de línea de comandos del SDK de Android…'
                apt-get update -qq || true
                DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl unzip
                rm -rf /tmp/cs-cmdline-tools /tmp/cs-cmdline-tools.zip
                curl -fsSL --retry 3 -o /tmp/cs-cmdline-tools.zip '$CMDLINE_TOOLS_URL'
                unzip -q -o /tmp/cs-cmdline-tools.zip -d /tmp/cs-cmdline-tools
                rm -rf "${'$'}ANDROID_SDK_PATH/cmdline-tools/latest"
                mv /tmp/cs-cmdline-tools/cmdline-tools "${'$'}ANDROID_SDK_PATH/cmdline-tools/latest"
                rm -f /tmp/cs-cmdline-tools.zip
              fi
              SDKMANAGER="${'$'}ANDROID_SDK_PATH/cmdline-tools/latest/bin/sdkmanager"
              export ANDROID_HOME=${'$'}ANDROID_SDK_PATH
              export ANDROID_SDK_ROOT=${'$'}ANDROID_SDK_PATH
              export PATH="${'$'}ANDROID_SDK_PATH/cmdline-tools/latest/bin:${'$'}ANDROID_SDK_PATH/platform-tools:${'$'}PATH"
              if [ ! -d "${'$'}ANDROID_SDK_PATH/licenses" ]; then
                echo 'Aceptando las licencias del SDK de Android…'
                yes 2>/dev/null | "${'$'}SDKMANAGER" --sdk_root="${'$'}ANDROID_SDK_PATH" --licenses >/dev/null 2>&1 || true
              fi
              if [ ! -d "${'$'}ANDROID_SDK_PATH/licenses" ]; then
                echo "No se pudieron aceptar las licencias del SDK de Android en ${'$'}ANDROID_SDK_PATH." >&2
                exit 1
              fi

              # 3. One platform on disk, chosen the way the project asks for it. `flutter create` writes
              #    `compileSdk = flutter.compileSdkVersion`, i.e. no literal, so fall back to the newest already
              #    installed and finally to a recent default. Anything else the project pins is fetched by AGP.
              target=''
              for gradle_file in android/app/build.gradle.kts android/app/build.gradle; do
                if [ -f "${'$'}gradle_file" ]; then
                  literal=$(sed -n 's/.*compileSdk\(Version\)\{0,1\}[[:space:]]*\(=[[:space:]]*\)\{0,1\}\([0-9]\{2,3\}\).*/\3/p' "${'$'}gradle_file" | head -n1)
                  if [ -n "${'$'}literal" ]; then
                    target="android-${'$'}literal"
                    break
                  fi
                fi
              done
              if [ -z "${'$'}target" ]; then
                target=$(ls -1d "${'$'}ANDROID_SDK_PATH"/platforms/android-* 2>/dev/null | sed 's#.*/##' | sort -V | tail -n1)
              fi
              if [ -z "${'$'}target" ]; then
                target='$DEFAULT_PLATFORM'
              fi
              api=$(printf '%s' "${'$'}target" | sed 's/^android-//')
              if [ ! -f "${'$'}ANDROID_SDK_PATH/platforms/${'$'}target/android.jar" ]; then
                echo "Descargando la plataforma Android ${'$'}api y sus build-tools…"
                if ! "${'$'}SDKMANAGER" --sdk_root="${'$'}ANDROID_SDK_PATH" "platform-tools" "platforms;${'$'}target" "build-tools;${'$'}api.0.0"; then
                  echo "sdkmanager no pudo instalar platforms;${'$'}target en ${'$'}ANDROID_SDK_PATH." >&2
                  exit 1
                fi
              fi

              # 4. Gradle resolves the SDK from local.properties (flutter create never writes it) and only
              #    auto-downloads the components it still misses when sdkDownload is on.
              if [ -d android ]; then
                printf 'sdk.dir=%s\nflutter.sdk=%s\n' "${'$'}ANDROID_SDK_PATH" "${'$'}FLUTTER_BIN" > android/local.properties
                if [ -f android/gradle.properties ] && ! grep -q '^android.builder.sdkDownload=' android/gradle.properties; then
                  echo 'android.builder.sdkDownload=true' >> android/gradle.properties
                fi
              fi
            fi

            # ── Engine artifacts (Android only; `analyze`/`test` never need them) ──────────────────────────
            if [ "${'$'}NEEDS_ANDROID" = true ] && [ ! -f /root/flutter/.codestudio-android-precache ]; then
              echo 'Descargando Flutter Engine para Android…'
              precache_ok=false
              for attempt in 1 2 3; do
                if ${'$'}FLUTTER_BIN precache --android; then
                  precache_ok=true
                  break
                fi
                if [ "${'$'}attempt" -lt 3 ]; then
                  echo "Reintentando descarga del Engine (intento ${'$'}attempt/3)…"
                  sleep 3
                fi
              done
              if [ "${'$'}precache_ok" != true ]; then
                echo 'No se pudo descargar el Flutter Engine después de 3 intentos.' >&2
                exit 1
              fi
              touch /root/flutter/.codestudio-android-precache
            fi

            # Point the tool at the now-valid SDK. This is deliberately AFTER the bootstrap above: the setting
            # takes precedence over ANDROID_HOME and an unusable value is not a fallback.
            ${'$'}FLUTTER_BIN config --android-sdk "${'$'}ANDROID_SDK_PATH" >/dev/null 2>&1 || true

            # A project named "flutter" conflicts with Flutter's own SDK dependency in pubspec.yaml.
            if [ -f pubspec.yaml ] && grep -q '^name: flutter[[:space:]]*${'$'}' pubspec.yaml; then
              echo 'Renombrando el paquete Flutter reservado a flutter_app…'
              sed -i 's/^name: flutter[[:space:]]*${'$'}/name: flutter_app/' pubspec.yaml
              find test -type f -name '*.dart' -exec sed -i 's/package:flutter\/main\.dart/package:flutter_app\/main.dart/g' {} + 2>/dev/null || true
            fi

            # ── Legacy Android project repair ──────────────────────────────────────────────────────────────
            if [ "${'$'}NEEDS_ANDROID" = true ] && [ -d android ]; then
              # `flutter create` emits the meta-data across three lines, so a single-line grep never matches and
              # the migration below used to re-run `flutter create` on EVERY build, rewriting the project each
              # time. Compare on a whitespace-free copy instead.
              embedding_is_v2() {
                tr -d '\r\n\t ' < "${'$'}1" | grep -q 'android:name="flutterEmbedding"android:value="2"'
              }
              # Process substitution (not a pipe) so `exit 1` inside the loop really fails the build.
              while IFS= read -r -d '' manifest; do
                if grep -qE 'io\.flutter\.app\.(android\.)?(SplashScreenUntilFirstFrame|FlutterActivity|FlutterApplication)' "${'$'}manifest"; then
                  echo "Actualizando embedding Flutter: ${'$'}manifest"
                  sed -i '/io\.flutter\.app\.android\.SplashScreenUntilFirstFrame/{N;d;}' "${'$'}manifest"
                  sed -i 's/io\.flutter\.app\.FlutterActivity/io.flutter.embedding.android.FlutterActivity/g' "${'$'}manifest"
                  sed -i 's/android:name="io\.flutter\.app\.FlutterApplication"/android:name="${'$'}{applicationName}"/g' "${'$'}manifest"
                fi
                # Flutter classifies the project as embedding v1 when this marker is absent or set to 1.
                # Accept the spacing and self-closing variations produced by older templates.
                sed -E -i 's/(android:name="flutterEmbedding"[^>]*android:value=")1("|\x27)/\12\2/g' "${'$'}manifest"
                sed -i "s/android:name='flutterEmbedding' android:value='1'/android:name='flutterEmbedding' android:value='2'/g" "${'$'}manifest"
                if ! grep -q 'android:name="flutterEmbedding"' "${'$'}manifest"; then
                  sed -i '/<\/application>/i\        <meta-data android:name="flutterEmbedding" android:value="2" />' "${'$'}manifest"
                fi
              done < <(find ./android -name 'AndroidManifest.xml' -type f -print0 2>/dev/null)

              # Let Flutter repair any legacy Android project layout the manifest pass could not classify.
              main_manifest=./android/app/src/main/AndroidManifest.xml
              if [ ! -f "${'$'}main_manifest" ] || ! embedding_is_v2 "${'$'}main_manifest"; then
                echo 'Migrando la estructura Android del proyecto Flutter…'
                ${'$'}FLUTTER_BIN create --platforms=android --no-pub .
                if [ ! -f "${'$'}main_manifest" ] || ! embedding_is_v2 "${'$'}main_manifest"; then
                  echo 'flutter create no pudo actualizar la estructura Android del proyecto.' >&2
                  exit 1
                fi
                # `create` regenerated the module from its template: re-apply the pointers it just dropped.
                printf 'sdk.dir=%s\nflutter.sdk=%s\n' "${'$'}ANDROID_SDK_PATH" "${'$'}FLUTTER_BIN" > android/local.properties
                if [ -f android/gradle.properties ] && ! grep -q '^android.builder.sdkDownload=' android/gradle.properties; then
                  echo 'android.builder.sdkDownload=true' >> android/gradle.properties
                fi
              fi

              find . -path '*/android/app/src/main/*' -type f \( -name '*.kt' -o -name '*.java' \) -print0 2>/dev/null | while IFS= read -r -d '' source; do
                if grep -q 'io.flutter.app.' "${'$'}source"; then
                  echo "Actualizando API Flutter v1: ${'$'}source"
                  sed -i 's/io\.flutter\.app\.FlutterActivity/io\.flutter\.embedding\.android\.FlutterActivity/g' "${'$'}source"
                  sed -i '/import io\.flutter\.app\.FlutterApplication/d' "${'$'}source"
                fi
              done
            fi

            ${'$'}FLUTTER_BIN $commandArgs
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
