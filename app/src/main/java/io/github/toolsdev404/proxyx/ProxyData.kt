package io.github.toolsdev404.proxyx

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ProxyType { SOCKS5, HTTP, HTTPS }

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

class ProxyRepository(private val dao: ProxyDao) {
    val profiles: Flow<List<ProxyProfile>> = dao.getAll()
    suspend fun add(p: ProxyProfile) { dao.insert(p) }
    suspend fun update(p: ProxyProfile) { dao.update(p) }
    suspend fun delete(p: ProxyProfile) { dao.delete(p) }
}

// DataStore for preferences (theme, etc.)
private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.System.name) }
            .getOrDefault(ThemeMode.System)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
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

class ProxyViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProxyRepository(AppDatabase.getInstance(app).proxyDao())
    private val settingsRepo = SettingsRepository(app)

    val profiles: StateFlow<List<ProxyProfile>> =
        repo.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val themeMode: StateFlow<ThemeMode> =
        settingsRepo.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.System)

    private val _selectedId = MutableStateFlow<Int?>(null)
    val selectedId: StateFlow<Int?> = _selectedId.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _testingId = MutableStateFlow<Int?>(null)
    val testingId: StateFlow<Int?> = _testingId.asStateFlow()

    private val _testResult = MutableStateFlow<TestOutcome?>(null)
    val testResult: StateFlow<TestOutcome?> = _testResult.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun select(id: Int) { _selectedId.value = id; _isConnected.value = false }
    fun toggleConnect() { _isConnected.value = !_isConnected.value }
    fun addProfile(p: ProxyProfile) = viewModelScope.launch { repo.add(p.copy(id = 0)) }
    fun updateProfile(p: ProxyProfile) = viewModelScope.launch { repo.update(p) }
    fun toggleFavorite(p: ProxyProfile) = viewModelScope.launch { repo.update(p.copy(isFavorite = !p.isFavorite)) }
    fun deleteProfile(p: ProxyProfile) = viewModelScope.launch {
        repo.delete(p)
        if (_selectedId.value == p.id) { _selectedId.value = null; _isConnected.value = false }
    }

    fun testProxy(p: ProxyProfile) {
        if (_testingId.value != null) return
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

    fun clearTestResult() { _testResult.value = null }

    fun clearLogs() { _logs.value = emptyList() }
}

/** The result of testing a proxy: either it worked (with latency) or it failed (with a reason). */
sealed interface ProxyTestResult {
    data class Success(val latencyMs: Long) : ProxyTestResult
    data class Failure(val reason: String) : ProxyTestResult
}

object ProxyTester {

    private const val TIMEOUT_MS = 8000

    // Neutral connectivity endpoint. Returns HTTP 204 with an empty body.
    // We never send proxy details or personal data anywhere — only this check.
    private const val TEST_HOST = "www.gstatic.com"
    private const val TEST_PORT = 443
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** Runs the correct handshake for the profile's type on a background thread. */
    suspend fun test(profile: ProxyProfile): ProxyTestResult = withContext(Dispatchers.IO) {
        try {
            when (profile.type) {
                ProxyType.SOCKS5 -> testSocks5(profile)
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
}