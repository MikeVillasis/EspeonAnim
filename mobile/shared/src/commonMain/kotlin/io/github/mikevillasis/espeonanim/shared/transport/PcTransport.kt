package io.github.mikevillasis.espeonanim.shared.transport

/**
 * TODO(build order step 5): send a finished `.espeon` package to the PC over Wi-Fi
 * (QR pairing with the Unreal plugin's receiver). Not implemented yet; the UI shows a
 * disabled "Send to PC (Wi-Fi)" button until an implementation exists.
 */
interface PcTransport {
    /** True once the phone has been paired with a PC on this network. */
    val isPaired: Boolean

    /** Sends the package at [packagePath]; [onProgress] receives 0..1. */
    suspend fun send(packagePath: String, onProgress: (Float) -> Unit = {}): TransportResult
}

sealed interface TransportResult {
    data object Success : TransportResult
    data class Failure(val reason: String) : TransportResult
}
