package eu.kanade.tachiyomi.data.coil

import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.anglesgirl.echsdk.EchDoh
import eu.kanade.tachiyomi.network.KatHttp3State
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Request
import okio.FileSystem
import okio.buffer
import okio.source
import java.io.IOException

/** H3/ECH-first image fetcher with the existing OkHttp client as fallback. */
class KatHttp3ImageFetcher(
    private val uri: Uri,
    private val options: Options,
    private val callFactory: Call.Factory,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val url = uri.toString()
        val host = uri.host.orEmpty()
        if (uri.scheme != "https" || !EchDoh.isCloudflareHost(host)) {
            return fallback(url)
        }

        return try {
            val bytes = KatHttp3State.fetchImage(url)
            sourceResult(bytes, DataSource.NETWORK)
        } catch (e: CancellationException) {
            // 协程取消必须向上传播：fallback 会再发一次 OkHttp 请求，
            // 而取消场景（列表快速滚动/页面关闭）不需要也不应该继续加载
            throw e
        } catch (_: Exception) {
            fallback(url)
        }
    }

    private suspend fun fallback(url: String): FetchResult {
        val response = callFactory.newCall(Request.Builder().url(url).build()).await()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("图片请求失败：HTTP ${response.code}")
        }
        val body = checkNotNull(response.body) { "图片响应为空" }
        val bytes = try {
            body.bytes()
        } finally {
            response.close()
        }
        return sourceResult(bytes, DataSource.NETWORK)
    }

    private fun sourceResult(bytes: ByteArray, dataSource: DataSource): FetchResult {
        val source = bytes.inputStream().source().buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = dataSource,
        )
    }

    class Factory(
        private val callFactory: () -> Call.Factory,
    ) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "http" && data.scheme != "https") return null
            return KatHttp3ImageFetcher(data, options, callFactory())
        }
    }
}
