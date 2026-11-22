package eu.kanade.tachiyomi.network

/** Installed by the Android app before [NetworkHelper] is created. */
object EchProxyRegistry {
    @Volatile
    var provider: EchProxyProvider? = null
}
