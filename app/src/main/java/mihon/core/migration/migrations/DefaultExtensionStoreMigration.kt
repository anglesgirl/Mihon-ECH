package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat

/** Adds the maintained Keiyoushi extension index once, without duplicating it. */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultExtensionStoreMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val indexUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"

        val existing = repository.getAll()
        logcat(LogPriority.INFO) {
            "ExtStore: migration check stores=${existing.size} urls=${existing.map { it.indexUrl }}"
        }
        if (existing.none { it.indexUrl == indexUrl }) {
            logcat(LogPriority.INFO) { "ExtStore: migration insert default store url=$indexUrl" }
            val result = repository.insert(indexUrl)
            logcat(LogPriority.INFO) {
                "ExtStore: migration insert result success=${result.isSuccess} " +
                    "error=${result.exceptionOrNull()?.message}"
            }
        } else {
            logcat(LogPriority.INFO) { "ExtStore: migration skip, default store already present" }
        }
        true
    }
}
