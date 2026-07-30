package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository

/** Adds the maintained Keiyoushi extension index once, without duplicating it. */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultExtensionStoreMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val indexUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"

        if (repository.getAll().none { it.indexUrl == indexUrl }) {
            repository.insert(indexUrl)
        }
        true
    }
}
