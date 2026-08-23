package dev.ide.lang.dart

import dev.ide.lang.BackendCapability
import dev.ide.lang.CompilationContext
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageBackend
import dev.ide.lang.LanguageId
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionContribution

val DART_LANGUAGE_ID = LanguageId("dart")

val DART_FILE_TYPE_MAPPING = FileTypeMapping(
    suffixes = listOf(".dart", "pubspec.yaml"),
    language = DART_LANGUAGE_ID,
    order = 100
)

class DartLanguageBackend : LanguageBackend {
    override val id: String = "dart"
    override val languages: Set<LanguageId> = setOf(DART_LANGUAGE_ID)
    override val capabilities: Set<BackendCapability> = setOf(
        BackendCapability.ERROR_RECOVERY,
        BackendCapability.COMPLETION,
        BackendCapability.SEMANTIC_HIGHLIGHT,
        BackendCapability.FORMAT,
        BackendCapability.CODE_FOLDING
    )

    override fun createAnalyzer(ctx: CompilationContext): SourceAnalyzer = DartSourceAnalyzer(ctx)
}
