package dev.ide.android

import android.content.Context
import dev.ide.android.Terminal.TerminalSetupState
import dev.ide.android.Terminal.UbuntuRuntime
import dev.ide.lang.dart.FlutterHostRunner
import dev.ide.platform.log.Log
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
    private const val TAG = "FlutterAndroidHost"

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
                val extraBinds = extraBinds(workingDir)
                val command = buildCommand(resolveAndroidSdk(workspaceRoot), workingDir, args, extraBinds)
                UbuntuRuntime.runInPrefix(command, onOutput = log, extraBinds = extraBinds)
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

    private fun buildCommand(androidSdk: Path, workingDir: File, args: List<String>, extraBinds: String): String {
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
                mkdir -p "${'$'}ANDROID_SDK_PATH/cmdline-tools"
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
            if [ "${'$'}NEEDS_ANDROID" = true ]; then
              # `flutter_tools/lib/src/project.dart::computeEmbeddingVersion` refuses the build ("Build failed due
              # to use of deleted Android v1 embedding.") unless the main manifest carries
              # <meta-data android:name="flutterEmbedding" android:value="2"/> and does NOT declare
              # <application android:name="io.flutter.app.FlutterApplication"> — and it tests the application
              # element FIRST, then takes the FIRST flutterEmbedding meta-data it finds. Mirror that order
              # here, tolerating attribute order, quote style and line breaks (the tool parses XML; a flat grep
              # on a template that splits the attributes over three lines never matched, so `flutter create` used
              # to re-run on every build). A missing manifest is v1 too, which is why a project that has no
              # android/ directory at all is regenerated here instead of being skipped.
              # A faithful port of flutter_tools/lib/src/project.dart::computeEmbeddingVersion. Everything it
              # decides has to be reproduced exactly, because a disagreement here is invisible: the tool throws
              # the bare "Build failed due to use of deleted Android v1 embedding." without its reason.
              #
              #   * the file it reads is NOT fixed. `appManifestFile` is app/src/main/AndroidManifest.xml only when
              #     android/build.gradle(.kts) exists (hostAppGradleFile -> isUsingGradle); without it the tool is
              #     in the legacy layout and reads android/AndroidManifest.xml. Reading only the former is how a
              #     perfectly valid project gets reported v1 by the tool and v2 by this script.
              #   * document.findAllElements + getAttribute, not a grep: an <application> anywhere in the file with
              #     android:name="io.flutter.app.FlutterApplication" loses to any flutterEmbedding meta-data, and
              #     only the FIRST flutterEmbedding meta-data counts (1 loses, 2 wins, any other value keeps
              #     scanning). Attribute order, quote style and line breaks are irrelevant to the tool.
              #   * a missing manifest is v1.
              tool_manifest() {
                if [ -f android/build.gradle ] || [ -f android/build.gradle.kts ]; then
                  printf '%s' 'android/app/src/main/AndroidManifest.xml'
                else
                  printf '%s' 'android/AndroidManifest.xml'
                fi
              }
              # One line per element, in document order. The line breaks have to go BEFORE splitting on `<`:
              # the templates write <application> across several lines, and splitting on `<` alone leaves the tag
              # cut in half (which silently hides every attribute).
              manifest_elements() {
                tr -d '\r\n' < "${'$'}1" | tr '<' '\n' | grep -E "^${'$'}2([ />])" || true
              }
              attr_value() {
                printf '%s' "${'$'}1" | grep -o "${'$'}2[[:space:]]*=[[:space:]]*[\"'][^\"']*[\"']" | head -n1 | sed -E "s|^[^=]*=[[:space:]]*[\"']||; s|[\"']\$||"
              }
              # Prints the reason the tool would give; 0 = v2, 1 = v1.
              embedding_version() {
                manifest=${'$'}1
                if [ ! -f "${'$'}manifest" ]; then
                  echo "No \`${'$'}manifest\` file"
                  return 1
                fi
                while IFS= read -r element; do
                  if [ "$(attr_value "${'$'}element" 'android:name')" = 'io.flutter.app.FlutterApplication' ]; then
                    echo "${'$'}manifest uses \`android:name=\"io.flutter.app.FlutterApplication\"\`"
                    return 1
                  fi
                done < <(manifest_elements "${'$'}manifest" application)
                while IFS= read -r element; do
                  if [ "$(attr_value "${'$'}element" 'android:name')" = 'flutterEmbedding' ]; then
                    value=$(attr_value "${'$'}element" 'android:value')
                    if [ "${'$'}value" = '1' ]; then
                      echo "${'$'}manifest \`<meta-data android:name=\"flutterEmbedding\"\` has value 1"
                      return 1
                    fi
                    if [ "${'$'}value" = '2' ]; then
                      echo "${'$'}manifest \`<meta-data android:name=\"flutterEmbedding\"\` has value 2"
                      return 0
                    fi
                  fi
                done < <(manifest_elements "${'$'}manifest" meta-data)
                echo "No \`<meta-data android:name=\"flutterEmbedding\" android:value=\"2\"/>\` in ${'$'}manifest"
                return 1
              }
              manifest_is_v2() { embedding_version "${'$'}1" >/dev/null; }
              # Why the manifest was rejected — a bare tool exit is not diagnosable from the build log.
              manifest_diagnose() {
                manifest=${'$'}1
                if [ -f android/build.gradle ] || [ -f android/build.gradle.kts ]; then
                  echo "  layout: Gradle (el tool lee ${'$'}manifest)"
                else
                  echo "  layout: LEGACY, sin android/build.gradle[.kts] (el tool lee ${'$'}manifest)"
                fi
                if [ ! -f "${'$'}manifest" ]; then
                  echo "  ${'$'}manifest no existe"
                  return 0
                fi
                while IFS= read -r element; do
                  echo "  application: ${'$'}element"
                done < <(manifest_elements "${'$'}manifest" application)
                while IFS= read -r element; do
                  if [ "$(attr_value "${'$'}element" 'android:name')" = 'flutterEmbedding' ]; then
                    echo "  meta-data: ${'$'}element"
                  fi
                done < <(manifest_elements "${'$'}manifest" meta-data)
                echo "  veredicto: $(embedding_version "${'$'}manifest")"
              }
              # The passes below rewrite the manifests in place, and they can leave XML that no longer parses
              # (removing the two-line v1 SplashScreen meta-data also swallows the line after it, which in the
              # v1 template is `</application>`). A project we end up abandoning must be handed back exactly as
              # it came in, not in a worse state than we found it.
              find ./android -name 'AndroidManifest.xml' -type f -exec cp -p {} {}.flutterbak \; 2>/dev/null || true
              restore_manifests() {
                restored=0
                while IFS= read -r -d '' backup; do
                  mv -f "${'$'}backup" "${'$'}{backup%.flutterbak}"
                  restored=$((restored + 1))
                done < <(find ./android -name 'AndroidManifest.xml.flutterbak' -type f -print0 2>/dev/null)
                if [ "${'$'}restored" -gt 0 ]; then
                  echo "  restaurados ${'$'}restored AndroidManifest.xml originales" >&2
                fi
              }
              while IFS= read -r -d '' manifest; do
                if grep -qE 'io\.flutter\.app\.(android\.)?(SplashScreenUntilFirstFrame|FlutterActivity|FlutterApplication)' "${'$'}manifest"; then
                  echo "Actualizando embedding Flutter: ${'$'}manifest"
                  sed -i '/io\.flutter\.app\.android\.SplashScreenUntilFirstFrame/{N;d;}' "${'$'}manifest"
                  sed -i 's/io\.flutter\.app\.FlutterActivity/io.flutter.embedding.android.FlutterActivity/g' "${'$'}manifest"
                  sed -i "s/android:name='io\.flutter\.app\.FlutterApplication'/android:name=\"\${'$'}{applicationName}\"/g" "${'$'}manifest"
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
              main_manifest=$(tool_manifest)
              if ! manifest_is_v2 "${'$'}main_manifest"; then
                echo 'Migrando la estructura Android del proyecto Flutter…'
                manifest_diagnose "${'$'}main_manifest"
                ${'$'}FLUTTER_BIN create --platforms=android --no-pub .
                if ! manifest_is_v2 "${'$'}main_manifest"; then
                  echo 'flutter create no dejó el proyecto en embedding v2:' >&2
                  manifest_diagnose "${'$'}main_manifest" >&2
                  restore_manifests
                  exit 1
                fi
                # `create` regenerated the module from its template: re-apply the pointers it just dropped.
                mkdir -p android
                printf 'sdk.dir=%s\nflutter.sdk=%s\n' "${'$'}ANDROID_SDK_PATH" "${'$'}FLUTTER_BIN" > android/local.properties
                if [ -f android/gradle.properties ] && ! grep -q '^android.builder.sdkDownload=' android/gradle.properties; then
                  echo 'android.builder.sdkDownload=true' >> android/gradle.properties
                fi
              fi
              # Reached only when the manifest pass converged; a failure above already restored the originals.
              find ./android -name 'AndroidManifest.xml.flutterbak' -type f -delete 2>/dev/null || true

              find . -path '*/android/app/src/main/*' -type f \( -name '*.kt' -o -name '*.java' \) -print0 2>/dev/null | while IFS= read -r -d '' source; do
                if grep -q 'io.flutter.app.' "${'$'}source"; then
                  echo "Actualizando API Flutter v1: ${'$'}source"
                  sed -i 's/io\.flutter\.app\.FlutterActivity/io\.flutter\.embedding\.android\.FlutterActivity/g' "${'$'}source"
                  sed -i '/import io\.flutter\.app\.FlutterApplication/d' "${'$'}source"
                fi
              done
            fi

            # ── The Gradle wrapper has to be executable ─────────────────────────────────────────────────
            # The tool spawns <project>/android/gradlew directly, and a spawn returning EACCES/EPERM is
            # reported as the very unhelpful "Flutter failed to run .... Please ensure that the SDK and/or
            # project is installed in a location that has read/write permissions for the current user" —
            # which reads like a permissions problem and is not one. Two causes, very different:
            #   * the mode bit was lost (a zip import, an extraction through the document picker, or a copy
            #     that did not carry the permission), which chmod fixes;
            #   * the project sits on app-specific EXTERNAL storage, which stores regular files without POSIX
            #     permission bits — chmod is accepted and silently ignored. The host detects that and maps an
            #     executable copy from internal storage over the wrapper (EXTRA_BINDS), so under proot the
            #     wrapper already reads as executable here and needs no help.
            if [ "${'$'}NEEDS_ANDROID" = true ] && [ -f android/gradlew ] && [ ! -x android/gradlew ]; then
              echo 'Restaurando el permiso de ejecucion de android/gradlew'
              chmod +x android/gradlew || true
            fi
            if [ "${'$'}NEEDS_ANDROID" = true ] && [ -f android/gradlew ]; then
              if [ -x android/gradlew ]; then
                if [ -n "${'$'}EXTRA_BINDS" ]; then
                  echo '  almacenamiento externo: usando la copia ejecutable de gradlew del host'
                fi
              else
                echo 'AVISO: android/gradlew no se puede ejecutar y el host no tiene copia alternativa.' >&2
                echo '       El tool va a fallar al lanzarlo; revisa el espacio libre de la app' >&2
                echo '       o vuelve a importar el proyecto desde un zip.' >&2
              fi
            fi

            ${'$'}FLUTTER_BIN $commandArgs
            exit ${'$'}?
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * A proot bind that makes the project's `android/gradlew` executable when the project filesystem will not
     * keep the mode bit.
     *
     * Projects live in app-specific *external* storage so the Files app can browse them without All-Files-Access
     * (see [AndroidIde]). That filesystem stores regular files without POSIX permission bits: `chmod +x` is
     * accepted and silently ignored, and the tool — which spawns the wrapper directly rather than through a
     * shell — then fails with EACCES/EPERM, reported as the misleading "Flutter failed to run …. Please ensure
     * that the SDK and/or project is installed in a location that has read/write permissions".
     *
     * So stage the wrapper in the prefix (internal storage, where the bit sticks) and map it over the project's
     * copy. proot resolves the path to the staged file; argv[0] still carries the project path, so the wrapper
     * keeps finding `gradle/wrapper/gradle-wrapper.jar` beside itself. Nothing on the project changes.
     */
    private fun extraBinds(workingDir: File): String {
        val wrapper = File(workingDir, "android/gradlew")
        if (!wrapper.isFile || wrapper.canExecute()) return ""
        return runCatching {
            val staged = UbuntuRuntime.localToolchainDir("flutter/gradlew")
            wrapper.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
            staged.setExecutable(true, false)
            if (staged.canExecute()) "${staged.absolutePath}:${wrapper.absolutePath}" else ""
        }.getOrElse {
            // warn, not error: an ERROR carrying a throwable surfaces the host's critical-error dialog, and
            // failing to stage the wrapper is not critical — the build reports it itself, on the build log.
            Log.logger(TAG).warn("no se pudo preparar android/gradlew ejecutable", it)
            ""
        }
    }
}
