package com.badmintontracker.android.localanalysis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps an on-device analysis alive while the phone is asleep or the app is in
 * the background.
 *
 * Without this the work stops in the ways Android stops any long computation
 * that has not declared itself: the CPU is throttled once the screen is off,
 * Doze eventually suspends it, and the process is a candidate for death under
 * memory pressure. A one-minute clip takes about nine minutes to analyse and an
 * eight-minute one over an hour, so "finish it while the screen is on" is not a
 * usable constraint.
 *
 * It hosts no work itself. The analysis runs in the application scope as
 * before; this exists purely to hold the process open and the CPU awake for as
 * long as something is running, which keeps the service a lifetime concern
 * rather than another place the pipeline lives.
 *
 * Nothing in this app's own wiring cancels a run when the task is swiped off
 * the recents list: the analysis runs in the application scope rather than an
 * Activity's, this service declares no `stopWithTask` and overrides no
 * `onTaskRemoved`. Whether an aggressive OEM kills the process anyway on task
 * removal is not yet confirmed on the S23. What it certainly does not survive
 * is the process dying - a force-stop, or an out-of-memory kill. There is no
 * checkpoint, so the run is lost rather than resumed; resume needs the shuttle
 * track persisted as it is produced and is design section 9's Stage 5.
 */
class LocalAnalysisService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val heartbeat = Handler(Looper.getMainLooper())

    // Carried on the start intent rather than read from the constants directly
    // so a test can drive the heartbeat at a speed it can actually observe.
    private var lockTimeoutMillis = LOCK_TIMEOUT_MILLIS
    private var refreshIntervalMillis = REFRESH_INTERVAL_MILLIS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Analysing on device"
        startForeground(NOTIFICATION_ID, notification(text), foregroundType())

        lockTimeoutMillis = intent?.getLongExtra(EXTRA_TIMEOUT, LOCK_TIMEOUT_MILLIS)
            ?: LOCK_TIMEOUT_MILLIS
        refreshIntervalMillis = intent?.getLongExtra(EXTRA_REFRESH, REFRESH_INTERVAL_MILLIS)
            ?: REFRESH_INTERVAL_MILLIS

        if (wakeLock == null) {
            // Partial: the CPU stays awake, the screen does not. Analysis is
            // pure computation and does not need the display on, and holding a
            // screen lock for an hour would be indefensible.
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shuttl:local-analysis")
                .apply { setReferenceCounted(false) }
            heartbeat.post(refresh)
        }
        // NOT_STICKY: a restarted service would hold a wake lock with no
        // analysis behind it, since the work it was covering died with the
        // process and cannot be resumed.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        heartbeat.removeCallbacks(refresh)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Re-arms the lock's timeout for as long as the service is alive.
     *
     * `acquire(timeout)` on a lock that is already held and not reference
     * counted replaces the pending release rather than stacking a second one,
     * so this extends the deadline instead of leaking locks.
     */
    private val refresh = object : Runnable {
        override fun run() {
            wakeLock?.acquire(lockTimeoutMillis)
            heartbeat.postDelayed(this, refreshIntervalMillis)
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "On-device analysis", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while a video is being analysed on this phone." },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Analysing on device")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "local-analysis"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TIMEOUT = "lock-timeout-millis"
        private const val EXTRA_REFRESH = "refresh-interval-millis"

        /**
         * How long a single acquisition is good for, refreshed while the
         * service lives.
         *
         * A lock held with no timeout leaks until reboot if the process wedges,
         * so there has to be a deadline. But a deadline sized to "the longest
         * video we expect" is a trap: analysis runs at roughly seven times
         * realtime, so a two-hour lock covers only a seventeen-minute video,
         * and past that the lock lapsed silently. The service stayed up and the
         * notification kept showing a percentage while the CPU was free to
         * sleep, so with the screen off progress simply stopped until the
         * screen came back on.
         *
         * Re-arming a short deadline instead is unbounded in the direction that
         * matters - a three-hour video stays covered - while bounding a genuine
         * leak far tighter than two hours, since the refresh dies with the
         * service and the service is stopped in the runner's `finally`.
         */
        private const val LOCK_TIMEOUT_MILLIS = 15 * 60 * 1000L

        /** Comfortably inside [LOCK_TIMEOUT_MILLIS] so a late tick cannot lapse it. */
        private const val REFRESH_INTERVAL_MILLIS = 5 * 60 * 1000L

        fun start(context: Context, text: String) =
            start(context, text, LOCK_TIMEOUT_MILLIS, REFRESH_INTERVAL_MILLIS)

        /** Production callers use the two-argument [start]; the timings are a test seam. */
        @androidx.annotation.VisibleForTesting
        internal fun start(
            context: Context,
            text: String,
            lockTimeoutMillis: Long,
            refreshIntervalMillis: Long,
        ) {
            val i = Intent(context, LocalAnalysisService::class.java)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TIMEOUT, lockTimeoutMillis)
                .putExtra(EXTRA_REFRESH, refreshIntervalMillis)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalAnalysisService::class.java))
        }
    }
}
