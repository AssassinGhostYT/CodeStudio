CodeStudio es un entorno de desarrollo integrado de código abierto pensado y diseñado
desde cero para dispositivos Android. Su propósito es eliminar la dependencia del
escritorio: ofrecer un flujo de trabajo real y completo —editar, navegar, compilar,
depurar y versionar— dentro de una aplicación nativa que cabe en el bolsillo.
El proyecto está escrito en su totalidad en Kotlin, con una interfaz declarativa
construida sobre Jetpack Compose y Material 3, y sigue una arquitectura MVVM
con separación estricta entre las capas de datos, dominio y presentación. Cada módulo es
independiente y testeable, lo que permite que el editor, el motor de compilación y la
integración con Git evolucionen por separado sin acoplarse entre sí.
CodeStudio no aspira a ser un editor de texto con resaltado de sintaxis. Su objetivo es
comportarse como un estudio de desarrollo: entender la estructura de un proyecto,
resolver sus dependencias, producir un artefacto ejecutable y mantener un historial de
versiones coherente.
Motivación
La mayoría de las herramientas de programación para móvil se limitan a editar archivos
sueltos. Faltaba una alternativa que asumiera el problema completo: gestión de proyectos
multiarchivo, sistema de construcción, control de versiones y una experiencia táctil que
no resultara un obstáculo al escribir código. CodeStudio existe para cubrir ese espacio,
especialmente para quienes aprenden a programar sin acceso permanente a una computadora.
Características
Arquitectura
app/
├── data/          Repositorios, persistencia local y acceso al sistema de archivos
├── domain/        Modelos de dominio y casos de uso
├── ui/            Pantallas Compose, componentes reutilizables y sistema de temas
├── editor/        Motor del editor: lexer, resaltado incremental y autocompletado
├── build/         Orquestación del ciclo de compilación y consola de salida
└── vcs/           Integración con Git
Stack técnico
Lenguaje: Kotlin
Interfaz: Jetpack Compose · Material 3
Arquitectura: MVVM sobre principios de Clean Architecture
Concurrencia: Coroutines y Flow
Inyección de dependencias: Hilt
Persistencia: Room y DataStore
Construcción: Gradle con catálogo de versiones (libs.versions.toml)
Requisitos
Android 8.0 (API 26) o superior
2 GB de RAM recomendados
Aproximadamente 150 MB de espacio disponible
Compilar desde el código fuente
git clone https://github.com/<usuario>/CodeStudio.git
cd CodeStudio
./gradlew assembleDebug
El APK resultante se genera en app/build/outputs/apk/debug/.
Hoja de ruta
�Autocompletado semántico basado en el análisis del proyecto completo
�Depurador con puntos de ruptura e inspección de variables
�Vista previa en vivo de funciones @Composable
�Terminal integrada
�Soporte para Kotlin Multiplatform
�Arquitectura de plugins y extensiones de terceros
Contribuir
Las contribuciones son bienvenidas. Para cambios de alcance amplio, abre primero un
issue que describa la propuesta antes de invertir tiempo en la implementación.
Haz un fork del repositorio
Crea una rama descriptiva: git checkout -b feature/mi-mejora
Realiza commits atómicos con mensajes claros
Abre un pull request explicando el cambio y su motivación
Licencia
Distribuido bajo la licencia MIT. Consulta el archivo LICENSE para conocer
los términos completos.
