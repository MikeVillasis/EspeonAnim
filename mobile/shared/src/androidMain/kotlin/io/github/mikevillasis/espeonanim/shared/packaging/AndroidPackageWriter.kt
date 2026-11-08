package io.github.mikevillasis.espeonanim.shared.packaging

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Zips manifest.json plus take files into a `.espeon` package with java.util.zip. */
class AndroidPackageWriter : PackageWriter {
    override fun write(plan: PackagePlan, outputPath: String) {
        for (f in plan.files) require(File(f.localPath).isFile) { "Missing file for package: ${f.localPath}" }
        val out = File(outputPath)
        out.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(out.outputStream())).use { zip ->
            val manifest = plan.manifest.toJson().toByteArray(Charsets.UTF_8)
            zip.setLevel(6)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            for (f in plan.files) {
                val src = File(f.localPath)
                if (f.zipPath.endsWith(".mp4")) {
                    // Video is already compressed: store it (needs size and CRC up front).
                    val entry = ZipEntry(f.zipPath).apply {
                        method = ZipEntry.STORED
                        size = src.length()
                        compressedSize = src.length()
                        crc = crc32(src)
                    }
                    zip.putNextEntry(entry)
                } else {
                    zip.putNextEntry(ZipEntry(f.zipPath))
                }
                FileInputStream(src).use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }
}
