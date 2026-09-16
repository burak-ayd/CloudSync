package com.cloudsync.core

import android.util.Log
import com.cloudsync.models.SyncDataItem

/**
 * Çakışma çözümleyici.
 * "Son yazan kazanır" (Last-Write-Wins) stratejisi kullanır.
 *
 * Aynı key için hem local hem remote'da farklı değerler varsa,
 * timestamp'i daha yeni olan tercih edilir.
 */
class ConflictResolver {

    companion object {
        private const val TAG = "CloudSync.ConflictResolver"
    }

    /**
     * Local ve remote veri listelerini karşılaştırır.
     *
     * @param localItems Cihazdan çıkarılan veriler
     * @param remoteItems Buluttan indirilen veriler
     * @return Birleştirilmiş veri listesi (çakışmalar çözülmüş)
     */
    fun resolve(
        localItems: List<SyncDataItem>,
        remoteItems: List<SyncDataItem>,
        lastSyncTime: Long = 0L
    ): ResolveResult {
        val localMap = localItems.associateBy { "${it.dataType}:${it.dataKey}" }
        val remoteMap = remoteItems.associateBy { "${it.dataType}:${it.dataKey}" }

        val toUpload = mutableListOf<SyncDataItem>()   // Local -> Cloud
        val toDownload = mutableListOf<SyncDataItem>()  // Cloud -> Local
        var conflictCount = 0

        // Local'da olup remote'da olmayanlar → yükle
        for ((compositeKey, localItem) in localMap) {
            val remoteItem = remoteMap[compositeKey]
            if (remoteItem == null) {
                toUpload.add(localItem)
            } else {
                // Değerler tamamen aynıysa işlem yapmaya gerek yok
                if (localItem.dataValue == remoteItem.dataValue) {
                    continue
                }

                // Değerler farklı:
                val remoteTime = parseTimestamp(remoteItem.updatedAt)

                if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                    // Buluttaki veri daha yeni (son senkronizasyondan sonra güncellenmiş) → indir
                    toDownload.add(remoteItem)
                    conflictCount++
                } else {
                    // Bu cihazda yerel olarak değişti veya güncellendi → yükle
                    toUpload.add(localItem)
                    conflictCount++
                }
            }
        }

        // Remote'da olup local'da olmayanlar → indir
        for ((compositeKey, remoteItem) in remoteMap) {
            if (!localMap.containsKey(compositeKey)) {
                toDownload.add(remoteItem)
            }
        }

        Log.i(TAG, "Çözümleme tamamlandı: " +
                "${toUpload.size} yüklenecek, " +
                "${toDownload.size} indirilecek, " +
                "$conflictCount çakışma çözüldü")

        return ResolveResult(
            toUpload = toUpload,
            toDownload = toDownload,
            conflictCount = conflictCount
        )
    }

    /**
     * Supabase timestamp string'ini Long'a çevirir.
     * Format: "2024-01-15T12:30:00+00:00" veya null
     */
    private fun parseTimestamp(timestamp: String?): Long {
        if (timestamp == null) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(timestamp.substringBefore('+').substringBefore('Z'))?.time ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Timestamp parse hatası: $timestamp", e)
            0L
        }
    }

    /**
     * Çözümleme sonucu.
     */
    data class ResolveResult(
        val toUpload: List<SyncDataItem>,
        val toDownload: List<SyncDataItem>,
        val conflictCount: Int
    )
}
