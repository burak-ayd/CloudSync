package com.cloudsync.core

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Eklentiler (Plugins) ve Depolar (Repositories) için birleştirme (merge),
 * indirme ve yerel yükleme işlemlerini yöneten yardımcı sınıf.
 */
object PluginSyncHelper {

    private const val TAG = "CloudSync.PluginSync"
    private val objectMapper = ObjectMapper()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * GitHub raw URL'lerini Türkiye ve diğer bölgelerdeki erişim engellerine karşı
     * jsdelivr CDN formatına çevirir (fallback desteği ile).
     */
    fun forceConvertRawGitUrl(url: String): String {
        val pattern = Pattern.compile("^https://raw\\.githubusercontent\\.com/([A-Za-z0-9_-]+)/([A-Za-z0-9_.-]+)/(.*)$")
        val matcher = pattern.matcher(url)
        return if (matcher.find()) {
            val user = matcher.group(1)
            val repo = matcher.group(2)
            val rest = matcher.group(3)
            "https://cdn.jsdelivr.net/gh/$user/$repo@$rest"
        } else {
            url
        }
    }

    private fun cleanJson(rawVal: String?): String? {
        if (rawVal.isNullOrBlank()) return null
        return when {
            rawVal.startsWith("s:") -> rawVal.substring(2)
            rawVal.startsWith("j:") -> rawVal.substring(2)
            else -> rawVal
        }
    }

    /**
     * İki depo (Repositories) JSON dizisini URL bazında birleştirir (Merge).
     * Format: s:[{"name":"...","url":"...","iconUrl":...}, ...]
     */
    fun mergeRepositoriesJson(localJson: String?, remoteJson: String?): String {
        val localClean = cleanJson(localJson)
        val remoteClean = cleanJson(remoteJson)

        val localList = parseRepoNodes(localClean)
        val remoteList = parseRepoNodes(remoteClean)

        if (localList.isEmpty() && remoteList.isEmpty()) {
            return localJson ?: remoteJson ?: "s:[]"
        }

        val mergedMap = LinkedHashMap<String, JsonNode>()

        // Önce yerel repoları ekle
        for (node in localList) {
            val url = node.path("url").asText("").trim().lowercase()
            if (url.isNotBlank()) {
                mergedMap[url] = node
            }
        }

        // Buluttan gelen repoları ekle (aynı url varsa üzerine yaz veya koru)
        for (node in remoteList) {
            val url = node.path("url").asText("").trim().lowercase()
            if (url.isNotBlank()) {
                val existing = mergedMap[url]
                if (existing == null) {
                    mergedMap[url] = node
                } else {
                    // İsim veya icon eksikse doldur
                    val mergedNode = existing.deepCopy<ObjectNode>()
                    if (mergedNode.path("name").asText("").isBlank() && !node.path("name").asText("").isBlank()) {
                        mergedNode.put("name", node.path("name").asText())
                    }
                    if (mergedNode.path("iconUrl").isNull && !node.path("iconUrl").isNull) {
                        mergedNode.put("iconUrl", node.path("iconUrl").asText())
                    }
                    mergedMap[url] = mergedNode
                }
            }
        }

        val arrayNode = objectMapper.createArrayNode()
        mergedMap.values.forEach { arrayNode.add(it) }
        val resultJson = objectMapper.writeValueAsString(arrayNode)
        Log.i(TAG, "Repolar birleştirildi: ${localList.size} yerel + ${remoteList.size} bulut = ${mergedMap.size} toplam")
        return "s:$resultJson"
    }

    /**
     * İki eklenti (Plugins) JSON dizisini internalName bazında birleştirir (Merge).
     * Format: s:[{"internalName":"DiziMom","url":"..."}, ...]
     */
    fun mergePluginsJson(localJson: String?, remoteJson: String?): String {
        val localClean = cleanJson(localJson)
        val remoteClean = cleanJson(remoteJson)

        val localList = parsePluginNodes(localClean)
        val remoteList = parsePluginNodes(remoteClean)

        if (localList.isEmpty() && remoteList.isEmpty()) {
            return localJson ?: remoteJson ?: "s:[]"
        }

        val mergedMap = LinkedHashMap<String, JsonNode>()

        for (node in localList) {
            val name = node.path("internalName").asText("").trim().lowercase()
            if (name.isNotBlank()) {
                mergedMap[name] = node
            }
        }

        for (node in remoteList) {
            val name = node.path("internalName").asText("").trim().lowercase()
            if (name.isNotBlank()) {
                val existing = mergedMap[name]
                if (existing == null) {
                    mergedMap[name] = node
                } else {
                    // Bulut versiyonu daha güncel URL içerebilir
                    val mergedNode = existing.deepCopy<ObjectNode>()
                    val remoteUrl = node.path("url").asText("")
                    if (remoteUrl.isNotBlank()) {
                        mergedNode.put("url", remoteUrl)
                    }
                    mergedMap[name] = mergedNode
                }
            }
        }

        val arrayNode = objectMapper.createArrayNode()
        mergedMap.values.forEach { arrayNode.add(it) }
        val resultJson = objectMapper.writeValueAsString(arrayNode)
        Log.i(TAG, "Eklentiler birleştirildi: ${localList.size} yerel + ${remoteList.size} bulut = ${mergedMap.size} toplam")
        return "s:$resultJson"
    }

