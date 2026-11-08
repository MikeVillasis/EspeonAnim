package io.github.mikevillasis.espeonanim.shared.transport

import android.content.Context

/** Stores the in-flight send as JSON in app-private SharedPreferences (not backed up). */
class SharedPrefsJobStore(context: Context) : JobStore {
    private val prefs = context.applicationContext.getSharedPreferences("espeon_send_job", Context.MODE_PRIVATE)

    override fun load(): PersistedJob? = prefs.getString(KEY, null)?.let(PersistedJob::decode)

    override fun save(job: PersistedJob) {
        prefs.edit().putString(KEY, job.encode()).commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val KEY = "job"
    }
}
