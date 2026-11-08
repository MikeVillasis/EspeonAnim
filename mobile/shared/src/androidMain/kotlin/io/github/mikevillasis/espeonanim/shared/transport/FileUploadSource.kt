package io.github.mikevillasis.espeonanim.shared.transport

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.File

/** Streams a local file (buffered chunks, never the whole file in memory). */
class FileUploadSource(private val file: File) : UploadSource {
    override val length: Long get() = file.length()
    override fun open(): ByteReadChannel = file.inputStream().buffered(64 * 1024).toByteReadChannel()
}
