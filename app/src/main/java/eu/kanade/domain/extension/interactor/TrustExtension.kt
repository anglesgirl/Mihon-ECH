package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.getAndSet

class TrustExtension(
    private val repository: ExtensionStoreRepository,
    private val preferences: SourcePreferences,
) {

    /**
     * 签名级信任：
     * - 签名在内置仓库白名单 → 信任
     * - 签名 hash 与任意已信任记录匹配（忽略包名/版本）→ 信任
     * 同源扩展（签名一致）任意版本自动信任，不再每次手动确认。
     */
    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = repository.getAll().map { it.signingKey }.toHashSet()
        if (trustedFingerprints.any { fingerprints.contains(it) }) return true
        val signatureHash = fingerprints.last()
        return preferences.trustedExtensions.get().any { record ->
            // 新格式：纯签名；旧格式：pkg:version:签名
            record == signatureHash || record.endsWith(":$signatureHash")
        }
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // 只按签名记录，不绑版本：版本更新后仍可信
            (exts.filterNot { it.endsWith(":$signatureHash") } + signatureHash).toMutableSet()
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }
}
