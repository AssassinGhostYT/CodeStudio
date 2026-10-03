package dev.ide.android.support.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation

/**
 * A CodeStudio plugin, scaffolded as an ordinary Gradle Android app the IDE loads it from: the packaged
 * manifest under `res/raw`, the marker activity the package manager is queried for, and an entry-point class
 * implementing `dev.ide.plugin.Plugin`. Building it produces an installable APK; CodeStudio discovers it once
 * installed and loads it on the next launch (see the external-plugin tier).
 *
 * The plugin SPI is declared `compileOnly`, never bundled: the IDE's classloader is the parent of the
 * plugin's, so the SPI, the Kotlin stdlib and (for a UI plugin) the Compose runtime resolve to the IDE's own
 * copies at runtime. The coordinates below are the ones the SPI is published under; a plugin compiled against
 * them runs against the IDE's copies.
 *
 * The generated app targets the IDE's own `minSdk` (26): the plugin's code runs inside the IDE's process, so
 * a plugin installed on a device the IDE itself cannot run on could never be loaded there.
 */
object CodeStudioPluginTemplate : ProjectTemplate {
    override val id = TemplateId("codestudio-plugin")
    override val displayName = "Complemento de CodeStudio"
    override val description =
        "Un complemento para este IDE, empaquetado como su propia app. Añade un comando o una página de ajustes."
    override val category = TemplateCategory.PLUGIN
    override val iconId = "pkg"
    override val scaffoldsGradle: Boolean = true

    override fun parameters(): List<TemplateParameter> = listOf(
        TemplateParameter.Text(
            key = PLUGIN_ID,
            label = "Id del complemento",
            placeholder = "com.example.micomplemento",
            validation = TextValidation.PACKAGE_NAME,
            help = "La identidad del complemento, usada para el orden de carga y para habilitarlo o " +
                "deshabilitarlo. Por defecto es el paquete. No es un nombre visible.",
        ),
        TemplateParameter.Choice(
            key = CONTRIBUTES,
            label = "Contribuye",
            options = listOf(
                TemplateParameter.Choice.Option(BOTH, "Un comando y una página de ajustes"),
                TemplateParameter.Choice.Option(COMMAND, "Un comando"),
                TemplateParameter.Choice.Option(SETTINGS, "Una página de ajustes"),
            ),
            defaultIndex = 0,
            help = "Lo que registra el complemento generado. Un comando aparece de inmediato en la paleta " +
                "de comandos; una página de ajustes se añade a los ajustes del IDE.",
        ),
    )

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val pluginId = args.string(PLUGIN_ID, pkg)
        val contributes = args.string(CONTRIBUTES, BOTH)
        val command = contributes == BOTH || contributes == COMMAND
        val settings = contributes == BOTH || contributes == SETTINGS
        val name = args.name
        val entryClass = "${className(name)}Plugin"
        val path = AndroidTemplateSupport.pkgPath(pkg)

        GradleScaffold.writeRootFiles(scaffold, name)
        // Overwrite the settings file so the SPI coordinates resolve from the local Maven repository the
        // IDE publishes them to (see README).
        scaffold.writeText("settings.gradle.kts", settingsGradle(name))
        scaffold.writeText("app/build.gradle.kts", appBuildGradle(pkg, settings))
        scaffold.writeText("app/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText("app/src/main/AndroidManifest.xml", androidManifest())
        scaffold.writeText("app/src/main/res/values/strings.xml", stringsXml(name))
        scaffold.writeText(
            "app/src/main/res/raw/codestudio_plugin.toml",
            manifestToml(pluginId, name, pkg, entryClass, command, settings),
        )
        scaffold.writeText("app/src/main/kotlin/$path/$entryClass.kt", entryPoint(pkg, pluginId, name, entryClass, command, settings))
        scaffold.writeText("app/src/main/kotlin/$path/PluginInfoActivity.kt", infoActivity(pkg, entryClass))
        scaffold.writeText("README.md", readme(name, pluginId, entryClass, command, settings))
    }

    // ---- generated Gradle files -------------------------------------------------------------------

