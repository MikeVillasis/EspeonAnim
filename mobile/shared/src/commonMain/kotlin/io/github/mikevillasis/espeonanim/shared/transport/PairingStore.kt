package io.github.mikevillasis.espeonanim.shared.transport

/** Remembers the last pairing across app restarts. Platform code supplies the storage. */
interface PairingStore {
    fun load(): PairingInfo?
    fun save(info: PairingInfo)
    fun clear()
}

/** In-memory store for tests and previews. */
class MemoryPairingStore(private var info: PairingInfo? = null) : PairingStore {
    override fun load() = info
    override fun save(info: PairingInfo) { this.info = info }
    override fun clear() { info = null }
}
