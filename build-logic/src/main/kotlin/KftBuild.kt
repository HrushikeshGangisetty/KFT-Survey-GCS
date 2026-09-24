import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Access gradle/libs.versions.toml from inside convention plugins. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion
internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/**
 * Android namespace derived from the Gradle path, so modules never hand-type it:
 * ":core:planning" -> "com.kft.gcs.core.planning", ":feature:connections" -> "com.kft.gcs.feature.connections".
 */
internal val Project.kftNamespace: String
    get() = "com.kft.gcs" + path.replace(':', '.').replace('-', '_')
