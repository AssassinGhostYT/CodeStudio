package dev.ide.core.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/**
 * The C/C++ templates. They are declared and rendered in the Create-Project gallery today, but flagged
 * [ProjectTemplate.comingSoon]: the NDK toolchain they need has not landed yet, so the gallery shows the
 * "coming soon" badge and refuses the pick rather than producing a project nothing can build.
 */
internal object CppTemplateSupport {
    /** A safe PascalCase C++ identifier derived from a free-form project name (fallback "App"). */
    fun targetName(raw: String): String {
        val cleaned = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
        val candidate = cleaned.ifEmpty { "App" }
        return if (candidate.first().isdigit()) "App$candidate" else candidate
    }

    /**
     * Files only — no module is attached. [CppConsoleAppTemplate.generate] is unreachable while the template
     * is "coming soon" (the gallery blocks the pick), and there is no C++ module type or language backend to
     * attach one to yet. This keeps the template honest about what it will produce once that work lands.
     */
    fun writeSources(scaffold: ProjectScaffold, target: String, withMain: Boolean) {
        scaffold.writeText(
            "CMakeLists.txt",
            """
            cmake_minimum_required(VERSION 3.20)
            project($target CXX)

            set(CMAKE_CXX_STANDARD 17)
            set(CMAKE_CXX_STANDARD_REQUIRED ON)

            add_executable($target src/main.cpp)
            """.trimIndent(),
        )
        if (withMain) {
            scaffold.writeText(
                "src/main.cpp",
                """
                #include <iostream>

                int main() {
                    std::cout << "Hello from $target!" << std::endl;
                    return 0;
                }
                """.trimIndent(),
            )
        } else {
            scaffold.writeText(
                "src/math.cpp",
                """
                int add(int a, int b) { return a + b; }
                """.trimIndent(),
            )
            scaffold.writeText(
                "src/math.h",
                """
                #pragma once

                int add(int a, int b);
                """.trimIndent(),
            )
        }
    }
}

/**
 * A CMake C++ console application: one executable target with a `main.cpp`.
 * Coming soon — the NDK/C++ toolchain has not shipped yet.
 */
object CppConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("cpp-console")
    override val displayName = "C++ Console App"
    override val description = "A CMake-based C++ application with a main() entry point."
    override val category = TemplateCategory.CPP
    override val iconId = "cpp"
    override val comingSoon = true

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        CppTemplateSupport.writeSources(scaffold, CppTemplateSupport.targetName(args.name), withMain = true)
    }
}

/**
 * A CMake C++ library: a header plus its implementation, built as a static library.
 * Coming soon — the NDK/C++ toolchain has not shipped yet.
 */
object CppLibraryTemplate : ProjectTemplate {
    override val id = TemplateId("cpp-library")
    override val displayName = "C++ Library"
    override val description = "A reusable CMake C++ library with a header and an implementation file."
    override val category = TemplateCategory.CPP
    override val iconId = "cpp"
    override val comingSoon = true

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        CppTemplateSupport.writeSources(scaffold, CppTemplateSupport.targetName(args.name), withMain = false)
    }
}
