package mihon.core.migration.migrations

import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.lang.withIOContext

/** Adds the maintained Keiyoushi extension index once, without duplicating it. */
class DefaultExtensionStoreMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val repository = migrationContext.get<ExtensionStoreRepository>() ?: return@withIOContext false
        val indexUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"

        if (repository.getAll().none { it.indexUrl == indexUrl }) {
            repository.insert(indexUrl)
        }
        true
    }
}
