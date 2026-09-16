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
    }

    private val objectMapper = ObjectMapper()

    /**
     * Güvenlik nedeniyle senkronize EDİLMEYECEK key'ler.
     * Token, şifre ve cihaza özel ayarlar bu listede yer alır.
     */
    private val nonTransferableKeys = setOf(
        // Hesap token'ları
        "anilist_token", "anilist_user",
        "mal_token", "mal_user",
        "kitsu_token", "kitsu_user",
        "simkl_token", "simkl_user",
        "account_token", "account_ids",

        // Cihaza özel ayarlar
        "biometric_key",
        "nginx_user",
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",

        // Cache verileri (geçici, senkronize etmeye gerek yok)
        "anilist_cached_list",
        "mal_cached_list",
        "kitsu_cached_list",

        // Plugin binary verileri
        "PLUGINS_KEY",
        "PLUGINS_KEY_LOCAL"
    )

    /**
     * Belirtilen veri tiplerine ait tüm SharedPreferences verilerini çıkarır.
     */
    fun extractData(dataTypes: List<SyncDataType>): List<SyncDataItem> {
        val items = mutableListOf<SyncDataItem>()
        val userId = SyncConfig.getUserId(context)
        val deviceId = SyncConfig.getDeviceId(context)

        // Her iki SharedPreferences kaynağından veri çıkar
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        extractFromPrefs(defaultPrefs, dataTypes, userId, deviceId, items)
        extractFromPrefs(rebuildPrefs, dataTypes, userId, deviceId, items)

        Log.i(TAG, "Toplam ${items.size} veri çıkarıldı. Tipler: ${dataTypes.map { it.name }}")
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
        items: MutableList<SyncDataItem>
    ) {
        val allEntries = prefs.all ?: return

        for ((key, value) in allEntries) {
            // CloudSync'in kendi key'lerini ve hassas verileri atla
            if (SyncConfig.isCloudSyncKey(key)) continue
            if (nonTransferableKeys.contains(key)) continue

            // Bu key hangi veri tipine ait?
            val dataType = SyncDataType.fromKey(key) ?: continue

            // Kullanıcı bu veri tipini senkronize etmek istiyor mu?
            if (!dataTypes.contains(dataType)) continue

            // Değeri JSON string'e çevir
            val serializedValue = serializeValue(value)

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
     * Buluttan indirilen verileri local SharedPreferences'a yazar.
     */
    fun applyData(items: List<SyncDataItem>) {
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rebuildPrefs = context.getSharedPreferences(REBUILD_PREFS_NAME, Context.MODE_PRIVATE)

        val defaultEditor = defaultPrefs.edit()
        val rebuildEditor = rebuildPrefs.edit()

        var appliedCount = 0

        for (item in items) {
            // Güvenlik kontrolü
            if (nonTransferableKeys.contains(item.dataKey)) continue
            if (SyncConfig.isCloudSyncKey(item.dataKey)) continue

            val value = item.dataValue ?: continue

            // Hangi SharedPreferences'a yazılacağını belirle
            // Settings tipindeki veriler default prefs'e, diğerleri rebuild'e
            val editor = if (item.dataType == SyncDataType.SETTINGS.name) {
                defaultEditor
            } else {
                rebuildEditor
            }

            // Değeri geri yazarken tipini korumaya çalış
            deserializeAndApply(editor, item.dataKey, value)
            appliedCount++
        }

        defaultEditor.apply()
        rebuildEditor.apply()

        Log.i(TAG, "$appliedCount veri uygulandı")
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
            if (SyncDataType.fromKey(key) == dataType) count++
        }
        rebuildPrefs.all?.forEach { (key, _) ->
            if (SyncDataType.fromKey(key) == dataType) count++
        }
        return count
    }
}
