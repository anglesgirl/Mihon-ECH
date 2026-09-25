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

/** 独立实验 H3 通道；不替换 Mihon 的 OkHttp 主通道。 */
object KatHttp3State {
    private const val ENABLED = true
    private const val PROBE_HOST = "archiveofourown.org"
    private const val PROBE_URL = "https://archiveofourown.org/cdn-cgi/trace"

    @Volatile private var applicationContext: Context? = null

    fun install(context: Context) {
        applicationContext = context.applicationContext
    }

    suspend fun probeAo3Trace(): String = withContext(Dispatchers.IO) {
        check(ENABLED) { "KatHttp3 实验通道尚未启用" }
        val context = checkNotNull(applicationContext) { "KatHttp3 尚未初始化" }
        val resolver = object : DnsResolver {
            override fun resolve(host: String, port: Int): List<dev.kathttp3.ResolvedAddress> {
                val ech = EchDoh.echConfigList(host)
                if (host.equals(PROBE_HOST, ignoreCase = true)) {
                    check(!ech.isNullOrEmpty()) {
                        "DoH 未返回 $PROBE_HOST 的 ECH 配置，已阻止明文连接"
                    }
                }
                return EchDoh.resolve(host).map { address ->
                    dev.kathttp3.ResolvedAddress(address.hostAddress ?: error("无效地址"), port, ech)
                }
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
