package com.cloudsync.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.cloudsync.models.PrefsBundle
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.utils.DataStore

/**
 * SharedPreferences'dan senkronize edilecek verileri çıkarır ve geri yazar.
 *
 * CloudStream verileri iki farklı SharedPreferences dosyasında saklar:
 * 1. Default SharedPreferences (ayarlar, tercihler)
 * 2. "rebuild_preference" (içerik verileri, favoriler, izleme geçmişi)
 *
 * AYARLAR (SETTINGS) ve ARAMA GEÇMİŞİ (SEARCH_HISTORY) referans eklenti standardına uygun olarak
 * bütünsel demetler (bundle) halinde saklanır. Bu sayede:
 * - Yüzlerce ayar anahtarının tek tek satır olarak Supabase limitlerine takılması önlenir.
 * - Hangi ayarın hangi SharedPreferences dosyasına ait olduğu garanti altına alınır.
 * - Arama geçmişi temizlendiğinde diğer cihazlarda atomik olarak silinmesi sağlanır.
 */
class DataExtractor(private val context: Context) {

    companion object {
        private const val TAG = "CloudSync.DataExtractor"
        private const val REBUILD_PREFS_NAME = "rebuild_preference"
        const val TOMBSTONE_VALUE = "__DELETED__"
        const val EMPTY_BUNDLE_VALUE = "EMPTY"
        const val SETTINGS_BUNDLE_KEY = "settings_bundle"
        const val SEARCH_HISTORY_BUNDLE_KEY = "search_history_bundle"

        /**
         * Güvenlik nedeniyle senkronize EDİLMEYECEK key'ler.
         * Token, şifre ve cihaza özel ayarlar bu listede yer alır.
         * Referans CloudStream sync-plugin ile birebir uyumlu tam liste.
         */
        val nonTransferableKeys = setOf(
            // Hesap token'ları ve oturumlar
            "anilist_token", "anilist_user", "anilist_unixtime", "anilist_cached_list", "anilist_accounts", "anilist_active",
            "mal_token", "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_accounts", "mal_active",
            "kitsu_token", "kitsu_user", "kitsu_cached_list",
            "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active", "simkl_api_cache", "aniwave_simkl_sync",
            "account_token", "account_ids",
            "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
            "subdl_user", "subdl_accounts", "subdl_active",

            // Cihaza özel ayarlar, sync ve dosya yolları
            "biometric_key",
            "nginx_user",
            "download_path_key",
            "download_path_key_visual",
            "backup_path_key",
            "backup_dir_path_key",
            "download_info", "download_resume", "download_q_resume", "download_episode_cache",
            "cs3-votes", "last_sync_api", "last_click_action", "last_opened_id",
            "library_folder", "viewpager_item_key", "data_store_helper/account_key_index",
            "version_name", "files_to_delete_key", "has_done_setup",
            "fshare_setup", "fshare_token", "bluphim_token",
            "device_id", "sync_token", "sync_project_num", "sync_project_id", "sync_item_id", "sync_device_id",
            "restore_device", "backup_device", "prerelease_update",
            "CLOUDSYNC_WATCH_SYNC_CREDS", "CLOUDSYNC_APP_SETTINGS_SYNC_CREDS",
            "used_fstream_providers_v3", "fstream_version",
            "home_api_used", "home_api", "user_selected_homepage_api", "last_sync_api_key", "home_pref_homepage",
            "library_sorting_mode", "results_sorting_mode", "jsdelivr_proxy_key",

            // Plugin binary ve eklenti yerel verileri
            "plugins_key_local"
        )

        fun isNonTransferable(key: String): Boolean {
            val lower = key.lowercase()
            return nonTransferableKeys.any { lower.contains(it.lowercase()) } ||
                    lower.startsWith("cloudsync_")
        }
    }

    private val objectMapper = ObjectMapper()

