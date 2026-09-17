package com.cloudsync.models

import android.content.Context
import android.content.SharedPreferences

/**
 * CloudSync eklenti yapılandırması.
 * Tüm ayarlar SharedPreferences'a kaydedilir.
 */
object SyncConfig {
    private const val PREFS_NAME = "cloudsync_config"

    // Supabase ayarları
    private const val KEY_SUPABASE_URL = "cloudsync_supabase_url"
    private const val KEY_SUPABASE_KEY = "cloudsync_supabase_key"
    private const val KEY_USER_ID = "cloudsync_user_id"
    private const val KEY_DEVICE_ID = "cloudsync_device_id"

    // Senkronizasyon ayarları
    private const val KEY_AUTO_SYNC = "cloudsync_auto_sync"
    private const val KEY_SYNC_ON_LAUNCH = "cloudsync_sync_on_launch"
    private const val KEY_SYNC_ON_EXIT = "cloudsync_sync_on_exit"
    private const val KEY_SYNC_INTERVAL_MIN = "cloudsync_sync_interval"
    private const val KEY_LAST_SYNC_TIME = "cloudsync_last_sync"

    // Hangi veri tipleri senkronize edilecek
    private const val KEY_SYNC_BOOKMARKS = "cloudsync_sync_bookmarks"
    private const val KEY_SYNC_WATCH_PROGRESS = "cloudsync_sync_progress"
    private const val KEY_SYNC_SEARCH_HISTORY = "cloudsync_sync_search"
    private const val KEY_SYNC_REPOS = "cloudsync_sync_repos"
    private const val KEY_SYNC_SETTINGS = "cloudsync_sync_settings"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== Supabase Ayarları ====================

