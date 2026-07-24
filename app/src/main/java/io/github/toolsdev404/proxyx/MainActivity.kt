package io.github.toolsdev404.proxyx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.toolsdev404.proxyx.ui.theme.AccentGreen
import io.github.toolsdev404.proxyx.ui.theme.Disconnected
import io.github.toolsdev404.proxyx.ui.theme.ProxyXTheme

sealed interface FormMode {
    object Add : FormMode
    data class Edit(val profile: ProxyProfile) : FormMode
    data class Duplicate(val profile: ProxyProfile) : FormMode
}

enum class SortOrder { Name, Type, Favorites }

class SettingsState {
    var autoConnect by mutableStateOf(false)
    var autoReconnect by mutableStateOf(true)
    var notifications by mutableStateOf(true)
    var background by mutableStateOf(true)
}

enum class Tab { Home, Profiles, Logs, Settings }

private fun isValidIpv4(host: String): Boolean {
    val parts = host.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
                part.all { it.isDigit() } &&
                (part == "0" || !part.startsWith("0")) &&
                (part.toIntOrNull() ?: -1) in 0..255
    }
}

private fun isValidDomain(host: String): Boolean {
    if (host.equals("localhost", ignoreCase = true)) return true
    if (host.contains("..")) return false
    val labels = host.split(".")
    if (labels.size < 2) return false
    val labelRegex = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")
    if (labels.any { it.length > 63 || !it.matches(labelRegex) }) return false
    val tld = labels.last()
    return tld.length >= 2 && tld.all { it.isLetter() }
}

private fun isValidHost(host: String): Boolean = isValidIpv4(host) || isValidDomain(host)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProxyXApp()
        }
    }
}

