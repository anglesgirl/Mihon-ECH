package eu.kanade.tachiyomi.ech

import android.content.Context
import android.os.Build
import echproxy.Echproxy
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures bounded ECH lifecycle/proxy logs for local export. */
class EchDiagnostics(private val context: Context) {
    private val scope = CoroutineScope(Job() + Dispatchers.IO)
    private val file = File(context.filesDir, "ech-diagnostics.log")
    private val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    @Volatile private var lastGoSnapshot = ""

    fun start() {
        append(
            "startup",
            "package=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}",
        )
        scope.launch {
            while (isActive) {
                flushGoLogs()
                delay(5_000)
            }
        }
    }

    fun event(name: String, detail: String = "") {
        append(name, detail.replace(Regex("(?i)(token|password|secret|key|cookie)=[^\\s&]+"), "$1=[REDACTED]"))
    }

    private fun flushGoLogs() {
        runCatching { Echproxy.diagnostics() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != lastGoSnapshot }
            ?.let {
                lastGoSnapshot = it
                append("go", it.takeLast(12_000))
            }
    }

    private fun append(name: String, detail: String) {
        runCatching {
            synchronized(file.path.intern()) {
                file.parentFile?.mkdirs()
                file.appendText("${formatter.format(Date())} [$name] $detail\n")
                if (file.length() > 256_000) {
                    val text = file.readText().takeLast(128_000)
                    file.writeText(text)
                }
            }
        }.onFailure { logcat(LogPriority.WARN, it) { "ECH diagnostics write failed" } }
    }
}