    fun getSupabaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_SUPABASE_URL, "") ?: ""
    }

    fun setSupabaseUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_SUPABASE_URL, url.trimEnd('/')).apply()
    }

    fun getSupabaseKey(context: Context): String {
        return getPrefs(context).getString(KEY_SUPABASE_KEY, "") ?: ""
    }

    fun setSupabaseKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_SUPABASE_KEY, key.trim()).apply()
    }

    fun getUserId(context: Context): String {
        return getPrefs(context).getString(KEY_USER_ID, "") ?: ""
    }

    fun setUserId(context: Context, userId: String) {
        getPrefs(context).edit().putString(KEY_USER_ID, userId.trim()).apply()
    }

    fun getDeviceId(context: Context): String {
        var deviceId = getPrefs(context).getString(KEY_DEVICE_ID, "") ?: ""
        if (deviceId.isEmpty()) {
            deviceId = generateDeviceId()
            getPrefs(context).edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun setDeviceId(context: Context, deviceId: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    // ==================== Senkronizasyon Ayarları ====================

    fun isAutoSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSync(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    fun isSyncOnLaunchEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_ON_LAUNCH, true)
    }

    fun setSyncOnLaunch(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_ON_LAUNCH, enabled).apply()
    }

    fun isSyncOnExitEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_ON_EXIT, false)
    }

    fun setSyncOnExit(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_ON_EXIT, enabled).apply()
    }

    fun getSyncIntervalMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_SYNC_INTERVAL_MIN, 30)
    }

    fun setSyncIntervalMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_SYNC_INTERVAL_MIN, minutes).apply()
    }

    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun setLastSyncTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    // ==================== Veri Tipi Seçimleri ====================

    fun isSyncEnabled(context: Context, dataType: SyncDataType): Boolean {
        val key = when (dataType) {
            SyncDataType.BOOKMARKS -> KEY_SYNC_BOOKMARKS
            SyncDataType.WATCH_PROGRESS -> KEY_SYNC_WATCH_PROGRESS
            SyncDataType.SEARCH_HISTORY -> KEY_SYNC_SEARCH_HISTORY
            SyncDataType.REPOS -> KEY_SYNC_REPOS
            SyncDataType.SETTINGS -> KEY_SYNC_SETTINGS
        }
        return getPrefs(context).getBoolean(key, true) // Varsayılan: hepsi aktif
    }

    fun setSyncEnabled(context: Context, dataType: SyncDataType, enabled: Boolean) {
        val key = when (dataType) {
            SyncDataType.BOOKMARKS -> KEY_SYNC_BOOKMARKS
            SyncDataType.WATCH_PROGRESS -> KEY_SYNC_WATCH_PROGRESS
            SyncDataType.SEARCH_HISTORY -> KEY_SYNC_SEARCH_HISTORY
            SyncDataType.REPOS -> KEY_SYNC_REPOS
            SyncDataType.SETTINGS -> KEY_SYNC_SETTINGS
        }
        getPrefs(context).edit().putBoolean(key, enabled).apply()
    }

    // ==================== Doğrulama ====================

    /**
     * Supabase ayarlarının girilip girilmediğini kontrol eder.
     */
    fun isConfigured(context: Context): Boolean {
        return getSupabaseUrl(context).isNotEmpty() &&
                getSupabaseKey(context).isNotEmpty() &&
                getUserId(context).isNotEmpty()
    }

    /**
     * Tüm CloudSync ayarlarını sıfırlar.
     */
    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    /**
     * Rastgele bir cihaz ID'si üretir.
     */
    private fun generateDeviceId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val prefix = android.os.Build.MODEL.take(10).replace(" ", "_")
        val random = (1..8).map { chars.random() }.joinToString("")
        return "${prefix}_$random"
    }

    /**
     * CloudSync kendi ayar key'lerini senkronize etmekten hariç tutar.
     */
    fun isCloudSyncKey(key: String): Boolean {
        return key.startsWith("cloudsync_")
    }

    // ==================== Silme Takibi (Known Keys) ====================
    private const val KEY_KNOWN_SYNC_KEYS = "cloudsync_known_keys"

    fun getKnownKeys(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_KNOWN_SYNC_KEYS, emptySet()) ?: emptySet()
    }

    fun saveKnownKeys(context: Context, keys: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_KNOWN_SYNC_KEYS, HashSet(keys)).apply()
    }

    // ==================== Tek Linkle Kurulum (Magic Link) ====================

    /**
     * Hızlı Kurulum Bağlantısı (Magic Link / Connection String) ayrıştırır ve ayarları anında uygular.
     * Desteklenen formatlar:
     * 1. https://xyz.supabase.co#key=eyJ...&user=kullanici (Fragment tabanlı)
     * 2. https://xyz.supabase.co?apikey=eyJ...&user_id=kullanici (Query param tabanlı)
     * 3. cloudsync://<base64>
     */
    fun parseAndApplyConnectionLink(context: Context, rawLink: String): Boolean {
        val link = rawLink.trim()
        if (link.isEmpty()) return false

        try {
            // 1. cloudsync:// formatı
            if (link.startsWith("cloudsync://", ignoreCase = true)) {
                val encoded = link.substringAfter("://")
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(decoded)
                val url = json.path("url").asText("")
                val key = json.path("key").asText("")
                val user = json.path("user").asText("")
                if (url.isNotEmpty() && key.isNotEmpty()) {
                    setSupabaseUrl(context, url)
                    setSupabaseKey(context, key)
                    if (user.isNotEmpty()) setUserId(context, user)
                    else if (getUserId(context).isEmpty()) setUserId(context, "user_${(1000..9999).random()}")
                    return true
                }
            }

            // 2. Standart HTTP URL formatı
            val uri = android.net.Uri.parse(link)
            val host = uri.host ?: return false
            val scheme = uri.scheme ?: "https"
            val baseUrl = "$scheme://$host"

            var key: String? = null
            var user: String? = null

            // Fragment (#key=...&user=...) kontrolü
            val fragment = uri.fragment
            if (!fragment.isNullOrEmpty()) {
                val params = fragment.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                }
                key = params["key"] ?: params["apikey"] ?: params["anon_key"]
                user = params["user"] ?: params["user_id"] ?: params["userId"]
            }

            // Query params (?apikey=...&user_id=...) kontrolü
            if (key.isNullOrEmpty()) {
                key = uri.getQueryParameter("key")
                    ?: uri.getQueryParameter("apikey")
                    ?: uri.getQueryParameter("anon_key")
            }
            if (user.isNullOrEmpty()) {
                user = uri.getQueryParameter("user")
                    ?: uri.getQueryParameter("user_id")
                    ?: uri.getQueryParameter("userId")
            }

            if (!key.isNullOrEmpty()) {
                setSupabaseUrl(context, baseUrl)
                setSupabaseKey(context, key)
                if (!user.isNullOrEmpty()) {
                    setUserId(context, user)
                } else if (getUserId(context).isEmpty()) {
                    setUserId(context, "user_${(1000..9999).random()}")
                }
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Diğer cihazlara tek tıkla kurulum yapabilmek için paylaşılabilir bağlantı linki üretir.
     */
    fun generateConnectionLink(context: Context): String {
        val url = getSupabaseUrl(context)
        val key = getSupabaseKey(context)
        val user = getUserId(context)
        if (url.isEmpty() || key.isEmpty()) return ""
        return "$url#key=$key&user=$user"
    }
}
