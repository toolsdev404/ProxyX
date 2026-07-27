package io.github.toolsdev404.proxyx

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProxyType { SOCKS5, SOCKS4, HTTP, HTTPS }

enum class ThemeMode { System, Light, Dark }

@Entity(tableName = "profiles")
data class ProxyProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val requiresAuth: Boolean = false,
    val username: String = "",
    val password: String = "",
    val isFavorite: Boolean = false
)

class Converters {
    @TypeConverter fun fromType(t: ProxyType): String = t.name
    @TypeConverter fun toType(s: String): ProxyType = ProxyType.valueOf(s)
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE")
    fun getAll(): Flow<List<ProxyProfile>>

    @Query("SELECT * FROM profiles")
    suspend fun getAllOnce(): List<ProxyProfile>

    @Insert suspend fun insert(profile: ProxyProfile): Long
    @Update suspend fun update(profile: ProxyProfile)
    @Delete suspend fun delete(profile: ProxyProfile)
}

@Database(entities = [ProxyProfile::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "proxyx.db"
                ).build().also { INSTANCE = it }
            }
    }
}

// AES-256-GCM encryption backed by the Android Keystore. The key never leaves the device
// and is not extractable, so stored proxy passwords are ciphertext at rest.
object Crypto {
    private const val KEY_ALIAS = "proxyx_pw_key"
    private const val PREFIX = "enc1:"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        } catch (e: Exception) {
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_LEN)
            val cipherText = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

class ProxyRepository(private val dao: ProxyDao) {
    val profiles: Flow<List<ProxyProfile>> =
        dao.getAll().map { list -> list.map { it.copy(password = Crypto.decrypt(it.password)) } }

    suspend fun add(p: ProxyProfile) { dao.insert(p.copy(password = Crypto.encrypt(p.password))) }
    suspend fun update(p: ProxyProfile) { dao.update(p.copy(password = Crypto.encrypt(p.password))) }
    suspend fun delete(p: ProxyProfile) { dao.delete(p) }

    // One-time: encrypt any passwords still stored as plain text (from before encryption existed).
    suspend fun migratePasswords() {
        dao.getAllOnce()
            .filter { it.password.isNotEmpty() && !Crypto.isEncrypted(it.password) }
            .forEach { dao.update(it.copy(password = Crypto.encrypt(it.password))) }
    }
}

// DataStore for preferences (theme, etc.)
private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val autoConnectKey = booleanPreferencesKey("auto_connect")
    private val autoReconnectKey = booleanPreferencesKey("auto_reconnect")
    private val notificationsKey = booleanPreferencesKey("notifications")
    private val backgroundKey = booleanPreferencesKey("background")
    private val selectedIdKey = intPreferencesKey("selected_id")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)
    }
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { it[autoConnectKey] ?: false }
    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { it[autoReconnectKey] ?: true }
    val notifications: Flow<Boolean> = context.dataStore.data.map { it[notificationsKey] ?: true }
    val background: Flow<Boolean> = context.dataStore.data.map { it[backgroundKey] ?: true }
    val selectedId: Flow<Int?> = context.dataStore.data.map { it[selectedIdKey] }

    suspend fun setThemeMode(mode: ThemeMode) { context.dataStore.edit { it[themeKey] = mode.name } }
    suspend fun setAutoConnect(v: Boolean) { context.dataStore.edit { it[autoConnectKey] = v } }
    suspend fun setAutoReconnect(v: Boolean) { context.dataStore.edit { it[autoReconnectKey] = v } }
    suspend fun setNotifications(v: Boolean) { context.dataStore.edit { it[notificationsKey] = v } }
    suspend fun setBackground(v: Boolean) { context.dataStore.edit { it[backgroundKey] = v } }
    suspend fun setSelectedId(id: Int?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(selectedIdKey) else prefs[selectedIdKey] = id
        }
    }
}

