package com.cloudsync.core

import android.util.Log
import com.cloudsync.models.SyncDataItem
import com.cloudsync.models.SyncDataType

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

        // Yerel verilerden sadece geçerli anahtarları al (eski v1 result_resume_watching veya hesapsız çöp anahtarları atla)
        val validLocalItems = localItems.filter { item ->
            val key = item.dataKey
            if (key.contains("result_resume_watching") && !key.contains("result_resume_watching_2")) {
                false
            } else if (item.dataType == SyncDataType.WATCH_PROGRESS.name &&
                !Regex("^[0-9]+/").containsMatchIn(key) &&
                !key.startsWith("download_header_cache")) {
                false
            } else if (item.dataType == SyncDataType.REPOS.name && Regex("^[0-9]+/").containsMatchIn(key)) {
                false
            } else {
                true
            }
        }
        val localMap = validLocalItems.associateBy { makeKey(it) }

        val toUpload = mutableListOf<SyncDataItem>()   // Local -> Cloud
        val toDownload = mutableListOf<SyncDataItem>()  // Cloud -> Local
        var conflictCount = 0

        // Remote verilerdeki eski v1 kalıntılarını, hesapsız kopyaları ve hesap önekli repo/plugin anahtarlarını temizle (Supabase'den kalıcı olarak sil)
        val validRemoteItems = mutableListOf<SyncDataItem>()
        for (item in remoteItems) {
            val key = item.dataKey
            val isLegacyV1Resume = key.contains("result_resume_watching") && !key.contains("result_resume_watching_2")
            val isUnprefixedProgress = item.dataType == SyncDataType.WATCH_PROGRESS.name &&
                    !Regex("^[0-9]+/").containsMatchIn(key) &&
                    !key.startsWith("download_header_cache")
            val isPrefixedRepo = item.dataType == SyncDataType.REPOS.name && Regex("^[0-9]+/").containsMatchIn(key)

            if (isLegacyV1Resume || isUnprefixedProgress || isPrefixedRepo) {
                // Supabase'deki bu çöp/eski satırı silmek için tombstone ekle
                if (item.dataValue != DataExtractor.TOMBSTONE_VALUE) {
                    toUpload.add(item.copy(dataValue = DataExtractor.TOMBSTONE_VALUE))
                    Log.i(TAG, "Buluttaki eski/çöp anahtar için silme kaydı oluşturuldu (Purge): $key")
                }
            } else {
                validRemoteItems.add(item)
            }
        }

        // Supabase'den gelen liste updated_at.desc sırasındadır (en yeni en başta).
        // associateBy duplicate key durumunda sonuncuyu (en eskiyi) almasın diye distinctBy ile en yeniyi koruyoruz:
        val remoteMap = validRemoteItems.distinctBy { makeKey(it) }.associateBy { makeKey(it) }

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
                    } else if (dataType == SyncDataType.WATCH_PROGRESS && localItem.dataKey.contains("video_pos_dur")) {
                        // Eğer yerel pozisyon 0 veya geçersizse buluta yükleyip boşuna yer kaplama
                        val pos = extractPosition(localItem.dataValue)
                        if (pos == null || pos > 0L) {
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
                    val isVideoPos = localItem.dataKey.contains("video_pos_dur") ||
                            remoteItem.dataKey.contains("video_pos_dur")

                    if (isVideoPos) {
                        // Oynatma konumu çakışması: Normalde daha yeni olan (updated_at) her zaman kazanır.
                        // Geri sarmalara (rewind) izin vermek için sadece pozisyona bakmıyoruz.
                        val localPos = extractPosition(localItem.dataValue)
                        val remotePos = extractPosition(remoteItem.dataValue)

                        when {
                            // Gerçek Çakışma: Hem bulutta yeni veri var hem de bu cihazda yeni izleme yapılmış.
                            // Bu durumda (veya eşitlikte) en ileri olan pozisyonu tercih edelim.
                            lastSyncTime != 0L && remoteTime > lastSyncTime && isLocallyDirty -> {
                                if (localPos != null && remotePos != null && localPos > remotePos) {
                                    Log.i(TAG, "Çakışma: Yerel izleme konumu daha ileri ($localPos > $remotePos) -> Yükleniyor: ${localItem.dataKey}")
                                    toUpload.add(localItem)
                                } else {
                                    Log.i(TAG, "Çakışma: Bulut izleme konumu daha ileri veya eşit ($remotePos >= $localPos) -> İndiriliyor: ${remoteItem.dataKey}")
                                    toDownload.add(remoteItem)
                                }
                            }
                            // Bulut verisi daha yeniyse indir (geri sarmayı desteklemek için bulutu tercih et)
                            lastSyncTime == 0L || remoteTime > lastSyncTime -> {
                                Log.i(TAG, "Bulut izleme konumu daha yeni -> İndiriliyor: ${remoteItem.dataKey}")
                                toDownload.add(remoteItem)
                            }
                            // Bulut eski, yerelde değişiklik var veya farklı
                            else -> {
                                Log.i(TAG, "Yerel izleme konumu buluta gönderiliyor -> Yükleniyor: ${localItem.dataKey}")
                                toUpload.add(localItem)
                            }
                        }
                    } else if (localItem.dataKey.contains("result_resume_watching_2") || remoteItem.dataKey.contains("result_resume_watching_2")) {
                        // ResumeWatching karşılaştırması: updateTime ve episodeId bütünlüğü korunur
                        val localResume = extractResumeData(localItem.dataValue)
                        val remoteResume = extractResumeData(remoteItem.dataValue)

                        val localTime = localResume?.updateTime ?: 0L
                        val remoteUpdateTime = remoteResume?.updateTime ?: 0L

                        when {
                            localTime > remoteUpdateTime -> {
                                Log.i(TAG, "Yerel izleme kaydı daha yeni ($localTime > $remoteUpdateTime) -> Yükleniyor: ${localItem.dataKey}")
                                toUpload.add(localItem)
                            }
                            remoteUpdateTime > localTime -> {
                                Log.i(TAG, "Bulut izleme kaydı daha yeni ($remoteUpdateTime > $localTime) -> İndiriliyor: ${remoteItem.dataKey}")
                                val downloadItem = if (remoteResume?.episodeId == null && localResume?.episodeId != null) {
                                    remoteItem.copy(dataValue = patchEpisodeId(remoteItem.dataValue ?: "", localResume.episodeId))
                                } else {
                                    remoteItem
                                }
                                toDownload.add(downloadItem)
                            }
                            isLocallyDirty -> {
                                toUpload.add(localItem)
                            }
                            lastSyncTime == 0L || remoteTime > lastSyncTime -> {
                                val downloadItem = if (remoteResume?.episodeId == null && localResume?.episodeId != null) {
                                    remoteItem.copy(dataValue = patchEpisodeId(remoteItem.dataValue ?: "", localResume.episodeId))
                                } else {
                                    remoteItem
                                }
                                toDownload.add(downloadItem)
                            }
                            else -> {
                                toUpload.add(localItem)
                            }
                        }
                    } else if (isLocallyDirty) {
                        toUpload.add(localItem)
                    } else if (lastSyncTime == 0L || remoteTime > lastSyncTime) {
                        toDownload.add(remoteItem)
                    } else {
                        toUpload.add(localItem)
                    }
                    conflictCount++
                    continue
                }

                // 3. REPOS (Eklentiler ve Depolar) Akıllı Birleştirme (Merge):
                if (dataType == SyncDataType.REPOS) {
                    val cleanKey = localItem.dataKey.replaceFirst(Regex("^[0-9]+/"), "")
                    val isRepoList = cleanKey.equals("REPOSITORIES_KEY", ignoreCase = true) ||
                            cleanKey.equals("plugins_repositories", ignoreCase = true) ||
                            cleanKey.equals("repositories", ignoreCase = true)
                    val isPluginList = cleanKey.equals("PLUGINS_KEY", ignoreCase = true)

                    if (isRepoList) {
                        val merged = PluginSyncHelper.mergeRepositoriesJson(localItem.dataValue, remoteItem.dataValue)
                        if (merged != localItem.dataValue) {
                            Log.i(TAG, "Bulut ve yerel depo listeleri birleştirildi -> İndiriliyor: $cleanKey")
                            toDownload.add(remoteItem.copy(dataKey = cleanKey, dataValue = merged))
                        }
                        if (merged != remoteItem.dataValue || isLocallyDirty) {
                            Log.i(TAG, "Bulut ve yerel depo listeleri birleştirildi -> Yükleniyor: $cleanKey")
                            toUpload.add(localItem.copy(dataKey = cleanKey, dataValue = merged))
                        }
                        conflictCount++
                        continue
                    } else if (isPluginList) {
                        val merged = PluginSyncHelper.mergePluginsJson(localItem.dataValue, remoteItem.dataValue)
                        if (merged != localItem.dataValue) {
                            Log.i(TAG, "Bulut ve yerel eklenti listeleri birleştirildi -> İndiriliyor: $cleanKey")
                            toDownload.add(remoteItem.copy(dataKey = cleanKey, dataValue = merged))
                        }
                        if (merged != remoteItem.dataValue || isLocallyDirty) {
                            Log.i(TAG, "Bulut ve yerel eklenti listeleri birleştirildi -> Yükleniyor: $cleanKey")
                            toUpload.add(localItem.copy(dataKey = cleanKey, dataValue = merged))
                        }
                        conflictCount++
                        continue
                    }
                }

                // 4. BOOKMARKS ve diğerleri:
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
                    val cleanItem = if (remoteItem.dataType == SyncDataType.REPOS.name) {
                        remoteItem.copy(dataKey = remoteItem.dataKey.replaceFirst(Regex("^[0-9]+/"), ""))
                    } else {
                        remoteItem
                    }
                    toDownload.add(cleanItem)
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

    data class ResumeData(
        val parentId: Int?,
        val episodeId: Int?,
        val episode: Int?,
        val season: Int?,
        val updateTime: Long?
    )

    private fun extractResumeData(value: String?): ResumeData? {
        if (value.isNullOrBlank() || value == DataExtractor.TOMBSTONE_VALUE) return null
        return try {
            val raw = cleanJsonString(value)
            if (raw.startsWith("{")) {
                val node = objectMapper.readTree(raw)
                val parentId = if (node.has("parentId") && !node.get("parentId").isNull) node.get("parentId").asInt() else null
                val epNode = node.get("episodeId")
                val episodeId = if (epNode != null && !epNode.isNull && epNode.asInt(0) != 0) epNode.asInt() else null
                val episode = if (node.has("episode") && !node.get("episode").isNull) node.get("episode").asInt() else null
                val season = if (node.has("season") && !node.get("season").isNull) node.get("season").asInt() else null
                val updateTime = if (node.has("updateTime") && !node.get("updateTime").isNull) node.get("updateTime").asLong() else null
                ResumeData(parentId, episodeId, episode, season, updateTime)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun patchEpisodeId(jsonValue: String, episodeId: Int): String {
        return try {
            val prefix = when {
                jsonValue.startsWith("s:") -> "s:"
                jsonValue.startsWith("j:") -> "j:"
                else -> ""
            }
            val raw = if (prefix.isNotEmpty()) jsonValue.substring(prefix.length) else jsonValue
            val node = objectMapper.readTree(raw) as com.fasterxml.jackson.databind.node.ObjectNode
            node.put("episodeId", episodeId)
            val updated = objectMapper.writeValueAsString(node)
            if (prefix.isNotEmpty()) "$prefix$updated" else updated
        } catch (_: Exception) {
            jsonValue
        }
    }

    private fun cleanJsonString(value: String): String {
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
        return raw
    }

    /**
     * JSON veya serileştirilmiş video_pos_dur değerinden oynatma pozisyonunu (position) ayıklar.
     */
    private fun extractPosition(value: String?): Long? {
        if (value.isNullOrBlank() || value == DataExtractor.TOMBSTONE_VALUE) return null
        return try {
            val raw = cleanJsonString(value)
            if (raw.startsWith("{")) {
                val node = objectMapper.readTree(raw)
                if (node.has("position")) {
                    node.get("position").asLong()
                } else if (node.has("pos")) {
                    node.get("pos").asLong()
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
