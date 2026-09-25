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
              git clone --depth 1 --branch stable https://github.com/flutter/flutter.git /root/flutter
              $flutter config --no-analytics
            fi
            $flutter config --android-sdk $sdk
            if [ ! -f /root/flutter/.codestudio-android-precache ]; then
              echo 'Descargando Flutter Engine para Android…'
              $flutter precache --android
              touch /root/flutter/.codestudio-android-precache
            fi
            cd $cwd
            # Migrate projects created by older CodeStudio templates from Flutter embedding v1.
            if [ -f android/app/src/main/AndroidManifest.xml ] && grep -q 'io.flutter.app.android.SplashScreenUntilFirstFrame' android/app/src/main/AndroidManifest.xml; then
              echo 'Actualizando el proyecto Flutter al embedding moderno…'
              sed -i '/io.flutter.app.android.SplashScreenUntilFirstFrame/{N;d;}' android/app/src/main/AndroidManifest.xml
            fi
            $flutter $commandArgs
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
