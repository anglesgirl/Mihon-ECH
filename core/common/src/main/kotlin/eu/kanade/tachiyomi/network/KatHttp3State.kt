package eu.kanade.tachiyomi.network

import android.content.Context
import com.anglesgirl.echsdk.EchSdk
import dev.kathttp3.DnsResolver
import dev.kathttp3.DohResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig
import dev.kathttp3.KatHttp3Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 独立实验 H3 通道；不替换 Mihon 的 OkHttp 主通道。 */
object KatHttp3State {
    private const val ENABLED = false
    private const val PROBE_HOST = "archiveofourown.org"
    private const val PROBE_URL = "https://archiveofourown.org/cdn-cgi/trace"

    @Volatile private var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    suspend fun probeAo3Trace(): String = withContext(Dispatchers.IO) {
        check(ENABLED) { "KatHttp3 实验通道尚未启用" }
        val context = checkNotNull(applicationContext) { "KatHttp3 尚未初始化" }
        val endpoint = checkNotNull(EchSdk.activeDohEndpoint()) { "ECH SDK 当前没有可用 DoH 网关" }
        val upstream = DohResolver(endpoint)
        val resolver = object : DnsResolver {
            override fun resolve(host: String, port: Int): List<dev.kathttp3.ResolvedAddress> {
                val resolved = upstream.resolve(host, port)
                if (host.equals(PROBE_HOST, ignoreCase = true) &&
                    resolved.none { it.echConfig?.isNotEmpty() == true }
                ) {
                    throw IOException("DoH 未返回 $PROBE_HOST 的 ECH 配置，已阻止明文连接")
                }
                return resolved
            }
        }
        KatHttp3Client(
            config = KatHttp3ClientConfig(
                resolver = resolver,
                enable0Rtt = false,
                enableCookies = false,
            ),
            applicationContext = context,
        ).use { client ->
            val response = client.execute(KatHttp3Request("GET", PROBE_URL))
            val body = response.body.toString(Charsets.UTF_8)
            if (response.status != 200) throw IOException("AO3 trace HTTP ${response.status}: $body")
            if ("http=http/3" !in body || "sni=encrypted" !in body) {
                throw IOException("H3/ECH 验收失败：$body")
            }
            body
        }
    }
}