    private fun settingsGradle(name: String): String = """
        import org.gradle.api.initialization.resolve.RepositoriesMode

        pluginManagement {
            repositories {
                google()
                mavenCentral()
                gradlePluginPortal()
            }
        }

        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
                // The plugin SPI is resolved from here once it is published (see README).
                mavenLocal()
            }
        }

        rootProject.name = "$name"
        include(":app")
    """

    private fun appBuildGradle(pkg: String, settings: Boolean): String = buildString {
        val sdk = AndroidTemplateSupport.COMPILE_SDK
        append("plugins {\n")
        append("    id(\"com.android.application\")\n")
        append("    id(\"org.jetbrains.kotlin.android\")\n")
        append("}\n\n")
        append("android {\n")
        append("    namespace = \"$pkg\"\n")
        append("    compileSdk = $sdk\n\n")
        append("    defaultConfig {\n")
        append("        applicationId = \"$pkg\"\n")
        append("        minSdk = $IDE_MIN_SDK\n")
        append("        targetSdk = $sdk\n")
        append("        versionCode = 1\n")
        append("        versionName = \"1.0\"\n")
        append("    }\n\n")
        append("    buildTypes {\n")
        append("        release {\n")
        append("            isMinifyEnabled = false\n")
        append("            proguardFiles(\n")
        append("                getDefaultProguardFile(\"proguard-android-optimize.txt\"),\n")
        append("                \"proguard-rules.pro\",\n")
        append("            )\n")
        append("        }\n")
        append("    }\n\n")
        append("    compileOptions {\n")
        append("        sourceCompatibility = JavaVersion.VERSION_17\n")
        append("        targetCompatibility = JavaVersion.VERSION_17\n")
        append("    }\n")
        append("    kotlinOptions {\n")
        append("        jvmTarget = \"17\"\n")
        append("    }\n")
        append("}\n\n")
        append("dependencies {\n")
        append("    compileOnly(\"$SPI_GROUP:$SPI_API:$SPI_VERSION\")\n")
        if (settings) append("    compileOnly(\"$SPI_GROUP:$SPI_PLATFORM:$SPI_VERSION\")\n")
        append("}\n")
    }

    // ---- generated Android + manifest files -------------------------------------------------------