    /**
     * Belirtilen veri tiplerine ait verileri çıkarır.
     * SETTINGS ve SEARCH_HISTORY demet olarak, BOOKMARKS, WATCH_PROGRESS ve REPOS
     * ise öğe bazlı olarak çıkarılır.
     */
    fun extractData(dataTypes: List<SyncDataType>): List<SyncDataItem> {
        val items = mutableListOf<SyncDataItem>()
        val userId = SyncConfig.getUserId(context)
        val deviceId = SyncConfig.getDeviceId(context)

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val dataPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        // ==================== 1. UYGULAMA AYARLARI (SETTINGS BUNDLE) ====================
        if (dataTypes.contains(SyncDataType.SETTINGS)) {
            val rebuildSettings = mutableMapOf<String, String>()
            val defaultSettings = mutableMapOf<String, String>()

            for ((key, value) in dataPrefs.all.orEmpty()) {
                if (!SyncConfig.isCloudSyncKey(key) && !isNonTransferable(key)) {
                    if (SyncDataType.fromKey(key, isRebuildPrefs = true) == SyncDataType.SETTINGS) {
                        serializeValue(value)?.let { rebuildSettings[key] = it }
                    }
                }
            }

            for ((key, value) in defaultPrefs.all.orEmpty()) {
                if (!SyncConfig.isCloudSyncKey(key) && !isNonTransferable(key)) {
                    if (SyncDataType.fromKey(key, isRebuildPrefs = false) == SyncDataType.SETTINGS) {
                        serializeValue(value)?.let { defaultSettings[key] = it }
                    }
                }
            }

            val bundle = PrefsBundle(rebuild = rebuildSettings, default = defaultSettings)
            val bundleJson = objectMapper.writeValueAsString(bundle)

            items.add(
                SyncDataItem(
                    userId = userId,
                    deviceId = deviceId,
                    dataType = SyncDataType.SETTINGS.name,
                    dataKey = SETTINGS_BUNDLE_KEY,
                    dataValue = bundleJson,
                    updatedAt = null
                )
            )
            Log.i(TAG, "Ayarlar demeti oluşturuldu: ${rebuildSettings.size} rebuild, ${defaultSettings.size} default")
        }

        // ==================== 2. ARAMA GEÇMİŞİ (SEARCH_HISTORY BUNDLE) ====================
        if (dataTypes.contains(SyncDataType.SEARCH_HISTORY)) {
            val rebuildSearch = mutableMapOf<String, String>()
            val defaultSearch = mutableMapOf<String, String>()

            for ((key, value) in dataPrefs.all.orEmpty()) {
                if (key.contains("search_history", ignoreCase = true) && !isNonTransferable(key)) {
                    val s = serializeValue(value)
                    if (s != null && s != "s:[]" && s != "ss:[]" && s != "s:\"\"" && s != "s:{}") {
                        rebuildSearch[key] = s
                    }
                }
            }

            for ((key, value) in defaultPrefs.all.orEmpty()) {
                if (key.contains("search_history", ignoreCase = true) && !isNonTransferable(key)) {
                    val s = serializeValue(value)
                    if (s != null && s != "s:[]" && s != "ss:[]" && s != "s:\"\"" && s != "s:{}") {
                        defaultSearch[key] = s
                    }
                }
            }

            val isSearchEmpty = rebuildSearch.isEmpty() && defaultSearch.isEmpty()
            val searchBundleValue = if (isSearchEmpty) {
                EMPTY_BUNDLE_VALUE
            } else {
                objectMapper.writeValueAsString(PrefsBundle(rebuild = rebuildSearch, default = defaultSearch))
            }

            items.add(
                SyncDataItem(
                    userId = userId,
                    deviceId = deviceId,
                    dataType = SyncDataType.SEARCH_HISTORY.name,
                    dataKey = SEARCH_HISTORY_BUNDLE_KEY,
                    dataValue = searchBundleValue,
                    updatedAt = null
                )
            )
            Log.i(TAG, "Arama geçmişi demeti oluşturuldu: ${if (isSearchEmpty) "TEMİZ / BOŞ" else "${rebuildSearch.size + defaultSearch.size} kayıt"}")
        }

        // ==================== 3. ÖĞE BAZLI VERİLER (BOOKMARKS, PROGRESS, REPOS) ====================
        val otherTypes = dataTypes.filter {
            it != SyncDataType.SETTINGS && it != SyncDataType.SEARCH_HISTORY
        }

        if (otherTypes.isNotEmpty()) {
            val currentKeys = mutableSetOf<String>()
            extractFromPrefs(defaultPrefs, otherTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = false)
            extractFromPrefs(dataPrefs, otherTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = true)

            // Silinen öğeleri tespit et (Tombstone)
            val knownKeys = SyncConfig.getKnownKeys(context)
            if (knownKeys.isNotEmpty()) {
                for (knownKey in knownKeys) {
                    if (knownKey == SETTINGS_BUNDLE_KEY || knownKey == SEARCH_HISTORY_BUNDLE_KEY) continue

                    if (!currentKeys.contains(knownKey)) {
                        val isRebuild = knownKey.contains("/") || Regex("^[0-9]+/").containsMatchIn(knownKey)
                        val dataType = SyncDataType.fromKey(knownKey, isRebuild)
                        if (otherTypes.contains(dataType)) {
                            if (items.none { it.dataKey == knownKey }) {
                                items.add(
                                    SyncDataItem(
                                        userId = userId,
                                        deviceId = deviceId,
                                        dataType = dataType.name,
                                        dataKey = knownKey,
                                        dataValue = TOMBSTONE_VALUE,
                                        updatedAt = null
                                    )
                                )
                                Log.i(TAG, "Silinmiş veri tespit edildi (Tombstone): $knownKey ($dataType)")
                            }
                        }
                    }
                }
            }
        }

        Log.i(TAG, "Toplam ${items.size} veri çıkarıldı. Tipler: ${dataTypes.map { it.name }}")
        return items
    }

