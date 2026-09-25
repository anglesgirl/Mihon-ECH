package eu.kanade.tachiyomi.network

import android.content.Context
import com.anglesgirl.echsdk.EchDoh
import dev.kathttp3.DnsResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import dev.kathttp3.KatHttp3Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 图片专用 H3 通道；失败由上层回退 Mihon 的 OkHttp。 */
object KatHttp3State {
    private const val ENABLED = true

    @Volatile private var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    suspend fun fetchImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        check(ENABLED) { "KatHttp3 图片通道尚未启用" }
        val context = checkNotNull(applicationContext) { "KatHttp3 尚未初始化" }
        val client = createClient(context)
        val response = client.use { it.execute(KatHttp3Request("GET", url)) }
        if (response.status !in 200..299) {
            throw IOException("H3 图片请求 HTTP ${response.status}")
        }
        response.body
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
                enableCookies = false,
            ),
            applicationContext = context,
        )
    }
}
