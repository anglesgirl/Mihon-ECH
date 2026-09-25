package eu.kanade.tachiyomi.network

import dev.kathttp3.DnsResolver
import dev.kathttp3.DohResolver
import dev.kathttp3.ResolvedAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * CF 托管域名 ECH 解析器（DoH + 官方活值兜底）。
 *
 * 机制：
 * 1) 用用户配置的 DoH 解析目标域名（A/AAAA + HTTPS RR）。
 * 2) 目标域名自己发布了 ech 配置（HTTPS RR 有 ech=）→ 用之。
 * 3) 目标域名故意不发布（如 x.com 关闭 ECH）→ 注入 cloudflare-ech.com
 *    官方活值。CF 边缘全局持有该密钥，解密后按 inner SNI 路由到目标
 *    zone——"未发布配置"不影响边缘能力，ECH 必生效。
 *
 * 活值来源：cloudflare-ech.com 的 HTTPS RR，客户端启动拉一次、按轮换
 * 周期（默认 6 小时）刷新缓存，不依赖云端喂配置。
 */
class CfEchResolver(
    dohEndpoints: List<String>,
    private val officialEchConfigDomain: String = "cloudflare-ech.com",
    private val refreshIntervalMs: Long = 6 * 60 * 60 * 1000L,
) : DnsResolver {

    private val doh = DohResolver(
        endpoint = dohEndpoints.firstOrNull() ?: "https://cloudflare-dns.com/dns-query",
    )

    /** CF 官方活值缓存（cloudflare-ech.com 的 ech 配置）。 */
    private val officialEch = AtomicReference<ByteArray?>(null)
    private val lastRefresh = AtomicReference(0L)

    private fun refreshOfficialEch() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh.get() < refreshIntervalMs) return
        synchronized(this) {
            if (now - lastRefresh.get() < refreshIntervalMs) return
            val live = runCatching {
                doh.resolve(officialEchConfigDomain, 443)
                    .firstOrNull { it.echConfig != null }
                    ?.echConfig
            }.getOrNull()
            if (live != null) officialEch.set(live)
            lastRefresh.set(now)
        }
    }

    override fun resolve(host: String, port: Int): List<ResolvedAddress> {
        val resolved = doh.resolve(host, port)
        // 目标域名自己有 ech 配置 → 用它（标准路径）
        if (resolved.any { it.echConfig != null }) return resolved
        // 目标域名没有发布 ech 配置 → 注入 CF 官方活值（兜底路径）
        refreshOfficialEch()
        val live = officialEch.get() ?: return resolved
        return resolved.map { it.copy(echConfig = live) }
    }
}