data class TestOutcome(
    val profileId: Int,
    val profileName: String,
    val success: Boolean,
    val message: String
)

data class LogEntry(
    val timeMillis: Long,
    val timeText: String,
    val proxyName: String,
    val success: Boolean,
    val message: String
)

/** True if the DEVICE has a validated internet connection right now (independent of our VPN). */
fun deviceHasInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProxyRepository(AppDatabase.getInstance(app).proxyDao())
    private val settingsRepo = SettingsRepository(app)

    init {
        viewModelScope.launch { repo.migratePasswords() }
    }

    val profiles: StateFlow<List<ProxyProfile>> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> =
        settingsRepo.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    val selectedId: StateFlow<Int?> =
        settingsRepo.selectedId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val autoConnect: StateFlow<Boolean> =
        settingsRepo.autoConnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoReconnect: StateFlow<Boolean> =
        settingsRepo.autoReconnect.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifications: StateFlow<Boolean> =
        settingsRepo.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val background: StateFlow<Boolean> =
        settingsRepo.background.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _testingId = MutableStateFlow<Int?>(null)
    val testingId: StateFlow<Int?> = _testingId.asStateFlow()

    private val _testResult = MutableStateFlow<TestOutcome?>(null)
    val testResult: StateFlow<TestOutcome?> = _testResult.asStateFlow()

    // One-off user-facing notices (e.g. device offline), surfaced as a snackbar.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // profileId -> reachable? Present only for profiles tested in the current scan.
    private val _scanResults = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val scanResults: StateFlow<Map<Int, Boolean>> = _scanResults.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun select(id: Int) = viewModelScope.launch { settingsRepo.setSelectedId(id) }
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoConnect(v) }
    fun setAutoReconnect(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoReconnect(v) }
    fun setNotifications(v: Boolean) = viewModelScope.launch { settingsRepo.setNotifications(v) }
    fun setBackground(v: Boolean) = viewModelScope.launch { settingsRepo.setBackground(v) }
    fun addProfile(p: ProxyProfile) = viewModelScope.launch { repo.add(p.copy(id = 0)) }
    fun updateProfile(p: ProxyProfile) = viewModelScope.launch { repo.update(p) }
    fun toggleFavorite(p: ProxyProfile) = viewModelScope.launch { repo.update(p.copy(isFavorite = !p.isFavorite)) }
    fun deleteProfile(p: ProxyProfile) = viewModelScope.launch {
        repo.delete(p)
        if (selectedId.value == p.id) settingsRepo.setSelectedId(null)
    }

    fun testProxy(p: ProxyProfile) {
        if (_testingId.value != null) return
        if (!deviceHasInternet(getApplication<Application>())) {
            _notice.value = "No internet on this device. Connect to Wi-Fi or mobile data, then try again."
            return
        }
        _testingId.value = p.id
        viewModelScope.launch {
            val result = ProxyTester.test(p)
            val ok = result is ProxyTestResult.Success
            val msg = when (result) {
                is ProxyTestResult.Success -> "Connected in ${result.latencyMs} ms"
                is ProxyTestResult.Failure -> result.reason
            }
            _testResult.value = TestOutcome(p.id, p.name, ok, msg)
            val now = System.currentTimeMillis()
            val timeText = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(now))
            _logs.value = (listOf(LogEntry(now, timeText, p.name, ok, msg)) + _logs.value).take(100)
            _testingId.value = null
        }
    }

    /**
     * Verifies a proxy before saving. On failure, probes the other protocols and passes back
     * a suggested type (or null) so the UI can offer to switch when the chosen type is wrong.
     */
    fun verifyProxy(p: ProxyProfile, onResult: (Boolean, String, ProxyType?) -> Unit) {
        viewModelScope.launch {
            when (val result = ProxyTester.test(p)) {
                is ProxyTestResult.Success -> onResult(true, "Reachable in ${result.latencyMs} ms", null)
                is ProxyTestResult.Failure -> onResult(false, result.reason, ProxyTester.detectType(p))
            }
        }
    }

    /** Tests every saved proxy in parallel and records which are reachable (badges + cleanup). */
    fun scanAll() {
        if (_scanning.value) return
        val list = profiles.value
        if (list.isEmpty()) return
        if (!deviceHasInternet(getApplication<Application>())) {
            _notice.value = "You're offline. Connect to Wi-Fi or mobile data to check your proxies."
            return
        }
        _scanning.value = true
        _scanResults.value = emptyMap()
        viewModelScope.launch {
            list.map { p ->
                async {
                    val r = ProxyTester.test(p)
                    _scanResults.value = _scanResults.value + (p.id to (r is ProxyTestResult.Success))
                }
            }.awaitAll()
            _scanning.value = false
        }
    }

    /** Deletes every proxy the last scan marked unreachable. */
    fun deleteDead() {
        val deadIds = _scanResults.value.filterValues { !it }.keys
        if (deadIds.isEmpty()) return
        viewModelScope.launch {
            val dead = profiles.value.filter { it.id in deadIds }
            dead.forEach { repo.delete(it) }
            if (selectedId.value in deadIds) settingsRepo.setSelectedId(null)
            _scanResults.value = _scanResults.value.filterKeys { it !in deadIds }
        }
    }

    /** Clears the current scan badges. */
    fun clearScan() { _scanResults.value = emptyMap() }

    fun clearTestResult() { _testResult.value = null }

    fun clearNotice() { _notice.value = null }

    fun clearLogs() { _logs.value = emptyList() }
}

