package io.github.mikevillasis.espeonanim.shared.transport

import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.CameraInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.Manifest
import io.github.mikevillasis.espeonanim.shared.manifest.TakeEntry
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.packaging.AndroidPackageWriter
import io.github.mikevillasis.espeonanim.shared.packaging.PackageFile
import io.github.mikevillasis.espeonanim.shared.packaging.PackagePlan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Talks to a real Unreal receiver. Skipped unless ESPEON_TEST_URL is set to the pairing URL
 * (espeon://pair?...). ESPEON_TEST_VIDEO may point to the mp4 used as the take.
 *
 * Start the receiver with: UnrealEditor-Cmd EspeonAnim.uproject -run=EspeonServeTest -Seconds=240 -unattended -nop4 -nosplash -NullRHI
 */
class LiveReceiverIntegrationTest {
    private fun pairing(): PairingInfo {
        val url = System.getenv("ESPEON_TEST_URL")
        assumeTrue("ESPEON_TEST_URL not set; skipping live receiver test", !url.isNullOrBlank())
        val r = PairingInfo.parse(url!!)
        check(r is PairingInfo.ParseResult.Ok) { "ESPEON_TEST_URL is not a valid pairing URL: $r" }
        return r.info
    }

    private fun buildPackage(): File {
        val video = File(System.getenv("ESPEON_TEST_VIDEO") ?: "../../testdata/raw/Jump1.mp4")
        assumeTrue("take video not found: ${video.absolutePath}", video.isFile)
        val dir = File(System.getProperty("java.io.tmpdir"), "espeon-it").apply { mkdirs() }
        val cal = File(video.parentFile, "APoseTurn.mp4").takeIf { it.isFile } ?: video // calibration clip; any decodable video will do
        fun entry(id: String, role: TakeRole) = TakeEntry(
            id = id, role = role, file = "takes/$id.mp4", fps = 30.0, width = 1920, height = 1080,
            rotationDeg = 0, durationS = 6.0, camera = CameraInfo(1450.0, "back"),
        )
        val plan = PackagePlan(
            Manifest(
                createdUtc = "2026-11-08T00:00:00Z",
                app = AppInfo("EspeonAnim", "0.1.0", "android"),
                device = DeviceInfo("Test", "JVM", "0"),
                takes = listOf(entry("action_01", TakeRole.Action), entry("calibration", TakeRole.Calibration)),
            ),
            listOf(PackageFile("takes/action_01.mp4", video.absolutePath), PackageFile("takes/calibration.mp4", cal.absolutePath)),
        )
        val out = File(dir, "IT.espeon")
        AndroidPackageWriter().write(plan, out.absolutePath)
        return out
    }

    @Test
    fun pingUploadWrongTokenAndAwaitResult() = runBlocking {
        val p = pairing()
        val transport = KtorPcTransport(p)

        val info = transport.ping()
        println("IT ping: name=${info.name} version=${info.version} api=${info.api}")
        assertEquals("EspeonAnim", info.app)

        val pkg = buildPackage()
        println("IT package: ${pkg.length() / 1_048_576.0} MB")
        val progress = ArrayList<Float>()
        val id = transport.upload(FileUploadSource(pkg)) { progress += it }
        println("IT upload: id=$id progress callbacks=${progress.size}")
        assertTrue(id.isNotBlank())
        assertEquals(1f, progress.last())
        assertTrue(progress.size > 1, "expected incremental progress")

        // Wrong token -> Unauthorized (for upload and status), ping still works without a token.
        val bad = KtorPcTransport(p.copy(token = "f".repeat(32)))
        assertFailsWith<PcTransportException.Unauthorized> { bad.upload(FileUploadSource(pkg)) }
        assertFailsWith<PcTransportException.Unauthorized> { bad.status(id) }
        assertEquals("EspeonAnim", bad.ping().app)

        // Nothing listens on this port -> Unreachable.
        assertFailsWith<PcTransportException.Unreachable> { KtorPcTransport(p.copy(port = 1)).ping() }

        val seen = ArrayList<String>()
        val final = withTimeout(420_000) {
            transport.awaitResult(id) { s ->
                val line = "${s.state} ${"%.2f".format(s.progress)} ${s.message}"
                if (seen.lastOrNull() != line) { seen += line; println("IT status: $line") }
            }
        }
        println("IT final: state=${final.state} progress=${final.progress} quality='${final.quality}' message='${final.message}' warnings=${final.warnings}")
        assertTrue(final.state.isTerminal)
    }
}
