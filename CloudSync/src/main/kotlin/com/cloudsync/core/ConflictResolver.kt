package com.cloudsync.core

import android.util.Log
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType

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
        // Composite key oluştururken hesap öneklerini temizle (cihazlar arası hesap id uyumsuzluğunu gidermek için)
        fun makeKey(item: SyncDataItem): String {
            val normKey = if (item.dataType == SyncDataType.SEARCH_HISTORY.name) {
                "search_history"
            } else {
                item.dataKey
            }
            return "${item.dataType}:$normKey"
        }

        val localMap = localItems.associateBy { makeKey(it) }
        val remoteMap = remoteItems.associateBy { makeKey(it) }

        val toUpload = mutableListOf<SyncDataItem>()   // Local -> Cloud
        val toDownload = mutableListOf<SyncDataItem>()  // Cloud -> Local
        var conflictCount = 0

        // Local'da olup remote'da olmayanlar → yükle
        for ((compositeKey, localItem) in localMap) {
            val remoteItem = remoteMap[compositeKey]
            if (remoteItem == null) {
                // Local'da silinmiş (tombstone) ve bulutta da zaten yoksa yüklemeye gerek yok
                if (localItem.dataValue != DataExtractor.TOMBSTONE_VALUE) {
                    toUpload.add(localItem)
                }
            } else {
                // Değerler tamamen aynıysa işlem yapmaya gerek yok
                if (localItem.dataValue == remoteItem.dataValue) {
                    continue
                }

                // Değerler farklı:
                val remoteTime = parseTimestamp(remoteItem.updatedAt)

                // SEARCH_HISTORY özel kuralı: Bulutta bir geçmiş varsa veya silinmişse (Tombstone),
                // yereldeki eski geçmiş buluttakinin üstüne yüklenmez; buluttaki indirilir!
                if (localItem.dataType == SyncDataType.SEARCH_HISTORY.name) {
                    if (remoteItem.dataValue == DataExtractor.TOMBSTONE_VALUE || remoteTime >= lastSyncTime) {
                        toDownload.add(remoteItem)
                    } else {
                        toUpload.add(localItem)
                    }
                    conflictCount++
                    continue
                }

                if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                    // Buluttaki veri daha yeni (son senkronizasyondan sonra güncellenmiş veya silinmiş) → indir
                    toDownload.add(remoteItem)
                    conflictCount++
                } else {
                    // Bu cihazda yerel olarak değişti veya silindi → yükle
                    toUpload.add(localItem)
                    conflictCount++
                }
            }
        }

        // Remote'da olup local'da olmayanlar → indir
        for ((compositeKey, remoteItem) in remoteMap) {
            if (!localMap.containsKey(compositeKey)) {
                // Bulutta zaten silinmiş (tombstone) ve yerelde de yoksa indirmeye gerek yok
                if (remoteItem.dataValue != DataExtractor.TOMBSTONE_VALUE && remoteItem.dataValue != null) {
                    toDownload.add(remoteItem)
                }
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
