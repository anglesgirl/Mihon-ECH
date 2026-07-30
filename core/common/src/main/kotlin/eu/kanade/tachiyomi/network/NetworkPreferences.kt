package eu.kanade.tachiyomi.network

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class NetworkPreferences(
    preferenceStore: PreferenceStore,
    verboseLoggingDefault: Boolean = false,
) {

    val verboseLogging: Preference<Boolean> = preferenceStore.getBoolean("verbose_logging", verboseLoggingDefault)

    val dohProvider: Preference<Int> = preferenceStore.getInt("doh_provider", -1)

    // Optional generic TLS ECH routing through the bundled local proxy.
    val echEnabled: Preference<Boolean> = preferenceStore.getBoolean("ech_enabled", false)

    val echConfigDomain: Preference<String> = preferenceStore.getString(
        "ech_config_domain",
        "ech-config.anglesgirl.eu.org",
    )

    val echDohEndpoints: Preference<String> = preferenceStore.getString(
        "ech_doh_endpoints",
        "https://pieqllv9i7.cloudflare-gateway.com/dns-query,https://m2b4x7vw98.cloudflare-gateway.com/dns-query,https://dz1598pphb.cloudflare-gateway.com/dns-query",
    )

    val echIpList: Preference<String> = preferenceStore.getString(
        "ech_ip_list",
        "162.159.27.168,162.159.11.204,172.64.144.100,198.41.222.174,172.64.148.169,162.159.40.8,172.64.41.127,172.64.40.118,104.18.47.94,162.159.9.27",
    )

    val defaultUserAgent: Preference<String> = preferenceStore.getString(
        "default_user_agent",
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
    )
}
