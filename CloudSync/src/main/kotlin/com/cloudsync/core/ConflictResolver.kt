package com.cloudsync.core

import android.util.Log
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType

import com.fasterxml.jackson.databind.ObjectMapper
import com.cloudsync.models.PrefsBundle

/**
 * Çakışma çözümleyici.
 * "Son yazan kazanır" (Last-Write-Wins) stratejisi kullanır.
 *
 * Aynı key için hem local hem remote'da farklı değerler varsa,
 * timestamp'i daha yeni olan tercih edilir.
 * Demet (bundle) veriler (Ayarlar ve Arama Geçmişi) için JSON bazlı birleştirme (merge) yapılır.
 */
class ConflictResolver {

    private val objectMapper = ObjectMapper()

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
            val cleanKey = item.dataKey.replaceFirst(Regex("^[0-9]+/"), "")
            return "${item.dataType}:$cleanKey"
        }

        val localMap = localItems.associateBy { makeKey(it) }
        // Supabase'den gelen liste updated_at.desc sırasındadır (en yeni en başta).
        // associateBy duplicate key durumunda sonuncuyu (en eskiyi) almasın diye distinctBy ile en yeniyi koruyoruz:
        val remoteMap = remoteItems.distinctBy { makeKey(it) }.associateBy { makeKey(it) }

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

                val remoteTime = parseTimestamp(remoteItem.updatedAt)

                // 1. SETTINGS ve SEARCH_HISTORY (Demetler / Bundles):
                if (dataType == SyncDataType.SETTINGS || dataType == SyncDataType.SEARCH_HISTORY) {
                    // Kullanıcı yerelde bilerek temizlediyse
                    if (localItem.dataValue == DataExtractor.EMPTY_BUNDLE_VALUE && isLocallyDirty) {
                        toUpload.add(localItem)
                        conflictCount++
                        continue
                    }
                    // Bulutta temizlendiyse
                    if (remoteItem.dataValue == DataExtractor.EMPTY_BUNDLE_VALUE) {
                        if (isLocallyDirty) {
                            toUpload.add(localItem)
                        } else {
                            toDownload.add(remoteItem)
                        }
                        conflictCount++
                        continue
                    }

                    // Her ikisinde de veri varsa -> BİRLEŞTİR (MERGE)
                    val mergedValue = mergeBundles(localItem.dataValue, remoteItem.dataValue)

                    if (mergedValue != localItem.dataValue) {
                        Log.i(TAG, "Buluttan gelen demet verileri ile yerel veriler birleştirildi -> İndiriliyor")
                        toDownload.add(remoteItem.copy(dataValue = mergedValue))
                    }
                    if (mergedValue != remoteItem.dataValue || isLocallyDirty) {
                        Log.i(TAG, "Yerel değişiklikler ile bulut verileri birleştirildi -> Yükleniyor")
                        toUpload.add(localItem.copy(dataValue = mergedValue))
                    }
                    conflictCount++
                    continue
                }

                // 2. WATCH_PROGRESS (Kaldığın Yerden Devam & İlerleme):
                if (dataType == SyncDataType.WATCH_PROGRESS) {
                    if (isLocallyDirty) {
                        toUpload.add(localItem)
                    } else if (localItem.dataKey.contains("video_pos_dur")) {
                        // Oynatma konumu çakışması: Daha ileride olan (daha büyük position) kazanır!
                        val localPos = extractPosition(localItem.dataValue)
                        val remotePos = extractPosition(remoteItem.dataValue)
                        if (localPos != null && remotePos != null) {
                            if (localPos > remotePos) {
                                Log.i(TAG, "Yerel izleme konumu daha ileri ($localPos > $remotePos) -> Yükleniyor: ${localItem.dataKey}")
                                toUpload.add(localItem)
                            } else {
                                Log.i(TAG, "Bulut izleme konumu daha ileri ($remotePos >= $localPos) -> İndiriliyor: ${remoteItem.dataKey}")
                                toDownload.add(remoteItem)
                            }
                        } else if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                            toDownload.add(remoteItem)
                        } else {
                            toUpload.add(localItem)
                        }
                    } else if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                        toDownload.add(remoteItem)
                    } else {
                        toUpload.add(localItem)
                    }
                    conflictCount++
                    continue
                }

                // 3. BOOKMARKS, REPOS ve diğerleri:
                if (isLocallyDirty) {
                    toUpload.add(localItem)
                } else if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                    toDownload.add(remoteItem)
                } else {
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
     * İki JSON demetini birleştirir (merge).
     * Aynı anahtarlar varsa localValue (kullanıcı cihazındaki) değeri kazanır, çünkü
     * genellikle local daha günceldir (veya değişiklik buradadır).
     */
    private fun mergeBundles(localValue: String?, remoteValue: String?): String {
        if (localValue.isNullOrBlank() || localValue == DataExtractor.EMPTY_BUNDLE_VALUE) return remoteValue ?: ""
        if (remoteValue.isNullOrBlank() || remoteValue == DataExtractor.EMPTY_BUNDLE_VALUE) return localValue
        
        return try {
            val localBundle = objectMapper.readValue(localValue, PrefsBundle::class.java)
            val remoteBundle = objectMapper.readValue(remoteValue, PrefsBundle::class.java)
            
            val mergedRebuild = remoteBundle.rebuild.toMutableMap()
            mergedRebuild.putAll(localBundle.rebuild)
            
            val mergedDefault = remoteBundle.default.toMutableMap()
            mergedDefault.putAll(localBundle.default)
            
            objectMapper.writeValueAsString(PrefsBundle(mergedRebuild, mergedDefault))
        } catch (e: Exception) {
            Log.e(TAG, "Demetler birleştirilirken hata: ${e.message}")
            localValue
        }
    }

    /**
     * JSON veya serileştirilmiş video_pos_dur değerinden oynatma pozisyonunu (position) ayıklar.
     */
    private fun extractPosition(value: String?): Long? {
        if (value.isNullOrBlank() || value == DataExtractor.TOMBSTONE_VALUE) return null
        return try {
            var raw = when {
                value.startsWith("s:") -> value.substring(2)
                value.startsWith("j:") -> value.substring(2)
                else -> value
            }
            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 2) {
                try {
                    raw = objectMapper.readValue(raw, String::class.java)
                } catch (_: Exception) {}
            }
            if (raw.startsWith("{")) {
                val node = objectMapper.readTree(raw)
                if (node.has("position")) {
                    node.get("position").asLong()
                } else null
            } else null
        } catch (_: Exception) {
            null
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