    /**
     * Belirli bir SharedPreferences'dan öğe bazlı verileri çıkarır.
     */
    private fun extractFromPrefs(
        prefs: SharedPreferences,
        dataTypes: List<SyncDataType>,
        userId: String,
        deviceId: String,
        items: MutableList<SyncDataItem>,
        currentKeys: MutableSet<String>,
        isRebuildPrefs: Boolean
    ) {
        val allEntries = prefs.all ?: return

        for ((key, value) in allEntries) {
            if (SyncConfig.isCloudSyncKey(key)) continue
            if (isNonTransferable(key)) continue

            val dataType = SyncDataType.fromKey(key, isRebuildPrefs)
            if (!dataTypes.contains(dataType)) continue

            currentKeys.add(key)
            val serializedValue = serializeValue(value) ?: continue

            items.add(
                SyncDataItem(
                    userId = userId,
                    deviceId = deviceId,
                    dataType = dataType.name,
                    dataKey = key,
                    dataValue = serializedValue,
                    updatedAt = null
                )
            )
        }
    }

    /**
     * Buluttan indirilen verileri local SharedPreferences'a yazar veya siler.
     *
     * @param scheduler Eğer sağlanırsa, yazma sırasında restore guard aktifleştirilir.
     */
    fun applyData(items: List<SyncDataItem>, scheduler: SyncScheduler? = null) {
        scheduler?.beginRestore()

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        val defaultEditor = defaultPrefs.edit()
        val rebuildEditor = rebuildPrefs.edit()

        val currentAccount = try {
            com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
        } catch (_: Exception) {
            "0"
        }

        var appliedCount = 0

        for (item in items) {
            if (isNonTransferable(item.dataKey)) continue
            if (SyncConfig.isCloudSyncKey(item.dataKey)) continue

            val value = item.dataValue
            val isTombstone = value == null || value == TOMBSTONE_VALUE

            // ==================== 1. UYGULAMA AYARLARI (SETTINGS BUNDLE) ====================
            if (item.dataType == SyncDataType.SETTINGS.name || item.dataKey == SETTINGS_BUNDLE_KEY) {
                if (!isTombstone && !value.isNullOrBlank() && value != EMPTY_BUNDLE_VALUE) {
                    try {
                        val bundle = objectMapper.readValue(value, PrefsBundle::class.java)
                        bundle.rebuild.forEach { (k, v) ->
                            if (!isNonTransferable(k) && !SyncConfig.isCloudSyncKey(k)) {
                                deserializeAndApply(rebuildEditor, k, v)
                            }
                        }
                        bundle.default.forEach { (k, v) ->
                            if (!isNonTransferable(k) && !SyncConfig.isCloudSyncKey(k)) {
                                deserializeAndApply(defaultEditor, k, v)
                            }
                        }
                        Log.i(TAG, "Uygulama ayarları demeti uygulandı: ${bundle.rebuild.size} rebuild, ${bundle.default.size} default")
                    } catch (e: Exception) {
                        Log.e(TAG, "Ayarlar demeti uygulanamadı: ${e.message}")
                    }
                }
                appliedCount++
                continue
            }

            // ==================== 2. ARAMA GEÇMİŞİ (SEARCH_HISTORY BUNDLE) ====================
            if (item.dataType == SyncDataType.SEARCH_HISTORY.name || item.dataKey == SEARCH_HISTORY_BUNDLE_KEY) {
                // Her halükarda yereldeki tüm eski arama geçmişi anahtarlarını sil (atomik temizleme)
                rebuildPrefs.all?.keys?.forEach { k ->
                    if (k.contains("search_history", ignoreCase = true)) {
                        rebuildEditor.remove(k)
                    }
                }
                defaultPrefs.all?.keys?.forEach { k ->
                    if (k.contains("search_history", ignoreCase = true)) {
                        defaultEditor.remove(k)
                    }
                }

                if (isTombstone || value.isNullOrBlank() || value == EMPTY_BUNDLE_VALUE || value == "s:[]" || value == "ss:[]") {
                    Log.i(TAG, "Arama geçmişi bulut tarafından temizlendi")
                } else {
                    try {
                        val bundle = objectMapper.readValue(value, PrefsBundle::class.java)
                        bundle.rebuild.forEach { (k, v) ->
                            deserializeAndApply(rebuildEditor, k, v)
                            val baseKey = k.substringAfter('/')
                            deserializeAndApply(rebuildEditor, "$currentAccount/$baseKey", v)
                            deserializeAndApply(rebuildEditor, "search_history", v)
                        }
                        bundle.default.forEach { (k, v) ->
                            deserializeAndApply(defaultEditor, k, v)
                            deserializeAndApply(defaultEditor, "search_history", v)
                        }
                        Log.i(TAG, "Yeni arama geçmişi uygulandı (${bundle.rebuild.size + bundle.default.size} kayıt)")
                    } catch (e: Exception) {
                        // Geriye dönük uyumluluk (düz JSON/string ise)
                        deserializeAndApply(rebuildEditor, "$currentAccount/search_history", value)
                        deserializeAndApply(rebuildEditor, "search_history", value)
                        deserializeAndApply(defaultEditor, "search_history", value)
                    }
                }
                appliedCount++
                continue
            }

            // ==================== 3. ÖĞE BAZLI VERİLER (BOOKMARKS, PROGRESS, REPOS) ====================
            val isRebuildItem = item.dataKey.contains("/") ||
                    Regex("^[0-9]+/").containsMatchIn(item.dataKey)

            val editor = if (isRebuildItem) rebuildEditor else defaultEditor
            val accountMatch = Regex("^([0-9]+)/(.*)").find(item.dataKey)

            if (isTombstone) {
                editor.remove(item.dataKey)

                if (accountMatch != null) {
                    val relativePath = accountMatch.groupValues[2]
                    rebuildEditor.remove("$currentAccount/$relativePath")

                    if (relativePath.startsWith("result_watch_state_data")) {
                        val counterpart = relativePath.replace("result_watch_state_data", "result_watch_state")
                        rebuildEditor.remove("$currentAccount/$counterpart")
                        rebuildEditor.remove("${accountMatch.groupValues[1]}/$counterpart")
                    }
                    if (relativePath.startsWith("result_resume_watching")) {
                        val counterpart = relativePath.replace("result_resume_watching", "result_resume_watching_2")
                        rebuildEditor.remove("$currentAccount/$counterpart")
                        rebuildEditor.remove("${accountMatch.groupValues[1]}/$counterpart")
                    }
                }
                appliedCount++
                continue
            }

            deserializeAndApply(editor, item.dataKey, value)
            appliedCount++

            if (accountMatch != null) {
                val sourceAccount = accountMatch.groupValues[1]
                val relativePath = accountMatch.groupValues[2]
                if (sourceAccount != currentAccount) {
                    deserializeAndApply(rebuildEditor, "$currentAccount/$relativePath", value)
                }
            }
        }

        defaultEditor.apply()
        rebuildEditor.apply()

        scheduler?.endRestore()
        Log.i(TAG, "$appliedCount veri uygulandı/silindi (Hedef aktif profil: $currentAccount)")
    }

