package dev.ide.ui.backend

/**
 * One plugin offered by the community plugin store, as the index describes it. Neutral to where the index
 * came from: today a single JSON document in a public repository, so anyone can publish a plugin without
 * touching the IDE.
 *
 * [id] is the id the plugin's OWN manifest declares — the same id the loader registers it under — so an
 * installed plugin matches an index entry and the card can say "installed" rather than offer a second copy.
 * [apkUrl] is where the plugin app (an ordinary Android APK) comes from; [sha256], when the index carries
 * one, is verified before the installer is ever offered. [installedVersion] is the version already on the
 * device, read from that APK's manifest, so a card can offer "Actualizar" as well as "Instalar".
 *
 * [price] is a display string the index owns ("Gratis" / "5,00 €" / …). Nothing is charged: the field exists
 * so a paid plugin has a home in the model before there is anything to charge with.
 */
data class UiPluginStoreItem(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    /** An icon image URL, or null when the entry has none (the card then shows a generic plugin glyph). */
    val iconUrl: String? = null,
    val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK, verified after download; null ⇒ no publisher digest to check. */
    val sha256: String? = null,
    val tags: List<String> = emptyList(),
    /** The oldest IDE that can load this plugin, or null when it states none. */
    val minHostVersion: String? = null,
    val price: String? = null,
    /** The version installed on this device for [id], or null when the plugin isn't installed. */
    val installedVersion: String? = null,
) {
    /** Whether this plugin is already on the device (so the card offers an update, not a first install). */
    val installed: Boolean get() = installedVersion != null
}

/**
 * The store landing payload, plus the reason a load failed (no network, an index that isn't valid JSON) so
 * the screen can SAY so instead of showing an empty list with no explanation. [error] null ⇒ [items] is
 * authoritative.
 */
data class UiPluginStoreCatalog(
    val items: List<UiPluginStoreItem> = emptyList(),
    val error: String? = null,
)

/**
 * The outcome of installing a store plugin. On success [apkPath] is the downloaded APK, which the host hands
 * to the platform package installer: the IDE never installs an app itself, because on every platform that is
 * the OS's job and the user's decision to confirm.
 */
data class UiPluginInstallResult(
    val success: Boolean,
    val message: String,
    val apkPath: String? = null,
)

/**
 * The community plugin store: the catalog of plugins people published, and the download half of installing
 * one. Deliberately narrower than [StoreService], which sells projects — a plugin is an APK, not a workspace,
 * so it is not created into a project and its "install" ends at a downloaded archive.
 *
 * A backend that wires no catalog inherits [PluginStoreService.Unsupported]. The Plugins screen keys its
 * "Explorar" tab off [storeAvailable], so a build with no store simply has no such tab.
 */
interface PluginStoreService {
    /** Whether a catalog source is configured. False ⇒ the store tab is not offered. */
    fun storeAvailable(): Boolean = false

    /** The store catalog: every published plugin, each stamped with the version installed here (if any). */
    suspend fun catalog(): UiPluginStoreCatalog = UiPluginStoreCatalog()

    /** Items matching [query] (blank = every item), from the same catalog [catalog] serves. */
    suspend fun search(query: String): List<UiPluginStoreItem> = emptyList()

    /**
     * Download the plugin [id]'s APK to a local path, verifying [UiPluginStoreItem.sha256] when the index
     * publishes one. Returns that path for the host to install; it does NOT install it, and does not report
     * the plugin as installed — the platform's own installer, and a restart of the IDE, are what make it so.
     */
    suspend fun install(id: String): UiPluginInstallResult =
        UiPluginInstallResult(false, "La tienda de complementos no está disponible en esta compilación")

    companion object {
        /** A store that advertises nothing — the default for backends that wire no catalog. */
        val Unsupported: PluginStoreService = object : PluginStoreService {}
    }
}
