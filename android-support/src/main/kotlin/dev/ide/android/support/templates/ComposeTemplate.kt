package dev.ide.android.support.templates

import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateDependency
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter
import dev.ide.platform.log.Log

/**
 * A Jetpack Compose application scaffolded as a normal Gradle project — scripts are the source of truth,
 * `app/build.gradle.kts` declares the Compose BOM + Material 3 in `dependencies {}`, and the model is
 * derived by the Gradle importer (no `module.toml`). The starter screen is a `Greeting` composable shown
 * via `setContent`, plus two `@Preview` composables — a single `Text` and a `Column` of `Text`s — to
 * showcase the editor Preview button on both a leaf and a nested (content-lambda) composable.
 */
object JetpackComposeAppTemplate : ProjectTemplate {
    override val id = TemplateId("compose-app")
    override val displayName = "Jetpack Compose App"
    override val description = "An Android app with a Jetpack Compose UI and @Preview composables you can render in the editor."
    override val category = TemplateCategory.ANDROID
    override val iconId = "module.android"
    override val scaffoldsGradle: Boolean = true

    private val log = Log.logger("Jetpack Compose Template Generator")

    override fun parameters(): List<TemplateParameter> = listOf(
        // Compose requires minSdk 21+; drop the lower options.
        AndroidTemplateSupport.minSdkParam.copy(
            options = AndroidTemplateSupport.minSdkParam.options.filter { it.value.toInt() >= 21 },
            defaultIndex = 0,
        ),
        AndroidTemplateSupport.targetSdkParam,
    )

    // Declared in the generated app/build.gradle.kts (the importer reads it); nothing to write to module.toml.
    override fun dependencies(args: TemplateArgs): List<TemplateDependency> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val pkg = args.packageName
        val minSdk = args.int("minSdk", 21)
        val targetSdk = args.int("targetSdk", AndroidTemplateSupport.COMPILE_SDK)

        GradleScaffold.writeRootFiles(scaffold, args.name)
        GradleScaffold.writeAppModule(
            scaffold,
            GradleScaffold.AppOptions(
                module = "app",
                namespace = pkg,
                minSdk = minSdk,
                targetSdk = targetSdk,
                compileSdk = AndroidTemplateSupport.COMPILE_SDK,
                kotlin = true,
                compose = true,
            ),
        )

        val path = AndroidTemplateSupport.pkgPath(pkg)
        scaffold.writeText("app/proguard-rules.pro", AndroidTemplateSupport.PROGUARD_RULES_PRO)
        scaffold.writeText(
            "app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                <application
                    android:allowBackup="true"
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:roundIcon="@mipmap/ic_launcher_round"
                    android:supportsRtl="true"
                    android:theme="@style/Theme.App">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${args.name}</string>
            </resources>
            """,
        )
        scaffold.writeText(
            "app/src/main/res/values/colors.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                ${AndroidAppAssets.ICON_BACKGROUND_COLOR_XML}
            </resources>
            """,
        )
        // A NoActionBar framework theme — Compose handles its own theming, so no Material XML theme is needed.
        scaffold.writeText(
            "app/src/main/res/values/themes.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="android:Theme.Material.Light.NoActionBar"/>
            </resources>
            """,
        )
        for ((rel, content) in AndroidAppAssets.launcherIconResFiles) {
            scaffold.writeText("app/src/main/res/$rel", content)
        }
        scaffold.writeText(
            "app/src/main/kotlin/$path/MainActivity.kt",
            """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Column
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { Greeting("World") }
                }
            }

            @Composable
            fun Greeting(name: String) {
                Text(text = "Hello, " + name + "!")
            }

            // Press the Preview button in the editor toolbar to render these through the Compose interpreter.
            @Preview
            @Composable
            fun GreetingPreview() {
                Greeting("Compose")
            }

            @Preview
            @Composable
            fun CardPreview() {
                Column {
                    Text("Title")
                    Text("Body")
                }
            }
            """,
        )
    }

}