    /**
     * Belirtilen tiplere ait bilinen anahtarları döndürür.
     */
    fun getAllCurrentKeys(dataTypes: List<SyncDataType>): Set<String> {
        val keys = mutableSetOf<String>()
        if (dataTypes.contains(SyncDataType.SETTINGS)) {
            keys.add(SETTINGS_BUNDLE_KEY)
        }
        if (dataTypes.contains(SyncDataType.SEARCH_HISTORY)) {
            keys.add(SEARCH_HISTORY_BUNDLE_KEY)
        }

        val otherTypes = dataTypes.filter {
            it != SyncDataType.SETTINGS && it != SyncDataType.SEARCH_HISTORY
        }

        if (otherTypes.isNotEmpty()) {
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

            defaultPrefs.all?.keys?.forEach { key ->
                if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                    val type = SyncDataType.fromKey(key, isRebuildPrefs = false)
                    if (otherTypes.contains(type)) keys.add(key)
                }
            }
            rebuildPrefs.all?.keys?.forEach { key ->
                if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                    val type = SyncDataType.fromKey(key, isRebuildPrefs = true)
                    if (otherTypes.contains(type)) keys.add(key)
                }
            }
        }
        return keys
    }

    /**
     * Belirtilen veri tipine ait yerel anahtar sayısını döndürür.
     */
    fun countKeys(type: SyncDataType): Int {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        var count = 0
        for ((k, _) in defaultPrefs.all.orEmpty()) {
            if (!isNonTransferable(k) && !SyncConfig.isCloudSyncKey(k) && SyncDataType.fromKey(k, isRebuildPrefs = false) == type) {
                count++
            }
        }
        for ((k, _) in rebuildPrefs.all.orEmpty()) {
            if (!isNonTransferable(k) && !SyncConfig.isCloudSyncKey(k) && SyncDataType.fromKey(k, isRebuildPrefs = true) == type) {
                count++
            }
        }
        return count
    }

    /**
     * SharedPreferences değerini JSON-uyumlu string'e serileştirir.
     */
    private fun serializeValue(value: Any?): String? {
        if (value == null) return null
        return when (value) {
            is Boolean -> "b:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is Float -> "f:$value"
            is String -> "s:$value"
            is Set<*> -> {
                val strSet = value.filterIsInstance<String>()
                val json = objectMapper.writeValueAsString(strSet)
                "ss:$json"
            }
            else -> "s:${value.toString()}"
        }
    }

    /**
     * Serileştirilmiş string'i orijinal tipine dönüştürerek SharedPreferences'a yazar.
     */
    private fun deserializeAndApply(
        editor: SharedPreferences.Editor,
        key: String,
        serializedValue: String
    ) {
        try {
            when {
                serializedValue.startsWith("b:") -> {
                    editor.putBoolean(key, serializedValue.substring(2).toBoolean())
                }
                serializedValue.startsWith("i:") -> {
                    editor.putInt(key, serializedValue.substring(2).toInt())
                }
                serializedValue.startsWith("l:") -> {
                    editor.putLong(key, serializedValue.substring(2).toLong())
                }
                serializedValue.startsWith("f:") -> {
                    editor.putFloat(key, serializedValue.substring(2).toFloat())
                }
                serializedValue.startsWith("ss:") -> {
                    val json = serializedValue.substring(3)
                    val set = objectMapper.readValue(json, object : TypeReference<Set<String>>() {})
                    editor.putStringSet(key, set)
                }
                serializedValue.startsWith("s:") -> {
                    editor.putString(key, serializedValue.substring(2))
                }
                else -> {
                    editor.putString(key, serializedValue)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Değer deserialize edilemedi: key=$key, val=$serializedValue", e)
        }
    }
}
