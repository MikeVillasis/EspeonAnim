package io.github.mikevillasis.espeonanim.shared.transport

import android.content.Context

/**
 * Stores the last pairing as its `espeon://` URI in app-private SharedPreferences
 * (excluded from backup in the app's data extraction rules).
 */
class SharedPrefsPairingStore(context: Context) : PairingStore {
    private val prefs = context.applicationContext.getSharedPreferences("espeon_pairing", Context.MODE_PRIVATE)

    override fun load(): PairingInfo? {
        val raw = prefs.getString(KEY, null) ?: return null
        return (PairingInfo.parse(raw) as? PairingInfo.ParseResult.Ok)?.info
    }

    override fun save(info: PairingInfo) {
        prefs.edit().putString(KEY, info.toUri()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "pairing_uri"
    }
}