@Composable
fun ProxyXApp() {
    val vm: ProxyViewModel = viewModel()
    val themeMode by vm.themeMode.collectAsState()

    ProxyXTheme(themeMode = themeMode) {
        val profiles by vm.profiles.collectAsState()
        val selectedId by vm.selectedId.collectAsState()
        val isConnected by vm.isConnected.collectAsState()

        val settings = remember { SettingsState() }
        var selectedTab by remember { mutableStateOf(Tab.Home) }
        var formMode by remember { mutableStateOf<FormMode?>(null) }

        val navColors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF03110A),
            selectedTextColor = AccentGreen,
            indicatorColor = AccentGreen,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        BackHandler(enabled = formMode != null) { formMode = null }

        val mode = formMode
        if (mode != null) {
            val editProfile = (mode as? FormMode.Edit)?.profile
            val initial: ProxyProfile? = when (mode) {
                is FormMode.Add -> null
                is FormMode.Edit -> mode.profile
                is FormMode.Duplicate -> mode.profile.copy(name = mode.profile.name + " copy")
            }
            val assignId = editProfile?.id ?: 0

            ProfileFormScreen(
                title = if (editProfile != null) "Edit Proxy" else "Add Proxy",
                initial = initial,
                assignId = assignId,
                existing = profiles,
                excludeId = editProfile?.id,
                onCancel = { formMode = null },
                onSave = { saved ->
                    if (editProfile != null) vm.updateProfile(saved) else vm.addProfile(saved)
                    formMode = null
                    selectedTab = Tab.Profiles
                }
            )
        } else {
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
                        Tab.Home -> HomeScreen(
                            profiles = profiles,
                            isConnected = isConnected,
                            onToggleConnect = { vm.toggleConnect() },
                            selectedId = selectedId,
                            onSelect = { id -> vm.select(id) }
                        )
                        Tab.Profiles -> ProfilesScreen(
                            profiles = profiles,
                            activeId = selectedId,
                            onSelect = { p -> vm.select(p.id) },
                            onFavorite = { p -> vm.toggleFavorite(p) },
                            onAdd = { formMode = FormMode.Add },
                            onEdit = { formMode = FormMode.Edit(it) },
                            onDuplicate = { formMode = FormMode.Duplicate(it) },
                            onDelete = { p -> vm.deleteProfile(p) }
                        )
                        Tab.Logs -> LogsScreen()
                        Tab.Settings -> SettingsScreen(
                            settings = settings,
                            themeMode = themeMode,
                            onSetTheme = { vm.setThemeMode(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    profiles: List<ProxyProfile>,
    isConnected: Boolean,
    onToggleConnect: () -> Unit,
    selectedId: Int?,
    onSelect: (Int) -> Unit
) {
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val favorites = profiles.filter { it.isFavorite }
    val statusColor = if (isConnected) AccentGreen else Disconnected

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_proxyx_logo),
                contentDescription = "ProxyX logo",
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
            Spacer(Modifier.width(10.dp))
            Text("ProxyX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))

        if (selected == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    "No proxies yet. Add one from the Profiles tab.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
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
                    Text(
                        selected.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${selected.type} · ${selected.host}:${selected.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                onClick = onToggleConnect,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) Disconnected else AccentGreen,
                    contentColor = Color(0xFF03110A)
                )
            ) {
                Text(if (isConnected) "Disconnect" else "Connect", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(28.dp))

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
                        isActive = fav.id == (selected?.id),
                        onClick = { onSelect(fav.id) }
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
                Text(
                    profile.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${profile.type} · ${profile.host}:${profile.port}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Spacer(Modifier.width(8.dp))
                Text("Active", color = AccentGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ProfilesScreen(
    profiles: List<ProxyProfile>,
    activeId: Int?,
    onSelect: (ProxyProfile) -> Unit,
    onFavorite: (ProxyProfile) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ProxyProfile) -> Unit,
    onDuplicate: (ProxyProfile) -> Unit,
    onDelete: (ProxyProfile) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.Name) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<ProxyProfile?>(null) }

    val visible = profiles
        .filter {
            query.isBlank() ||
                    it.name.contains(query, true) ||
                    it.host.contains(query, true) ||
                    it.type.name.contains(query, true) ||
                    it.port.toString().contains(query)
        }
        .let { list ->
            when (sort) {
                SortOrder.Name -> list.sortedBy { it.name.lowercase() }
                SortOrder.Type -> list.sortedBy { it.type.name }
                SortOrder.Favorites -> list.sortedByDescending { it.isFavorite }
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text("Profiles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a proxy to set it active, then connect from Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Name (A–Z)") }, onClick = { sort = SortOrder.Name; sortMenuOpen = false })
                        DropdownMenuItem(text = { Text("Type") }, onClick = { sort = SortOrder.Type; sortMenuOpen = false })
                        DropdownMenuItem(text = { Text("Favorites first") }, onClick = { sort = SortOrder.Favorites; sortMenuOpen = false })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                profiles.isEmpty() -> Text(
                    "No proxies yet. Tap the + button to add one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                visible.isEmpty() -> Text(
                    "No proxies match your search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(visible, key = { it.id }) { profile ->
                        ProfileCard(
                            profile = profile,
                            isActive = profile.id == activeId,
                            onClick = { onSelect(profile) },
                            onFavorite = { onFavorite(profile) },
                            onEdit = { onEdit(profile) },
                            onDuplicate = { onDuplicate(profile) },
                            onDelete = { profileToDelete = profile }
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            containerColor = AccentGreen,
            contentColor = Color(0xFF03110A),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add profile")
        }
    }

    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete proxy?") },
            text = { Text("\"${target.name}\" will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    profileToDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileCard(
    profile: ProxyProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isActive) BorderStroke(2.dp, AccentGreen) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text("Active", color = AccentGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ProfileFormScreen(
    title: String,
    initial: ProxyProfile?,
    assignId: Int,
    existing: List<ProxyProfile>,
    excludeId: Int?,
    onCancel: () -> Unit,
    onSave: (ProxyProfile) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ProxyType.SOCKS5) }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var requiresAuth by remember { mutableStateOf(initial?.requiresAuth ?: false) }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Text("Type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProxyType.entries.forEach { t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(t.name) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Host — IP or domain") },
                supportingText = { Text("e.g. 203.0.113.5 or proxy.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = port,
                onValueChange = { new -> port = new.filter { it.isDigit() }.take(5) },
                label = { Text("Port") },
                supportingText = { Text("Numbers only, 1–65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Requires authentication", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = requiresAuth, onCheckedChange = { requiresAuth = it })
            }

            if (requiresAuth) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val cleanName = name.trim()
                        val cleanHost = host.trim()
                        val portNum = port.toIntOrNull()
                        val isDuplicate = portNum != null && existing.any {
                            it.id != excludeId &&
                                    it.host.equals(cleanHost, ignoreCase = true) &&
                                    it.port == portNum
                        }
                        when {
                            cleanName.isBlank() -> error = "Name is required"
                            cleanHost.isBlank() -> error = "Host is required"
                            cleanHost.contains(":") ->
                                error = "Enter only the address in Host (no port). Put the port in the Port field."
                            !isValidHost(cleanHost) ->
                                error = "Enter a valid IP (e.g. 203.0.113.5) or domain (e.g. proxy.example.com)"
                            portNum == null || portNum !in 1..65535 ->
                                error = "Port must be a number from 1 to 65535"
                            isDuplicate -> error = "A proxy with this host and port already exists"
                            requiresAuth && username.isBlank() -> error = "Username is required for authentication"
                            requiresAuth && password.isBlank() -> error = "Password is required for authentication"
                            else -> onSave(
                                ProxyProfile(
                                    id = assignId,
                                    name = cleanName,
                                    type = type,
                                    host = cleanHost,
                                    port = portNum,
                                    requiresAuth = requiresAuth,
                                    username = username.trim(),
                                    password = password,
                                    isFavorite = initial?.isFavorite ?: false
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        contentColor = Color(0xFF03110A)
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LogsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Text(
                "No logs yet. Connection, error, authentication, DNS, and reconnection events will appear here.",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsScreen(
    settings: SettingsState,
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        SettingsSection("APPEARANCE") {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose how ProxyX looks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = themeMode == m,
                            onClick = { onSetTheme(m) },
                            label = {
                                Text(
                                    when (m) {
                                        ThemeMode.System -> "System"
                                        ThemeMode.Light -> "Light"
                                        ThemeMode.Dark -> "Dark"
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        SettingsSection("CONNECTION") {
            SettingSwitch("Auto-connect on launch", settings.autoConnect) { settings.autoConnect = it }
            SettingsDivider()
            SettingSwitch("Auto-reconnect", settings.autoReconnect) { settings.autoReconnect = it }
        }

        SettingsSection("NOTIFICATIONS & SERVICE") {
            SettingSwitch("Notifications", settings.notifications) { settings.notifications = it }
            SettingsDivider()
            SettingSwitch("Keep running in background", settings.background) { settings.background = it }
        }

        SettingsSection("ABOUT") {
            AboutRow("Version", "0.1.0")
            SettingsDivider()
            AboutRow("Privacy", "No ads · No tracking")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = AccentGreen,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}