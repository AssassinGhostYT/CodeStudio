package dev.ide.android.icons

import android.content.Context
import dev.ide.ui.backend.IconService
import dev.ide.ui.backend.UiCatalogIcon
import java.util.zip.ZipInputStream

/**
 * Reads the bundled Material-icon catalog from `assets/icons.zip` (entries named
 * `baseline_<name>_24.xml`). Mirrors CodeAssist's `ActivityM3Icons` loader so the Canvas
 * Icon Manager shows the same Material icon set.
 *
 * The vectors are parsed lazily on first access; the UI receives the full flat list.
 */
object AssetIconCatalog {

    fun build(context: Context): IconService {
        val icons = runCatching { readIcons(context) }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
        return object : IconService {
            private val cached: List<UiCatalogIcon> = icons
            override fun icons(): List<UiCatalogIcon> = cached
        }
    }

    private fun readIcons(context: Context): List<UiCatalogIcon> {
        val result = mutableListOf<UiCatalogIcon>()
        ZipInputStream(context.assets.open("icons.zip")).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                    val fileName = entry.name.substringAfterLast("/")
                    val cleanName = fileName.removeSuffix(".xml")
                        .removePrefix("baseline_")
                        .removeSuffix("_24")
                        .replace("_", " ")
                    // Read bytes directly: wrapping the ZipInputStream in a Reader (bufferedReader())
                    // closes the underlying stream when the Reader closes, so nextEntry() returns null
                    // after the first entry — leaving the catalog empty.
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    if (xml.isNotBlank()) {
                        result.add(UiCatalogIcon(name = cleanName, xml = xml))
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }
}
