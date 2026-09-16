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
            "bookmark_",
            "favorites_"
        )
    ),
    WATCH_PROGRESS(
        "Kaldığın Yerden Devam",
        listOf(
            "video_pos_dur",
            "result_resume_watching",
            "video_watch_state",
            "result_watch_state",
            "result_season",
            "result_episode",
            "result_dub",
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
            "repos_key",
            "user_custom_sites",
            "repository_"
        )
    ),
    SETTINGS(
        "Uygulama Ayarları",
        listOf(
            "app_layout_key", "prefer_media_type_key", "color_primary",
            "theme_key", "quality_pref", "resize_pref", "dns_pref",
            "subtitle_", "playback_speed", "player_", "provider_",
            "lang_pref", "auto_", "show_fillers", "prerelease_updates",
            "poster_ui_key", "ui_settings", "home_api_used",
            "result_resume_watching_migrated"
        )
    );

    companion object {
        private val ACCOUNT_PREFIX_REGEX = Regex("^[0-9]+/")

        /**
         * Verilen SharedPreferences key'inin hangi sync tipine ait olduğunu bulur.
         * CloudStream verileri "0/result_favorites_state_data/123" gibi hesap önekleriyle saklar.
         *
         * @param key SharedPreferences anahtarı
         * @param isRebuildPrefs Anahtarın rebuild_preference dosyasından gelip gelmediği
         */
        fun fromKey(key: String, isRebuildPrefs: Boolean = false): SyncDataType? {
            val cleanKey = key.replaceFirst(ACCOUNT_PREFIX_REGEX, "")
            val cleanLower = cleanKey.lowercase()

            // 1. Favoriler, Listeler (Planlananlar, İzlenenler vb.)
            if (BOOKMARKS.keyPatterns.any { cleanLower.startsWith(it) }) {
                return BOOKMARKS
            }

            // 2. Kaldığın Yerden Devam & İzleme Geçmişi
            if (WATCH_PROGRESS.keyPatterns.any { cleanLower.startsWith(it) }) {
                return WATCH_PROGRESS
            }

            // 3. Arama Geçmişi
            if (SEARCH_HISTORY.keyPatterns.any { cleanLower.startsWith(it) }) {
                return SEARCH_HISTORY
            }

            // 4. Depolar
            if (REPOS.keyPatterns.any { cleanLower.startsWith(it) }) {
                return REPOS
            }

            // 5. Ayarlar
            if (SETTINGS.keyPatterns.any { cleanLower.startsWith(it) }) {
                return SETTINGS
            }

            // 6. Default SharedPreferences'da bulunan ve yukarıdakilere girmeyen ayarlar
            if (!isRebuildPrefs && !cleanKey.contains("/")) {
                return SETTINGS
            }

            return null
        }
    }
}
