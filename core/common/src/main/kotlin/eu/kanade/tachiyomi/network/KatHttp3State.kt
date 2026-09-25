package eu.kanade.tachiyomi.network

import android.content.Context
import dev.kathttp3.DohResolver
import dev.kathttp3.KatHttp3Client
import dev.kathttp3.KatHttp3ClientConfig

/** Experimental H3 channel; kept disabled until Mihon-side ECH/DoH routing is wired. */
object KatHttp3State {
    private const val ENABLED = false

    @Volatile private var client: KatHttp3Client? = null

    fun install(context: Context, dohUrl: String?): Boolean {
        if (!ENABLED || dohUrl.isNullOrBlank()) return false
        if (client != null) return true
        synchronized(this) {
            if (client == null) {
                client = KatHttp3Client(
                    config = KatHttp3ClientConfig(
                        resolver = DohResolver(dohUrl),
                    ),
                    applicationContext = context.applicationContext,
                )
            }
        }
        return true
    }

    fun get(): KatHttp3Client? = client
}
