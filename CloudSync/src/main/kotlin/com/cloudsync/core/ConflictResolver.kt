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
     * @param lastSyncTime Son başarılı senkronizasyon zamanı
     * @param dirtyTypes Bu cihazda yerel olarak değişen veri tipleri (listener tarafından yakalanan)
     * @return Birleştirilmiş veri listesi (çakışmalar çözülmüş)
     */
    fun resolve(
        localItems: List<SyncDataItem>,
        remoteItems: List<SyncDataItem>,
        lastSyncTime: Long = 0L,
        dirtyTypes: Set<SyncDataType> = emptySet()
    ): ResolveResult {
        // Composite key oluştururken hesap öneklerini temizle (cihazlar arası hesap id uyumsuzluğunu gidermek için)
        fun makeKey(item: SyncDataItem): String {
            return "${item.dataType}:${item.dataKey}"
        }

        val localMap = localItems.associateBy { makeKey(it) }
        val remoteMap = remoteItems.associateBy { makeKey(it) }

        val toUpload = mutableListOf<SyncDataItem>()   // Local -> Cloud
        val toDownload = mutableListOf<SyncDataItem>()  // Cloud -> Local
        var conflictCount = 0

        // Local'da olup remote'da olmayanlar
        for ((compositeKey, localItem) in localMap) {
            val remoteItem = remoteMap[compositeKey]
            val dataType = try { SyncDataType.valueOf(localItem.dataType) } catch (_: Exception) { null }
            val isLocallyDirty = dataType != null && dirtyTypes.contains(dataType)

            if (remoteItem == null) {
                // Local'da silinmiş veya boş bundle ise ve bulutta da yoksa yüklemeye gerek yok
                if (localItem.dataValue != DataExtractor.TOMBSTONE_VALUE && localItem.dataValue != DataExtractor.EMPTY_BUNDLE_VALUE) {
                    if (dataType == SyncDataType.SETTINGS || dataType == SyncDataType.SEARCH_HISTORY) {
                        if (isLocallyDirty || lastSyncTime == 0L) {
                            toUpload.add(localItem)
                        }
                    } else {
                        toUpload.add(localItem)
                    }
                }
            } else {
                // Değerler tamamen aynıysa işlem yapmaya gerek yok
                if (localItem.dataValue == remoteItem.dataValue) {
                    continue
                }

                // 1. SETTINGS (Uygulama Ayarları Demeti):
                if (dataType == SyncDataType.SETTINGS) {
                    if (isLocallyDirty) {
                        // Kullanıcı bu cihazda ayar değiştirdi -> Yükle
                        toUpload.add(localItem)
                    } else {
                        // Bu cihaz sadece açıldı veya bu ayara dokunulmadı -> Buluttaki ayarı uygula
                        toDownload.add(remoteItem)
                    }
                    conflictCount++
                    continue
                }

                // 2. SEARCH_HISTORY (Arama Geçmişi Demeti):
                if (dataType == SyncDataType.SEARCH_HISTORY) {
                    if (isLocallyDirty) {
                        // Kullanıcı bu cihazda arama yaptı veya geçmişi sildi -> Yükle
                        toUpload.add(localItem)
                    } else {
                        // Bu cihazda yeni arama yapılmadı -> Buluttaki geçmişi uygula
                        toDownload.add(remoteItem)
                    }
                    conflictCount++
                    continue
                }

                // 3. BOOKMARKS, WATCH_PROGRESS, REPOS ve diğerleri:
                val remoteTime = parseTimestamp(remoteItem.updatedAt)
                if (isLocallyDirty) {
                    toUpload.add(localItem)
                } else if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                    // Buluttaki veri daha yeni (veya ilk senkronizasyon) -> İndir
                    toDownload.add(remoteItem)
                } else {
                    // Yerel veri daha yeni -> Yükle
                    toUpload.add(localItem)
                }
                conflictCount++
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

        Log.i(TAG, "Çözümleme tamamlandı (dirtyTypes=${dirtyTypes.map { it.name }}): " +
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
     * Supabase ISO 8601 formatlarını (mikrosaniye, 'Z', '+00:00') hatasız parse eder.
     */
    private fun parseTimestamp(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0L
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                java.time.Instant.parse(timestamp).toEpochMilli()
            } else {
                parseTimestampLegacy(timestamp)
            }
        } catch (_: Throwable) {
            parseTimestampLegacy(timestamp)
        }
    }

    private fun parseTimestampLegacy(timestamp: String?): Long {
        if (timestamp.isNullOrBlank()) return 0L
        return try {
            // "2026-09-17T18:14:26.123456+00:00" -> "2026-09-17T18:14:26"
            val clean = timestamp.substringBefore('.').substringBefore('+').substringBefore('Z')
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time ?: 0L
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
