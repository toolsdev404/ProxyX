package io.github.toolsdev404.proxyx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared VPN running state, observed by the UI so Home/tile can reflect the real on/off status.
 */
object VpnState {
    val running = MutableStateFlow(false)
}

/**
 * E1 skeleton: brings a VpnService TUN interface up and down, as a proper Android-14
 * foreground service. There is no packet forwarder yet (that arrives in E2 with the
 * tun2socks engine), so while this is ON, device internet is paused — expected for now.
 */
class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_START = "io.github.toolsdev404.proxyx.action.START"
        const val ACTION_STOP = "io.github.toolsdev404.proxyx.action.STOP"
        private const val CHANNEL_ID = "proxyx_vpn"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ProxyVpnService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tun != null) return
        createChannel()
        startForegroundCompat()
        tun = try {
            Builder()
                .setSession("ProxyX")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish()
        } catch (e: Exception) {
            null
        }
        if (tun == null) {
            stopVpn()
        } else {
            VpnState.running.value = true
        }
    }

    private fun stopVpn() {
        VpnState.running.value = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // The system (or another VPN app) revoked our permission.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        VpnState.running.value = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ProxyX")
            .setContentText("Routing is on")
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "ProxyX VPN",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }
}