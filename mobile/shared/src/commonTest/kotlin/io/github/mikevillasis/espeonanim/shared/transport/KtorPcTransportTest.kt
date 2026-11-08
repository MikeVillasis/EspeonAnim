package io.github.mikevillasis.espeonanim.shared.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorPcTransportTest {
    private val pairing = PairingInfo("192.168.1.20", 47800, "0123456789abcdef0123456789abcdef", "PC")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun transport(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): KtorPcTransport {
        val client = HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(KtorPcTransport.json) } }
        return KtorPcTransport(pairing, client)
    }

    private class BytesSource(val data: ByteArray) : UploadSource {
        override val length = data.size.toLong()
        override fun open() = ByteReadChannel(data)
    }

    @Test fun pingSendsNoTokenAndParses() = runTest {
        var auth: String? = "unset"
        val t = transport { req ->
            auth = req.headers[HttpHeaders.Authorization]
            assertEquals("/api/v1/ping", req.url.encodedPath)
            respond("""{"name":"My PC","app":"EspeonAnim","version":"0.1.0","api":1}""", HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals("My PC", t.ping().name)
        assertNull(auth)
    }

    @Test fun pingFromOtherAppIsRejected() = runTest {
        val t = transport { respond("""{"name":"x","app":"Other"}""", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<PcTransportException.Server> { t.ping() }
    }

    @Test fun uploadStreamsBodyWithBearerAndReturnsId() = runTest {
        var received = 0
        var auth: String? = null
        var method: HttpMethod? = null
        val t = transport { req ->
            auth = req.headers[HttpHeaders.Authorization]
            method = req.method
            received = req.body.toByteArray().size
            respond("""{"id":"abc123"}""", HttpStatusCode.Created, jsonHeaders)
        }
        val progress = ArrayList<Float>()
        val id = t.upload(BytesSource(ByteArray(300_000) { it.toByte() })) { progress += it }
        assertEquals("abc123", id)
        assertEquals(300_000, received)
        assertEquals("Bearer ${pairing.token}", auth)
        assertEquals(HttpMethod.Post, method)
        assertEquals(1f, progress.last())
    }

    @Test fun unauthorizedMapsToUnauthorized() = runTest {
        val t = transport { respond("""{"error":"Missing or wrong pairing token"}""", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertFailsWith<PcTransportException.Unauthorized> { t.upload(BytesSource(ByteArray(10))) }
        assertFailsWith<PcTransportException.Unauthorized> { t.status("x") }
    }

    @Test fun serverErrorCarriesDetail() = runTest {
        val t = transport { respond("""{"error":"Package larger than 1 GiB"}""", HttpStatusCode.PayloadTooLarge, jsonHeaders) }
        val e = assertFailsWith<PcTransportException.Server> { t.upload(BytesSource(ByteArray(10))) }
        assertEquals(413, e.httpStatus)
        assertTrue("1 GiB" in e.message!!)
    }

    @Test fun networkFailureMapsToUnreachable() = runTest {
        val t = transport { throw RuntimeException("connect failed") }
        assertFailsWith<PcTransportException.Unreachable> { t.ping() }
    }

    @Test fun statusParsesWarnings() = runTest {
        val t = transport {
            respond(
                """{"id":"j1","state":"done","progress":1,"message":"Done","quality":"usable",
                    "warnings":[{"code":"low_light","message":"Dark","suggestion":"Add light"}],"extra":1}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val s = t.status("j1")
        assertTrue(s.isDone)
        assertEquals("usable", s.quality)
        assertEquals("Add light", s.warnings.single().suggestion)
    }

    @Test fun awaitResultPollsUntilTerminal() = runTest {
        val states = ArrayDeque(listOf("received", "processing", "processing", "importing", "done"))
        var polls = 0
        val t = transport {
            polls++
            val st = states.removeFirst()
            respond("""{"id":"j","state":"$st","progress":0.5,"message":"m","quality":"","warnings":[]}""", HttpStatusCode.OK, jsonHeaders)
        }
        val seen = ArrayList<JobState>()
        val result = t.awaitResult("j") { seen += it.state }
        assertTrue(result.isDone)
        assertEquals(5, polls)
        assertEquals(JobState.Received, seen.first())
    }

    @Test fun awaitResultToleratesBlipsThenGivesUp() = runTest {
        var n = 0
        val flaky = transport {
            n++
            if (n <= 2) throw RuntimeException("blip")
            respond("""{"id":"j","state":"failed","progress":0.3,"message":"No person found","quality":"","warnings":[]}""", HttpStatusCode.OK, jsonHeaders)
        }
        val r = flaky.awaitResult("j")
        assertTrue(r.isFailed)
        assertEquals("No person found", r.message)

        val dead = transport { throw RuntimeException("down") }
        assertFailsWith<PcTransportException.Unreachable> { dead.awaitResult("j") }
    }

    @Test fun awaitResultFailsFastOnUnknownJobOrBadToken() = runTest {
        val gone = transport { respond("""{"error":"Unknown package id"}""", HttpStatusCode.NotFound, jsonHeaders) }
        assertFailsWith<PcTransportException.Server> { gone.awaitResult("nope") }
        val denied = transport { respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) }
        assertFailsWith<PcTransportException.Unauthorized> { denied.awaitResult("j") }
    }
}
