package dev.ide.lang.dart

import dev.ide.lang.AnalysisResult
import dev.ide.lang.CompilationContext
import dev.ide.lang.SourceAnalyzer
import dev.ide.lang.completion.CompletionContribution
import dev.ide.lang.completion.CompletionContributor
import dev.ide.lang.completion.CompletionItem
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.completion.CompletionParams
import dev.ide.lang.completion.CompletionResultSet
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.DomNode
import dev.ide.lang.dom.NodeKind
import dev.ide.lang.dom.ParsedFile
import dev.ide.lang.dom.TextRange
import dev.ide.lang.formatting.FormatStyle
import dev.ide.lang.formatting.FormattingService
import dev.ide.lang.highlight.SemanticHighlightService
import dev.ide.lang.incremental.DocumentEdit
import dev.ide.lang.incremental.DocumentSnapshot
import dev.ide.lang.incremental.IncrementalParser
import dev.ide.lang.incremental.ReparseResult
import dev.ide.lang.resolve.ResolveResult
import dev.ide.lang.resolve.Scope
import dev.ide.lang.resolve.TypeRef
import dev.ide.vfs.VirtualFile

class DartSourceAnalyzer(private val ctx: CompilationContext) : SourceAnalyzer {

    override val incrementalParser: IncrementalParser = object : IncrementalParser {
        override fun parseFull(snapshot: DocumentSnapshot): ParsedFile = DartParsedFile(snapshot.file, snapshot.version)
        override fun reparse(previous: ParsedFile, newSnapshot: DocumentSnapshot, edits: List<DocumentEdit>): ReparseResult {
            val parsed = DartParsedFile(newSnapshot.file, newSnapshot.version)
            return ReparseResult(parsed, TextRange(0, newSnapshot.length()), 0)
        }
    }

    override val semanticHighlighter: SemanticHighlightService = DartSemanticHighlighter()
    override val formatting: FormattingService = DartFormatter()

    override fun completionContributions(): List<CompletionContribution> = listOf(
        CompletionContribution(contributor = DartKeywordCompletionContributor(), languages = setOf(DART_LANGUAGE_ID)),
        CompletionContribution(contributor = FlutterWidgetCompletionContributor(), languages = setOf(DART_LANGUAGE_ID))
    )

    override suspend fun parsedFile(file: VirtualFile): ParsedFile = DartParsedFile(file, 0L)

    override suspend fun analyze(file: VirtualFile): AnalysisResult = AnalysisResult(file, emptyList())

    override fun resolve(node: DomNode): ResolveResult = ResolveResult.Unresolved

    override fun scopeAt(file: VirtualFile, offset: Int): Scope = object : Scope {
        override val enclosing: Scope? = null
        override fun symbols(filter: dev.ide.lang.resolve.SymbolFilter): List<dev.ide.lang.resolve.Symbol> = emptyList()
        override fun resolve(name: String): ResolveResult = ResolveResult.Unresolved
    }

    override fun expectedTypeAt(file: VirtualFile, offset: Int): TypeRef? = null
}

class DartParsedFile(
    override val file: VirtualFile,
    override val documentVersion: Long,
) : ParsedFile {
    override val kind: NodeKind = NodeKind.COMPILATION_UNIT
    override val range: TextRange = TextRange(0, 0)
    override val parent: DomNode? = null
    override val children: List<DomNode> = emptyList()
    override val diagnostics: List<Diagnostic> = emptyList()

    override fun text(): CharSequence = ""
    override fun nodeAt(offset: Int): DomNode = this
    override fun nodesIn(range: TextRange): Sequence<DomNode> = emptySequence()
}

class DartSemanticHighlighter : SemanticHighlightService {
    /** No type-aware highlighting yet — the Dart backend has no real parser/resolver, so the lexical
     *  layer (SyntaxHighlighter.highlightDart) handles all coloring. Returning empty lets the lexical
     *  spans show through without interference from a regex-based guesser. */
    override suspend fun highlight(file: VirtualFile): List<SemanticToken> = emptyList()
}

class DartFormatter : FormattingService {
    override suspend fun format(file: VirtualFile, text: CharSequence, style: FormatStyle): List<DocumentEdit> = emptyList()
}

class DartKeywordCompletionContributor : CompletionContributor {
    override val id: String = "dart.keywords"

    private val keywords = listOf(
        "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const",
        "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends",
        "extension", "external", "factory", "false", "final", "finally", "for", "Function", "get", "hide",
        "if", "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new",
        "null", "on", "operator", "part", "required", "rethrow", "return", "set", "show", "static",
        "super", "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
    )

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        keywords.filter { params.prefixMatches(it) }.forEach { kw ->
            result.addElement(CompletionItem(label = kw, insertText = kw, kind = CompletionItemKind.KEYWORD))
        }
    }
}

class FlutterWidgetCompletionContributor : CompletionContributor {
    override val id: String = "flutter.widgets"

    private val widgets = listOf(
        "Widget", "StatelessWidget", "StatefulWidget", "Container", "Column", "Row", "Text", "Center",
        "Padding", "SizedBox", "Scaffold", "AppBar", "ListView", "Stack", "Positioned", "Expanded", "Flexible",
        "GestureDetector", "InkWell", "SingleChildScrollView", "FloatingActionButton", "Icon", "Image", "ElevatedButton"
    )

    override suspend fun fillCompletionVariants(params: CompletionParams, result: CompletionResultSet) {
        widgets.filter { params.prefixMatches(it) }.forEach { w ->
            result.addElement(CompletionItem(label = w, insertText = w, kind = CompletionItemKind.CLASS, detail = "Flutter Widget"))
        }
    }
}