/** The result of testing a proxy: either it worked (with latency) or it failed (with a reason). */
sealed interface ProxyTestResult {
    data class Success(val latencyMs: Long) : ProxyTestResult
    data class Failure(val reason: String) : ProxyTestResult
}

object ProxyTester {

    private const val TIMEOUT_MS = 8000

    // A cold proxy's first attempt can be slow (its first CONNECT, or its first DNS
    // resolution of the target), slow enough to time out and wrongly mark a valid proxy
    // as unreachable. Retrying once removes almost all of those false negatives.
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 400L

    // Neutral connectivity endpoint. Returns HTTP 204 with an empty body.
    // We never send proxy details or personal data anywhere — only this check.
    private const val TEST_HOST = "www.gstatic.com"
    private const val TEST_PORT = 443
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** Runs the correct handshake for the profile's type, retrying once on failure. */
    suspend fun test(profile: ProxyProfile): ProxyTestResult = withContext(Dispatchers.IO) {
        var last: ProxyTestResult = ProxyTestResult.Failure("The proxy didn't respond")
        for (attempt in 0 until MAX_ATTEMPTS) {
            val result = attemptOnce(profile)
            if (result is ProxyTestResult.Success) return@withContext result
            last = result
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        last
    }

    /** A single test attempt; runs the correct handshake for the profile's type. */
    private fun attemptOnce(profile: ProxyProfile): ProxyTestResult {
        return try {
            when (profile.type) {
                ProxyType.SOCKS5 -> testSocks5(profile)
                ProxyType.SOCKS4 -> testSocks4(profile)
                ProxyType.HTTP, ProxyType.HTTPS -> testHttp(profile)
            }
        } catch (e: SocketTimeoutException) {
            ProxyTestResult.Failure("Timed out — the proxy didn't respond")
        } catch (e: UnknownHostException) {
            ProxyTestResult.Failure("Can't find that host — check the address")
        } catch (e: ConnectException) {
            ProxyTestResult.Failure("Connection refused — check the host and port")
        } catch (e: IOException) {
            ProxyTestResult.Failure(e.message ?: "Network error")
        } catch (e: Exception) {
            ProxyTestResult.Failure(e.message ?: "Unexpected error")
        }
    }

    /**
     * Probes the proxy across protocols and returns the first that works other than the
     * profile's current type, or null. Lets the app suggest the right Type when the chosen
     * one fails (users often paste just host:port). A quick TCP check first keeps a dead
     * proxy fast. HTTPS is omitted (indistinguishable from HTTP by our test).
     */
    suspend fun detectType(profile: ProxyProfile): ProxyType? = withContext(Dispatchers.IO) {
        if (!isReachableTcp(profile.host, profile.port, 4000)) return@withContext null
        for (candidate in listOf(ProxyType.SOCKS5, ProxyType.HTTP, ProxyType.SOCKS4)) {
            if (candidate == profile.type) continue
            if (attemptOnce(profile.copy(type = candidate)) is ProxyTestResult.Success) {
                return@withContext candidate
            }
        }
        null
    }

    private fun isReachableTcp(host: String, port: Int, timeoutMs: Int): Boolean {
        if (host.isEmpty() || port <= 0) return false
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- HTTP / HTTPS proxy ----------
    private fun testHttp(profile: ProxyProfile): ProxyTestResult {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(profile.host, profile.port))
        val builder = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS)

        if (profile.requiresAuth) {
            builder.proxyAuthenticator { _, response ->
                // If we already tried credentials once, stop (avoids an infinite loop on bad login).
                if (response.request.header("Proxy-Authorization") != null) {
                    return@proxyAuthenticator null
                }
                val credential = Credentials.basic(profile.username, profile.password)
                response.request.newBuilder()
                    .header("Proxy-Authorization", credential)
                    .build()
            }
        }

        val client = builder.build()
        val request = Request.Builder().url(TEST_URL).build()

        val start = System.currentTimeMillis()
        val response = client.newCall(request).execute()
        try {
            val latency = System.currentTimeMillis() - start
            return when {
                response.code == 204 || response.isSuccessful -> ProxyTestResult.Success(latency)
                response.code == 407 -> ProxyTestResult.Failure("Proxy needs a login — check username/password")
                else -> ProxyTestResult.Failure("Proxy returned HTTP ${response.code}")
            }
        } finally {
            response.close()
        }
    }

