package io.github.toolsdev404.proxyx

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Quick Settings tile — a real one-tap connect / disconnect toggle.
 *
 *  - Reflects the live VPN state (Active = routing, Inactive = off) while the panel is open.
 *  - Tap while off: starts the currently-selected SOCKS5 proxy. If VPN consent hasn't been
 *    granted yet (first time / after revoke), it opens the app, because a tile can't host
 *    the system consent dialog.
 *  - Tap while on: stops routing.
 */
class ProxyTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        if (watchJob?.isActive != true) {
            watchJob = scope.launch {
                VpnState.running.collect { render(it) }
            }
        }
    }

    override fun onStopListening() {
        watchJob?.cancel()
        watchJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(running: Boolean) {
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "ProxyX"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = if (running) "Connected" else "Tap to connect"
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        if (VpnState.running.value) {
            ProxyVpnService.stop(this)
            return
        }

        // A tile can't show the VPN consent dialog — send the user to the app for that.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }

        scope.launch {
            val profile = loadSelectedProxy()
            when {
                profile == null -> {
                    Toast.makeText(
                        this@ProxyTileService,
                        "Add and select a proxy in ProxyX first",
                        Toast.LENGTH_SHORT
                    ).show()
                    openApp()
                }
                profile.type != ProxyType.SOCKS5 -> {
                    Toast.makeText(
                        this@ProxyTileService,
                        "Routing supports SOCKS5 proxies. Open ProxyX to pick one.",
                        Toast.LENGTH_LONG
                    ).show()
                    openApp()
                }
                else -> ProxyVpnService.start(this@ProxyTileService, profile)
            }
        }
    }

    private suspend fun loadSelectedProxy(): ProxyProfile? {
        val repo = ProxyRepository(AppDatabase.getInstance(this).proxyDao())
        val settings = SettingsRepository(this)
        val profiles = repo.profiles.first()
        if (profiles.isEmpty()) return null
        val selId = settings.selectedId.first()
        return profiles.firstOrNull { it.id == selId } ?: profiles.first()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            startActivity(intent)
        }
    }
}