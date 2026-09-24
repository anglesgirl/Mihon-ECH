package eu.kanade.tachiyomi.network

import android.content.Context
import android.util.Log
import com.anglesgirl.echsdk.EchLogger
import com.anglesgirl.echsdk.EchSdk

/** 将 ECH 初始化和网络配置完全移出启动界面路径。 */
object EchSdkState {
    private const val TAG = "Mihon-ECH"
    private const val ENABLED = true

    @Volatile private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (!ENABLED || installed) return
        try {
            EchSdk.install(
                context = context.applicationContext,
                config = EchSdk.Config(
                    protectedHosts = setOf(
                        "archiveofourown.org",
                    ),
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
            Log.i(TAG, "ECH SDK 初始化完成")
        } catch (t: Throwable) {
            Log.e(TAG, "ECH SDK 初始化失败；不启用受保护网络客户端", t)
            installed = false
        }
    }

    val enabled: Boolean get() = ENABLED && installed
}
