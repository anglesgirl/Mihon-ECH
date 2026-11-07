package mihon.data.extension.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.data.extension.model.NetworkExtensionStore
import mihon.data.extension.model.NetworkLegacyExtension
import mihon.data.extension.model.NetworkLegacyExtensionRepo
import mihon.data.extension.model.toAvailableExtensions
import mihon.domain.extension.model.ExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

@Inject
@SingleIn(AppScope::class)
class ExtensionStoreService(
    private val network: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    suspend fun fetch(indexUrl: String): Result<ExtensionStore> = withContext(Dispatchers.IO) { fetchStore(indexUrl) }

    /**
     * Fetches a store, falling back to the self-hosted mirror when the
     * upstream jsDelivr address fails: on the user's network its TLS
     * handshake gets Connection reset, so a jsDelivr-only fetch leaves the
     * store table empty. The mirror serves the same repo files.
     */
    private suspend fun fetchStore(indexUrl: String): Result<ExtensionStore> {
        val result = fetchStoreOnce(indexUrl)
        if (result.isSuccess) return result
        val fallbackUrl = jsDelivrToMirror(indexUrl) ?: return result
        logcat(LogPriority.INFO) {
            "ExtStore: fetch fallback url=$indexUrl fallbackUrl=$fallbackUrl " +
                "error=${result.exceptionOrNull()?.message}"
        }
        return fetchStoreOnce(fallbackUrl)
    }

    private fun jsDelivrToMirror(indexUrl: String): String? {
        val prefix = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions@repo/"
        if (!indexUrl.startsWith(prefix)) return null
        return "https://gh.anglesya.win/keiyoushi/extensions/raw/repo/" + indexUrl.removePrefix(prefix)
    }

    /**
     * Same rewrite rule as GithubReleaseMirrorInterceptor, applied to the
     * stored address: a github.com /<owner>/<repo>/raw/... URL becomes the
     * mirror URL, so the store entry itself points at the mirror instead of
     * only being rewritten per-request. Returns null when the URL is not a
     * mirrorable GitHub raw URL.
     */
    private fun githubRawToMirror(url: String): String? {
        val path = url.removePrefix("https://github.com/")
        if (path == url) return null
        val segments = path.split("/")
        if (segments.size < 5 || segments[2] != "raw" || segments[3].isEmpty() || segments[4].isEmpty()) {
            return null
        }
        return "https://gh.anglesya.win/$path"
    }

    private suspend fun fetchStoreOnce(indexUrl: String): Result<ExtensionStore> {
        var updatedIndexUrl: String = indexUrl
        return try {
            logcat(LogPriority.INFO) { "ExtStore: fetch start url=$updatedIndexUrl" }
            val response = network.client.newCall(GET(updatedIndexUrl)).awaitSuccess()
            logcat(LogPriority.INFO) {
                "ExtStore: fetch ok url=$updatedIndexUrl code=${response.code} finalUrl=${response.request.url}"
            }
            val store = response.body.source().decompressIfGzipped().use { source ->
                val networkStore = when (source.peek().readByte()) {
                    // "[..."
                    0x5B.toByte() -> run {
                        logcat(LogPriority.INFO) {
                            "ExtStore: format=json-array (legacy index.min.json) url=$updatedIndexUrl"
                        }
                        if (!indexUrl.endsWith("/index.min.json")) {
                            throw IllegalArgumentException("Provided legacy store url is not valid")
                        }
                        updatedIndexUrl = indexUrl.replace("/index.min.json", "/repo.json")
                        logcat(LogPriority.INFO) { "ExtStore: fetch repo.json url=$updatedIndexUrl" }
                        network.client.newCall(GET(updatedIndexUrl)).awaitSuccess().body.source().use {
                            json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(it)
                        }
                    }
                    // "{..."
                    0x7B.toByte() -> try {
                        logcat(LogPriority.INFO) { "ExtStore: format=json-object url=$updatedIndexUrl" }
                        json.decodeFromBufferedSource<NetworkLegacyExtensionRepo>(source.peek())
                    } catch (_: IllegalArgumentException) {
                        json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                    }
                    else -> {
                        logcat(LogPriority.INFO) { "ExtStore: format=protobuf (index.pb) url=$updatedIndexUrl" }
                        protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                    }
                }

                if (networkStore is NetworkLegacyExtensionRepo && networkStore.indexV2 != null) {
                    val redirectUrl = networkStore.indexV2
                    val mirroredUrl = githubRawToMirror(redirectUrl)
                    if (mirroredUrl != null) {
                        logcat(LogPriority.INFO) {
                            "ExtStore: indexV2 redirect from=$updatedIndexUrl to=$redirectUrl " +
                                "mirrored=$mirroredUrl"
                        }
                        val mirrored = fetchStoreOnce(mirroredUrl)
                        if (mirrored.isSuccess) return mirrored
                        logcat(LogPriority.INFO) {
                            "ExtStore: mirrored indexV2 fetch failed, retry original url=$redirectUrl " +
                                "error=${mirrored.exceptionOrNull()?.message}"
                        }
                    } else {
                        logcat(LogPriority.INFO) {
                            "ExtStore: indexV2 redirect from=$updatedIndexUrl to=$redirectUrl"
                        }
                    }
                    return fetchStoreOnce(redirectUrl)
                }

                networkStore.toExtensionStore(updatedIndexUrl)
            }
            logcat(LogPriority.INFO) {
                "ExtStore: fetch done name=${store.name} indexUrl=${store.indexUrl} " +
                    "extensionListUrl=${store.extensionListUrl} isLegacy=${store.isLegacy} " +
                    "signingKey=${store.signingKey}"
            }
            Result.success(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "ExtStore: fetch failed url=$updatedIndexUrl error=${e.message}"
            }
            Result.failure(e)
        }
    }

    suspend fun getExtensions(store: ExtensionStore): Result<List<Extension.Available>> = withContext(Dispatchers.IO) {
        try {
            val extensions = if (store.extensionListUrl != null) {
                logcat(LogPriority.INFO) {
                    "ExtStore: getExtensions via extensionListUrl store=${store.name} url=${store.extensionListUrl}"
                }
                val response = network.client.newCall(GET(store.extensionListUrl!!)).awaitSuccess()
                response.body.source().decompressIfGzipped().use { source ->
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore.ExtensionList>(source)
                        else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(
                            source.readByteArray(),
                        )
                    }
                        .toAvailableExtensions(store)
                }
            } else if (!store.isLegacy) {
                logcat(LogPriority.INFO) {
                    "ExtStore: getExtensions via indexUrl store=${store.name} url=${store.indexUrl}"
                }
                val response = network.client.newCall(GET(store.indexUrl)).awaitSuccess()
                response.body.source().decompressIfGzipped().use { source ->
                    when (source.peek().readByte()) {
                        // "{..."
                        0x7B.toByte() -> json.decodeFromBufferedSource<NetworkExtensionStore>(source)
                        else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(source.readByteArray())
                    }
                        .extensionList!!
                        .toAvailableExtensions(store)
                }
            } else {
                val storeBaseUrl = store.indexUrl.removeSuffix("/repo.json")
                logcat(LogPriority.INFO) {
                    "ExtStore: getExtensions via legacy index.min.json store=${store.name} base=$storeBaseUrl"
                }
                val response = network.client.newCall(GET("$storeBaseUrl/index.min.json")).awaitSuccess()
                response.body.source().use { source ->
                    json.decodeFromBufferedSource<List<NetworkLegacyExtension>>(source)
                        .map { it.toAvailableExtension(store, storeBaseUrl) }
                }
            }
            logcat(LogPriority.INFO) {
                "ExtStore: getExtensions done store=${store.name} count=${extensions.size}"
            }
            Result.success(extensions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "ExtStore: getExtensions failed store=${store.name} indexUrl=${store.indexUrl} " +
                    "extensionListUrl=${store.extensionListUrl} error=${e.message}"
            }
            Result.failure(e)
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }
}
