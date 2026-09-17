package com.cloudsync.core

import android.content.Context
import android.util.Log
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType
import com.cloudsync.models.SyncLogEntry
import com.cloudsync.providers.SupabaseProvider
import com.cloudsync.providers.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Senkronizasyon yöneticisi.
 *
 * Veri çıkarma, bulut iletişimi ve çakışma çözümleme işlemlerini koordine eder.
 * Bu sınıf plugin'in ana iş mantığını barındırır.
 */
class SyncManager(private val context: Context) {

    companion object {
        private const val TAG = "CloudSync.SyncManager"
    }

    private val dataExtractor = DataExtractor(context)
    private val conflictResolver = ConflictResolver()
    private var provider: SyncProvider = SupabaseProvider(context)

    /**
     * SyncScheduler referansı. DataExtractor.applyData çağrılırken
     * restore guard'ı aktifleştirmek için kullanılır.
     */
    var syncScheduler: SyncScheduler? = null

    /**
     * Senkronizasyon durumu callback'leri
     */
    interface SyncCallback {
        fun onSyncStarted()
        fun onSyncProgress(message: String)
        fun onSyncCompleted(result: SyncResult)
        fun onSyncError(error: String)
    }

    /**
     * Senkronizasyon sonucu
     */
    data class SyncResult(
        val success: Boolean,
        val uploadedCount: Int = 0,
        val downloadedCount: Int = 0,
        val conflictsResolved: Int = 0,
        val message: String = "",
        val updatedTypes: List<SyncDataType> = emptyList()
    )

    // ==================== Ana Senkronizasyon İşlemleri ====================

