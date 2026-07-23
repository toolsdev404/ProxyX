package io.github.toolsdev404.proxyx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.toolsdev404.proxyx.ui.theme.AccentGreen
import io.github.toolsdev404.proxyx.ui.theme.Disconnected
import io.github.toolsdev404.proxyx.ui.theme.ProxyXTheme

// ---------- Data model ----------
enum class ProxyType { SOCKS5, HTTP, HTTPS }

data class ProxyProfile(
    val id: Int,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val isFavorite: Boolean = false
)

val sampleProfiles = listOf(
    ProxyProfile(1, "US Datacenter 01", ProxyType.SOCKS5, "203.0.113.5", 1080, true),
    ProxyProfile(2, "EU Home", ProxyType.HTTP, "198.51.100.20", 8080, true),
    ProxyProfile(3, "Local Dev", ProxyType.HTTPS, "127.0.0.1", 3128, false)
)

enum class Tab { Home, Profiles, Logs, Settings }

// ---------- Entry point ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProxyXTheme {
                ProxyXApp()
            }
        }
    }
}

// ---------- App shell with bottom navigation ----------
@Composable
fun ProxyXApp() {
    var selectedTab by remember { mutableStateOf(Tab.Home) }

    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Color(0xFF03110A),
        selectedTextColor = AccentGreen,
        indicatorColor = AccentGreen,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == Tab.Home,
                    onClick = { selectedTab = Tab.Home },
                    icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.Profiles,
                    onClick = { selectedTab = Tab.Profiles },
                    icon = { Icon(Icons.Filled.Dns, contentDescription = "Profiles") },
                    label = { Text("Profiles") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.Logs,
                    onClick = { selectedTab = Tab.Logs },
                    icon = { Icon(Icons.Filled.Description, contentDescription = "Logs") },
                    label = { Text("Logs") },
                    colors = navColors
                )
                NavigationBarItem(
                    selected = selectedTab == Tab.Settings,
                    onClick = { selectedTab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    colors = navColors
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                Tab.Home -> HomeScreen()
                Tab.Profiles -> ProfilesScreen()
                Tab.Logs -> LogsScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

// ---------- Home ----------
@Composable
fun HomeScreen() {
    var isConnected by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(sampleProfiles.first()) }
    val favorites = sampleProfiles.filter { it.isFavorite }
    val statusColor = if (isConnected) AccentGreen else Disconnected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Brand header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentGreen),
                contentAlignment = Alignment.Center
            ) {
                Text("P", color = Color(0xFF03110A), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text("ProxyX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) "Connected" else "Not connected",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(selected.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${selected.type} · ${selected.host}:${selected.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isConnected) {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Ping", "38 ms")
                        StatItem("Down", "12.4 MB/s")
                        StatItem("Up", "3.1 MB/s")
                        StatItem("Time", "00:14:22")
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { isConnected = !isConnected },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) Disconnected else AccentGreen,
                contentColor = Color(0xFF03110A)
            )
        ) {
            Text(if (isConnected) "Disconnect" else "Connect", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))

        // Favorites quick-connect
        Text("Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (favorites.isEmpty()) {
            Text(
                "No favorites yet. Tap the star on a proxy in Profiles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                favorites.forEach { fav ->
                    FavoriteRow(
                        profile = fav,
                        isActive = fav.id == selected.id,
                        onClick = {
                            selected = fav
                            isConnected = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FavoriteRow(profile: ProxyProfile, isActive: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isActive) BorderStroke(2.dp, AccentGreen) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${profile.type} · ${profile.host}:${profile.port}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isActive) {
                Text("Active", color = AccentGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------- Profiles ----------
@Composable
fun ProfilesScreen() {
    val profiles = remember { mutableStateListOf(*sampleProfiles.toTypedArray()) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("Profiles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        onFavorite = {
                            val i = profiles.indexOf(profile)
                            if (i >= 0) profiles[i] = profile.copy(isFavorite = !profile.isFavorite)
                        }
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = { /* Add profile — coming next */ },
            containerColor = AccentGreen,
            contentColor = Color(0xFF03110A),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add profile")
        }
    }
}

@Composable
fun ProfileCard(profile: ProxyProfile, onFavorite: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentGreen)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            profile.type.name,
                            color = Color(0xFF03110A),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${profile.host}:${profile.port}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (profile.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (profile.isFavorite) AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- Logs ----------
@Composable
fun LogsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "No logs yet. Connection, error, and DNS logs will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- Settings ----------
@Composable
fun SettingsScreen() {
    var autoConnect by remember { mutableStateOf(false) }
    var autoReconnect by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var background by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        SettingSwitch("Auto-connect on launch", autoConnect) { autoConnect = it }
        SettingSwitch("Auto-reconnect", autoReconnect) { autoReconnect = it }
        SettingSwitch("Notifications", notifications) { notifications = it }
        SettingSwitch("Keep running in background", background) { background = it }
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Preview(showBackground = true)
@Composable
fun ProxyXAppPreview() {
    ProxyXTheme {
        ProxyXApp()
    }
}