    private fun androidManifest(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">

            <application
                android:allowBackup="true"
                android:icon="@android:drawable/ic_menu_info_details"
                android:label="@string/app_name">

                <!-- Cómo CodeStudio descubre esta app: consulta al gestor de paquetes por esta acción y lee
                     el manifiesto del recurso al que apunta el meta-data. -->
                <activity
                    android:name=".PluginInfoActivity"
                    android:exported="true"
                    android:label="@string/app_name">

                    <intent-filter>
                        <action android:name="dev.ide.codestudio.action.PLUGIN" />
                        <category android:name="android.intent.category.DEFAULT" />
                    </intent-filter>

                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>

                    <meta-data
                        android:name="dev.ide.codestudio.plugin.manifest"
                        android:resource="@raw/codestudio_plugin" />
                </activity>
            </application>

        </manifest>
    """

    private fun stringsXml(name: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="app_name">$name</string>
        </resources>
    """

    private fun manifestToml(
        pluginId: String,
        name: String,
        pkg: String,
        entryClass: String,
        command: Boolean,
        settings: Boolean,
    ): String {
        val capabilities = buildList {
            if (command) add("\"ui.action\"")
            if (settings) add("\"ui.settingsPage\"")
        }.joinToString(", ")
        return """
            # Lo que lee CodeStudio para decidir si carga este complemento y cómo listarlo en
            # Ajustes > Complementos. Se lee sin ejecutar nada del complemento, así que debe concordar con
            # lo que registra realmente el punto de entrada.
            [plugin]
            id = "$pluginId"
            name = "$name"
            version = "1.0.0"
            # Debe coincidir con PLUGIN_API_VERSION del IDE, o el complemento se rechaza con ese motivo.
            apiVersion = $SPI_API_VERSION
            description = "$name, un complemento de CodeStudio."
            entryPoints = ["$pkg.$entryClass"]
            capabilities = [$capabilities]
        """.trimIndent() + "\n"
    }

    // ---- generated Kotlin source ------------------------------------------------------------------

    /**
     * The entry-point source, written flush-left on purpose: the host trims a template's common indentation,
     * so multi-line values interpolated into a block must already be indented to their final column.
     */
    private fun entryPoint(
        pkg: String,
        pluginId: String,
        name: String,
        entryClass: String,
        command: Boolean,
        settings: Boolean,
    ): String {
        val imports = buildList {
            if (command) {
                add("import dev.ide.plugin.action.ActionPlaces")
                add("import dev.ide.plugin.action.ActionResult")
                add("import dev.ide.plugin.action.SimpleAction")
                add("import dev.ide.plugin.action.UI_ACTION_EP")
            }
            add("import dev.ide.plugin.PLUGIN_API_VERSION")
            add("import dev.ide.plugin.Plugin")
            add("import dev.ide.plugin.PluginManifest")
            add("import dev.ide.plugin.PluginRegistration")
            if (settings) {
                add("import dev.ide.platform.settings.PreferenceReader")
                add("import dev.ide.platform.settings.SETTINGS_PAGE_EP")
                add("import dev.ide.platform.settings.SettingControl")
                add("import dev.ide.platform.settings.SettingsPage")
            }
        }.joinToString("\n")

        val body = buildString {
            if (command) {
                append(
                    """
                            reg.register(
                                UI_ACTION_EP,
                                SimpleAction(
                                    id = "$pluginId.hello",
                                    text = "$name: saludar",
                                    places = setOf(ActionPlaces.COMMAND_PALETTE, ActionPlaces.MORE_MENU),
                                    iconId = "sparkle",
                                ) { ctx ->
                                    log.info("saludo invocado en " + ctx.place.id)
                                    ActionResult.message("Hola desde $name.")
                                },
                            )
                    """.trimIndent(),
                )
            }
            if (settings) {
                if (command) append("\n\n")
                append("        reg.register(SETTINGS_PAGE_EP, ${entryClass}SettingsPage())")
            }
        }

        val settingsPage = if (!settings) "" else """


/** Una categoría de ajustes. El IDE dibuja los controles; esta clase sólo los declara. */
private class ${entryClass}SettingsPage : SettingsPage {

    override val id = "$pluginId"
    override val title = "$name"
    override val iconId = "sparkle"

    override fun controls(): List<SettingControl> = listOf(
        SettingControl.Toggle(
            key = "enabled",
            title = "Hacer la cosa",
            description = "Un interruptor de ejemplo. Su valor se guarda por ti, con el id de esta página como espacio de nombres.",
            default = true,
        ),
    )

    override fun onChanged(key: String, values: PreferenceReader) {
        // Reacciona aquí a un valor cambiado.
    }
}"""

        return """package $pkg

$imports

/**
 * El punto de entrada que nombra `res/raw/codestudio_plugin.toml`. El IDE lo instancia desde el APK
 * instalado con su propio cargador de clases como padre, así que cada tipo del SPI se enlaza con la copia
 * del IDE.
 *
 * [register] se ejecuta una sola vez, al arrancar el IDE, después de cada complemento del que dependa.
 * Todo lo que contribuye se elimina automáticamente si el complemento se descarga.
 */
class $entryClass : Plugin {

    override val manifest = PluginManifest(
        id = "$pluginId",
        name = "$name",
        version = "1.0.0",
        apiVersion = PLUGIN_API_VERSION,
        description = "$name, un complemento de CodeStudio.",
    )

    override fun register(reg: PluginRegistration) {
        val log = reg.logger("$entryClass")
        log.info("cargado")

$body
    }
}$settingsPage
"""
    }

    private fun infoActivity(pkg: String, entryClass: String): String = """
        package $pkg

        import android.app.Activity
        import android.graphics.Color
        import android.os.Bundle
        import android.util.TypedValue
        import android.view.ViewGroup
        import android.widget.LinearLayout
        import android.widget.ScrollView
        import android.widget.TextView

        /**
         * La pantalla propia de esta app y la actividad cuyo intent-filter la hace descubrible como
         * complemento de CodeStudio. El código que importa se ejecuta dentro del IDE, no aquí.
         */
        class PluginInfoActivity : Activity() {

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#101014"))
                    setPadding(dp(24), dp(48), dp(24), dp(24))
                }
                body.addView(text(getString(R.string.app_name), 28f))
                body.addView(
                    text(
                        "Un complemento de CodeStudio.\n\n" +
                            "Instala esta app y luego abre CodeStudio en Ajustes > Complementos > " +
                            "Instalados. Se carga en el siguiente arranque del IDE.\n\n" +
                            "Punto de entrada: $entryClass",
                        15f,
                    )
                )
                setContentView(
                    ScrollView(this).apply {
                        addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                )
            }

            private fun text(value: String, size: Float) = TextView(this).apply {
                this.text = value
                setTextColor(Color.parseColor("#F2F2F7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
                setPadding(0, dp(6), 0, dp(6))
            }

            private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        }
    """

    private fun readme(name: String, pluginId: String, entryClass: String, command: Boolean, settings: Boolean): String {
        val contributes = buildList {
            if (command) add("- Un comando en la paleta de comandos y en el menú \"Más\" del editor.")
            if (settings) add("- Una página en los ajustes del IDE.")
        }.joinToString("\n")
        return buildString {
            append("# $name\n\n")
            append("Un complemento de CodeStudio, empaquetado como su propia app. CodeStudio lo encuentra a través del\n")
            append("gestor de paquetes, lee `app/src/main/res/raw/codestudio_plugin.toml` y carga `$entryClass` desde el\n")
            append("APK instalado.\n\n")
            append("Contribuye:\n\n")
            append(contributes).append("\n\n")
            append("## Pruébalo\n\n")
            append("Compila e instala este proyecto, reinicia CodeStudio y abre **Ajustes > Complementos > Instalados**.\n")
            append("Los complementos se cargan una vez al arrancar, así que uno recién instalado aparece en el siguiente\n")
            append("arranque. Si no aparece, su fila en esa pantalla indica el motivo.\n\n")
            append("## Las tres partes\n\n")
            append("| Parte | Dónde |\n")
            append("| --- | --- |\n")
            append("| El manifiesto empaquetado | `app/src/main/res/raw/codestudio_plugin.toml` |\n")
            append("| La actividad marcador que CodeStudio consulta | `app/src/main/AndroidManifest.xml` |\n")
            append("| El punto de entrada que nombra el manifiesto | `$entryClass.kt` |\n\n")
            append("## Notas\n\n")
            append("- `id` es `$pluginId`. Es la identidad del complemento, no un nombre visible: decide el orden de\n")
            append("  carga, a qué se refiere el `dependsOn` de otro complemento y contra qué se guarda la elección de\n")
            append("  habilitar/deshabilitar.\n")
            append("- El SPI es una dependencia `compileOnly`. Nunca se empaqueta: el cargador de clases del IDE es el\n")
            append("  padre del de este complemento, así que el SPI y la stdlib de Kotlin se resuelven con las copias\n")
            append("  del IDE.\n")
            append("- No actives la minificación. El IDE instancia el punto de entrada por el nombre del manifiesto y\n")
            append("  R8 lo renombraría.\n")
            append("- El complemento se ejecuta dentro del proceso del IDE, con sus permisos. El cargador de clases\n")
            append("  separa versiones, no privilegios.\n\n")
            append("## Compilar\n\n")
            append("El SPI se resuelve como dependencia `compileOnly` desde un repositorio Maven. Mientras el SPI no\n")
            append("esté publicado en un repositorio accesible, este proyecto no resolverá esa dependencia; el resto del\n")
            append("proyecto ya es un proyecto Android normal.\n")
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** The project name reduced to a usable class-name prefix ("Mi Plugin!" becomes "MiPlugin"). */
    private fun className(name: String): String {
        val cleaned = name.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        return when {
            cleaned.isEmpty() -> "My"
            cleaned.first().isDigit() -> "P$cleaned"
            else -> cleaned
        }
    }

    /** The IDE's own `minSdk`; a plugin's code is loaded into the IDE's process. */
    private const val IDE_MIN_SDK = 26

    private const val PLUGIN_ID = "pluginId"
    private const val CONTRIBUTES = "contributes"
    private const val BOTH = "both"
    private const val COMMAND = "command"
    private const val SETTINGS = "settings"

    /** The published SPI coordinates a plugin compiles against. */
    private const val SPI_GROUP = "dev.ide"
    private const val SPI_API = "plugin-api"
    private const val SPI_PLATFORM = "platform-core"
    private const val SPI_VERSION = "1.0.0"
    private const val SPI_API_VERSION = 1
}