    private fun parseRepoNodes(jsonStr: String?): List<JsonNode> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(jsonStr)
            if (node.isArray) {
                node.filter { it.isObject && it.has("url") }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePluginNodes(jsonStr: String?): List<JsonNode> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val node = objectMapper.readTree(jsonStr)
            if (node.isArray) {
                node.filter { it.isObject && (it.has("internalName") || it.has("name")) }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Buluttan gelen eklenti listesini inceler.
     * Cihazda fiziksel .cs3 dosyası bulunmayan eklentileri arka planda indirir ve CloudStream'e tanıtır.
     */
    fun downloadMissingPlugins(context: Context, pluginsJson: String) {
        val clean = cleanJson(pluginsJson) ?: return
        val pluginNodes = parsePluginNodes(clean)
        if (pluginNodes.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val extensionsDir = File(context.filesDir, "Extensions")
                if (!extensionsDir.exists()) {
                    extensionsDir.mkdirs()
                }

                var newlyDownloaded = 0

                for (node in pluginNodes) {
                    val internalName = node.path("internalName").asText("").ifBlank { node.path("name").asText("") }
                    if (internalName.isBlank()) continue

                    // CloudSync'in kendisini indirmeye çalışma
                    if (internalName.equals("CloudSync", ignoreCase = true) || internalName.equals("Cloud-Sync", ignoreCase = true)) {
                        continue
                    }

                    val downloadUrl = node.path("url").asText("").trim()
                    if (downloadUrl.isBlank()) continue

                    // Yerel eklenti dosyasının var olup olmadığını kontrol et
                    val isAlreadyInstalled = checkPluginFileExists(extensionsDir, internalName)
                    if (isAlreadyInstalled) {
                        Log.d(TAG, "Eklenti zaten mevcut: $internalName")
                        continue
                    }

                    Log.i(TAG, "Eksik eklenti tespit edildi, indiriliyor: $internalName ($downloadUrl)")
                    val downloaded = downloadPluginFile(context, extensionsDir, internalName, downloadUrl)
                    if (downloaded) {
                        newlyDownloaded++
                    }
                }

                if (newlyDownloaded > 0) {
                    Log.i(TAG, "$newlyDownloaded yeni eklenti başarıyla indirildi. CloudStream eklenti sistemi yenileniyor...")
                    reloadCloudStreamPlugins(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eklenti indirme işlemi sırasında hata", e)
            }
        }
    }

    private fun checkPluginFileExists(extensionsDir: File, internalName: String): Boolean {
        val lowerName = internalName.lowercase()

        // 1. Extensions/internalName.cs3
        val direct = File(extensionsDir, "$internalName.cs3")
        if (direct.exists() && direct.length() > 0) return true

        // 2. Extensions alt klasörlerini tara (örn: Extensions/DefaultRepo/internalName.cs3)
        val files = extensionsDir.listFiles() ?: return false
        for (f in files) {
            if (f.isDirectory) {
                val subFiles = f.listFiles() ?: continue
                for (sub in subFiles) {
                    if (sub.isFile && sub.name.lowercase() == "$lowerName.cs3" && sub.length() > 0) {
                        return true
                    }
                }
            } else if (f.isFile && f.name.lowercase() == "$lowerName.cs3" && f.length() > 0) {
                return true
            }
        }
        return false
    }

    private fun downloadPluginFile(
        context: Context,
        extensionsDir: File,
        internalName: String,
        targetUrl: String
    ): Boolean {
        // İndirilecek URL listesi: Önce CDN / raw git dönüştürülmüş URL, sonra orijinal URL
        val urlsToTry = mutableListOf<String>()
        val convertedUrl = forceConvertRawGitUrl(targetUrl)
        if (convertedUrl != targetUrl) {
            urlsToTry.add(convertedUrl)
        }
        urlsToTry.add(targetUrl)

        val targetDir = File(extensionsDir, "DefaultRepo")
        if (!targetDir.exists()) targetDir.mkdirs()
        val targetFile = File(targetDir, "$internalName.cs3")

        for (url in urlsToTry) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; CloudStream)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        val tempFile = File.createTempFile(internalName, ".tmp", context.cacheDir)
                        FileOutputStream(tempFile).use { fos ->
                            body.byteStream().use { bis ->
                                bis.copyTo(fos)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            if (targetFile.exists()) targetFile.delete()
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                            Log.i(TAG, "Eklenti başarıyla indirildi: $internalName -> ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Eklenti indirme denemesi başarısız ($url): ${e.message}")
            }
        }
        return false
    }

    /**
     * İndirilen eklentileri CloudStream'e tanıtır ve ana sayfayı yeniler.
     */
    private fun reloadCloudStreamPlugins(context: Context) {
        try {
            // CloudStream PluginManager reflection çağrısı (varsa doğrudan yükle)
            val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
            val pmInstance = pmClass.getField("INSTANCE").get(null)

            // loadAllLocalPlugins metodu
            try {
                val loadMethod = pmClass.methods.firstOrNull {
                    it.name.contains("loadAllLocalPlugins")
                }
                loadMethod?.invoke(pmInstance, context, false)
                Log.i(TAG, "PluginManager.loadAllLocalPlugins çağrıldı")
            } catch (_: Throwable) {}
        } catch (_: Throwable) {}

        try {
            com.lagradost.cloudstream3.MainActivity.reloadHomeEvent.invoke(true)
        } catch (_: Throwable) {}
        try {
            com.lagradost.cloudstream3.MainActivity.mainPluginsLoadedEvent.invoke(true)
        } catch (_: Throwable) {}
    }
}
