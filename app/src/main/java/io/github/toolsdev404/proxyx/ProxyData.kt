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
}