package dev.ide.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import dev.ide.ui.theme.SyntaxColors

enum class CodeLanguage { Java, Kotlin, Dart, Swift, Xml, Proguard, Markdown, Plain }

fun languageFor(fileName: String): CodeLanguage = when {
    fileName.endsWith(".java") -> CodeLanguage.Java
    fileName.endsWith(".kt") || fileName.endsWith(".kts") -> CodeLanguage.Kotlin
    fileName.endsWith(".dart") -> CodeLanguage.Dart
    fileName.endsWith(".swift") -> CodeLanguage.Swift
    fileName.endsWith(".xml") -> CodeLanguage.Xml
    // ProGuard/R8 keep-rule files: `proguard-rules.pro`, `consumer-rules.pro`, any `*.pro`.
    fileName.endsWith(".pro") -> CodeLanguage.Proguard
    fileName.endsWith(".md") || fileName.endsWith(".markdown") -> CodeLanguage.Markdown
    else -> CodeLanguage.Plain
}

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
    "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
    "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
    "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
    "volatile", "while", "true", "false", "null", "var", "record", "sealed", "permits", "yield",
    // Kotlin extras (shared scanner)
    "fun", "val", "when", "is", "in", "object", "companion", "data", "override", "open", "internal",
    "lateinit", "by", "constructor", "init", "suspend", "vararg", "typealias", "as", "out", "reified",
)

private val DART_KEYWORDS = setOf(
    "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const",
    "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends",
    "extension", "external", "factory", "false", "final", "finally", "for", "Function", "get", "hide",
    "if", "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new",
    "null", "on", "operator", "part", "required", "rethrow", "return", "set", "show", "static",
    "super", "switch", "sync", "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
)

private fun isPunct(c: Char) = c in "{}()[];,.<>=+-*/%&|!?:^~@"

/** Single-pass scanner → colored [AnnotatedString]. Backend-free; good enough for editor highlighting. */
fun highlight(text: String, language: CodeLanguage, syntax: SyntaxColors): AnnotatedString {
    if (language == CodeLanguage.Dart) return highlightDart(text, syntax)
    if (language == CodeLanguage.Swift) return highlightSwift(text, syntax)
    if (language == CodeLanguage.Xml) return highlightXml(text, syntax)
    if (language == CodeLanguage.Proguard) return highlightProguard(text, syntax)
    // Markdown has no whole-document scanner (the active editor uses the incremental styleMarkdownLine); the
    // legacy scanner just renders it uncolored rather than mis-tokenizing prose as Java.
    if (language == CodeLanguage.Markdown) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(color = syntax.default), 0, text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val start = i; i += 2
                    while (i < n && text[i] != '\n') i++
                    addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val start = i; i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                    addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
                }
                c == '"' -> {
                    val start = i; i++
                    while (i < n && text[i] != '"' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                    if (i < n && text[i] == '"') i++
                    addStyle(SpanStyle(color = syntax.string), start, i)
                }
                c == '\'' -> {
                    val start = i; i++
                    while (i < n && text[i] != '\'' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                    if (i < n && text[i] == '\'') i++
                    addStyle(SpanStyle(color = syntax.string), start, i)
                }
                c.isDigit() -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
                    addStyle(SpanStyle(color = syntax.number), start, i)
                }
                c == '@' -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    addStyle(SpanStyle(color = syntax.annotation), start, i)
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    val start = i; i++
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                    val word = text.substring(start, i)
                    val color = when {
                        word in JAVA_KEYWORDS -> syntax.keyword
                        else -> {
                            var j = i
                            while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                            when {
                                j < n && text[j] == '(' -> syntax.func
                                word[0].isUpperCase() -> syntax.type
                                else -> null
                            }
                        }
                    }
                    if (color != null) addStyle(SpanStyle(color = color), start, i)
                }
                isPunct(c) -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
            else -> i++
        }
    }
}
}

