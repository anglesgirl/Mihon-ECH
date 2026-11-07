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

/**
 * Adds the maintained Keiyoushi extension index once, without duplicating it.
 *
 * Default store goes through the self-hosted EdgeOne mirror: the upstream
 * default (jsDelivr index.min.json) gets Connection reset during the TLS
 * handshake on the user's network, so the migration insert fails and the
 * store table stays empty (2026-11-07 echfix3 log). A successful insert
 * stores the index.pb URL (after the repo.json indexV2 redirect), not the
 * index.min.json URL it was fetched from, so presence is checked against
 * both. The stored URL is the mirror address: the interceptor already
 * rewrote github.com fetches to the mirror per-request, but the entry
 * itself still showed github.com (echfix4), so it is rewritten here too.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultExtensionStoreMigration(
    private val repository: ExtensionStoreRepository,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val indexUrl = "https://gh.anglesya.win/keiyoushi/extensions/raw/repo/index.min.json"
        val legacyIndexUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json"
        val resolvedIndexUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        val resolvedMirrorIndexUrl = "https://gh.anglesya.win/keiyoushi/extensions/raw/repo/index.pb"

        val existing = repository.getAll()
        logcat(LogPriority.INFO) {
            "ExtStore: migration check stores=${existing.size} urls=${existing.map { it.indexUrl }}"
        }
        if (existing.any { it.indexUrl == legacyIndexUrl }) {
            logcat(LogPriority.INFO) { "ExtStore: migration remove legacy jsDelivr store url=$legacyIndexUrl" }
            repository.remove(legacyIndexUrl)
        }
        val githubStore = existing.firstOrNull { it.indexUrl == resolvedIndexUrl }
        if (githubStore != null) {
            logcat(LogPriority.INFO) {
                "ExtStore: migration rewrite store url=$resolvedIndexUrl to=$resolvedMirrorIndexUrl"
            }
            repository.upsert(githubStore.copy(indexUrl = resolvedMirrorIndexUrl))
            repository.remove(resolvedIndexUrl)
        }
        val current = repository.getAll()
        val alreadyPresent = current.any {
            it.indexUrl == indexUrl || it.indexUrl == resolvedMirrorIndexUrl || it.indexUrl == resolvedIndexUrl
        }
        if (alreadyPresent) {
            logcat(LogPriority.INFO) { "ExtStore: migration skip, default store already present" }
        } else {
            logcat(LogPriority.INFO) { "ExtStore: migration insert default store url=$indexUrl" }
            val result = repository.insert(indexUrl)
            logcat(LogPriority.INFO) {
                "ExtStore: migration insert result success=${result.isSuccess} " +
                    "error=${result.exceptionOrNull()?.message}"
            }
        }
        true
    }
}
