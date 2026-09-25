package eu.kanade.tachiyomi.network

import android.content.Context
import com.anglesgirl.echsdk.EchDoh
import dev.kathttp3.DnsResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import dev.kathttp3.KatHttp3Header
import dev.kathttp3.KatHttp3Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Mihon 请求复用的 QUIC/HTTP3 客户端。 */
object KatHttp3State {
    private const val ENABLED = true

    @Volatile private var applicationContext: Context? = null

    @Volatile private var client: KatHttp3Client? = null

    @Synchronized
    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    suspend fun execute(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
    ): dev.kathttp3.KatHttp3Response = withContext(Dispatchers.IO) {
        check(ENABLED) { "KatHttp3 未启用" }
        try {
            getClient().execute(
                KatHttp3Request(
                    method = method,
                    url = url,
                    headers = headers.map { (name, value) -> KatHttp3Header(name, value) },
                    body = body,
                ),
            )
        } catch (t: Throwable) {
            EchH3Diag.log(
                "ECH/H3: transport error method=$method url=$url " +
                    "err=${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        }
    }

    suspend fun fetchImage(url: String): ByteArray {
        val response = execute("GET", url, emptyList(), null)
        if (response.status !in 200..299) throw IOException("H3 图片请求 HTTP ${response.status}")
        return response.body
    }

    private fun getClient(): KatHttp3Client {
        client?.let { return it }
        return synchronized(this) {
            client ?: createClient(checkNotNull(applicationContext) { "KatHttp3 未初始化" })
                .also { client = it }
        }
    }

    private fun createClient(context: Context): KatHttp3Client {
        val resolver = object : DnsResolver {
            override fun resolve(host: String, port: Int): List<dev.kathttp3.ResolvedAddress> {
                val ech = EchDoh.echConfigList(host)
                val addresses = EchDoh.resolve(host)
                if (addresses.isEmpty()) throw IOException("DoH 未解析到 $host")
                return addresses.map { address ->
                    dev.kathttp3.ResolvedAddress(address.hostAddress ?: error("无效地址"), port, ech)
                }
            }
        }
        return KatHttp3Client(
            config = KatHttp3ClientConfig(
                resolver = resolver,
                enable0Rtt = false,
                enableCookies = true,
            ),
            applicationContext = context,
        )
    }
}