/** Dart lexical scanner: `//` and `/* */` comments, single/double/triple-quoted strings, numbers,
 *  `@`-annotations, keywords, identifiers (Capitalized → type, followed by `(` → func). */
private fun highlightDart(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                val start = i; i += 2
                while (i < n && text[i] != '\n') i++
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                val start = i; i += 2
                while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(n)
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            (c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') ||
            (c == '\'' && i + 2 < n && text[i + 1] == '\'' && text[i + 2] == '\'') -> {
                val quote = c; val start = i; i += 3
                while (i < n) {
                    if (text[i] == quote && i + 2 < n && text[i + 1] == quote && text[i + 2] == quote) { i += 3; break }
                    i++
                }
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c == '"' -> {
                val start = i; i++
                while (i < n && text[i] != '"' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                if (i < n && text[i] == '"') i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c == '\'' -> {
                val start = i; i++
                while (i < n && text[i] != '\'' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                if (i < n && text[i] == '\'') i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isDigit() -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_' || text[i] == 'e' || text[i] == 'E')) i++
                addStyle(SpanStyle(color = syntax.number), start, i)
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                addStyle(SpanStyle(color = syntax.annotation), start, i)
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i)
                val color = when {
                    word in DART_KEYWORDS -> syntax.keyword
                    else -> {
                        var j = i
                        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                        when {
                            j < n && text[j] == '(' -> syntax.func
                            word[0].isUpperCase() -> syntax.type
                            else -> null
                        }
                    }
                }
                if (color != null) addStyle(SpanStyle(color = color), start, i)
            }
            isPunct(c) -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
            else -> i++
        }
    }
}

private val SWIFT_KEYWORDS = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "func", "import", "init", "inout",
    "internal", "let", "operator", "private", "protocol", "public", "static", "struct", "subscript",
    "typealias", "var", "break", "case", "continue", "default", "defer", "do", "else", "fallthrough",
    "for", "guard", "if", "in", "repeat", "return", "switch", "where", "while", "as", "catch", "dynamicType",
    "false", "is", "nil", "rethrows", "super", "self", "Self", "throw", "throws", "true", "try",
    "__COLUMN__", "__FILE__", "__FUNCTION__", "__LINE__", "#available", "#colorLiteral", "#column",
    "#conditional", "#dsohandle", "#error", "#file", "#fileLiteral", "#function", "#if", "#imageLiteral",
    "#line", "#selector", "#sourceLocation", "associativity", "convenience", "dynamic", "didSet",
    "final", "get", "indirect", "infix", "lazy", "left", "mutating", "none", "nonmutating", "optional",
    "override", "postfix", "precedence", "prefix", "Protocol", "required", "right", "set", "Type",
    "unowned", "weak", "willSet", "iOS", "iOSApplicationExtension", "macOS", "OSX", "OSXApplicationExtension",
    "tvOS", "watchOS", "wildcard", "Self", "PackageDescription"
)

/** Swift lexical scanner: `//` and `/* */` comments, strings, numbers, `@`-annotations, keywords, identifiers. */
private fun highlightSwift(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                val start = i; i += 2
                while (i < n && text[i] != '\n') i++
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                val start = i; i += 2
                while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(n)
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            c == '"' -> {
                val start = i; i++
                while (i < n && text[i] != '"' && text[i] != '\n') { if (text[i] == '\\') i++; i++ }
                if (i < n && text[i] == '"') i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isDigit() -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_' || text[i] == 'e' || text[i] == 'E')) i++
                addStyle(SpanStyle(color = syntax.number), start, i)
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                addStyle(SpanStyle(color = syntax.annotation), start, i)
            }
            c.isLetter() || c == '_' || c == '$' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i)
                val color = when {
                    word in SWIFT_KEYWORDS -> syntax.keyword
                    else -> {
                        var j = i
                        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                        when {
                            j < n && text[j] == '(' -> syntax.func
                            word[0].isUpperCase() -> syntax.type
                            else -> null
                        }
                    }
                }
                if (color != null) addStyle(SpanStyle(color = color), start, i)
            }
            isPunct(c) -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
            else -> i++
        }
    }
}

/**
 * ProGuard/R8 keep-rule files: `#` line comments, `-directives` (keyword), `@`-annotations, quoted
 * strings, and capitalised class names as types. Line-based and tolerant — no real grammar needed.
 */
private fun highlightProguard(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '#' -> {
                val start = i
                while (i < n && text[i] != '\n') i++
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            // A directive like `-keep`, `-dontwarn`, `-keepclassmembers`.
            c == '-' && (i == 0 || text[i - 1] == '\n' || text[i - 1] == ' ' || text[i - 1] == '\t') -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                addStyle(SpanStyle(color = syntax.keyword), start, i)
            }
            c == '@' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                addStyle(SpanStyle(color = syntax.annotation), start, i)
            }
            c == '"' || c == '\'' -> {
                val quote = c; val start = i; i++
                while (i < n && text[i] != quote && text[i] != '\n') i++
                if (i < n && text[i] == quote) i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isLetter() || c == '_' -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.' || text[i] == '$')) i++
                // Class-name patterns read as types; keep-rule member keywords stay default.
                if (text[start].isUpperCase()) addStyle(SpanStyle(color = syntax.type), start, i)
            }
            c in "{}()[];,*" -> { addStyle(SpanStyle(color = syntax.punctuation), i, i + 1); i++ }
            else -> i++
        }
    }
}

private fun highlightXml(text: String, syntax: SyntaxColors): AnnotatedString = buildAnnotatedString {
    append(text)
    addStyle(SpanStyle(color = syntax.default), 0, text.length)
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        when {
            c == '<' && i + 3 < n && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-' -> {
                val start = i; i += 4
                while (i < n && !(text[i] == '-' && i + 2 < n && text[i + 1] == '-' && text[i + 2] == '>')) i++
                i = (i + 3).coerceAtMost(n)
                addStyle(SpanStyle(color = syntax.comment, fontStyle = FontStyle.Italic), start, i)
            }
            c == '<' -> {
                val start = i; i++
                if (i < n && (text[i] == '/' || text[i] == '?')) i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) i++
                addStyle(SpanStyle(color = syntax.type), start, i)
            }
            c == '"' -> {
                val start = i; i++
                while (i < n && text[i] != '"') i++
                if (i < n) i++
                addStyle(SpanStyle(color = syntax.string), start, i)
            }
            c.isLetter() -> {
                val start = i; i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == ':')) i++
                var j = i
                while (j < n && text[j] == ' ') j++
                if (j < n && text[j] == '=') addStyle(SpanStyle(color = syntax.property), start, i)
            }
            else -> i++
        }
    }
}
