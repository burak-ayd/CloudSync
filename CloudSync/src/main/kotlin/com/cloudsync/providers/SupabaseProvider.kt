package com.cloudsync.providers

import android.content.Context
import android.util.Log
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncLogEntry
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Supabase REST API üzerinden senkronizasyon sağlayıcısı.
 *
 * Supabase SDK kullanmak yerine doğrudan REST API çağrıları yapar.
 * Bu sayede ekstra bağımlılık eklemeye gerek kalmaz (OkHttp zaten CloudStream'de mevcut).
 *
 * Kullanıcı kendi Supabase projesini oluşturur ve API bilgilerini girer.
 */
class SupabaseProvider(private val context: Context) : SyncProvider {

    companion object {
        private const val TAG = "CloudSync.Supabase"
        private const val TABLE_SYNC_DATA = "sync_data"
        private const val TABLE_SYNC_LOG = "sync_log"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    override val providerName = "Supabase"

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ==================== Yardımcı Fonksiyonlar ====================

    private fun getBaseUrl(): String = SyncConfig.getSupabaseUrl(context)
    private fun getApiKey(): String = SyncConfig.getSupabaseKey(context)

    /**
     * Supabase REST API URL'si oluşturur.
     * PostgREST endpoint: {base_url}/rest/v1/{table}
     */
    private fun buildUrl(table: String, queryParams: String = ""): String {
        val base = getBaseUrl().trimEnd('/')
        val url = "$base/rest/v1/$table"
        return if (queryParams.isNotEmpty()) "$url?$queryParams" else url
    }

    /**
     * Ortak HTTP header'larını ekler.
     */
    private fun Request.Builder.addSupabaseHeaders(): Request.Builder {
        val apiKey = getApiKey()
        header("apikey", apiKey)
        header("Authorization", "Bearer $apiKey")
        header("Content-Type", "application/json")
        header("Prefer", "return=minimal")
        return this
    }

    /**
     * PostgREST hata JSON'ını okunaklı metne çevirir.
     */
    private fun extractErrorMessage(code: Int, body: String?): String {
        if (body.isNullOrBlank()) return "HTTP $code"
        return try {
            val root = objectMapper.readTree(body)
            val msg = root.path("message").asText(null)
            val hint = root.path("hint").asText(null)
            val details = root.path("details").asText(null)
            val pgrstCode = root.path("code").asText(null)
            val sb = StringBuilder()
            if (!pgrstCode.isNullOrEmpty()) sb.append("[$pgrstCode] ")
            if (!msg.isNullOrEmpty()) sb.append(msg)
            if (!details.isNullOrEmpty()) sb.append(" | $details")
            if (!hint.isNullOrEmpty()) sb.append(" (İpucu: $hint)")
            if (sb.isEmpty()) "HTTP $code: $body" else sb.toString()
        } catch (_: Exception) {
            "HTTP $code: $body"
        }
    }

    // ==================== Interface Implementasyonları ====================

    override fun isConfigured(): Boolean = SyncConfig.isConfigured(context)

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                return@withContext Result.failure(Exception("Supabase ayarları yapılandırılmamış"))
            }

            val url = buildUrl(TABLE_SYNC_DATA, "select=id&limit=1")
            val request = Request.Builder()
                .url(url)
                .get()
                .addSupabaseHeaders()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "Bağlantı testi başarılı")
                Result.success(true)
            } else {
                val errorMsg = extractErrorMessage(response.code, response.body?.string())
                Log.e(TAG, "Bağlantı testi başarısız: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bağlantı testi hatası", e)
            Result.failure(e)
        }
    }

    override suspend fun downloadData(
        userId: String,
        dataTypes: List<String>
    ): Result<List<SyncDataItem>> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                return@withContext Result.failure(Exception("Supabase yapılandırılmamış"))
            }

            val allItems = mutableListOf<SyncDataItem>()
            val pageSize = 1000
            var offset = 0
            var hasMore = true

            // PostgREST sorgusu taban parametreleri
            val baseQueryParts = mutableListOf("user_id=eq.$userId")

            if (dataTypes.isNotEmpty()) {
                val typesFilter = dataTypes.joinToString(",")
                baseQueryParts.add("data_type=in.($typesFilter)")
            }

            baseQueryParts.add("select=*")
            // Sayfalama sırasında aynı timestamp'e sahip satırların deterministik gelmesi için id eklenir
            baseQueryParts.add("order=updated_at.desc,id.asc")

            while (hasMore) {
                val queryParts = baseQueryParts.toMutableList()
                queryParts.add("limit=$pageSize")
                queryParts.add("offset=$offset")

                val url = buildUrl(TABLE_SYNC_DATA, queryParts.joinToString("&"))

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addSupabaseHeaders()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorMsg = extractErrorMessage(response.code, response.body?.string())
                    Log.e(TAG, "Veri indirme hatası (offset=$offset): $errorMsg")
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val body = response.body?.string() ?: "[]"
                val pageItems: List<SyncDataItem> = objectMapper.readValue(
                    body,
                    object : TypeReference<List<SyncDataItem>>() {}
                )

                allItems.addAll(pageItems)
                Log.d(TAG, "Veri sayfası indirildi: offset=$offset, gelen=${pageItems.size}")

                if (pageItems.size < pageSize) {
                    hasMore = false
                } else {
                    offset += pageSize
                }
            }

            // Supabase sorgusu order=updated_at.desc olduğundan en yeni öğeler en baştadır.
            // distinctBy ile duplicate satırlar elenir ve her zaman en yeni öğe korunur.
            val distinctItems = allItems.distinctBy { "${it.dataType}:${it.dataKey}" }

            Log.i(TAG, "Tüm sayfalar tamamlandı: ${distinctItems.size} veri indirildi (toplam ham: ${allItems.size})")
            Result.success(distinctItems)
        } catch (e: Exception) {
            Log.e(TAG, "Veri indirme hatası", e)
            Result.failure(e)
        }
    }

    override suspend fun uploadData(items: List<SyncDataItem>): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                if (!isConfigured()) {
                    return@withContext Result.failure(Exception("Supabase yapılandırılmamış"))
                }

                if (items.isEmpty()) {
                    return@withContext Result.success(0)
                }

                // Supabase upsert: user_id + data_type + data_key üzerinde çakışma çözümleme
                val url = buildUrl(
                    TABLE_SYNC_DATA,
                    "on_conflict=user_id,data_type,data_key"
                )

                // Büyük veri setlerini parçalara böl (Supabase limit)
                val batchSize = 100
                var totalUploaded = 0

                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                val nowIso = sdf.format(java.util.Date())

                for (batch in items.chunked(batchSize)) {
                    val batchWithTimestamp = batch.map { item ->
                        if (item.updatedAt.isNullOrBlank()) item.copy(updatedAt = nowIso) else item
                    }
                    val jsonBody = objectMapper.writeValueAsString(batchWithTimestamp)

                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                        .addSupabaseHeaders()
                        .header("Prefer", "resolution=merge-duplicates,return=minimal")
                        .build()

                    val response = httpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorMsg = extractErrorMessage(response.code, response.body?.string())
                        Log.e(TAG, "Batch yükleme hatası: $errorMsg")
                        return@withContext Result.failure(Exception(errorMsg))
                    }

                    totalUploaded += batch.size
                    Log.d(TAG, "Batch yüklendi: ${batch.size} öğe")
                }

                Log.i(TAG, "Toplam $totalUploaded veri yüklendi")
                Result.success(totalUploaded)
            } catch (e: Exception) {
                Log.e(TAG, "Veri yükleme hatası", e)
                Result.failure(e)
            }
        }

    override suspend fun deleteAllData(userId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(TABLE_SYNC_DATA, "user_id=eq.$userId")

                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .addSupabaseHeaders()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    Log.i(TAG, "Tüm veriler silindi: user=$userId")
                    Result.success(true)
                } else {
                    val errorBody = response.body?.string() ?: "Bilinmeyen hata"
                    Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Veri silme hatası", e)
                Result.failure(e)
            }
        }

    override suspend fun logSync(entry: SyncLogEntry): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = buildUrl(TABLE_SYNC_LOG)
                val jsonBody = objectMapper.writeValueAsString(entry)

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                    .addSupabaseHeaders()
                    .build()

                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // Log kaydı başarısız olsa bile senkronizasyonu durdurma
                    Log.w(TAG, "Sync log kaydedilemedi: ${response.code}")
                    Result.success(false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sync log hatası", e)
                Result.success(false)
            }
        }

    override suspend fun getSyncLogs(
        userId: String,
        limit: Int
    ): Result<List<SyncLogEntry>> = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(
                TABLE_SYNC_LOG,
                "user_id=eq.$userId&select=*&order=synced_at.desc&limit=$limit"
            )

            val request = Request.Builder()
                .url(url)
                .get()
                .addSupabaseHeaders()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("HTTP ${response.code}")
                )
            }

            val body = response.body?.string() ?: "[]"
            val logs: List<SyncLogEntry> = objectMapper.readValue(
                body,
                object : TypeReference<List<SyncLogEntry>>() {}
            )

            Result.success(logs)
        } catch (e: Exception) {
            Log.e(TAG, "Sync log okuma hatası", e)
            Result.failure(e)
        }
    }
}