    /**
     * Tam çift yönlü senkronizasyon yapar.
     *
     * 1. Local verileri çıkar
     * 2. Remote verileri indir
     * 3. Çakışmaları çöz
     * 4. ÖNCE buluttaki verileri yerel cihaza uygula
     * 5. SONRA yeni/değişen yerel verileri buluta yükle
     */
    suspend fun syncAll(
        callback: SyncCallback? = null,
        dirtyTypes: Set<SyncDataType> = emptySet()
    ): SyncResult =
        withContext(Dispatchers.IO) {
            callback?.onSyncStarted()

            if (!provider.isConfigured()) {
                val error = "Bulut servisi yapılandırılmamış. Lütfen ayarlardan Supabase bilgilerinizi girin."
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }

            val userId = SyncConfig.getUserId(context)
            val deviceId = SyncConfig.getDeviceId(context)

            try {
                // Hangi veri tiplerini senkronize edeceğiz?
                val enabledTypes = SyncDataType.entries.filter {
                    SyncConfig.isSyncEnabled(context, it)
                }

                if (enabledTypes.isEmpty()) {
                    val msg = "Senkronize edilecek veri tipi seçilmemiş."
                    callback?.onSyncError(msg)
                    return@withContext SyncResult(false, message = msg)
                }

                callback?.onSyncProgress("Local veriler okunuyor...")

                // 1. Local verileri çıkar
                val localItems = dataExtractor.extractData(enabledTypes)
                Log.i(TAG, "Local'dan ${localItems.size} veri çıkarıldı")

                callback?.onSyncProgress("Buluttan veriler indiriliyor...")

                // 2. Remote verileri indir
                val remoteResult = provider.downloadData(
                    userId,
                    enabledTypes.map { it.name }
                )

                if (remoteResult.isFailure) {
                    val error = "Buluttan veri indirilemedi: ${remoteResult.exceptionOrNull()?.message}"
                    callback?.onSyncError(error)
                    return@withContext SyncResult(false, message = error)
                }

                val remoteItems = remoteResult.getOrDefault(emptyList())
                Log.i(TAG, "Buluttan ${remoteItems.size} veri indirildi")

                callback?.onSyncProgress("Çakışmalar çözümleniyor...")

                // 3. Çakışmaları çöz
                val lastSyncTime = SyncConfig.getLastSyncTime(context)
                val resolved = conflictResolver.resolve(localItems, remoteItems, lastSyncTime, dirtyTypes)

                // 4. ÖNCE buluttan gelen yeni verileri yerel cihaza uygula
                var downloadedCount = 0
                if (resolved.toDownload.isNotEmpty()) {
                    callback?.onSyncProgress("${resolved.toDownload.size} veri yerel cihaza uygulanıyor...")

                    dataExtractor.applyData(resolved.toDownload, syncScheduler)
                    downloadedCount = resolved.toDownload.size
                }

                // 5. SONRA bu cihazdaki yeni/değişen verileri buluta yükle
                var uploadedCount = 0
                if (resolved.toUpload.isNotEmpty()) {
                    callback?.onSyncProgress("${resolved.toUpload.size} veri buluta yükleniyor...")

                    val uploadResult = provider.uploadData(resolved.toUpload)
                    if (uploadResult.isSuccess) {
                        uploadedCount = uploadResult.getOrDefault(0)
                    } else {
                        Log.w(TAG, "Yükleme kısmen başarısız: ${uploadResult.exceptionOrNull()?.message}")
                    }
                }

                // 6. Bilinen anahtarları (knownKeys) güncelle
                val allCurrentKeys = dataExtractor.getAllCurrentKeys(enabledTypes)
                SyncConfig.saveKnownKeys(context, allCurrentKeys)

                // 7. Son senkronizasyon zamanını güncelle
                SyncConfig.setLastSyncTime(context, System.currentTimeMillis())

                // 8. Hangi tiplerin güncellendiğini belirle
                val updatedTypes = (resolved.toUpload + resolved.toDownload).mapNotNull { item ->
                    try { SyncDataType.valueOf(item.dataType) } catch (_: Exception) { null }
                }.distinct()

                val summaryText = if (updatedTypes.isNotEmpty()) {
                    updatedTypes.joinToString(", ") { it.displayName }
                } else "Veriler"

                val msg = if (downloadedCount > 0 || uploadedCount > 0) {
                    "$summaryText güncellendi (↑$uploadedCount ↓$downloadedCount)"
                } else {
                    "Tüm verileriniz güncel"
                }

                // 9. Sync log kaydet
                provider.logSync(
                    SyncLogEntry(
                        userId = userId,
                        deviceId = deviceId,
                        action = "full_sync",
                        dataType = enabledTypes.joinToString(",") { it.name },
                        itemsCount = uploadedCount + downloadedCount
                    )
                )

                val result = SyncResult(
                    success = true,
                    uploadedCount = uploadedCount,
                    downloadedCount = downloadedCount,
                    conflictsResolved = resolved.conflictCount,
                    message = msg,
                    updatedTypes = updatedTypes
                )

                callback?.onSyncCompleted(result)
                Log.i(TAG, "Senkronizasyon tamamlandı: $result")

                return@withContext result

            } catch (e: Exception) {
                val error = "Senkronizasyon hatası: ${e.message}"
                Log.e(TAG, error, e)
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }
        }

    /**
     * Sadece buluta yükleme yapar (tek yönlü).
     */
    suspend fun uploadOnly(callback: SyncCallback? = null): SyncResult =
        withContext(Dispatchers.IO) {
            callback?.onSyncStarted()

            if (!provider.isConfigured()) {
                val error = "Yapılandırma eksik"
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }

            try {
                val enabledTypes = SyncDataType.entries.filter {
                    SyncConfig.isSyncEnabled(context, it)
                }

                callback?.onSyncProgress("Veriler hazırlanıyor...")
                val localItems = dataExtractor.extractData(enabledTypes)

                callback?.onSyncProgress("${localItems.size} veri yükleniyor...")
                val uploadResult = provider.uploadData(localItems)

                if (uploadResult.isFailure) {
                    val error = "Yükleme hatası: ${uploadResult.exceptionOrNull()?.message}"
                    callback?.onSyncError(error)
                    return@withContext SyncResult(false, message = error)
                }

                val count = uploadResult.getOrDefault(0)
                SyncConfig.setLastSyncTime(context, System.currentTimeMillis())

                val allCurrentKeys = dataExtractor.getAllCurrentKeys(enabledTypes)
                SyncConfig.saveKnownKeys(context, allCurrentKeys)

                val updatedTypes = localItems.mapNotNull { item ->
                    try { SyncDataType.valueOf(item.dataType) } catch (_: Exception) { null }
                }.distinct()
                val summaryText = if (updatedTypes.isNotEmpty()) {
                    updatedTypes.joinToString(", ") { it.displayName }
                } else "Veriler"

                val userId = SyncConfig.getUserId(context)
                val deviceId = SyncConfig.getDeviceId(context)
                provider.logSync(
                    SyncLogEntry(
                        userId = userId,
                        deviceId = deviceId,
                        action = "upload",
                        itemsCount = count
                    )
                )

                val result = SyncResult(
                    success = true,
                    uploadedCount = count,
                    message = "↑ $count veri yüklendi ($summaryText)",
                    updatedTypes = updatedTypes
                )
                callback?.onSyncCompleted(result)
                return@withContext result

            } catch (e: Exception) {
                val error = "Yükleme hatası: ${e.message}"
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }
        }

