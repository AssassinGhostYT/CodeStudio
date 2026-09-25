package dev.ide.lang.dart

import java.io.File
import java.nio.file.Path

/**
 * Optional Android host for Dart/Flutter commands. Desktop uses the normal process runner; Android wires this
 * to its Ubuntu/proot runtime so the Linux Flutter SDK can run without trying to execute glibc binaries directly
 * through Android's linker.
 */
object FlutterHostRunner {
    @Volatile
    var runner: (suspend (workspaceRoot: Path, workingDir: File, isFlutter: Boolean, args: List<String>, log: (String) -> Unit) -> Int)? = null
}
