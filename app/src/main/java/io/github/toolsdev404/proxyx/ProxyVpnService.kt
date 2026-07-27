package io.github.toolsdev404.proxyx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Connectivity health of the tunnel, observed by the UI / used for alerts. */
enum class Connectivity { IDLE, CHECKING, ONLINE, NO_INTERNET }

/** Shared VPN running state, observed by the UI. */
object VpnState {
    val running = MutableStateFlow(false)
    val connectivity = MutableStateFlow(Connectivity.IDLE)
}

/**
 * E2/E3: brings up the VPN TUN and hands it + the selected SOCKS5 proxy to the bundled
 * hev-socks5-tunnel engine (via hev.htproxy.TProxyService), which forwards all device
 * traffic through the proxy.
 *
 * Leak-proofing: captures IPv4 + IPv6 (proxy is IPv4-only, so stray IPv6 fails closed);
 * remembers the last proxy (password encrypted) so Always-on VPN can reconnect it.
 *
 * Reliability (Step 0):
 *  - Pre-connect gate: before establishing the TUN, we TCP-test the proxy. If it's
 *    unreachable (dead/offline/wrong host:port) we DON'T bring the tunnel up and show a
 *    clear "proxy offline" alert — so a dead proxy can't masquerade as connected.
 *  - Post-connect health: if a reachable proxy still yields no internet, we re-test to
 *    attribute correctly (proxy offline vs Private DNS vs not-routing). A working proxy
 *    validates first and shows nothing.
 */
class ProxyVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var configFile: File? = null

    private var connectivityManager: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthCheck = Runnable { onHealthGrace() }

    @Volatile private var proxyHost = ""
    @Volatile private var proxyPort = 0
    private var startGen = 0

    companion object {
        const val ACTION_START = "io.github.toolsdev404.proxyx.action.START"
        const val ACTION_STOP = "io.github.toolsdev404.proxyx.action.STOP"
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_USER = "extra_user"
        private const val EXTRA_PASS = "extra_pass"
        private const val CHANNEL_ID = "proxyx_vpn"
        private const val ALERT_CHANNEL_ID = "proxyx_alerts"
        private const val NOTIF_ID = 1001
        private const val ALERT_ID = 1002

        // Pre-connect reachability timeout: how long we wait for the proxy to answer
        // before declaring it dead and refusing to bring the tunnel up.
        private const val GATE_TIMEOUT_MS = 5000

        // Post-connect: warn only if still no internet after this window.
        private const val HEALTH_GRACE_MS = 8_000L
        private const val POST_TEST_TIMEOUT_MS = 5000

        // The TUN's own virtual addresses. Must match tunnel.ipv4 / tunnel.ipv6 below.
        private const val TUN_ADDRESS = "198.18.0.1"
        private const val TUN_ADDRESS6 = "fc00::1"
        private const val TUN_MTU = 8500

        // Mapped-DNS (fake-IP) resolver — resolves locally, connects by hostname via the
        // proxy, so DNS works without proxy UDP. Must match the mapdns block below.
        private const val DNS_ADDRESS = "198.18.0.2"
        private const val FAKE_DNS_NETWORK = "100.64.0.0"
        private const val FAKE_DNS_NETMASK = "255.192.0.0"

        // Encrypted store for the last-used proxy, so Always-on VPN can reconnect it.
        private const val PREFS = "proxyx_vpn_state"
        private const val K_HOST = "host"
        private const val K_PORT = "port"
        private const val K_USER = "user"
        private const val K_PASS = "pass"

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

    private data class SavedProxy(val host: String, val port: Int, val user: String, val pass: String)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelAlert()
                clearSavedProxy()
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: ""
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val user = intent.getStringExtra(EXTRA_USER) ?: ""
                val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
                saveProxy(host, port, user, pass)
                startVpn(host, port, user, pass)
            }
            else -> {
                val saved = loadSavedProxy()
                if (saved == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startVpn(saved.host, saved.port, saved.user, saved.pass)
            }
        }
        return START_STICKY
    }

    private fun startVpn(host: String, port: Int, user: String, pass: String) {
        if (tun != null) return
        if (host.isEmpty() || port <= 0) {
            stopVpn()
            return
        }
        proxyHost = host
        proxyPort = port
        cancelAlert()
        createChannel()
        startForegroundCompat()
        VpnState.connectivity.value = Connectivity.CHECKING

        // Pre-connect gate: verify the proxy is actually reachable before we route
        // anything through it. Runs off the main thread; result handled back on main.
        val gen = ++startGen
        Thread {
            val reachable = isReachable(host, port, GATE_TIMEOUT_MS)
            mainHandler.post {
                if (gen != startGen) return@post
                if (reachable) {
                    bringUpTunnel(host, port, user, pass)
                } else {
                    VpnState.connectivity.value = Connectivity.NO_INTERNET
                    postProxyOfflineAlert()
                    // Tear down without wiping the alert we just posted.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    private fun bringUpTunnel(host: String, port: Int, user: String, pass: String) {
        if (tun != null) return

        val descriptor = try {
            val builder = Builder()
                .setSession("ProxyX")
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, 32)
                .addAddress(TUN_ADDRESS6, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(DNS_ADDRESS)
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
            // No blocklist yet: write EMPTY lists so nothing is blocked (fail-open),
            // overwriting any stale files from a previous run. Ad-block Step 3 will
            // replace these with the user's Settings-driven lists.
            File(filesDir, "blocklist.txt").writeText("")
            File(filesDir, "allowlist.txt").writeText("")
            TProxyService.TProxyStartService(file.absolutePath, descriptor.fd)
        } catch (e: Throwable) {
            false
        }
        if (!started) {
            stopVpn()
            return
        }
        VpnState.running.value = true
        startConnectivityWatch()
    }

    private fun buildConfig(host: String, port: Int, user: String, pass: String): String {
        val sb = StringBuilder()
        sb.append("tunnel:\n")
        sb.append("  mtu: ").append(TUN_MTU).append("\n")
        sb.append("  ipv4: ").append(TUN_ADDRESS).append("\n")
        sb.append("  ipv6: '").append(TUN_ADDRESS6).append("'\n")
        sb.append("socks5:\n")
        sb.append("  address: ").append(host).append("\n")
        sb.append("  port: ").append(port).append("\n")
        sb.append("  udp: 'udp'\n")
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            // Escape single quotes for YAML single-quoted scalars ('' means a literal ').
            sb.append("  username: '").append(user.replace("'", "''")).append("'\n")
            sb.append("  password: '").append(pass.replace("'", "''")).append("'\n")
        }
        sb.append("mapdns:\n")
        sb.append("  address: ").append(DNS_ADDRESS).append("\n")
        sb.append("  port: 53\n")
        sb.append("  network: ").append(FAKE_DNS_NETWORK).append("\n")
        sb.append("  netmask: ").append(FAKE_DNS_NETMASK).append("\n")
        sb.append("  cache-size: 10000\n")
        return sb.toString()
    }

    // --- Reachability test (used by the pre-connect gate and the health check) --------

    private fun isReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        if (host.isEmpty() || port <= 0) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // --- Connection health watch -----------------------------------------------------

    private fun startConnectivityWatch() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    VpnState.connectivity.value = Connectivity.ONLINE
                    mainHandler.removeCallbacks(healthCheck)
                    cancelAlert()
                }
            }
        }
        netCallback = cb
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (_: Throwable) {
        }
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.postDelayed(healthCheck, HEALTH_GRACE_MS)
    }

    /**
     * After the grace window with the link still unvalidated, figure out WHY and warn.
     * Re-tests the proxy so the message is accurate (offline vs Private DNS vs not
     * routing). A working proxy validates first and cancels this, so healthy = silent.
     */
    private fun onHealthGrace() {
        if (tun == null) return
        if (VpnState.connectivity.value == Connectivity.ONLINE) return
        val gen = startGen
        val host = proxyHost
        val port = proxyPort
        Thread {
            val reachable = isReachable(host, port, POST_TEST_TIMEOUT_MS)
            mainHandler.post {
                if (gen != startGen) return@post
                if (tun == null) return@post
                if (VpnState.connectivity.value == Connectivity.ONLINE) return@post
                VpnState.connectivity.value = Connectivity.NO_INTERNET
                if (!reachable) {
                    postProxyOfflineAlert()
                } else {
                    val dnsName = strictPrivateDnsName()
                    if (dnsName != null) {
                        postDnsAlert(dnsName)
                        stopVpn()
                    } else {
                        postProxyStuckAlert()
                    }
                }
            }
        }.start()
    }

    private fun stopConnectivityWatch() {
        mainHandler.removeCallbacks(healthCheck)
        val cm = connectivityManager
        val cb = netCallback
        if (cm != null && cb != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
        netCallback = null
        connectivityManager = null
        VpnState.connectivity.value = Connectivity.IDLE
    }

    /** Returns the configured Private DNS hostname if (and only if) strict mode is on. */
    private fun strictPrivateDnsName(): String? {
        return try {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            if (mode == "hostname") {
                Settings.Global.getString(contentResolver, "private_dns_specifier")
                    ?.takeIf { it.isNotBlank() } ?: "on"
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun postProxyOfflineAlert() {
        val text = "This proxy is offline or unreachable — the server may be down, or the " +
                "host/port may be wrong. Choose another proxy."
        postAlert(text, Intent(this, MainActivity::class.java))
    }

    private fun postProxyStuckAlert() {
        val text = "Connected, but this proxy isn't routing traffic — the username/password " +
                "may be wrong. Try another proxy, or test it in the app."
        postAlert(text, Intent(this, MainActivity::class.java))
    }

    private fun postDnsAlert(dnsName: String) {
        val text = "Your Private DNS ($dnsName) is blocking the proxy, so websites can't load. " +
                "To fix it:\n" +
                "1. Open your phone's Settings\n" +
                "2. Search for \"Private DNS\"\n" +
                "3. Set it to Off (or Automatic)\n" +
                "4. Come back and tap Route all traffic again\n" +
                "Tap here to open Settings. (ProxyX keeps your DNS private through the proxy anyway.)"
        val tapIntent = try {
            Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Throwable) {
            Intent(this, MainActivity::class.java)
        }
        postAlert(text, tapIntent)
    }

    private fun postAlert(text: String, tapIntent: Intent) {
        createAlertChannel()
        val pending = PendingIntent.getActivity(
            this, 2, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("No internet through ProxyX")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_tile)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, notif)
        } catch (_: Throwable) {
        }
    }

    private fun cancelAlert() {
        try {
            getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        } catch (_: Throwable) {
        }
    }

    // --- Last-proxy persistence (password encrypted via Keystore-backed Crypto) ------

    private fun saveProxy(host: String, port: Int, user: String, pass: String) {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_HOST, host)
                .putInt(K_PORT, port)
                .putString(K_USER, user)
                .putString(K_PASS, if (pass.isEmpty()) "" else Crypto.encrypt(pass))
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun loadSavedProxy(): SavedProxy? {
        return try {
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val host = p.getString(K_HOST, null) ?: return null
            val port = p.getInt(K_PORT, 0)
            if (host.isEmpty() || port <= 0) return null
            val user = p.getString(K_USER, "") ?: ""
            val encPass = p.getString(K_PASS, "") ?: ""
            val pass = if (encPass.isEmpty()) "" else Crypto.decrypt(encPass)
            SavedProxy(host, port, user, pass)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearSavedProxy() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Throwable) {
        }
    }

    private fun cleanup() {
        startGen++
        VpnState.running.value = false
        stopConnectivityWatch()
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
        cancelAlert()
        clearSavedProxy()
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

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID,
                        "ProxyX alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }
}