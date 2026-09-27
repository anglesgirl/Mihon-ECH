package mihon.telemetry

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // 数据上报到本项目自己的 Firebase（mihon-ech）。只对当前项目包名启用，
        // 避免其它签名/其它分支的测试包污染统计数据。
        if (!context.isMihonProductionApp()) return

        // Check if Google Play Services is available before initializing Firebase
        if (!isGooglePlayServicesAvailable(context)) {
            logcat(LogPriority.WARN) { "Google Play Services not available, skipping Firebase initialization" }
            return
        }

        try {
            analytics = FirebaseAnalytics.getInstance(context)
            analytics?.setUserProperty("preferred_abi", Build.SUPPORTED_ABIS[0])
            FirebaseApp.initializeApp(context)
            crashlytics = FirebaseCrashlytics.getInstance()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize Firebase" }
        }
    }

    private fun isGooglePlayServicesAvailable(context: Context): Boolean {
        return try {
            context.packageManager
                .getPackageInfo("com.google.android.gms", PackageManager.GET_META_DATA)
                .applicationInfo
                ?.enabled == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isMihonProductionApp(): Boolean = packageName in MIHON_PACKAGES
}

/* 本项目的包名白名单：release 主包 + preview（.debug 后缀）变体。
 * 原版按官方签名指纹过滤，数据归自己后不再需要指纹校验；
 * 包名是新注册的，只有本项目的构建才会命中。 */
private val MIHON_PACKAGES = hashSetOf(
    "com.anglesgirl.mihonech",
    "com.anglesgirl.mihonech.debug",
)
