package com.cloudsync.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * SharedPreferences'dan senkronize edilecek verileri çıkarır ve geri yazar.
 *
 * CloudStream verileri iki farklı SharedPreferences dosyasında saklar:
 * 1. Default SharedPreferences (ayarlar, tercihler)
 * 2. "rebuild_preference" (içerik verileri, favoriler, izleme geçmişi)
 */
class DataExtractor(private val context: Context) {

    companion object {
        private const val TAG = "CloudSync.DataExtractor"
        private const val REBUILD_PREFS_NAME = "rebuild_preference"
        const val TOMBSTONE_VALUE = "__DELETED__"
    }

    private val objectMapper = ObjectMapper()

    /**
     * Güvenlik nedeniyle senkronize EDİLMEYECEK key'ler.
     * Token, şifre ve cihaza özel ayarlar bu listede yer alır.
     * Referans CloudStream sync-plugin ile birebir uyumlu tam liste.
     */
    private val nonTransferableKeys = setOf(
        // Hesap token'ları ve oturumlar
        "anilist_token", "anilist_user", "anilist_unixtime", "anilist_cached_list", "anilist_accounts", "anilist_active",
        "mal_token", "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_accounts", "mal_active",
        "kitsu_token", "kitsu_user", "kitsu_cached_list",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active", "simkl_api_cache", "aniwave_simkl_sync",
        "account_token", "account_ids",
        "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active",

        // Cihaza özel ayarlar ve dosya yolları
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

        // Plugin binary ve eklenti yerel verileri
        "plugins_key_local"
    )

    private fun isNonTransferable(key: String): Boolean {
        val lower = key.lowercase()
        return nonTransferableKeys.any { lower.contains(it) }
    }

