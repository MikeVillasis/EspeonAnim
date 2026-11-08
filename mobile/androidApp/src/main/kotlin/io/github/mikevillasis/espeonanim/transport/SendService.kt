package io.github.mikevillasis.espeonanim.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.mikevillasis.espeonanim.MainActivity
import io.github.mikevillasis.espeonanim.shared.transport.FileUploadSource
import io.github.mikevillasis.espeonanim.shared.transport.JobStatus
import io.github.mikevillasis.espeonanim.shared.transport.KtorPcTransport
import io.github.mikevillasis.espeonanim.shared.transport.NotificationContent
import io.github.mikevillasis.espeonanim.shared.transport.PairingInfo
import io.github.mikevillasis.espeonanim.shared.transport.PcTransportException
import io.github.mikevillasis.espeonanim.shared.transport.PersistedJob
import io.github.mikevillasis.espeonanim.shared.transport.ResumeDecision
import io.github.mikevillasis.espeonanim.shared.transport.SendJobLogic
import io.github.mikevillasis.espeonanim.shared.transport.SendNotifications
import io.github.mikevillasis.espeonanim.shared.transport.SendState
import io.github.mikevillasis.espeonanim.shared.transport.SharedPrefsPairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service (type dataSync) that uploads the package and polls the PC until the job
 * finishes, so locking the phone or leaving the app does not lose the outcome. The job is
 * persisted; when the process or service is restarted it resumes polling instead of uploading again.
 */
class SendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var userCancelled = false
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelWork()
            return START_NOT_STICKY
        }
        // Must go to the foreground right away for every start via startForegroundService.
        enterForeground(NotificationContent("Sending to PC", "Starting...", null, true, null))
        if (runner?.isActive == true) return START_STICKY

        val store = SendRepository.store(this)
        if (intent?.action == ACTION_START) {
            val path = intent.getStringExtra(EXTRA_FILE)
            val pairing = SharedPrefsPairingStore(this).load()
            if (path == null || pairing == null) {
                SendRepository.publish(SendState.Failed("This phone is not paired with a PC.", needsRescan = true))
                finishService()
                return START_NOT_STICKY
            }
            val job = PersistedJob(pairing.toUri(), path)
            store.save(job)
            launchRun(pairing, job, File(path))
        } else {
            // Resume (explicit, or the system restarted the service with a null intent).
            when (val d = SendJobLogic.decide(store.load())) {
                ResumeDecision.Nothing -> finishService()
                is ResumeDecision.Show -> { SendRepository.publish(d.state); finishService() }
                is ResumeDecision.Poll -> {
                    val job = store.load() ?: return START_NOT_STICKY
                    SendRepository.publish(SendState.Processing(null))
                    launchRun(d.pairing, job, null)
                }
            }
        }
        return START_STICKY
    }

    private fun launchRun(pairing: PairingInfo, job: PersistedJob, file: File?) {
        userCancelled = false
        timedOut = false
        runner = scope.launch {
            val pcName = pairing.pcName
            val progressJob = launch {
                SendRepository.state
                    .map { SendNotifications.contentFor(it, pcName) }
                    .distinctUntilChanged()
                    .collect { c -> if (c != null && c.ongoing) notifyOngoing(c) }
            }
            try {
                run(pairing, job, file)
            } finally {
                progressJob.cancel()
            }
        }
    }

    private suspend fun run(pairing: PairingInfo, startJob: PersistedJob, file: File?) {
        val store = SendRepository.store(this)
        val client = KtorPcTransport.defaultClient()
        try {
            val transport = KtorPcTransport(pairing, client)
            var job = startJob
            var id = job.jobId
            if (id == null) {
                SendRepository.publish(SendState.Uploading(0f))
                id = transport.upload(FileUploadSource(requireNotNull(file))) { SendRepository.publish(SendState.Uploading(it)) }
                job = job.withJobId(id)
                store.save(job)
            }
            SendRepository.publish(SendState.Processing(null))
            val result: JobStatus = transport.awaitResult(id) { SendRepository.publish(SendState.Processing(it)) }
            finish(
                job,
                if (result.isDone) SendState.Done(result)
                else SendState.Failed(result.message.ifBlank { "The PC could not turn this take into an animation." }, false, result),
                pairing.pcName,
            )
        } catch (e: CancellationException) {
            when {
                userCancelled -> {
                    store.clear()
                    SendRepository.publish(SendState.Cancelled)
                    finishService()
                }
                timedOut -> finish(
                    startJob,
                    SendState.Failed("Android stopped the background transfer after a long time. Check Unreal to see if the PC finished.", false),
                    pairing.pcName,
                )
                // Otherwise the service was destroyed (e.g. process killed): keep the record so it resumes.
            }
            throw e
        } catch (e: PcTransportException) {
            finish(startJob, SendState.Failed(e.message.orEmpty(), e is PcTransportException.Unauthorized), pairing.pcName)
        } catch (e: Exception) {
            finish(startJob, SendState.Failed(e.message ?: "Sending failed.", false), pairing.pcName)
        } finally {
            client.close()
        }
    }

    private fun finish(job: PersistedJob, state: SendState, pcName: String) {
        SendRepository.store(this).save(job.withOutcome(state))
        SendRepository.publish(state)
        finishService()
        SendNotifications.contentFor(state, pcName)?.let { notifyResult(it) }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelWork() {
        val r = runner
        if (r?.isActive == true) {
            userCancelled = true
            r.cancel()
        } else {
            // Nothing running in this process (stale state): just clean up.
            SendRepository.store(this).clear()
            SendRepository.publish(SendState.Cancelled)
            stopSelf()
        }
    }

    /** Android 15+: a dataSync service may only run for a limited time per day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        runner?.cancel()
        finishService()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notifications ----

    private fun enterForeground(c: NotificationContent) {
        ensureChannels(this)
        val n = buildOngoing(c)
        try {
            ServiceCompat.startForeground(this, ONGOING_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            // Foreground start refused (e.g. restarted in the background on Android 12+).
            SendRepository.publish(SendState.Failed("Android would not keep the transfer running in the background: ${e.message.orEmpty()}", false))
            stopSelf()
        }
    }

    private fun notifyOngoing(c: NotificationContent) {
        getSystemService(NotificationManager::class.java).notify(ONGOING_NOTIFICATION_ID, buildOngoing(c))
    }

    private fun buildOngoing(c: NotificationContent): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(this))
        val pct = c.progress
        if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        c.cancelLabel?.let { b.addAction(0, it, cancelPendingIntent(this)) }
        return b.build()
    }

    private fun notifyResult(c: NotificationContent) {
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(c.title)
            .setContentText(c.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(c.text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(this))
            .build()
        // Silently dropped by the system when POST_NOTIFICATIONS was denied; the app UI still shows the result.
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, n)
    }

    companion object {
        const val ONGOING_NOTIFICATION_ID = 4101
        const val RESULT_NOTIFICATION_ID = 4102
        private const val CHANNEL_PROGRESS = "send_progress"
        private const val CHANNEL_RESULT = "send_result"
        private const val ACTION_START = "io.github.mikevillasis.espeonanim.SEND_START"
        private const val ACTION_RESUME = "io.github.mikevillasis.espeonanim.SEND_RESUME"
        private const val ACTION_CANCEL = "io.github.mikevillasis.espeonanim.SEND_CANCEL"
        private const val EXTRA_FILE = "file"

        fun startIntent(context: Context, file: File): Intent =
            Intent(context, SendService::class.java).setAction(ACTION_START).putExtra(EXTRA_FILE, file.absolutePath)

        fun resumeIntent(context: Context): Intent =
            Intent(context, SendService::class.java).setAction(ACTION_RESUME)

        fun cancelIntent(context: Context): Intent =
            Intent(context, SendService::class.java).setAction(ACTION_CANCEL)

        private fun cancelPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(context, 1, cancelIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Sending to PC", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, "Send result", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