    /**
     * Sadece buluttan indirme yapar (tek yönlü).
     */
    suspend fun downloadOnly(callback: SyncCallback? = null): SyncResult =
        withContext(Dispatchers.IO) {
            callback?.onSyncStarted()

            if (!provider.isConfigured()) {
                val error = "Yapılandırma eksik"
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }

            try {
                val userId = SyncConfig.getUserId(context)
                val deviceId = SyncConfig.getDeviceId(context)
                val enabledTypes = SyncDataType.entries.filter {
                    SyncConfig.isSyncEnabled(context, it)
                }

                callback?.onSyncProgress("Buluttan indiriliyor...")
                val downloadResult = provider.downloadData(
                    userId,
                    enabledTypes.map { it.name }
                )

                if (downloadResult.isFailure) {
                    val error = "İndirme hatası: ${downloadResult.exceptionOrNull()?.message}"
                    callback?.onSyncError(error)
                    return@withContext SyncResult(false, message = error)
                }

                val items = downloadResult.getOrDefault(emptyList())

                callback?.onSyncProgress("${items.size} veri uygulanıyor...")
                dataExtractor.applyData(items, syncScheduler)

                SyncConfig.setLastSyncTime(context, System.currentTimeMillis())

                val allCurrentKeys = dataExtractor.getAllCurrentKeys(enabledTypes)
                SyncConfig.saveKnownKeys(context, allCurrentKeys)

                val updatedTypes = items.mapNotNull { item ->
                    try { SyncDataType.valueOf(item.dataType) } catch (_: Exception) { null }
                }.distinct()
                val summaryText = if (updatedTypes.isNotEmpty()) {
                    updatedTypes.joinToString(", ") { it.displayName }
                } else "Veriler"

                provider.logSync(
                    SyncLogEntry(
                        userId = userId,
                        deviceId = deviceId,
                        action = "download",
                        itemsCount = items.size
                    )
                )

                val result = SyncResult(
                    success = true,
                    downloadedCount = items.size,
                    message = "↓ ${items.size} veri indirildi ($summaryText)",
                    updatedTypes = updatedTypes
                )
                callback?.onSyncCompleted(result)
                return@withContext result

            } catch (e: Exception) {
                val error = "İndirme hatası: ${e.message}"
                callback?.onSyncError(error)
                return@withContext SyncResult(false, message = error)
            }
        }

    /**
     * Buluttaki tüm kullanıcı verilerini siler.
     */
    suspend fun deleteCloudData(callback: SyncCallback? = null): SyncResult =
        withContext(Dispatchers.IO) {
            try {
                val userId = SyncConfig.getUserId(context)
                val result = provider.deleteAllData(userId)

                if (result.isSuccess) {
                    SyncResult(true, message = "Buluttaki tüm veriler silindi")
                } else {
                    SyncResult(false, message = "Silme hatası: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                SyncResult(false, message = "Silme hatası: ${e.message}")
            }
        }

    /**
     * Bağlantıyı test eder.
     */
    suspend fun testConnection(): Result<Boolean> {
        return provider.testConnection()
    }

    /**
     * Local veri istatistiklerini döndürür.
     */
    fun getLocalStats(): Map<SyncDataType, Int> {
        return SyncDataType.entries.associateWith { type ->
            dataExtractor.countKeys(type)
        }
    }
}
