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
            // Eski/geçersiz "result_resume_watching" anahtarları (2 olmayanlar) CloudStream'in
            // migrateResumeWatching fonksiyonunu tetikleyip episodeId'yi null yaptığı için
            // kesinlikle transfer edilmemeli ve yok sayılmalıdır.
            if (lower.contains("result_resume_watching") && !lower.contains("result_resume_watching_2")) {
                return true
            }
            return nonTransferableKeys.any { lower.contains(it.lowercase()) } ||
                    lower.startsWith("cloudsync_")
        }
    }

    private val objectMapper = ObjectMapper()

    private fun getCurrentAccountId(): String {
        return try {
            com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
        } catch (_: Exception) {
            "0"
        }
    }

    private fun nowIso(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    /**
     * Belirtilen veri tiplerine ait verileri çıkarır.
     * SETTINGS ve SEARCH_HISTORY demet olarak, BOOKMARKS, WATCH_PROGRESS ve REPOS
     * ise öğe bazlı olarak çıkarılır.
     */
    fun extractData(dataTypes: List<SyncDataType>): List<SyncDataItem> {
        val items = mutableListOf<SyncDataItem>()
        val userId = SyncConfig.getUserId(context)
        val deviceId = SyncConfig.getDeviceId(context)
        val now = nowIso()

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
                    updatedAt = now
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
                    serializeValue(value)?.let { rebuildSearch[key] = it }
                }
            }

            for ((key, value) in defaultPrefs.all.orEmpty()) {
                if (key.contains("search_history", ignoreCase = true) && !isNonTransferable(key)) {
                    serializeValue(value)?.let { defaultSearch[key] = it }
                }
            }

            // Eğer spesifik alt anahtarlar varsa (örneğin "0/search_history/2068224914"),
            // eski sürümlerin yanlışlıkla yazdığı kök anahtarları ("search_history", "0/search_history") temizle
            val hasSubkeys = rebuildSearch.keys.any { it.matches(Regex(".*/search_history/.+")) } ||
                    defaultSearch.keys.any { it.matches(Regex(".*/search_history/.+")) }
            if (hasSubkeys) {
                rebuildSearch.remove("search_history")
                rebuildSearch.remove("0/search_history")
                val acc = getCurrentAccountId()
                rebuildSearch.remove("$acc/search_history")

                defaultSearch.remove("search_history")
                defaultSearch.remove("0/search_history")
                defaultSearch.remove("$acc/search_history")
            }

            val isSearchEmpty = (rebuildSearch.isEmpty() && defaultSearch.isEmpty()) ||
                    (rebuildSearch.values.all { it == "s:[]" || it == "s:\"\"" || it == "EMPTY" || it == "s:{}" } &&
                     defaultSearch.values.all { it == "s:[]" || it == "s:\"\"" || it == "EMPTY" || it == "s:{}" })

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
                    updatedAt = now
                )
            )
            Log.i(TAG, "Arama geçmişi demeti oluşturuldu: ${if (isSearchEmpty) "TEMİZ / BOŞ" else "${rebuildSearch.size} rebuild, ${defaultSearch.size} default"}")
        }

        // ==================== 3. ÖĞE BAZLI VERİLER (BOOKMARKS, PROGRESS, REPOS) ====================
        val otherTypes = dataTypes.filter {
            it != SyncDataType.SETTINGS && it != SyncDataType.SEARCH_HISTORY
        }

        if (otherTypes.isNotEmpty()) {
            val currentKeys = mutableSetOf<String>()
            extractFromPrefs(defaultPrefs, otherTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = false, now = now)
            extractFromPrefs(dataPrefs, otherTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = true, now = now)

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
                                        updatedAt = now
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
        isRebuildPrefs: Boolean,
        now: String
    ) {
        val allEntries = prefs.all ?: return

        for ((key, value) in allEntries) {
            if (SyncConfig.isCloudSyncKey(key)) continue
            if (isNonTransferable(key)) continue

            // rebuild_preference içinde hesap öneksiz kalmış çöp anahtarları atla (download_header_cache hariç)
            if (isRebuildPrefs && !Regex("^[0-9]+/").containsMatchIn(key) && !key.startsWith("download_header_cache")) {
                if (key.startsWith("video_pos_dur") || key.startsWith("result_resume_watching") ||
                    key.startsWith("video_watch_state") || key.startsWith("result_watch_state") ||
                    key.startsWith("result_favorites_state_data") || key.startsWith("result_subscribed_state_data") ||
                    key.startsWith("result_season") || key.startsWith("result_episode") || key.startsWith("result_dub")) {
                    continue
                }
            }

            val dataType = SyncDataType.fromKey(key, isRebuildPrefs)
            if (!dataTypes.contains(dataType)) continue

            // REPOS (Eklentiler ve Depolar) küreseldir, asla hesap önekli (0/...) olmamalıdır!
            val effectiveKey = if (dataType == SyncDataType.REPOS) {
                val cleanKey = key.replaceFirst(Regex("^[0-9]+/"), "")
                if (key != cleanKey) {
                    prefs.edit().remove(key).apply()
                    Log.i(TAG, "Yereldeki çöp hesap önekli repo anahtarı temizlendi: $key")
                }
                cleanKey
            } else {
                key
            }

            if (items.any { it.dataType == dataType.name && it.dataKey == effectiveKey }) {
                continue
            }

            currentKeys.add(effectiveKey)
            val serializedValue = serializeValue(value) ?: continue

            items.add(
                SyncDataItem(
                    userId = userId,
                    deviceId = deviceId,
                    dataType = dataType.name,
                    dataKey = effectiveKey,
                    dataValue = serializedValue,
                    updatedAt = now
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

                        fun applySearchEntry(rawKey: String, rawVal: String) {
                            var searchKey: String? = null
                            val lastPart = rawKey.substringAfterLast('/')
                            if (lastPart.isNotBlank() && lastPart != "search_history") {
                                searchKey = lastPart
                            }

                            if (searchKey == null) {
                                try {
                                    val cleanJson = if (rawVal.startsWith("s:")) rawVal.substring(2) else rawVal
                                    if (cleanJson.startsWith("{")) {
                                        val node = objectMapper.readTree(cleanJson)
                                        val k = node.path("key").asText(null)
                                        val text = node.path("searchText").asText(null)
                                        searchKey = when {
                                            !k.isNullOrBlank() -> k
                                            !text.isNullOrBlank() -> text.hashCode().toString()
                                            else -> null
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            // rawVal bir JSON dizisi ise her birini ayrı arama kaydı olarak yaz
                            try {
                                val cleanJson = if (rawVal.startsWith("s:")) rawVal.substring(2) else rawVal
                                if (cleanJson.startsWith("[")) {
                                    val arr = objectMapper.readTree(cleanJson)
                                    if (arr.isArray) {
                                        for (elem in arr) {
                                            val text = if (elem.isObject) elem.path("searchText").asText(elem.asText()) else elem.asText()
                                            if (!text.isNullOrBlank()) {
                                                val elemKey = if (elem.isObject && elem.has("key")) elem.get("key").asText() else text.hashCode().toString()
                                                val elemVal = if (elem.isObject) "s:${objectMapper.writeValueAsString(elem)}" else "s:{\"searchedAt\":${System.currentTimeMillis()},\"searchText\":\"$text\",\"type\":[],\"key\":\"$elemKey\"}"

                                                deserializeAndApply(rebuildEditor, "0/search_history/$elemKey", elemVal)
                                                if (currentAccount != "0") {
                                                    deserializeAndApply(rebuildEditor, "$currentAccount/search_history/$elemKey", elemVal)
                                                }
                                                deserializeAndApply(defaultEditor, "0/search_history/$elemKey", elemVal)
                                                if (currentAccount != "0") {
                                                    deserializeAndApply(defaultEditor, "$currentAccount/search_history/$elemKey", elemVal)
                                                }
                                            }
                                        }
                                        return
                                    }
                                }
                            } catch (_: Exception) {}

                            if (!searchKey.isNullOrBlank()) {
                                val k0 = "0/search_history/$searchKey"
                                deserializeAndApply(rebuildEditor, k0, rawVal)
                                deserializeAndApply(defaultEditor, k0, rawVal)
                                if (currentAccount != "0") {
                                    val kAcc = "$currentAccount/search_history/$searchKey"
                                    deserializeAndApply(rebuildEditor, kAcc, rawVal)
                                    deserializeAndApply(defaultEditor, kAcc, rawVal)
                                }
                            } else {
                                deserializeAndApply(rebuildEditor, rawKey, rawVal)
                                deserializeAndApply(defaultEditor, rawKey, rawVal)
                            }
                        }

                        bundle.rebuild.forEach { (k, v) ->
                            applySearchEntry(k, v)
                        }
                        bundle.default.forEach { (k, v) ->
                            applySearchEntry(k, v)
                        }
                        Log.i(TAG, "Yeni arama geçmişi uygulandı: ${bundle.rebuild.size} rebuild, ${bundle.default.size} default")
                    } catch (e: Exception) {
                        Log.w(TAG, "Arama geçmişi bundle okunamadı, doğrudan string olarak deneniyor: ${e.message}")
                        try {
                            val cleanJson = if (value.startsWith("s:")) value.substring(2) else value
                            if (cleanJson.startsWith("{")) {
                                val node = objectMapper.readTree(cleanJson)
                                val k = node.path("key").asText(null) ?: node.path("searchText").asText(null)?.hashCode()?.toString()
                                if (!k.isNullOrBlank()) {
                                    deserializeAndApply(rebuildEditor, "0/search_history/$k", value)
                                    deserializeAndApply(defaultEditor, "0/search_history/$k", value)
                                    if (currentAccount != "0") {
                                        deserializeAndApply(rebuildEditor, "$currentAccount/search_history/$k", value)
                                        deserializeAndApply(defaultEditor, "$currentAccount/search_history/$k", value)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                appliedCount++
                continue
            }

            // ==================== 3. DEPOLAR VE EKLENTİLER (REPOS) ====================
            val isRepoItem = item.dataType == SyncDataType.REPOS.name ||
                    SyncDataType.fromKey(item.dataKey) == SyncDataType.REPOS

            if (isRepoItem) {
                val cleanKey = item.dataKey.replaceFirst(Regex("^[0-9]+/"), "")
                if (isTombstone) {
                    defaultEditor.remove(cleanKey)
                    rebuildEditor.remove(cleanKey)
                    rebuildEditor.remove("0/$cleanKey")
                    rebuildEditor.remove("$currentAccount/$cleanKey")
                    appliedCount++
                    continue
                }

                val rawVal = value ?: continue
                val isRepoList = cleanKey.equals("REPOSITORIES_KEY", ignoreCase = true) ||
                        cleanKey.equals("plugins_repositories", ignoreCase = true) ||
                        cleanKey.equals("repositories", ignoreCase = true)
                val isPluginList = cleanKey.equals("PLUGINS_KEY", ignoreCase = true)

                if (isRepoList) {
                    val localRepo = defaultPrefs.getString("REPOSITORIES_KEY", null)
                        ?: rebuildPrefs.getString("REPOSITORIES_KEY", null)
                    val merged = PluginSyncHelper.mergeRepositoriesJson(localRepo, rawVal)

                    deserializeAndApply(defaultEditor, "REPOSITORIES_KEY", merged)
                    deserializeAndApply(defaultEditor, "plugins_repositories", merged)
                    deserializeAndApply(defaultEditor, "repositories", merged)

                    deserializeAndApply(rebuildEditor, "REPOSITORIES_KEY", merged)
                    deserializeAndApply(rebuildEditor, "plugins_repositories", merged)
                    deserializeAndApply(rebuildEditor, "repositories", merged)

                    rebuildEditor.remove("0/REPOSITORIES_KEY")
                    rebuildEditor.remove("$currentAccount/REPOSITORIES_KEY")
                    rebuildEditor.remove("0/plugins_repositories")
                    rebuildEditor.remove("0/repositories")
                    Log.i(TAG, "Depolar uygulandı ve birleştirildi: $cleanKey")
                } else if (isPluginList) {
                    val localPlugin = defaultPrefs.getString("PLUGINS_KEY", null)
                        ?: rebuildPrefs.getString("PLUGINS_KEY", null)
                    val merged = PluginSyncHelper.mergePluginsJson(localPlugin, rawVal)

                    deserializeAndApply(defaultEditor, "PLUGINS_KEY", merged)
                    deserializeAndApply(rebuildEditor, "PLUGINS_KEY", merged)

                    rebuildEditor.remove("0/PLUGINS_KEY")
                    rebuildEditor.remove("$currentAccount/PLUGINS_KEY")

                    // Eksik .cs3 dosyalarını arka planda indir ve CloudStream'e yükle
                    PluginSyncHelper.downloadMissingPlugins(context, merged)
                    Log.i(TAG, "Eklentiler uygulandı ve birleştirildi: $cleanKey")
                } else {
                    // auto_download_plugins_key2, user_custom_sites vb.
                    deserializeAndApply(defaultEditor, cleanKey, rawVal)
                    deserializeAndApply(rebuildEditor, cleanKey, rawVal)
                    rebuildEditor.remove("0/$cleanKey")
                    rebuildEditor.remove("$currentAccount/$cleanKey")
                }
                appliedCount++
                continue
            }

            // ==================== 4. ÖĞE BAZLI DİĞER VERİLER (BOOKMARKS, PROGRESS) ====================
            val isRebuildItem = item.dataType == SyncDataType.WATCH_PROGRESS.name ||
                    item.dataType == SyncDataType.BOOKMARKS.name ||
                    item.dataKey.contains("/") ||
                    Regex("^[0-9]+/").containsMatchIn(item.dataKey)

            val editor = if (isRebuildItem) rebuildEditor else defaultEditor
            val accountMatch = Regex("^([0-9]+)/(.*)").find(item.dataKey)

            if (isTombstone) {
                editor.remove(item.dataKey)

                if (accountMatch != null) {
                    val sourceAccount = accountMatch.groupValues[1]
                    val relativePath = accountMatch.groupValues[2]
                    rebuildEditor.remove("$currentAccount/$relativePath")
                    rebuildEditor.remove("0/$relativePath")
                    rebuildEditor.remove(relativePath)
                    if (sourceAccount != currentAccount) {
                        rebuildEditor.remove("$sourceAccount/$relativePath")
                    }

                    if (relativePath.startsWith("result_watch_state")) {
                        rebuildEditor.remove("$currentAccount/result_watch_state")
                        rebuildEditor.remove("$currentAccount/result_watch_state_data")
                    }
                } else {
                    rebuildEditor.remove("$currentAccount/${item.dataKey}")
                    rebuildEditor.remove("0/${item.dataKey}")
                }
                appliedCount++
                continue
            }

            val rawVal = value ?: continue
            val targetValue = if (item.dataKey.contains("result_resume_watching_2")) {
                repairResumeWatchingJson(rawVal, rebuildPrefs, currentAccount)
            } else {
                rawVal
            }

            if (accountMatch != null) {
                val sourceAccount = accountMatch.groupValues[1]
                val relativePath = accountMatch.groupValues[2]

                // CloudStream rebuild_preference verilerini HER ZAMAN hesap önekiyle okur ($currentAccount/...)
                deserializeAndApply(rebuildEditor, "$currentAccount/$relativePath", targetValue)
                if (currentAccount != "0") {
                    deserializeAndApply(rebuildEditor, "0/$relativePath", targetValue)
                }
                if (sourceAccount != currentAccount && sourceAccount != "0") {
                    deserializeAndApply(rebuildEditor, "$sourceAccount/$relativePath", targetValue)
                }

                // Eski sürümlerin oluşturduğu hesapsız çöp anahtarı temizle (download_header_cache hariç)
                if (!relativePath.startsWith("download_header_cache")) {
                    rebuildEditor.remove(relativePath)
                }
            } else if (item.dataKey.startsWith("download_header_cache")) {
                // download_header_cache hesapsız saklanır
                deserializeAndApply(rebuildEditor, item.dataKey, targetValue)
                rebuildEditor.remove("$currentAccount/${item.dataKey}")
                rebuildEditor.remove("0/${item.dataKey}")
            } else if (isRebuildItem) {
                // Key'in başında hesap öneki yoksa, sadece profil önekiyle yaz ve hesapsızı sil
                deserializeAndApply(rebuildEditor, "$currentAccount/${item.dataKey}", targetValue)
                if (currentAccount != "0") {
                    deserializeAndApply(rebuildEditor, "0/${item.dataKey}", targetValue)
                }
                rebuildEditor.remove(item.dataKey)
            } else {
                deserializeAndApply(defaultEditor, item.dataKey, targetValue)
            }
            appliedCount++
        }

        // Eski legacy result_resume_watching ve hesap önekli çöp repo anahtarlarını yerelden tamamen temizle
        rebuildPrefs.all?.keys?.forEach { k ->
            if (k.contains("result_resume_watching") && !k.contains("result_resume_watching_2")) {
                rebuildEditor.remove(k)
            } else if (Regex("^[0-9]+/").containsMatchIn(k)) {
                val cleanK = k.replaceFirst(Regex("^[0-9]+/"), "")
                if (cleanK.equals("REPOSITORIES_KEY", ignoreCase = true) ||
                    cleanK.equals("PLUGINS_KEY", ignoreCase = true) ||
                    cleanK.equals("plugins_repositories", ignoreCase = true) ||
                    cleanK.equals("repositories", ignoreCase = true) ||
                    cleanK.equals("auto_download_plugins_key2", ignoreCase = true) ||
                    cleanK.equals("user_custom_sites", ignoreCase = true)) {
                    rebuildEditor.remove(k)
                }
            }
        }

        defaultEditor.apply()
        rebuildEditor.apply()

        scheduler?.endRestore()
        Log.i(TAG, "$appliedCount veri uygulandı/silindi (Hedef aktif profil: $currentAccount)")
    }

    /**
     * ResumeWatching JSON verisinde episodeId null ise (CloudStream migrasyon hatası sonucu),
     * parentId ve yerel video_pos_dur eşleşmesine bakarak episodeId'yi tamir eder.
     */
    private fun repairResumeWatchingJson(
        jsonValue: String,
        prefs: SharedPreferences,
        account: String
    ): String {
        return try {
            val prefix = when {
                jsonValue.startsWith("s:") -> "s:"
                jsonValue.startsWith("j:") -> "j:"
                else -> ""
            }
            var raw = if (prefix.isNotEmpty()) jsonValue.substring(prefix.length) else jsonValue
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
                try {
                    raw = objectMapper.readValue(raw, String::class.java)
                } catch (_: Exception) {}
            }
            if (raw.startsWith("{")) {
                val node = objectMapper.readTree(raw) as? com.fasterxml.jackson.databind.node.ObjectNode
                    ?: return jsonValue
                val epNode = node.get("episodeId")
                val isNullOrZero = epNode == null || epNode.isNull || epNode.asInt(0) == 0
                if (isNullOrZero && node.has("parentId") && !node.get("parentId").isNull) {
                    val parentId = node.get("parentId").asInt()
                    // Film / tek bölüm kontrolü: Eğer parentId için video_pos_dur varsa, bu bir filmdir ve episodeId = parentId olmalıdır
                    val hasPosForParent = prefs.contains("$account/video_pos_dur/$parentId") ||
                            prefs.contains("0/video_pos_dur/$parentId")
                    val isMovieCandidate = node.path("episode").asInt(0) <= 1 && node.path("season").asInt(0) <= 1
                    if (hasPosForParent || isMovieCandidate) {
                        node.put("episodeId", parentId)
                        Log.i(TAG, "ResumeWatching episodeId tamir edildi (parentId=$parentId -> episodeId=$parentId)")
                        val updated = objectMapper.writeValueAsString(node)
                        return if (prefix.isNotEmpty()) "$prefix$updated" else updated
                    }
                }
            }
            jsonValue
        } catch (_: Exception) {
            jsonValue
        }
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
                    if (otherTypes.contains(type)) {
                        val effectiveKey = if (type == SyncDataType.REPOS) key.replaceFirst(Regex("^[0-9]+/"), "") else key
                        keys.add(effectiveKey)
                    }
                }
            }
            rebuildPrefs.all?.keys?.forEach { key ->
                if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                    if (!Regex("^[0-9]+/").containsMatchIn(key) && !key.startsWith("download_header_cache")) {
                        if (key.startsWith("video_pos_dur") || key.startsWith("result_resume_watching") ||
                            key.startsWith("video_watch_state") || key.startsWith("result_watch_state") ||
                            key.startsWith("result_favorites_state_data") || key.startsWith("result_subscribed_state_data") ||
                            key.startsWith("result_season") || key.startsWith("result_episode") || key.startsWith("result_dub")) {
                            return@forEach
                        }
                    }
                    val type = SyncDataType.fromKey(key, isRebuildPrefs = true)
                    if (otherTypes.contains(type)) {
                        val effectiveKey = if (type == SyncDataType.REPOS) key.replaceFirst(Regex("^[0-9]+/"), "") else key
                        keys.add(effectiveKey)
                    }
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
            if (!isNonTransferable(k) && !SyncConfig.isCloudSyncKey(k)) {
                if (!Regex("^[0-9]+/").containsMatchIn(k) && !k.startsWith("download_header_cache")) {
                    if (k.startsWith("video_pos_dur") || k.startsWith("result_resume_watching") ||
                        k.startsWith("video_watch_state") || k.startsWith("result_watch_state") ||
                        k.startsWith("result_favorites_state_data") || k.startsWith("result_subscribed_state_data") ||
                        k.startsWith("result_season") || k.startsWith("result_episode") || k.startsWith("result_dub")) {
                        continue
                    }
                }
                if (SyncDataType.fromKey(k, isRebuildPrefs = true) == type) {
                    count++
                }
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
                    var raw = serializedValue.substring(2)
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
                        try {
                            raw = objectMapper.readValue(raw, String::class.java)
                        } catch (_: Exception) {}
                    }
                    editor.putString(key, raw)
                }
                serializedValue.startsWith("j:") -> {
                    // Eski sürümlerden kalan j: önekli JSON verilerini string olarak kaydet
                    editor.putString(key, serializedValue.substring(2))
                }
                else -> {
                    // Tip öneki yoksa tahmin et (geriye dönük uyumluluk)
                    when {
                        serializedValue == "true" || serializedValue == "false" -> editor.putBoolean(key, serializedValue.toBoolean())
                        serializedValue.startsWith("{") || serializedValue.startsWith("[") -> editor.putString(key, serializedValue)
                        serializedValue.toIntOrNull() != null -> editor.putInt(key, serializedValue.toInt())
                        serializedValue.toLongOrNull() != null -> editor.putLong(key, serializedValue.toLong())
                        serializedValue.toFloatOrNull() != null -> editor.putFloat(key, serializedValue.toFloat())
                        else -> editor.putString(key, serializedValue)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Değer deserialize edilemedi: key=$key, val=$serializedValue", e)
        }
    }
}
