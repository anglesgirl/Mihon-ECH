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
            // keiyoushi index.pb 的资源 URL 若指向 github.com（H3/TCP 均不可达），
            // 改写到 cdn.jsdelivr.net（Cloudflare，走 ECH/H3 通道）；
            // 已是 cdn.jsdelivr.net 的 URL 原样保留。不切换到 Fastly（raw.githubusercontent.com）。
            apkUrl = mirrorToCloudflare(extension.resources.apkUrl),
            iconUrl = mirrorToCloudflare(extension.resources.iconUrl),
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
 * 将 github.com 资源 URL 改写为 cdn.jsdelivr.net（Cloudflare，ECH/H3 通道）：
 * - https://github.com/{owner}/{repo}/raw/{branch}/{path}
 *     → https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}
 * 已是 cdn.jsdelivr.net 或其他地址的 URL 原样返回。
 * 注意：不要改写为 raw.githubusercontent.com（Fastly，不支持 HTTP/3）。
 */
internal fun mirrorToCloudflare(url: String): String {
    val ghPrefix = "https://github.com/"
    if (!url.startsWith(ghPrefix)) return url
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
    val branchEnd = branchPath.indexOf('/')
    if (branchEnd <= 0) return url
    val branch = branchPath.substring(0, branchEnd)
    val filePath = branchPath.substring(branchEnd + 1)
    return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$filePath"
}
