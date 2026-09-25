package dev.ide.android

import android.content.Context
import dev.ide.android.Terminal.UbuntuRuntime
import dev.ide.lang.dart.FlutterHostRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.nio.file.Files

/** Connects Flutter's Linux toolchain to the Ubuntu/proot terminal instead of Android's bionic linker. */
object FlutterAndroidHost {
    fun install(context: Context) {
        val appContext = context.applicationContext
        UbuntuRuntime.init(appContext)
        FlutterHostRunner.runner = { workspaceRoot, workingDir, isFlutter, args, log ->
            if (!isFlutter) -1 else withContext(Dispatchers.IO) {
                var ready = false
                UbuntuRuntime.ensureReady { message -> log(message) }
                ready = UbuntuRuntime.setup.value is dev.ide.android.Terminal.TerminalSetupState.Ready
                if (!ready) {
                    log("No se pudo iniciar el entorno Ubuntu de Flutter.")
                    return@withContext -1
                }
                // SDK Manager stores the Android SDK in the shared app root, while the project itself lives
                // below <shared-root>/projects/<name>. Accept both layouts so old and new workspaces work.
                val sdkCandidates = listOf(
                    workspaceRoot.resolve(".platform/android-sdk"),
                    workspaceRoot.parent?.resolve(".platform/android-sdk"),
                    workspaceRoot.parent?.parent?.resolve(".platform/android-sdk"),
                ).filterNotNull()
                val androidSdk = sdkCandidates.firstOrNull { Files.isDirectory(it) } ?: sdkCandidates.last()
                val command = buildCommand(androidSdk, workingDir, args)
                UbuntuRuntime.runInPrefix(command, onOutput = log)
            }
        }
    }

    private fun buildCommand(androidSdk: Path, workingDir: File, args: List<String>): String {
        val sdk = shellQuote(androidSdk.toString())
        val cwd = shellQuote(workingDir.absolutePath)
        val flutter = "/root/flutter/bin/flutter"
        val commandArgs = args.joinToString(" ") { shellQuote(it) }
        return """
            set -e
            export ANDROID_HOME=$sdk
            export ANDROID_SDK_ROOT=$sdk
            if [ ! -x $flutter ]; then
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
              $flutter config --no-analytics
            fi
            $flutter config --android-sdk $sdk
            if [ ! -f /root/flutter/.codestudio-android-precache ]; then
              echo 'Descargando Flutter Engine para Android…'
              precache_ok=false
              for attempt in 1 2 3; do
                if $flutter precache --android; then
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
            cd $cwd
            # Migrate projects created by older CodeStudio templates from Flutter embedding v1.
            # Search all Android manifests because imported projects can use a different module layout.
            find ./android -name 'AndroidManifest.xml' -type f -print0 2>/dev/null | while IFS= read -r -d '' manifest; do
              if grep -qE 'io\.flutter\.app\.(android\.)?(SplashScreenUntilFirstFrame|FlutterActivity|FlutterApplication)' "${'$'}manifest"; then
                echo "Actualizando embedding Flutter: ${'$'}manifest"
                sed -i '/io\.flutter\.app\.android\.SplashScreenUntilFirstFrame/{N;d;}' "${'$'}manifest"
                sed -i 's/io\.flutter\.app\.FlutterActivity/io.flutter.embedding.android.FlutterActivity/g' "${'$'}manifest"
                sed -i 's/android:name="io\.flutter\.app\.FlutterApplication"/android:name="${'$'}{applicationName}"/g' "${'$'}manifest"
              fi
              # Flutter classifies the project as embedding v1 when this marker is absent or set to 1.
              sed -i 's/android:name="flutterEmbedding" android:value="1"/android:name="flutterEmbedding" android:value="2"/g' "${'$'}manifest"
              if ! grep -q 'android:name="flutterEmbedding"' "${'$'}manifest"; then
                sed -i '/<\/application>/i\        <meta-data android:name="flutterEmbedding" android:value="2" />' "${'$'}manifest"
              fi
            done
            # A project named "flutter" conflicts with Flutter's own SDK dependency in pubspec.yaml.
            if [ -f pubspec.yaml ] && grep -q '^name: flutter[[:space:]]*$' pubspec.yaml; then
              echo 'Renombrando el paquete Flutter reservado a flutter_app…'
              sed -i 's/^name: flutter[[:space:]]*$/name: flutter_app/' pubspec.yaml
              find test -type f -name '*.dart' -exec sed -i 's/package:flutter\/main\.dart/package:flutter_app\/main.dart/g' {} + 2>/dev/null || true
            fi
            find . -path '*/android/app/src/main/*' -type f \( -name '*.kt' -o -name '*.java' \) -print0 2>/dev/null | while IFS= read -r -d '' source; do
              if grep -q 'io.flutter.app.' "${'$'}source"; then
                echo "Actualizando API Flutter v1: ${'$'}source"
                sed -i 's/io\.flutter\.app\.FlutterActivity/io.flutter.embedding.android.FlutterActivity/g' "${'$'}source"
                sed -i '/import io\.flutter\.app\.FlutterApplication/d' "${'$'}source"
              fi
            done
            $flutter $commandArgs
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
