package io.github.mikevillasis.espeonanim.shared.transport

/**
 * What the Unreal plugin's QR code says: `espeon://pair?v=1&h=<host>&p=<port>&t=<token>&n=<name>`
 * (docs/formats.md section 5). Only LAN targets are accepted so a forged code cannot make the
 * phone upload a take to an address on the internet.
 */
data class PairingInfo(
    val host: String,
    val port: Int,
    val token: String,
    val pcName: String,
) {
    val baseUrl: String get() = "http://$host:$port"

    /** Same format as the QR code; used to persist the pairing. */
    fun toUri(): String =
        "espeon://pair?v=1&h=$host&p=$port&t=$token&n=${percentEncode(pcName)}"

    sealed interface ParseResult {
        data class Ok(val info: PairingInfo) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    companion object {
        const val SUPPORTED_VERSION = "1"

        fun parse(text: String): ParseResult {
            val raw = text.trim()
            val prefix = "espeon://pair?"
            if (!raw.startsWith(prefix, ignoreCase = true)) {
                return ParseResult.Invalid("This is not an EspeonAnim pairing code.")
            }
            val query = raw.substring(prefix.length).substringBefore('#')
            val params = HashMap<String, String>()
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val k = part.substringBefore('=')
                val v = if (part.contains('=')) part.substringAfter('=') else ""
                if (params.containsKey(k)) return ParseResult.Invalid("The pairing code is malformed (repeated field \"$k\").")
                params[k] = v
            }
            val v = params["v"]
            if (v != SUPPORTED_VERSION) {
                return ParseResult.Invalid(
                    if (v == null) "The pairing code is missing its version."
                    else "This pairing code is from a newer version. Update the EspeonAnim app.",
                )
            }
            val host = params["h"]?.trim().orEmpty()
            if (!isLanHost(host)) return ParseResult.Invalid("The pairing code does not point to a computer on your home network.")
            val port = params["p"]?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toIntOrNull()
            if (port == null || port !in 1..65535) return ParseResult.Invalid("The pairing code has an invalid port.")
            val token = params["t"].orEmpty().lowercase()
            if (!TOKEN.matches(token)) return ParseResult.Invalid("The pairing code has an invalid token.")
            val name = percentDecode(params["n"].orEmpty())?.trim()
                ?: return ParseResult.Invalid("The pairing code has an unreadable PC name.")
            return ParseResult.Ok(PairingInfo(host, port, token, name.ifEmpty { host }.take(64)))
        }

        private val TOKEN = Regex("[0-9a-f]{32}")
        private val HOSTNAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*")
        private val LAN_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")

        /** Private/link-local/loopback IPv4, a single-label hostname, or a name under a LAN-only suffix. */
        internal fun isLanHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val octets = host.split('.')
            if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
                val n = octets.map { it.takeIf { s -> s.length <= 3 }?.toIntOrNull() ?: return false }
                if (n.any { it !in 0..255 }) return false
                val (a, b) = n[0] to n[1]
                return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
            }
            if (host.all { it.isDigit() || it == '.' }) return false // malformed IPv4 such as 1.2.3
            if (!HOSTNAME.matches(host)) return false
            val h = host.lowercase()
            return !h.contains('.') || LAN_SUFFIXES.any { h.endsWith(it) }
        }

        /** URL-decodes UTF-8 percent escapes (and '+' as space); null if malformed. */
        internal fun percentDecode(s: String): String? {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '%' -> {
                        if (i + 3 > s.length) return null
                        val hi = s[i + 1].digitToIntOrNull(16) ?: return null
                        val lo = s[i + 2].digitToIntOrNull(16) ?: return null
                        out.add(((hi shl 4) or lo).toByte()); i += 3
                    }
                    c == '+' -> { out.add(' '.code.toByte()); i++ }
                    else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
                }
            }
            return try {
                out.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                null
            }
        }

        internal fun percentEncode(s: String): String {
            val sb = StringBuilder()
            for (b in s.encodeToByteArray()) {
                val v = b.toInt() and 0xFF
                val c = v.toChar()
                if (v < 128 && (c.isLetterOrDigit() || c in "-._~")) sb.append(c)
                else { sb.append('%'); sb.append("0123456789ABCDEF"[v shr 4]); sb.append("0123456789ABCDEF"[v and 15]) }
            }
            return sb.toString()
        }
    }
}
