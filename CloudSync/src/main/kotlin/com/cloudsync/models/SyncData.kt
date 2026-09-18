package com.cloudsync.models

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Senkronize edilen tek bir veri parçası.
 * SharedPreferences'dan çıkarılan her key-value çifti bu modelle temsil edilir.
 */
data class SyncDataItem(
    @get:JsonProperty("id") @param:JsonProperty("id") val id: String? = null,
    @get:JsonProperty("user_id") @param:JsonProperty("user_id") val userId: String,
    @get:JsonProperty("device_id") @param:JsonProperty("device_id") val deviceId: String,
    @get:JsonProperty("data_type") @param:JsonProperty("data_type") val dataType: String,
    @get:JsonProperty("data_key") @param:JsonProperty("data_key") val dataKey: String,
    @get:JsonProperty("data_value") @param:JsonProperty("data_value") val dataValue: String?,
    @get:JsonProperty("updated_at") @param:JsonProperty("updated_at") val updatedAt: String? = null
)

/**
 * Tüm senkronizasyon verisini kapsayan paket.
 * Bir cihazdan yükleme/indirme işlemi bu model üzerinden yapılır.
 */
data class SyncPackage(
    @get:JsonProperty("device_id") @param:JsonProperty("device_id") val deviceId: String,
    @get:JsonProperty("user_id") @param:JsonProperty("user_id") val userId: String,
    @get:JsonProperty("timestamp") @param:JsonProperty("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @get:JsonProperty("items") @param:JsonProperty("items") val items: List<SyncDataItem> = emptyList()
)

/**
 * Senkronizasyon log kaydı.
 */
data class SyncLogEntry(
    @get:JsonProperty("id") @param:JsonProperty("id") val id: String? = null,
    @get:JsonProperty("user_id") @param:JsonProperty("user_id") val userId: String,
    @get:JsonProperty("device_id") @param:JsonProperty("device_id") val deviceId: String,
    @get:JsonProperty("action") @param:JsonProperty("action") val action: String,
    @get:JsonProperty("data_type") @param:JsonProperty("data_type") val dataType: String? = null,
    @get:JsonProperty("items_count") @param:JsonProperty("items_count") val itemsCount: Int = 0,
    @get:JsonProperty("synced_at") @param:JsonProperty("synced_at") val syncedAt: String? = null
)

/**
 * SharedPreferences demeti.
 * Hem "rebuild_preference" (içerik/DataStore) hem de "default_preference" (ayarlar)
 * anahtarlarını kaynak dosya ayrımı bozulmadan tek bir JSON gövdesinde saklar.
 */
data class PrefsBundle(
    @get:JsonProperty("rebuild") @param:JsonProperty("rebuild") val rebuild: Map<String, String> = emptyMap(),
    @get:JsonProperty("default") @param:JsonProperty("default") val default: Map<String, String> = emptyMap()
)

/**
 * Supabase API yanıt modeli
 */
data class SupabaseResponse(
    @JsonProperty("error") val error: SupabaseError? = null
)

data class SupabaseError(
    @JsonProperty("message") val message: String? = null,
    @JsonProperty("code") val code: String? = null
)

/**
 * Desteklenen veri tipleri.
 * Her tip SharedPreferences'daki belirli key pattern'lerini kapsar.
 */
enum class SyncDataType(val displayName: String, val keyPatterns: List<String>) {
    BOOKMARKS(
        "Favoriler & Listeler",
        listOf(
            "result_favorites_state_data",
            "result_watch_state_data",
            "result_subscribed_state_data",
            "result_watch_state",
            "bookmark_",
            "favorites_"
        )
    ),
    WATCH_PROGRESS(
        "Kaldığın Yerden Devam",
        listOf(
            "result_resume_watching_2",
            "result_resume_watching",
            "video_pos_dur",
            "video_watch_state",
            "download_header_cache",
            "result_season",
            "result_dub",
            "result_episode",
            "video_pos_",
            "resume_",
            "episode_"
        )
    ),
    SEARCH_HISTORY(
        "Arama Geçmişi",
        listOf(
            "search_history"
        )
    ),
    REPOS(
        "Eklentiler & Depolar",
        listOf(
            "plugins_key",
            "plugins_repositories",
            "repositories",
            "user_custom_sites",
            "repos_key"
        )
    ),
    SETTINGS(
        "Uygulama Ayarları",
        listOf(
            "app_layout", "theme", "color_primary", "sub", "player", "video",
            "dns", "lang", "quality", "resize", "speed", "buffer", "gesture",
            "render", "fit", "aspect", "volume", "brightness", "skip",
            "home", "poster", "show_", "auto_"
        )
    );

    companion object {
        private val ACCOUNT_PREFIX_REGEX = Regex("^[0-9]+/")

        /**
         * Verilen SharedPreferences key'inin hangi sync tipine ait olduğunu bulur.
         * Referans eklenti standardı: Bookmarks, Resume, Search, Repos dışındaki tüm
         * transfer edilebilir ayarlar SETTINGS kategorisine dahil edilir.
         */
        fun fromKey(key: String, isRebuildPrefs: Boolean = false): SyncDataType {
            val cleanKey = key.replaceFirst(ACCOUNT_PREFIX_REGEX, "")
            val cleanLower = cleanKey.lowercase()

            // 1. Favoriler & Listeler
            if (BOOKMARKS.keyPatterns.any { cleanLower.contains(it) }) {
                return BOOKMARKS
            }

            // 2. Kaldığın Yerden Devam & İzleme Geçmişi
            if (WATCH_PROGRESS.keyPatterns.any { cleanLower.contains(it) }) {
                return WATCH_PROGRESS
            }

            // 3. Arama Geçmişi
            if (SEARCH_HISTORY.keyPatterns.any { cleanLower.contains(it) }) {
                return SEARCH_HISTORY
            }

            // 4. Depolar & Eklentiler
            if (REPOS.keyPatterns.any { cleanLower.contains(it) }) {
                return REPOS
            }

            // 5. Geriye kalan her şey (tema, player, altyazı, görünüm vb.) AYARLARDIR
            return SETTINGS
        }
    }
}
