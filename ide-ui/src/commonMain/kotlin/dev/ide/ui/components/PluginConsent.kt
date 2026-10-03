package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiPluginInfo
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The question asked before an installed plugin is allowed to run.
 *
 * Finding a plugin app on the device is not the user agreeing to execute it. Its classes are loaded into the
 * IDE's own process, so it runs with the IDE's permissions and its access to the user's projects and
 * account; class loading separates versions, not privileges. The OS install prompt the user already saw was
 * about installing an app, not about letting that app inside this one, so this is a separate decision.
 *
 * The wording keeps the two halves apart deliberately. What the plugin *declares* is a claim, because
 * nothing enforces the capability list yet, and presenting it as "permissions" would imply a sandbox that
 * does not exist. What is stated as fact is the part that is true of every plugin regardless of what it
 * declared.
 */
@Composable
fun PluginConsent(
    plugin: UiPluginInfo,
    onRefuse: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "¿Permitir que se ejecute ${plugin.name}?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (plugin.origin.isNotBlank()) {
            Text(
                plugin.origin,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (plugin.capabilities.isNotEmpty()) {
            Text(
                "Dice que hará:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (capability in plugin.capabilities) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            CaIcons.check,
                            null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            describeCapability(capability),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Ca.radius.md))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(CaIcons.info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Un complemento se ejecuta dentro de CodeStudio, con el mismo acceso a tus proyectos y tu " +
                    "cuenta que el propio IDE. No está en un entorno aislado y la lista de arriba no se " +
                    "aplica. Permítelo solo si confías en quien lo publicó.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            publisherLine(plugin),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onRefuse) { Text("No permitir") }
            TextButton(onAccept) { Text("Permitir") }
        }
    }
}

/**
 * Who signed the installed package. Read from the package manager, so it is a fact rather than a claim; an
 * unreadable certificate is stated plainly, because "we cannot tell who published this" is the most
 * important thing on the screen when it is true.
 */
internal fun publisherLine(plugin: UiPluginInfo): String {
    val signature = plugin.signature
    return if (signature == null) {
        "No se pudo identificar al editor: este paquete no tiene un certificado de firma legible."
    } else {
        "Firmado por ${shortSignature(signature)}. Una actualización del mismo editor reutiliza este certificado."
    }
}

/** A digest a person can compare at a glance, rather than 64 characters nobody reads. */
internal fun shortSignature(hex: String): String =
    if (hex.length <= 16) hex else "${hex.take(8)}…${hex.takeLast(8)}"

/**
 * A declared capability in words. An unrecognised value is shown verbatim rather than dropped: a capability
 * this build has never heard of is exactly the one the user should see.
 */
internal fun describeCapability(capability: String): String = when (capability) {
    "ui.action" -> "Agregar comandos a la paleta y a los menús"
    "ui.settingsPage" -> "Agregar una página a Ajustes"
    "ui.toolWindow" -> "Agregar una ventana de herramientas al IDE"
    "ui.screen" -> "Agregar una pantalla al IDE"
    "ui.overlay" -> "Mostrar un aviso sobre cualquier pantalla"
    "ui.editorAction" -> "Agregar acciones en el cursor del editor"
    "ui.editorPreview" -> "Agregar un panel de vista previa para algunos de tus archivos"
    "ui.keyBinding" -> "Tomar atajos de teclado"
    // The three that change how the user's own code looks, phrased so the difference between them is the
    // part a reader can act on: what is marked, what is added beside it, and what is painted over it.
    "ui.editorDecoration" -> "Resaltar el texto de tus archivos"
    "ui.editorLayer" -> "Poner controles propios dentro del editor"
    "ui.editorPainter" -> "Dibujar dentro del editor"
    "ui.editorLanguage" -> "Colorear y comentar un lenguaje en el editor, incluidos los que el IDE ya conoce"
    "ui.colorAttribute" -> "Agregar entradas a tu esquema de colores del editor y cambiar el aspecto de las integradas"
    "lang.backend" -> "Enseñar un lenguaje al editor: análisis, autocompletado y errores"
    "model.moduleType" -> "Agregar un tipo de módulo y plantillas que crean uno"
    "model.facet" -> "Guardar configuración propia en tus módulos"
    // Phrased as what it does to the user's own code, since that is the part worth deciding about.
    "interp.run" -> "Ejecutar el código de tu proyecto dentro del IDE, para previsualizarlo o ejecutarlo"
    "build.task" -> "Agregar pasos a tus compilaciones"
    "build.sourceGenerator" -> "Generar código fuente en tus módulos"
    "build.runTask" -> "Agregar una entrada al selector de Ejecutar"
    "fs.read" -> "Leer los archivos de tus proyectos"
    "fs.write" -> "Cambiar los archivos de tus proyectos"
    "net" -> "Hacer solicitudes de red"
    else -> capability
}
