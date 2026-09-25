package eu.kanade.tachiyomi.ech

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.EchH3Diag
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收集 ECH/H3 诊断事件到文件，并导出为可分享文本。
 *
 * 设置页"导出诊断日志"入口调用 [shareIntent]，
 * 输出：应用/设备信息 + ECH/H3 传输日志 + 扩展状态。
 */
object EchLogExporter {

    private const val MAX_FILE_BYTES = 256_000
    private const val KEEP_BYTES = 128_000

    @Volatile private var logFile: File? = null

    /** 挂载 sink 到文件，并在启动时写入基线信息。 */
    fun init(context: Context) {
        val file = File(context.filesDir, "ech-h3.log")
        logFile = file
        EchH3Diag.sink = { message ->
            try {
                synchronized(file.path.intern()) {
                    file.parentFile?.mkdirs()
                    file.appendText("${timestamp()} $message\n")
                    if (file.length() > MAX_FILE_BYTES) {
                        file.writeText(file.readText().takeLast(KEEP_BYTES))
                    }
                }
            } catch (_: Throwable) {
            }
        }
        EchH3Diag.log("=== app start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ===")
    }

    /** 生成系统信息 + 诊断日志 + 扩展状态的分享 Intent。 */
    fun shareIntent(context: Context): Intent {
        val sb = StringBuilder()
        sb.append("Mihon-ECH 诊断日志\n")
        sb.append("应用: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: SDK ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})\n")
        sb.append("时间: ${timestamp()}\n\n")

        sb.append("--- 扩展状态 ---\n")
        runCatching {
            val extMgr = Injekt.get<ExtensionManager>()
            val installed = extMgr.installedExtensionsFlow.value
            val untrusted = extMgr.untrustedExtensionsFlow.value
            val available = extMgr.availableExtensionsFlow.value
            sb.append("已安装: ${installed.size}\n")
            installed.forEach { sb.append("  [OK] ${it.name} ${it.versionName}\n") }
            sb.append("未信任: ${untrusted.size}\n")
            untrusted.forEach { sb.append("  [!] ${it.name} ${it.versionName}\n") }
            sb.append("可用(商店): ${available.size}\n")
        }.onFailure {
            sb.append("扩展状态读取失败: ${it.javaClass.simpleName}: ${it.message}\n")
        }
        sb.append("\n--- ECH/H3 日志 ---\n")
        sb.append(logFile?.takeIf { it.exists() }?.readText() ?: "(无日志)")
        sb.append("\n--- 说明 ---\n")
        sb.append("完整 logcat 可用: adb logcat -s ECH/H3 *:S\n")

        val shareFile = File(context.cacheDir, "mihon-ech-diagnostics.txt")
        shareFile.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.provider",
            shareFile,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mihon-ECH 诊断日志 ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
