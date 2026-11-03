package org.crisislink.service

import android.app.Notification
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
import org.crisislink.CrisisLinkApp
import org.crisislink.R
import org.crisislink.mesh.MeshRuntime
import org.crisislink.ui.MainActivity

/**
 * Keeps the mesh alive with the app closed. Type connectedDevice (Bluetooth), sticky so the system
 * restarts it after a kill. The notification shows nearby peers and carried messages.
 */
class MeshForegroundService : Service() {
    private val runtime get() = (application as CrisisLinkApp).runtime

    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("MissingPermission")   // notify() is wrapped in runCatching; the notification is optional
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.backgroundEnabled = false
            runtime.setServiceNotifier(null)
            runtime.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.start()   // creates notification channels
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(0, 0), type)
        runtime.setServiceNotifier { peers, carried ->
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(peers, carried)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.setServiceNotifier(null)
        super.onDestroy()
    }

    private fun build(peers: Int, carried: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, MeshRuntime.MESH_CHANNEL)
            .setSmallIcon(R.drawable.ic_mesh)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_peers, peers, carried))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_quit), stop)
            .setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.crisislink.STOP"

        fun start(context: Context) {
            ContextCompatStart.start(context, Intent(context, MeshForegroundService::class.java))
        }
    }
}

private object ContextCompatStart {
    fun start(context: Context, intent: Intent) = androidx.core.content.ContextCompat.startForegroundService(context, intent)
}
