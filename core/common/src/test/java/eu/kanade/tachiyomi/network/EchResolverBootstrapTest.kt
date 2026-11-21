package eu.kanade.tachiyomi.network

import dev.kathttp3.ResolvedAddress
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EchResolverBootstrapTest {
    @Test
    fun `parses historical TXT aliases and falls back to settings for invalid TXT endpoints`() {
        val ech = byteArrayOf(1, 2, 3)
        val encodedEch = Base64.getEncoder().encodeToString(ech)
        val parsed = EchResolverBootstrap.parseTxt(
            pieces = listOf(
                "\"doh=https://one.example/dns-query; ip=192.0.2.4,2001:db8::4\"",
                "\"doh2=http://insecure.example/dns-query; " +
                    "doh3=https://three.example/dns-query\"\nechconfig=$encodedEch",
            ),
            configuredEndpoints = listOf("https://settings.example/dns-query"),
            configuredIps = listOf("198.51.100.9"),
        )

        assertEquals(
            listOf("https://one.example/dns-query", "https://three.example/dns-query"),
            parsed.dohEndpoints,
        )
        assertEquals(listOf("192.0.2.4", "2001:db8::4"), parsed.edgeIps)
        assertTrue(parsed.echConfig!!.contentEquals(ech))

        val aliases = EchResolverBootstrap.parseTxt(
            pieces = listOf("doh2=https://two.example/dns-query; ips=203.0.113.7; ech=$encodedEch"),
            configuredEndpoints = emptyList(),
            configuredIps = emptyList(),
        )
        assertEquals(listOf("https://two.example/dns-query"), aliases.dohEndpoints)
        assertEquals(listOf("203.0.113.7"), aliases.edgeIps)
        assertTrue(aliases.echConfig!!.contentEquals(ech))

        val fallback = EchResolverBootstrap.parseTxt(
            pieces = listOf("doh=http://not-tls.example/dns-query; ips=not-an-ip"),
            configuredEndpoints = listOf("http://ignored.example", "https://settings.example/dns-query"),
            configuredIps = listOf("198.51.100.9", "example.com"),
        )
        assertEquals(listOf("https://settings.example/dns-query"), fallback.dohEndpoints)
        assertEquals(listOf("198.51.100.9"), fallback.edgeIps)
    }

    @Test
    fun `tries DoH endpoints in order until a usable response`() {
        val endpoints = listOf("first", "second", "third")
        val queried = mutableListOf<String>()
        val attempts = mutableListOf<Pair<Int, String>>()
        val result = EchResolverBootstrap.firstUsableEndpoint(
            endpoints = endpoints,
            isUsable = { it.isNotEmpty() },
            query = { endpoint ->
                queried += endpoint
                when (endpoint) {
                    "first" -> error("first endpoint failed")
                    "second" -> emptyList()
                    else -> listOf("address")
                }
            },
            onAttempt = { index, outcome -> attempts += index to outcome },
        )

        assertEquals(listOf("address"), result)
        assertEquals(listOf("first", "second", "third"), queried)
        assertEquals(listOf(1 to "error", 2 to "unusable", 3 to "usable"), attempts)
    }

    @Test
    fun `configured edge IPs are used only when DNS has no usable address`() {
        val fallbackEch = byteArrayOf(8)
        val dnsEch = byteArrayOf(9)
        val dnsAddresses = listOf(
            ResolvedAddress("192.0.2.20", 443, dnsEch),
            ResolvedAddress("origin.example", 443),
        )
        val preferred = EchResolverBootstrap.addressesWithFallback(
            dnsAddresses = dnsAddresses,
            edgeIps = listOf("198.51.100.20"),
            port = 443,
            fallbackEchConfig = fallbackEch,
        )
        assertEquals(listOf("192.0.2.20"), preferred.map { it.ip })
        assertSame(dnsEch, preferred.single().echConfig)

        val fallback = EchResolverBootstrap.addressesWithFallback(
            dnsAddresses = listOf(ResolvedAddress("not-an-ip", 443)),
            edgeIps = listOf("198.51.100.20", "2001:db8::20", "origin.example", "300.1.1.1"),
            port = 8443,
            fallbackEchConfig = fallbackEch,
        )
        assertEquals(listOf("198.51.100.20", "2001:db8::20"), fallback.map { it.ip })
        assertTrue(fallback.all { it.port == 8443 && it.echConfig === fallbackEch })
        assertFalse(EchResolverBootstrap.isLiteralIp("origin.example"))
    }
}
