package eu.kanade.tachiyomi.network

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * ECH/H3 诊断事件通道。
 *
 * core 层（拦截器/传输）只调用 [log]，默认落到 logcat（tag ECH/H3）；
 * app 层可注入 [sink] 追加写入文件，供设置页"导出诊断日志"使用。
 */
object EchH3Diag {

    @Volatile
    var sink: (String) -> Unit = { message ->
        logcat(LogPriority.INFO) { message }
    }

    fun log(message: String) {
        runCatching { sink(message) }
    }
}
