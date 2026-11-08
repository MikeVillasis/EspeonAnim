package io.github.mikevillasis.espeonanim.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingInfoTest {
    private val token = "0123456789abcdef0123456789abcdef"

    private fun ok(url: String): PairingInfo {
        val r = PairingInfo.parse(url)
        assertIs<PairingInfo.ParseResult.Ok>(r, "expected Ok for $url but got $r")
        return r.info
    }

    private fun invalid(url: String) {
        val r = PairingInfo.parse(url)
        assertIs<PairingInfo.ParseResult.Invalid>(r, "expected Invalid for $url but got $r")
        assertTrue(r.reason.isNotBlank())
    }

    @Test fun parsesValidCode() {
        val p = ok("espeon://pair?v=1&h=192.168.1.20&p=47800&t=$token&n=Miguel%27s%20PC")
        assertEquals("192.168.1.20", p.host)
        assertEquals(47800, p.port)
        assertEquals(token, p.token)
        assertEquals("Miguel's PC", p.pcName)
        assertEquals("http://192.168.1.20:47800", p.baseUrl)
    }

    @Test fun decodesUtf8AndPlus() {
        assertEquals("Café PC x", ok("espeon://pair?v=1&h=10.0.0.5&p=1&t=$token&n=Caf%C3%A9+PC%20x").pcName)
    }

    @Test fun nameIsOptional() {
        assertEquals("10.0.0.5", ok("espeon://pair?v=1&h=10.0.0.5&p=65535&t=$token").pcName)
    }

    @Test fun acceptsPrivateRangesAndHostnames() {
        for (h in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "169.254.1.1", "127.0.0.1", "DESKTOP-AB12", "mypc.local")) {
            ok("espeon://pair?v=1&h=$h&p=47800&t=$token&n=x")
        }
    }

    @Test fun rejectsPublicOrMalformedHosts() {
        for (h in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "1.2.3", "300.1.1.1", "10.0.0.256", "example.com", "", "a b", "-bad", "10.0.0.1.5", "[::1]")) {
            invalid("espeon://pair?v=1&h=$h&p=47800&t=$token")
        }
    }

    @Test fun rejectsBadPorts() {
        for (p in listOf("0", "65536", "-1", "abc", "", "123456", "4 7")) invalid("espeon://pair?v=1&h=10.0.0.1&p=$p&t=$token")
        invalid("espeon://pair?v=1&h=10.0.0.1&t=$token")
    }

    @Test fun rejectsBadTokens() {
        for (t in listOf("", "abc", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdef0", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz")) {
            invalid("espeon://pair?v=1&h=10.0.0.1&p=47800&t=$t")
        }
        invalid("espeon://pair?v=1&h=10.0.0.1&p=47800")
    }

    @Test fun rejectsWrongVersionOrScheme() {
        invalid("espeon://pair?v=2&h=10.0.0.1&p=47800&t=$token")
        invalid("espeon://pair?h=10.0.0.1&p=47800&t=$token")
        invalid("http://pair?v=1&h=10.0.0.1&p=47800&t=$token")
        invalid("espeon://other?v=1&h=10.0.0.1&p=47800&t=$token")
        invalid("hello")
        invalid("")
    }

    @Test fun rejectsBrokenEncodingAndDuplicates() {
        invalid("espeon://pair?v=1&h=10.0.0.1&p=47800&t=$token&n=%zz")
        invalid("espeon://pair?v=1&h=10.0.0.1&p=47800&t=$token&n=abc%2")
        invalid("espeon://pair?v=1&h=10.0.0.1&p=47800&t=$token&n=%FF")
        invalid("espeon://pair?v=1&h=10.0.0.1&h=8.8.8.8&p=47800&t=$token")
    }

    @Test fun toUriRoundTrips() {
        val p = PairingInfo("192.168.1.20", 47800, token, "Café & Co=PC")
        assertEquals(p, ok(p.toUri()))
    }

    @Test fun trimsWhitespaceAndUppercaseToken() {
        assertEquals(token, ok("  espeon://pair?v=1&h=10.0.0.1&p=47800&t=${token.uppercase()}\n").token)
    }
}
