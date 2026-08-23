package dev.ide.lang.dart.index

import dev.ide.index.IndexExtension
import dev.ide.index.IndexId
import dev.ide.index.IndexInput
import dev.ide.index.IndexOrigin
import dev.ide.index.InputFilter
import dev.ide.index.KeyDescriptor
import dev.ide.index.MatchingMode
import dev.ide.index.StringExternalizer
import dev.ide.index.StringKeyDescriptor

/** Index for Dart & Flutter class names (Widget, MaterialApp, StatelessWidget, custom classes) */
object DartClassNamesIndex : IndexExtension<String, String> {
    override val id = IndexId("dart.classNames")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { input -> input.unitName?.endsWith(".dart") == true }

    private val flutterBuiltInClasses = setOf(
        "Widget", "StatelessWidget", "StatefulWidget", "Container", "Column", "Row", "Text", "Center",
        "Padding", "SizedBox", "Scaffold", "AppBar", "ListView", "Stack", "Positioned", "Expanded", "Flexible",
        "GestureDetector", "InkWell", "SingleChildScrollView", "FloatingActionButton", "Icon", "Image", "ElevatedButton",
        "MaterialApp", "ThemeData", "ColorScheme", "Colors", "Icons", "State", "BuildContext", "WidgetTester"
    )

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val text = input.text() ?: return emptyMap()
        val results = mutableMapOf<String, MutableList<String>>()

        // Index built-in Flutter framework classes
        for (w in flutterBuiltInClasses) {
            results.getOrPut(w) { mutableListOf() }.add("dart.ui.$w")
        }

        // Parse class, mixin, enum, extension definitions in the source file
        val classRegex = Regex("""\b(class|enum|mixin|extension)\s+([A-Za-z0-9_]+)""")
        for (match in classRegex.findAll(text)) {
            val className = match.groupValues[2]
            val fqn = "${input.unitName ?: "unknown"}:$className"
            results.getOrPut(className) { mutableListOf() }.add(fqn)
        }

        return results
    }
}

/** Index for Dart top-level functions and method callables (main, calculate, runApp, etc.) */
object DartCallablesIndex : IndexExtension<String, String> {
    override val id = IndexId("dart.callables")
    override val version = 1
    override val keyDescriptor: KeyDescriptor<String> = StringKeyDescriptor
    override val valueExternalizer = StringExternalizer
    override val matching = MatchingMode.PREFIX_AND_FUZZY
    override val inputFilter = InputFilter { input -> input.unitName?.endsWith(".dart") == true }

    override fun index(input: IndexInput): Map<String, Collection<String>> {
        val text = input.text() ?: return emptyMap()
        val results = mutableMapOf<String, MutableList<String>>()

        val funcRegex = Regex("""\b([A-Za-z0-9_<>]+)\s+([A-Za-z0-9_]+)\s*\([^)]*\)\s*[\{=>]""")
        for (match in funcRegex.findAll(text)) {
            val funcName = match.groupValues[2]
            if (funcName !in setOf("if", "for", "while", "switch", "catch")) {
                results.getOrPut(funcName) { mutableListOf() }.add("${input.unitName ?: "unknown"}:$funcName")
            }
        }
        return results
    }
}