    // ---------- SOCKS5 proxy (hand-rolled handshake) ----------
    private fun testSocks5(profile: ProxyProfile): ProxyTestResult {
        val start = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(profile.host, profile.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            // 1) Greeting: tell the proxy which auth methods we support.
            if (profile.requiresAuth) {
                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02)) // no-auth + username/password
            } else {
                out.write(byteArrayOf(0x05, 0x01, 0x00))       // no-auth only
            }
            out.flush()

            val version = input.readUnsignedByte()
            val method = input.readUnsignedByte()
            if (version != 0x05) return ProxyTestResult.Failure("Not a SOCKS5 proxy")
            when (method) {
                0x00 -> { /* no auth required — continue */ }
                0x02 -> {
                    if (!profile.requiresAuth) {
                        return ProxyTestResult.Failure("Proxy requires a username and password")
                    }
                    val authFailure = doUserPassAuth(out, input, profile)
                    if (authFailure != null) return authFailure
                }
                0xFF -> return ProxyTestResult.Failure("Proxy rejected our authentication method")
                else -> return ProxyTestResult.Failure("Proxy asked for an unsupported auth method")
            }

            // 2) Ask the proxy to CONNECT to the neutral endpoint (by domain name).
            val domain = TEST_HOST.toByteArray(Charsets.US_ASCII)
            out.write(0x05)            // version
            out.write(0x01)            // command: CONNECT
            out.write(0x00)            // reserved
            out.write(0x03)            // address type: domain name
            out.write(domain.size)     // length of the domain
            out.write(domain)          // the domain bytes
            out.write((TEST_PORT shr 8) and 0xFF) // port high byte
            out.write(TEST_PORT and 0xFF)         // port low byte
            out.flush()

