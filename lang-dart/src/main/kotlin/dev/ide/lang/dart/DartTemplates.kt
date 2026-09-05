package dev.ide.lang.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.template.ProjectScaffold
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateCategory
import dev.ide.model.template.TemplateId
import dev.ide.model.template.TemplateParameter

/** Dart Console Module Type */
object DartConsoleModuleType : ModuleType {
    override val id = "dart-console"
    override val displayName = "Dart Console App"
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate(
            "main",
            DependencyScope.IMPLEMENTATION,
            mapOf(
                "lib" to setOf(ContentRole.SOURCE),
                "bin" to setOf(ContentRole.SOURCE),
                "test" to setOf(ContentRole.SOURCE),
            )
        )
    )
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/** Flutter App Module Type */
object FlutterAppModuleType : ModuleType {
    override val id = "flutter-app"
    override val displayName = "Flutter Application"
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate(
            "main",
            DependencyScope.IMPLEMENTATION,
            mapOf(
                "lib" to setOf(ContentRole.SOURCE),
                "bin" to setOf(ContentRole.SOURCE),
                "test" to setOf(ContentRole.SOURCE),
                "android/app/src/main/kotlin" to setOf(ContentRole.SOURCE),
                "android/app/src/main/res" to setOf(ContentRole.ANDROID_RES),
                "ios/Runner" to setOf(ContentRole.RESOURCE),
            )
        )
    )
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
}

