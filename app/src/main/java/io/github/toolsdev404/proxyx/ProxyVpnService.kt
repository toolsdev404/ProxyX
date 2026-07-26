package io.github.toolsdev404.proxyx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Shared VPN running state, observed by the UI. */
object VpnState {
    val running = MutableStateFlow(false)
}

/**
 * E2: brings up the VPN TUN and hands it + the selected SOCKS5 proxy to the bundled
 * hev-socks5-tunnel engine (via hev.htproxy.TProxyService), which forwards all device
 * traffic through the proxy.
 */
class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var configFile: File? = null

    companion object {
        const val ACTION_START = "io.github.toolsdev404.proxyx.action.START"
        const val ACTION_STOP = "io.github.toolsdev404.proxyx.action.STOP"
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_USER = "extra_user"
        private const val EXTRA_PASS = "extra_pass"
        private const val CHANNEL_ID = "proxyx_vpn"
        private const val NOTIF_ID = 1001

        // The TUN's own virtual address. Must match tunnel.ipv4 in the engine config.
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_MTU = 8500

        // Mapped-DNS (fake-IP) resolver. The device sends DNS here; the engine answers
        // it locally with a fake IP from the network below, then maps that IP back to
        // the hostname and does a SOCKS5 connect-by-name — so DNS works even when the
        // proxy has no UDP support. Must match the mapdns block in the engine config.
        private const val DNS_ADDRESS = "198.18.0.2"
        private const val FAKE_DNS_NETWORK = "100.64.0.0"
        private const val FAKE_DNS_NETMASK = "255.192.0.0"

        fun start(context: Context, profile: ProxyProfile) {
            val intent = Intent(context, ProxyVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_HOST, profile.host)
                .putExtra(EXTRA_PORT, profile.port)
                .putExtra(EXTRA_USER, if (profile.requiresAuth) profile.username else "")
                .putExtra(EXTRA_PASS, if (profile.requiresAuth) profile.password else "")
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
        val host = intent?.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val user = intent?.getStringExtra(EXTRA_USER) ?: ""
        val pass = intent?.getStringExtra(EXTRA_PASS) ?: ""
        startVpn(host, port, user, pass)
        return START_STICKY
    }

    private fun startVpn(host: String, port: Int, user: String, pass: String) {
        if (tun != null) return
        if (host.isEmpty() || port <= 0) {
            stopVpn()
            return
        }
        createChannel()
        startForegroundCompat()

        val descriptor = try {
            val builder = Builder()
                .setSession("ProxyX")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_ADDRESS)
            // Keep ProxyX's own traffic OFF the tunnel, so the engine's connection to
            // the remote proxy goes out over the real network instead of looping back
            // into the TUN (which would deadlock all routing).
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
            }
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (descriptor == null) {
            stopVpn()
            return
        }
        tun = descriptor

        val started = try {
            val file = File(filesDir, "hev-socks5-tunnel.yaml")
            file.writeText(buildConfig(host, port, user, pass))
            configFile = file
            TProxyService.TProxyStartService(file.absolutePath, descriptor.fd)
        } catch (e: Throwable) {
            false
        }
        if (!started) {
            stopVpn()
            return
        }
        VpnState.running.value = true
    }

    private fun buildConfig(host: String, port: Int, user: String, pass: String): String {
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  mtu: ").append(TUN_MTU).append("\n")
        sb.append("  ipv4: ").append(TUN_ADDRESS).append("\n")
        sb.append("socks5:\n")
        sb.append("  address: ").append(host).append("\n")
        sb.append("  port: ").append(port).append("\n")
        sb.append("  udp: 'udp'\n")
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            sb.append("  username: '").append(user).append("'\n")
            sb.append("  password: '").append(pass).append("'\n")
        }
        // Resolve DNS locally to fake IPs (no proxy UDP needed); the engine turns the
        // follow-up connection into a SOCKS5 connect-by-hostname.
        sb.append("mapdns:\n")
        sb.append("  address: ").append(DNS_ADDRESS).append("\n")
        sb.append("  port: 53\n")
        sb.append("  network: ").append(FAKE_DNS_NETWORK).append("\n")
        sb.append("  netmask: ").append(FAKE_DNS_NETMASK).append("\n")
        sb.append("  cache-size: 10000\n")
        return sb.toString()
    }

    private fun cleanup() {
        VpnState.running.value = false
        try {
            TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        try {
            configFile?.delete()
        } catch (_: Exception) {
        }
        configFile = null
    }

    private fun stopVpn() {
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        cleanup()
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
            .setContentText("Routing traffic through your proxy")
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