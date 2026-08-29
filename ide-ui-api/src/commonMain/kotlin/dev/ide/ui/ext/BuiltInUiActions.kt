package dev.ide.ui.ext

import dev.ide.ui.backend.UiActionPlaces

/**
 * The IDE's built-in UI contributions (the "More" menu + command-palette UI-navigation commands), contributed
 * through the [UiPlugin] model the same way an in-UI plugin would — the IDE dogfooding its own UI-contribution
 * API. Loaded once per process by [UiPluginHost].
 */
object BuiltInUiPlugin : UiPlugin {
    override val id = "ide-ui"

    override fun contributeUi(scope: UiContributionScope) {
        val more = setOf(UiActionPlaces.MORE_MENU)
        val palette = UiActionPlaces.COMMAND_PALETTE
        val moreAndPalette = setOf(UiActionPlaces.MORE_MENU, palette)

        scope.action(
            SimpleUiAction(
                "ui.hub",
                "Ajustes y Herramientas",
                moreAndPalette,
                "Ajustes · estilo de código · gestor de SDK · gestor de claves",
                "gear",
                10
            ) {
                it.navigate(UiDestinations.HUB)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.modules",
                "Módulos",
                more,
                "Añadir/quitar módulos · versión de Java · dependencias · repositorios",
                "layers",
                20
            ) {
                it.navigate(UiDestinations.MODULES)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.dependencies",
                "Gestionar dependencias",
                setOf(palette),
                iconId = "layers",
                order = 25
            ) {
                it.navigate(UiDestinations.DEPENDENCIES)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.reindex",
                "Reindexar proyecto",
                more,
                "Reconstruir índices de símbolos y finalización",
                "refresh",
                40
            ) {
                it.backend.search.reindex()
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.logs",
                "Ver registros",
                more,
                "Registros de editor, análisis y compilación — compártelos si algo falla",
                "terminal",
                50
            ) {
                it.navigate(UiDestinations.LOGS)
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.toggleTheme",
                "Cambiar tema",
                moreAndPalette,
                "Alternar entre claro y oscuro",
                "eye",
                60
            ) {
                it.toggleTheme()
            },
        )
        scope.action(
            SimpleUiAction(
                "ui.closeProject",
                "Cerrar proyecto",
                more,
                "Volver a todos los proyectos",
                "close",
                70
            ) {
                it.navigate(UiDestinations.PROJECTS)
            },
        )
    }
}