/** Dart Console App Starter Template */
object DartConsoleAppTemplate : ProjectTemplate {
    override val id = TemplateId("dart-console")
    override val displayName = "Dart Console App"
    override val description = "A runnable command-line Dart application with a main() entry point."
    override val category = TemplateCategory.DART
    override val iconId = "dart"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val projectName = args.name
        val cleanName = projectName.lowercase().replace(Regex("[^a-z0-9_]"), "_")

        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(cleanName, scaffold.moduleType("dart-console")).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("lib" to setOf(ContentRole.SOURCE), "bin" to setOf(ContentRole.SOURCE), "test" to setOf(ContentRole.SOURCE))))
            }
            commit()
        }

        scaffold.writeText(
            "$cleanName/pubspec.yaml",
            """
            name: $cleanName
            description: A new Dart project.
            version: 1.0.0
            environment:
              sdk: '>=3.0.0 <4.0.0'
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/analysis_options.yaml",
            """
            include: package:lints/recommended.yaml
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/README.md",
            """
            # $projectName

            A sample command-line application created with Dart.
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/.gitignore",
            """
            .dart_tool/
            .packages
            build/
            pubspec.lock
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/CHANGELOG.md",
            """
            # 1.0.0

            - Initial version.
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/bin/$cleanName.dart",
            """
            import 'package:$cleanName/$cleanName.dart' as $cleanName;

            void main(List<String> arguments) {
              print('Hello world: ${'$'}{$cleanName.calculate()}!');
            }
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/lib/$cleanName.dart",
            """
            int calculate() {
              return 42;
            }
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/test/${cleanName}_test.dart",
            """
            import 'package:$cleanName/$cleanName.dart';
            import 'package:test/test.dart';

            void main() {
              test('calculate', () {
                expect(calculate(), 42);
              });
            }
            """.trimIndent()
        )
    }
}

/** Flutter App Starter Template */
object FlutterAppTemplate : ProjectTemplate {
    override val id = TemplateId("flutter-app")
    override val displayName = "Flutter App"
    override val description = "A modern Flutter application with MaterialApp, Android/iOS configurations and Widget structure."
    override val category = TemplateCategory.DART
    override val iconId = "flutter"

    override fun parameters(): List<TemplateParameter> = emptyList()

    override fun generate(scaffold: ProjectScaffold, args: TemplateArgs) {
        val projectName = args.name
        val cleanName = projectName.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val pkg = args.packageName

        scaffold.workspace.beginModification().apply {
            addProject(projectName, BuildSystemId.NATIVE, scaffold.rootDir)
            commit()
        }
        scaffold.workspace.projects.first { it.name == projectName }.beginModification().apply {
            addModule(cleanName, scaffold.moduleType("flutter-app")).apply {
                languageLevel = scaffold.languageLevel
                addSourceSet(
                    SourceSetTemplate(
                        "main",
                        DependencyScope.IMPLEMENTATION,
                        mapOf(
                            "lib" to setOf(ContentRole.SOURCE),
                            "bin" to setOf(ContentRole.SOURCE),
                            "test" to setOf(ContentRole.SOURCE),
                            "android/app/src/main/kotlin" to setOf(ContentRole.SOURCE),
                            "android/app/src/main/res" to setOf(ContentRole.ANDROID_RES),
                            "ios/Runner" to setOf(ContentRole.RESOURCE),
                        )
                    )
                )
            }
            commit()
        }

        scaffold.writeText(
            "$cleanName/pubspec.yaml",
            """
            name: $cleanName
            description: A new Flutter project.
            publish_to: 'none'
            version: 1.0.0+1
            environment:
              sdk: '>=3.0.0 <4.0.0'
            dependencies:
              flutter:
                sdk: flutter
              cupertino_icons: ^1.0.8
            dev_dependencies:
              flutter_test:
                sdk: flutter
              flutter_lints: ^4.0.0
            flutter:
              uses-material-design: true
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/analysis_options.yaml",
            """
            include: package:flutter_lints/flutter.yaml
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/README.md",
            """
            # $projectName

            A new Flutter project.
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/.gitignore",
            """
            .dart_tool/
            .idea/
            .packages
            build/
            ios/Flutter/App.framework
            ios/Flutter/Flutter.framework
            android/app/build/
            """.trimIndent()
        )

        // Flutter main.dart
        scaffold.writeText(
            "$cleanName/lib/main.dart",
            """
            import 'package:flutter/material.dart';

            void main() {
              runApp(const MyApp());
            }

            class MyApp extends StatelessWidget {
              const MyApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '$projectName',
                  theme: ThemeData(
                    colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
                    useMaterial3: true,
                  ),
                  home: const MyHomePage(title: '$projectName Home Page'),
                );
              }
            }

            class MyHomePage extends StatefulWidget {
              const MyHomePage({super.key, required this.title});
              final String title;

              @override
              State<MyHomePage> createState() => _MyHomePageState();
            }

            class _MyHomePageState extends State<MyHomePage> {
              int _counter = 0;

              void _incrementCounter() {
                setState(() {
                  _counter++;
                });
              }

              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  appBar: AppBar(
                    backgroundColor: Theme.of(context).colorScheme.inversePrimary,
                    title: Text(widget.title),
                  ),
                  body: Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: <Widget>[
                        const Text('You have pushed the button this many times:'),
                        Text(
                          '${'$'}_counter',
                          style: Theme.of(context).textTheme.headlineMedium,
                        ),
                      ],
                    ),
                  ),
                  floatingActionButton: FloatingActionButton(
                    onPressed: _incrementCounter,
                    tooltip: 'Increment',
                    child: const Icon(Icons.add),
                  ),
                );
              }
            }
            """.trimIndent()
        )

        // Flutter widget test
        scaffold.writeText(
            "$cleanName/test/widget_test.dart",
            """
            import 'package:flutter/material.dart';
            import 'package:flutter_test/flutter_test.dart';
            import 'package:$cleanName/main.dart';

            void main() {
              testWidgets('Counter increments smoke test', (WidgetTester tester) async {
                await tester.pumpWidget(const MyApp());
                expect(find.text('0'), findsOneWidget);
                expect(find.text('1'), findsNothing);

                await tester.tap(find.byIcon(Icons.add));
                await tester.pump();

                expect(find.text('0'), findsNothing);
                expect(find.text('1'), findsOneWidget);
              });
            }
            """.trimIndent()
        )

        // Android files
        val pkgPath = pkg.replace('.', '/')
        scaffold.writeText(
            "$cleanName/android/app/src/main/kotlin/$pkgPath/MainActivity.kt",
            """
            package $pkg

            import io.flutter.embedding.android.FlutterActivity

            class MainActivity: FlutterActivity() {
            }
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/android/app/src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$pkg">
                <application
                    android:label="@string/app_name"
                    android:name="${'$'}{applicationName}"
                    android:icon="@drawable/ic_launcher"
                    android:hardwareAccelerated="true">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:launchMode="singleTop"
                        android:theme="@style/LaunchTheme"
                        android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
                        android:hardwareAccelerated="true"
                        android:windowSoftInputMode="adjustResize">
                        <meta-data
                          android:name="io.flutter.app.android.SplashScreenUntilFirstFrame"
                          android:value="true" />
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    <meta-data
                        android:name="flutterEmbedding"
                        android:value="2" />
                </application>
            </manifest>
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/android/app/src/main/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">$projectName</string>
            </resources>
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/android/app/src/main/res/values/styles.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="LaunchTheme" parent="@android:style/Theme.Light.NoTitleBar">
                    <item name="android:windowBackground">@android:color/white</item>
                </style>
            </resources>
            """.trimIndent()
        )

        scaffold.writeText(
            "$cleanName/android/app/proguard-rules.pro",
            """
            # Flutter Wrapper Proguard Rules
            -keep class io.flutter.app.** { *; }
            -keep class io.flutter.plugin.** { *; }
            -keep class io.flutter.util.** { *; }
            -keep class io.flutter.view.** { *; }
            -keep class io.flutter.embedding.** { *; }
            -keep class io.flutter.provider.** { *; }
            -dontwarn io.flutter.embedding.**
            """.trimIndent()
        )

        // Launcher icon: a self-contained vector drawable (no binary PNGs needed). Represents a simple
        // "F" mark on a solid background and works on any API level that Flutter supports.
        scaffold.writeText(
            "$cleanName/android/app/src/main/res/drawable/ic_launcher.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp"
                android:height="108dp"
                android:viewportWidth="108"
                android:viewportHeight="108">
                <path
                    android:fillColor="#2E7CF6"
                    android:pathData="M0,0h108v108h-108z" />
                <path
                    android:fillColor="#FFFFFF"
                    android:pathData="M64,16h-26v76h14v-30h12c13.255,0 24,-10.745 24,-24s-10.745,-22 -24,-22zM64,38h-12v-8h12c4.418,0 8,3.582 8,8s-3.582,8 -8,8z" />
            </vector>
            """.trimIndent()
        )

        // iOS AppDelegate
        scaffold.writeText(
            "$cleanName/ios/Runner/AppDelegate.swift",
            """
            import UIKit
            import Flutter

            @UIApplicationMain
            @objc class AppDelegate: FlutterAppDelegate {
              override func application(
                _ application: UIApplication,
                didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
              ) -> Bool {
                GeneratedPluginRegistrant.register(with: self)
                return super.application(application, didFinishLaunchingWithOptions: launchOptions)
              }
            }
            """.trimIndent()
        )
    }
}
