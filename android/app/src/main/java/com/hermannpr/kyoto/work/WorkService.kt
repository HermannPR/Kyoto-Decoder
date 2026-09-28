package com.hermannpr.kyoto.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hermannpr.kyoto.KyotoApp
import com.hermannpr.kyoto.MainActivity
import com.hermannpr.kyoto.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano mientras hay un trabajo: mantiene vivo el proceso para
 * archivos de varios GB y muestra el progreso con un botón "Cancelar".
 * El trabajo en sí lo hace [JobManager].
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collecting = false
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = (application as KyotoApp).jobs
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            jobs.cancel()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(jobs.state.value), type)
        if (!collecting) {
            collecting = true
            scope.launch {
                jobs.state.collect { s ->
                    if (s is JobState.Running) {
                        runCatching { NotificationManagerCompat.from(this@WorkService).notify(NOTIFICATION_ID, build(s)) }
                    } else {
                        ServiceCompat.stopForeground(this@WorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(state: JobState): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, WorkService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val running = state as? JobState.Running
        val title = when (running?.kind) {
            JobKind.RECOVER -> "Recuperando archivos"
            else -> "Codificando archivos"
        }
        val p = running?.progress
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kyoto)
            .setContentTitle(title)
            .setContentText(listOfNotNull(p?.title, p?.detail?.takeIf { it.isNotBlank() }).joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Cancelar", cancel)
        val f = p?.fraction
        if (f != null) b.setProgress(1000, (f * 1000).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "trabajos"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "com.hermannpr.kyoto.CANCELAR"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WorkService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }
    }
}
