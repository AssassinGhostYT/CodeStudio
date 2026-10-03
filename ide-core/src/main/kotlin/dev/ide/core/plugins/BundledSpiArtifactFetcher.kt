package dev.ide.core.plugins

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.deps.impl.HttpArtifactFetcher
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves the plugin SPI (`dev.ide:plugin-api` / `dev.ide:platform-core`) from the Maven-layout repository
 * shipped inside the IDE (staged into `:android-support`'s classpath resources by `bundlePluginSpiRepo`),
 * BEFORE the network is ever touched.
 *
 * A project scaffolded by `CodeStudioPluginTemplate` declares `compileOnly("dev.ide:plugin-api:1.0.0")`.
 * The IDE resolves that itself, and on a device with no connection the public repositories are
 * unreachable — the failure surfaced to the user as "Couldn't reach the repositories". The SPI belongs to
 * the IDE already, so it is answered locally and offline. Every other coordinate falls through to
 * [fallback] unchanged, so ordinary dependencies resolve exactly as before.
 */
internal class BundledSpiArtifactFetcher(
    private val fallback: ArtifactFetcher = HttpArtifactFetcher(),
) : ArtifactFetcher {

    override fun fetch(url: String): ByteArray? {
        val tail = SPI_TAIL.find(url)?.value ?: return fallback.fetch(url)
        // A shipped artifact (.pom/.jar) resolves from resources; a probe we do not ship (the optional
        // Gradle Module Metadata `*.module`) resolves to "absent" so the resolver takes the POM path
        // locally instead of going to the network for a coordinate the IDE already owns.
        return javaClass.classLoader?.getResourceAsStream("spi-repo/$tail")?.use { it.readBytes() }
    }

    override fun fetchTo(url: String, dest: Path, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): Boolean {
        val bytes = fetch(url) ?: return false
        Files.write(dest, bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return true
    }

    private companion object {
        /** The Maven path of a bundled SPI artifact, e.g. `dev/ide/plugin-api/1.0.0/plugin-api-1.0.0.jar`. */
        val SPI_TAIL = Regex("""dev/ide/(?:plugin-api|platform-core)/1\.0\.0/[^/]+$""")
    }
}
