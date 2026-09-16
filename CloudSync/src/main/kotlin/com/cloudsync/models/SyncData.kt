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
        "Favoriler",
        listOf("bookmark_", "favorites_", "result_", "result_season")
    ),
    WATCH_PROGRESS(
        "Kaldığın Yerden Devam",
        listOf("video_pos_", "resume_", "episode_")
    ),
    SEARCH_HISTORY(
        "Arama Geçmişi",
        listOf("search_history")
    ),
    REPOS(
        "Eklentiler & Depolar",
        listOf("REPOS_KEY", "repository_")
    ),
    SETTINGS(
        "Uygulama Ayarları",
        listOf(
            "app_layout_key", "prefer_media_type_key", "color_primary",
            "theme_key", "quality_pref", "resize_pref", "dns_pref",
            "subtitle_", "playback_speed", "player_", "provider_",
            "lang_pref", "auto_", "show_fillers", "prerelease_updates",
            "poster_ui_key", "ui_settings"
        )
    );

    companion object {
        /**
         * Verilen SharedPreferences key'inin hangi sync tipine ait olduğunu bulur.
         */
        fun fromKey(key: String): SyncDataType? {
            return entries.firstOrNull { type ->
                type.keyPatterns.any { pattern -> key.startsWith(pattern) }
            }
        }
    }
}
