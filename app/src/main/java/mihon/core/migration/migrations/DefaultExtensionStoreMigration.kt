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
        // 直接使用 jsdelivr 的 protobuf index：单请求即完整扩展列表 + 真实 signingKey，
        // 不经过 github.com（H3/TCP 均不可达）；CDN 由 Cloudflare 托管，走 ECH/H3 通道
        val indexUrl = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.pb"

        if (repository.getAll().none { it.indexUrl == indexUrl }) {
            // 离线入库：不依赖网络，商店源保证存在（signingKey 为占位，刷新后替换为真实值）
            repository.insertFromPreference(indexUrl, "Keiyoushi")
        } else {
            // 清理旧版 legacy URL 记录，避免重复源（旧链 index.min.json → repo.json → github.com）
            val legacyUrls = listOf(
                "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/index.min.json",
                "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/repo.json",
                "https://keiyoushi.github.io/extensions/index.min.json",
                "https://keiyoushi.github.io/extensions/repo.json",
            )
            repository.getAll().forEach { store ->
                if (store.indexUrl in legacyUrls) repository.remove(store.indexUrl)
            }
        }
        // 每次启动联网刷新（幂等）：成功则写入真实 signingKey（扩展自动受信）与扩展列表
        repository.insert(indexUrl)
        true
    }
}
