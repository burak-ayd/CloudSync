package com.cloudsync.providers

import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncLogEntry

/**
 * Bulut senkronizasyon sağlayıcı arayüzü.
 *
 * Tüm bulut servisleri (Supabase, Google Drive, Firebase vb.)
 * bu interface'i implemente eder.
 * Bu sayede yeni bir bulut servisi eklemek mevcut kodu bozmadan yapılabilir.
 */
interface SyncProvider {

    /**
     * Provider adı (örn: "Supabase", "Google Drive")
     */
    val providerName: String

    /**
     * Provider'ın yapılandırılıp yapılandırılmadığını kontrol eder.
     */
    fun isConfigured(): Boolean

    /**
     * Bağlantıyı test eder.
     * @return true: bağlantı başarılı, false: başarısız
     */
    suspend fun testConnection(): Result<Boolean>

    /**
     * Belirli bir kullanıcının belirli veri tiplerini buluttan indirir.
     *
     * @param userId Kullanıcı kimliği
     * @param dataTypes Filtrelenecek veri tipleri (boş = hepsi)
     * @return İndirilen veri listesi
     */
    suspend fun downloadData(
        userId: String,
        dataTypes: List<String> = emptyList()
    ): Result<List<SyncDataItem>>

    /**
     * Verileri buluta yükler.
     * Aynı user_id + data_type + data_key kombinasyonu varsa günceller (upsert).
     *
     * @param items Yüklenecek veriler
     * @return Başarıyla yüklenen öğe sayısı
     */
    suspend fun uploadData(items: List<SyncDataItem>): Result<Int>

    /**
     * Belirli bir kullanıcının tüm verilerini buluttan siler.
     *
     * @param userId Kullanıcı kimliği
     * @return true: başarılı
     */
    suspend fun deleteAllData(userId: String): Result<Boolean>

    /**
     * Senkronizasyon log'u kaydeder.
     */
    suspend fun logSync(entry: SyncLogEntry): Result<Boolean>

    /**
     * Son senkronizasyon log'larını getirir.
     */
    suspend fun getSyncLogs(userId: String, limit: Int = 20): Result<List<SyncLogEntry>>
}
