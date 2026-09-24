package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import com.anglesgirl.echsdk.EchLogger
import com.anglesgirl.echsdk.EchSdk

/** Mihon 实验分支的单一 ECH 配置入口。网关和优选 IP 从 TXT 动态读取。 */
internal object EchSdkState {
    private const val TAG = "Mihon-ECH"
    private const val ENABLED = true
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (!ENABLED || installed) return
        EchSdk.install(
            context = context,
            config = EchSdk.Config(
                protectedHosts = setOf("archiveofourown.org"),
                dohUrl = null,
                dohBootstrapIps = emptyList(),
                userAgent = null,
                gatewayPoolTxt = "doh.xn--pn1aul.eu.org",
                preferredIpsTxt = "ip.xn--pn1aul.eu.org",
                logger = EchLogger { name, fields ->
                    Log.i(TAG, "$name ${fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
                },
            ),
        )
        installed = true
    }

    val enabled: Boolean get() = ENABLED && installed
}