    /**
     * Belirtilen veri tiplerine ait tüm SharedPreferences verilerini çıkarır.
     * Silinen öğeleri tespit etmek için bilinen anahtarlarla (knownKeys) karşılaştırır.
     */
    fun extractData(dataTypes: List<SyncDataType>): List<SyncDataItem> {
        val items = mutableListOf<SyncDataItem>()
        val userId = SyncConfig.getUserId(context)
        val deviceId = SyncConfig.getDeviceId(context)

        // Her iki SharedPreferences kaynağından veri çıkar
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        val currentKeys = mutableSetOf<String>()

        extractFromPrefs(defaultPrefs, dataTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = false)
        extractFromPrefs(rebuildPrefs, dataTypes, userId, deviceId, items, currentKeys, isRebuildPrefs = true)

        // Arama Geçmişi özel kontrolü (boş arama listesi silinmiş sayılır)
        if (dataTypes.contains(SyncDataType.SEARCH_HISTORY)) {
            val searchItem = items.find { it.dataType == SyncDataType.SEARCH_HISTORY.name }
            val knownKeys = SyncConfig.getKnownKeys(context)
            val hadKnownSearch = knownKeys.any { it.contains("search_history", ignoreCase = true) }

            val isExplicitlyEmpty = searchItem != null && (
                    searchItem.dataValue == null ||
                    searchItem.dataValue == "s:[]" ||
                    searchItem.dataValue == "ss:[]" ||
                    searchItem.dataValue == "s:\"\"" ||
                    searchItem.dataValue == "s:{}"
            )

            if (isExplicitlyEmpty || (searchItem == null && hadKnownSearch)) {
                // Yerelde daha önce arama geçmişi vardı ve şimdi kullanıcı tarafından temizlendi!
                items.removeAll { it.dataType == SyncDataType.SEARCH_HISTORY.name }
                items.add(
                    SyncDataItem(
                        userId = userId,
                        deviceId = deviceId,
                        dataType = SyncDataType.SEARCH_HISTORY.name,
                        dataKey = "search_history",
                        dataValue = TOMBSTONE_VALUE,
                        updatedAt = null
                    )
                )
                Log.i(TAG, "Arama geçmişi kullanıcı tarafından temizlendi -> Tombstone eklendi")
            }
        }

        // Silinen verileri tespit et (Tombstone)
        val knownKeys = SyncConfig.getKnownKeys(context)
        if (knownKeys.isNotEmpty()) {
            for (knownKey in knownKeys) {
                if (!currentKeys.contains(knownKey)) {
                    val isRebuild = knownKey.contains("/") || Regex("^[0-9]+/").containsMatchIn(knownKey)
                    val dataType = SyncDataType.fromKey(knownKey, isRebuild)
                    if (dataTypes.contains(dataType)) {
                        // Eğer zaten eklenmemişse ekle
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

        Log.i(TAG, "Toplam ${items.size} veri çıkarıldı (${currentKeys.size} aktif). Tipler: ${dataTypes.map { it.name }}")
        return items
    }

    /**
     * Belirli bir SharedPreferences'dan verileri çıkarır.
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
            // CloudSync'in kendi key'lerini ve hassas verileri atla
            if (SyncConfig.isCloudSyncKey(key)) continue
            if (isNonTransferable(key)) continue

            // Bu key hangi veri tipine ait?
            val dataType = SyncDataType.fromKey(key, isRebuildPrefs)

            // Kullanıcı bu veri tipini senkronize etmek istiyor mu?
            if (!dataTypes.contains(dataType)) continue

            currentKeys.add(key)

            // Değeri JSON string'e çevir
            val serializedValue = serializeValue(value) ?: continue

            items.add(
                SyncDataItem(
                    userId = userId,
                    deviceId = deviceId,
                    dataType = dataType.name,
                    dataKey = key,
                    dataValue = serializedValue,
                    updatedAt = null // Sunucu tarafında atanacak
                )
            )
        }
    }

    /**
     * Buluttan indirilen verileri local SharedPreferences'a yazar veya siler (tombstone).
     *
     * @param scheduler Eğer sağlanırsa, yazma sırasında restore guard aktifleştirilir.
     *                  Bu, SharedPreferences listener'ın bu yazma işlemini
     *                  "yeni kullanıcı değişikliği" olarak algılamasını engeller.
     */
    fun applyData(items: List<SyncDataItem>, scheduler: SyncScheduler? = null) {
        // Restore guard'ı aktifleştir (listener feedback loop'u önleme)
        scheduler?.beginRestore()
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        val defaultEditor = defaultPrefs.edit()
        val rebuildEditor = rebuildPrefs.edit()

        // Hedef cihazın mevcut aktif profilini öğren
        val currentAccount = try {
            com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
        } catch (_: Exception) {
            "0"
        }

        var appliedCount = 0

        for (item in items) {
            // Güvenlik kontrolü
            if (isNonTransferable(item.dataKey)) continue
            if (SyncConfig.isCloudSyncKey(item.dataKey)) continue

            val isDeleted = item.dataValue == null || item.dataValue == TOMBSTONE_VALUE
            val value = item.dataValue

            // ==================== 1. ARAMA GEÇMİŞİ (SEARCH_HISTORY) ====================
            if (item.dataType == SyncDataType.SEARCH_HISTORY.name) {
                // Tüm geçmiş anahtarlarını hem rebuild hem default prefs'ten temizle
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

                if (isDeleted || value == "s:[]" || value == "ss:[]" || value == "s:\"\"") {
                    Log.i(TAG, "Arama geçmişi yerel hafızadan tamamen temizlendi (Tombstone)")
                } else if (value != null) {
                    // Temizlenen hafızaya yeni güncel arama geçmişini yaz
                    deserializeAndApply(rebuildEditor, "$currentAccount/search_history", value)
                    deserializeAndApply(rebuildEditor, "search_history", value)
                    deserializeAndApply(defaultEditor, "search_history", value)
                    Log.i(TAG, "Yeni arama geçmişi uygulandı: $value")
                }
                appliedCount++
                continue
            }

            // ==================== 2. UYGULAMA AYARLARI (SETTINGS) ====================
            if (item.dataType == SyncDataType.SETTINGS.name) {
                if (isDeleted) {
                    defaultEditor.remove(item.dataKey)
                    rebuildEditor.remove(item.dataKey)
                } else if (value != null) {
                    // Eğer anahtar rebuildPrefs'te kayıtlıysa veya slash içeriyorsa rebuildEditor'a yaz
                    if (rebuildPrefs.contains(item.dataKey) || item.dataKey.contains("/")) {
                        deserializeAndApply(rebuildEditor, item.dataKey, value)
                    } else {
                        // Standart uygulama ayarları defaultPrefs'e yazılır
                        deserializeAndApply(defaultEditor, item.dataKey, value)
                    }
                }
                appliedCount++
                continue
            }

            // ==================== 3. DİĞER VERİLER (BOOKMARKS, PROGRESS, REPOS) ====================
            val isRebuildItem = item.dataKey.contains("/") ||
                    Regex("^[0-9]+/").containsMatchIn(item.dataKey)

            val editor = if (isRebuildItem) rebuildEditor else defaultEditor
            val accountMatch = Regex("^([0-9]+)/(.*)").find(item.dataKey)

            if (isDeleted) {
                // SİLME İŞLEMİ (Tombstone):
                editor.remove(item.dataKey)

                if (accountMatch != null) {
                    val relativePath = accountMatch.groupValues[2]
                    rebuildEditor.remove("$currentAccount/$relativePath")

                    // İlgili ikili anahtarları da temizle
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

            if (value == null) continue

            // Normal veri yazma
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

        // Restore guard'ı deaktif et
        scheduler?.endRestore()

        Log.i(TAG, "$appliedCount veri uygulandı/silindi (Hedef aktif hesap: $currentAccount)")
    }

    /**
     * Belirtilen tiplere ait mevcut tüm yerel anahtarları döndürür.
     */
    fun getAllCurrentKeys(dataTypes: List<SyncDataType>): Set<String> {
        val keys = mutableSetOf<String>()
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        defaultPrefs.all?.keys?.forEach { key ->
            if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                val type = SyncDataType.fromKey(key, isRebuildPrefs = false)
                if (dataTypes.contains(type)) keys.add(key)
            }
        }
        rebuildPrefs.all?.keys?.forEach { key ->
            if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                val type = SyncDataType.fromKey(key, isRebuildPrefs = true)
                if (dataTypes.contains(type)) keys.add(key)
            }
        }
        return keys
    }

    /**
     * SharedPreferences değerini JSON-uyumlu string'e serileştirir.
     * Tip bilgisini korumak için önek sistemi kullanır.
     */
    private fun serializeValue(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> "s:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is Float -> "f:$value"
            is Boolean -> "b:$value"
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                val set = value as? Set<String> ?: return null
                "ss:" + objectMapper.writeValueAsString(set.toList())
            }
            else -> {
                try {
                    "j:" + objectMapper.writeValueAsString(value)
                } catch (e: Exception) {
                    Log.w(TAG, "Serileştirilemeyen değer: $value", e)
                    null
                }
            }
        }
    }

    /**
     * Serileştirilmiş değeri geri yazar, tip bilgisini korur.
     */
    private fun deserializeAndApply(editor: SharedPreferences.Editor, key: String, serialized: String) {
        try {
            val colonIndex = serialized.indexOf(':')
            if (colonIndex < 1) {
                // Eski format veya düz string
                editor.putString(key, serialized)
                return
            }

            val typePrefix = serialized.substring(0, colonIndex)
            val rawValue = serialized.substring(colonIndex + 1)

            when (typePrefix) {
                "s" -> editor.putString(key, rawValue)
                "i" -> editor.putInt(key, rawValue.toInt())
                "l" -> editor.putLong(key, rawValue.toLong())
                "f" -> editor.putFloat(key, rawValue.toFloat())
                "b" -> editor.putBoolean(key, rawValue.toBoolean())
                "ss" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = objectMapper.readValue(rawValue, List::class.java) as? List<String>
                    editor.putStringSet(key, list?.toSet() ?: emptySet())
                }
                "j" -> {
                    // JSON nesneleri string olarak saklanır
                    editor.putString(key, rawValue)
                }
                else -> editor.putString(key, serialized)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Deserialize hatası key=$key: ${e.message}")
            // Hata durumunda düz string olarak yaz
            editor.putString(key, serialized)
        }
    }

    /**
     * Belirli bir veri tipine ait toplam key sayısını döndürür (istatistik için).
     */
    fun countKeys(dataType: SyncDataType): Int {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        var count = 0
        defaultPrefs.all?.forEach { (key, _) ->
            if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                if (SyncDataType.fromKey(key, isRebuildPrefs = false) == dataType) count++
            }
        }
        rebuildPrefs.all?.forEach { (key, _) ->
            if (!isNonTransferable(key) && !SyncConfig.isCloudSyncKey(key)) {
                if (SyncDataType.fromKey(key, isRebuildPrefs = true) == dataType) count++
            }
        }
        return count
    }
}
