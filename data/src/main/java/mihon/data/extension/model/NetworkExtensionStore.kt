package mihon.data.extension.model

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.data.extension.model.NetworkExtensionStore.ContentWarning
import mihon.data.extension.model.NetworkExtensionStore.ExtensionList
import mihon.domain.extension.model.ExtensionStore
import eu.kanade.tachiyomi.extension.model.Extension as TachiyomiExtension

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class NetworkExtensionStore(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList?,
    @ProtoNumber(102) val extensionListUrl: String?,
) : BaseNetworkExtensionStore {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String?,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning,
        @ProtoNumber(8) val sources: List<Source>,
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        // @ProtoNumber(6) val contentWarning: ContentWarning = ContentWarning.SAFE,
        @ProtoNumber(7) val message: String? = null,
    )

    @Suppress("Unused")
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }

    override fun toExtensionStore(indexUrl: String): ExtensionStore {
        return ExtensionStore(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = badgeLabel,
            signingKey = signingKey,
            contact = ExtensionStore.Contact(
                website = contact.website,
                discord = contact.discord,
            ),
            isLegacy = false,
            extensionListUrl = extensionListUrl,
        )
    }
}

fun ExtensionList.toAvailableExtensions(store: ExtensionStore): List<TachiyomiExtension.Available> {
    return extensions.map { extension ->
        val lang = extension.sources.map { it.language }.toSet()
        TachiyomiExtension.Available(
            name = extension.name,
            pkgName = extension.packageName,
            // keiyoushi index.pb 的资源 URL 指向 github.com / cdn.jsdelivr.net，
            // 前者 H3/TCP 均不可达，后者对冷文件 301 回 raw.githubusercontent.com。
            // 统一改写为 raw.githubusercontent.com（Fastly，明文 H3 通道直连）。
            apkUrl = mirrorToRawGithub(extension.resources.apkUrl),
            iconUrl = mirrorToRawGithub(extension.resources.iconUrl),
            libVersion = extension.extensionLib.toDouble(),
            versionCode = extension.versionCode,
            versionName = extension.versionName,
            lang = if (lang.size == 1) lang.first() else "all",
            isNsfw = extension.contentWarning >= ContentWarning.MIXED,
            sources = extension.sources.map { source ->
                TachiyomiExtension.Available.Source(
                    id = source.id,
                    name = source.name,
                    lang = source.language,
                    baseUrl = source.homeUrl,
                )
            },
            store = store,
        )
    }
}

/**
 * 将 keiyoushi 资源 URL 改写为 raw.githubusercontent.com（Fastly CDN，明文 H3 通道直连）：
 * - https://github.com/{owner}/{repo}/raw/{branch}/{path}
 *     → https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
 * - https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}
 *     → https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}
 * 其他地址原样返回。原因：github.com 的 H3/TCP 均不可达；
 * jsdelivr 对 keiyoushi 冷资源（apk/图标）一律 301 回 raw.githubusercontent.com，
 * 直接改写可跳过两座断桥。
 */
internal fun mirrorToRawGithub(url: String): String {
    // github.com/{owner}/{repo}/raw/{branch}/{path}
    val ghPrefix = "https://github.com/"
    if (url.startsWith(ghPrefix)) {
        val rest = url.removePrefix(ghPrefix)
        val ownerEnd = rest.indexOf('/')
        if (ownerEnd <= 0) return url
        val owner = rest.substring(0, ownerEnd)
        val afterOwner = rest.substring(ownerEnd + 1)
        val repoEnd = afterOwner.indexOf('/')
        if (repoEnd <= 0) return url
        val repo = afterOwner.substring(0, repoEnd)
        val afterRepo = afterOwner.substring(repoEnd + 1)
        if (!afterRepo.startsWith("raw/")) return url
        val branchPath = afterRepo.removePrefix("raw/")
        return "https://raw.githubusercontent.com/$owner/$repo/$branchPath"
    }
    // cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}
    val jdPrefix = "https://cdn.jsdelivr.net/gh/"
    if (url.startsWith(jdPrefix)) {
        val rest = url.removePrefix(jdPrefix)
        val ownerEnd = rest.indexOf('/')
        if (ownerEnd <= 0) return url
        val owner = rest.substring(0, ownerEnd)
        val afterOwner = rest.substring(ownerEnd + 1)
        val at = afterOwner.indexOf('@')
        if (at <= 0) return url
        val repo = afterOwner.substring(0, at)
        val branchPath = afterOwner.substring(at + 1)
        return "https://raw.githubusercontent.com/$owner/$repo/$branchPath"
    }
    return url
}