            // 3) Read the proxy's reply.
            input.readUnsignedByte()              // version
            val rep = input.readUnsignedByte()    // status code
            input.readUnsignedByte()              // reserved
            val atyp = input.readUnsignedByte()   // bound-address type
            when (atyp) {                          // drain the bound address to keep the stream clean
                0x01 -> skipFully(input, 4 + 2)
                0x03 -> skipFully(input, input.readUnsignedByte() + 2)
                0x04 -> skipFully(input, 16 + 2)
            }

            val latency = System.currentTimeMillis() - start
            return if (rep == 0x00) {
                ProxyTestResult.Success(latency)
            } else {
                ProxyTestResult.Failure(socksError(rep))
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
                // ignore close errors
            }
        }
    }

    /** Performs SOCKS5 username/password auth. Returns a Failure on error, or null on success. */
    private fun doUserPassAuth(
        out: OutputStream,
        input: DataInputStream,
        profile: ProxyProfile
    ): ProxyTestResult? {
        val user = profile.username.toByteArray(Charsets.UTF_8)
        val pass = profile.password.toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) {
            return ProxyTestResult.Failure("Username or password is too long")
        }
        out.write(0x01)          // auth sub-negotiation version
        out.write(user.size)
        out.write(user)
        out.write(pass.size)
        out.write(pass)
        out.flush()

        input.readUnsignedByte()             // version
        val status = input.readUnsignedByte()
        return if (status == 0x00) {
            null
        } else {
            ProxyTestResult.Failure("Authentication failed — check username/password")
        }
    }

    private fun skipFully(input: DataInputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(64)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size))
            if (read < 0) break
            remaining -= read
        }
    }

    private fun socksError(rep: Int): String = when (rep) {
        0x02 -> "Connection not allowed by the proxy"
        0x03 -> "Network unreachable"
        0x04 -> "Host unreachable"
        0x05 -> "Connection refused by destination"
        0x06 -> "Connection timed out"
        0x07 -> "Command not supported by proxy"
        0x08 -> "Address type not supported by proxy"
        else -> "Proxy connection failed (code $rep)"
    }

    // ---------- SOCKS4 / SOCKS4a proxy (hand-rolled handshake) ----------
    // SOCKS4 has no password auth; the optional USERID is sent as-is. It addresses the
    // destination by raw IPv4, so we resolve the neutral test host to an IPv4 first.
    private fun testSocks4(profile: ProxyProfile): ProxyTestResult {
        val start = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(profile.host, profile.port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            val ipv4 = InetAddress.getAllByName(TEST_HOST).firstOrNull { it is Inet4Address }
                ?: return ProxyTestResult.Failure("Couldn't resolve a test address for SOCKS4")

            val req = ByteArrayOutputStream()
            req.write(0x04)                        // SOCKS version 4
            req.write(0x01)                        // command: CONNECT
            req.write((TEST_PORT shr 8) and 0xFF)  // dest port (high byte)
            req.write(TEST_PORT and 0xFF)          // dest port (low byte)
            req.write(ipv4.address)                // dest IPv4 (4 bytes)
            if (profile.username.isNotEmpty()) {   // optional USERID
                req.write(profile.username.toByteArray(Charsets.US_ASCII))
            }
            req.write(0x00)                        // USERID null terminator
            out.write(req.toByteArray())
            out.flush()

            // Reply is 8 bytes: a null byte, a status code, 2-byte port, 4-byte IP.
            input.readUnsignedByte()               // first byte (should be 0x00)
            val status = input.readUnsignedByte()
            skipFully(input, 6)                    // drain the bound port + IP

            val latency = System.currentTimeMillis() - start
            return if (status == 0x5A) {
                ProxyTestResult.Success(latency)
            } else {
                ProxyTestResult.Failure(socks4Error(status))
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun socks4Error(status: Int): String = when (status) {
        0x5B -> "Request rejected or failed"
        0x5C -> "Proxy couldn't reach your identd service"
        0x5D -> "Identd authentication failed"
        else -> "SOCKS4 connection failed (code $status)"
    }
}