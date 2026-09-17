package com.cloudsync.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reaktif senkronizasyon zamanlayıcısı.
 *
 * Referans eklentideki 3 kritik mekanizmayı implemente eder:
 *
 * 1. **SharedPreferences Listener**: Her veri değişikliğini anında yakalar
 *    (film ekleme, izleme durumu, favori vb.) ve debounced push başlatır.
 *
 * 2. **Activity Lifecycle Callbacks**: Uygulama ön plana geldiğinde
 *    otomatik pull yaparak diğer cihazlardan gelen değişiklikleri uygular.
 *
 * 3. **Debounced Push**: Aynı anda çok sayıda değişiklik yapıldığında
 *    (toplu ekleme vs.) gereksiz çoklu sync'i önlemek için 2 saniyelik debounce.
 *
 * Ayrıca periyodik zamanlayıcı (eski davranış) da korunur.
 */
class SyncScheduler(private val context: Context) {

    companion object {
        private const val TAG = "CloudSync.Scheduler"

        /** Debounce süresi: değişiklikten sonra push tetiklenmeden önceki bekleme (ms) */
        private const val PUSH_DEBOUNCE_MS = 2000L

        /** Kendi push'umuzdan sonra gelen pref değişikliklerini yok saymak için süre (ms) */
        private const val RESTORE_GUARD_MS = 5000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var syncManager: SyncManager? = null
    private var isRunning = false

    // ==================== Reaktif Sync Durumu ====================

    /** SharedPreferences listener referansları (cleanup için) */
    private var dataPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var defaultPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Activity lifecycle callback'leri */
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var registeredApp: Application? = null

    /** Değişen (dirty) veri tipleri - push sırasında hangi tiplerin güncelleneceğini belirler */
    private val dirtyTypes = mutableSetOf<SyncDataType>()
    private val dirtyLock = Any()

    /** Debounced push job'u */
    private var pushJob: Job? = null

    /**
     * Kendi indirdiğimiz veriyi yazarken SharedPreferences listener'ın
     * onu yeni değişiklik olarak algılamasını engelleyen bayrak.
     */
    @Volatile
    var isRestoring = false
        private set

    /** isRestoring true yapıldıktan sonra otomatik false olacağı zaman */
    @Volatile
    private var restoringUntil = 0L

    /** En son push yaptığımız zaman (kendi push'larımızdan gelen SSE event'lerini yok saymak için) */
    @Volatile
    var lastPushTimestamp = 0L
        private set

    /** Activity referansı (notifyUIUpdate ve lifecycle için) */
    var currentActivity: AppCompatActivity? = null

    // ==================== Periyodik Zamanlayıcı (Eski davranış) ====================

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!SyncConfig.isAutoSyncEnabled(context)) {
                stop()
                return
            }

            Log.i(TAG, "Periyodik otomatik senkronizasyon başlatılıyor...")
            scope.launch {
                try {
                    syncMutex.withLock {
                        syncManager?.syncAll()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Periyodik senkronizasyon hatası", e)
                }
            }

            // Sonraki çalıştırmayı planla
            val intervalMs = SyncConfig.getSyncIntervalMinutes(context) * 60 * 1000L
            handler.postDelayed(this, intervalMs)
        }
    }

    // ==================== Başlat / Durdur ====================

    /**
     * Tüm senkronizasyon mekanizmalarını başlatır:
     * - SharedPreferences listener (değişiklik algılama)
     * - Activity lifecycle (resume-pull)
     * - Periyodik zamanlayıcı
     */
    fun start(manager: SyncManager) {
        syncManager = manager

        // SharedPreferences listener'ları kaydet
        registerPrefsListeners()

        // Activity lifecycle callback'lerini kaydet
        registerLifecycleCallbacks()

        // Bookmark event'i dinle
        registerBookmarkListener()

        // Periyodik zamanlayıcıyı başlat (eğer aktifse)
        if (!isRunning && SyncConfig.isAutoSyncEnabled(context)) {
            isRunning = true
            val intervalMs = SyncConfig.getSyncIntervalMinutes(context) * 60 * 1000L
            handler.postDelayed(periodicSyncRunnable, intervalMs)
            Log.i(TAG, "Periyodik sync başlatıldı: her ${SyncConfig.getSyncIntervalMinutes(context)} dakikada bir")
        }

        Log.i(TAG, "Reaktif senkronizasyon aktif (SharedPrefs listener + Activity lifecycle)")
    }

    /**
     * Tüm senkronizasyon mekanizmalarını durdurur ve kaynakları temizler.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(periodicSyncRunnable)

        // Debounced push'u iptal et
        pushJob?.cancel()
        pushJob = null

        // SharedPreferences listener'ları kaldır
        unregisterPrefsListeners()

        // Activity lifecycle callback'lerini kaldır
        unregisterLifecycleCallbacks()

        Log.i(TAG, "Tüm senkronizasyon mekanizmaları durduruldu")
    }

    fun isSchedulerRunning(): Boolean = isRunning

    // ==================== SharedPreferences Listener ====================

    /**
     * CloudStream'in SharedPreferences dosyalarına listener kaydeder.
     * Her değişiklikte ilgili veri tipini dirty olarak işaretler ve debounced push başlatır.
     *
     * CloudStream iki farklı SharedPreferences dosyası kullanır:
     * - rebuild_preference: İçerik verileri (favoriler, izleme geçmişi)
     * - Default SharedPreferences: Uygulama ayarları
     */
    private fun registerPrefsListeners() {
        // Mevcut listener'ları temizle
        unregisterPrefsListeners()

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener

            // CloudSync'in kendi key'lerini ve transfer edilemeyen anahtarları yok say
            if (SyncConfig.isCloudSyncKey(key)) return@OnSharedPreferenceChangeListener
            if (DataExtractor.isNonTransferable(key)) return@OnSharedPreferenceChangeListener

            // Kendi indirdiğimiz veriyi yazarken tetiklenmeyi engelle
            if (isRestoring || System.currentTimeMillis() < restoringUntil) {
                return@OnSharedPreferenceChangeListener
            }

            // Yapılandırma tamamlanmamışsa yok say
            if (!SyncConfig.isConfigured(context)) return@OnSharedPreferenceChangeListener

            // Key'in hangi veri tipine ait olduğunu belirle
            val dataType = classifyKey(key)
            if (SyncConfig.isSyncEnabled(context, dataType)) {
                Log.d(TAG, "Pref değişikliği algılandı: $key → ${dataType.displayName}")
                markDirty(dataType)
            }
        }

        dataPrefsListener = listener
        defaultPrefsListener = listener

        try {
            // rebuild_preference (içerik verileri)
            val rebuildPrefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            rebuildPrefs.registerOnSharedPreferenceChangeListener(listener)

            // Default SharedPreferences (ayarlar)
            val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            defaultPrefs.registerOnSharedPreferenceChangeListener(listener)

            Log.d(TAG, "SharedPreferences listener'ları kaydedildi")
        } catch (e: Exception) {
            Log.e(TAG, "SharedPreferences listener kayıt hatası: ${e.message}")
        }
    }

    /**
     * SharedPreferences listener'larını kaldırır.
     */
    private fun unregisterPrefsListeners() {
        try {
            dataPrefsListener?.let { listener ->
                try {
                    val rebuildPrefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                    rebuildPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                } catch (_: Exception) {}
            }
            defaultPrefsListener?.let { listener ->
                try {
                    val defaultPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    defaultPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        dataPrefsListener = null
        defaultPrefsListener = null
    }

    // ==================== Activity Lifecycle ====================

    /**
     * Activity lifecycle callback'lerini kaydeder.
     * Uygulama ön plana geldiğinde (onActivityResumed) otomatik pull yapar.
     */
    private fun registerLifecycleCallbacks() {
        unregisterLifecycleCallbacks()

        val appInstance = context.applicationContext as? Application ?: return

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // Sadece MainActivity ön plana geldiğinde
                if (activity.javaClass.simpleName == "MainActivity") {
                    currentActivity = activity as? AppCompatActivity
                    onAppResumed(activity)
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (activity == currentActivity) {
                    currentActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        }

        lifecycleCallbacks = callbacks
        registeredApp = appInstance
        appInstance.registerActivityLifecycleCallbacks(callbacks)

        Log.d(TAG, "Activity lifecycle callback'leri kaydedildi")
    }

    /**
     * Activity lifecycle callback'lerini kaldırır.
     */
    private fun unregisterLifecycleCallbacks() {
        lifecycleCallbacks?.let { cb ->
            registeredApp?.unregisterActivityLifecycleCallbacks(cb)
        }
        lifecycleCallbacks = null
        registeredApp = null
    }

    /**
     * Uygulama ön plana geldiğinde çağrılır.
     * Diğer cihazlardan gelen değişiklikleri çekmek için otomatik pull yapar.
     */
    private fun onAppResumed(activity: Activity) {
        if (!SyncConfig.isConfigured(context)) return

        Log.i(TAG, "Uygulama ön plana geldi — otomatik pull başlatılıyor")
        scope.launch {
            try {
                syncMutex.withLock {
                    val result = syncManager?.syncAll(dirtyTypes = emptySet())
                    if (result?.success == true && result.downloadedCount > 0) {
                        Log.i(TAG, "Resume-pull: ${result.downloadedCount} veri indirildi")
                        withContext(Dispatchers.Main) {
                            notifyUIUpdate(result.updatedTypes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Resume-pull hatası: ${e.message}")
            }
        }
    }

    // ==================== Bookmark Event ====================

    /**
     * CloudStream'in bookmark güncelleme event'ini dinler.
     * Favori eklendiğinde/silindiğinde anında dirty olarak işaretler.
     */
    private fun registerBookmarkListener() {
        try {
            com.lagradost.cloudstream3.MainActivity.bookmarksUpdatedEvent += { _ ->
                if (!isRestoring && System.currentTimeMillis() > restoringUntil) {
                    markDirty(SyncDataType.BOOKMARKS)
                }
            }
            Log.d(TAG, "Bookmark event listener kaydedildi")
        } catch (e: Exception) {
            Log.w(TAG, "Bookmark event listener kaydedilemedi: ${e.message}")
        }
    }

    // ==================== Dirty Tracking & Debounced Push ====================

    /**
     * Bir veri tipini değişmiş (dirty) olarak işaretler ve debounced push başlatır.
     * Referans eklentideki markDirty + scheduleDebouncedPush mekanizmasının karşılığı.
     */
    private fun markDirty(dataType: SyncDataType) {
        synchronized(dirtyLock) {
            dirtyTypes.add(dataType)
        }
        scheduleDebouncedPush()
    }

    /**
     * Dirty olan veri tiplerini consume eder (alır ve temizler).
     */
    private fun consumeDirtyTypes(): Set<SyncDataType> {
        synchronized(dirtyLock) {
            val snapshot = dirtyTypes.toSet()
            dirtyTypes.clear()
            return snapshot
        }
    }

    /**
     * Debounced push zamanlayıcısı.
     * Önceki bekleyen push'u iptal eder ve PUSH_DEBOUNCE_MS sonra yeni push başlatır.
     *
     * Bu sayede kısa sürede çok sayıda değişiklik olduğunda (toplu ekleme vs.)
     * sadece tek bir sync tetiklenir.
     */
    private fun scheduleDebouncedPush() {
        if (!SyncConfig.isConfigured(context)) return

        // Önceki bekleyen push'u iptal et
        pushJob?.cancel()

        pushJob = scope.launch {
            // Debounce bekleme
            delay(PUSH_DEBOUNCE_MS)

            // Dirty tipleri al
            val types = consumeDirtyTypes()
            if (types.isEmpty()) return@launch

            Log.i(TAG, "Debounced push başlatılıyor: ${types.joinToString { it.displayName }}")

            try {
                syncMutex.withLock {
                    lastPushTimestamp = System.currentTimeMillis()
                    val result = syncManager?.syncAll(dirtyTypes = types)
                    lastPushTimestamp = System.currentTimeMillis()

                    if (result?.success == true) {
                        val uploadCount = result.uploadedCount
                        val downloadCount = result.downloadedCount
                        if (uploadCount > 0 || downloadCount > 0) {
                            Log.i(TAG, "Debounced push tamamlandı: ↑$uploadCount ↓$downloadCount")
                            if (downloadCount > 0) {
                                withContext(Dispatchers.Main) {
                                    notifyUIUpdate(result.updatedTypes)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debounced push hatası: ${e.message}")
            }
        }
    }

    // ==================== Restore Guard ====================

    /**
     * Buluttan veri indirip local'a yazarken çağrılır.
     * SharedPreferences listener'ın bu yazma işlemini "yeni değişiklik"
     * olarak algılamasını engeller (sonsuz döngü önleme).
     */
    fun beginRestore() {
        isRestoring = true
        restoringUntil = System.currentTimeMillis() + RESTORE_GUARD_MS
    }

    /**
     * Restore işlemi tamamlandığında çağrılır.
     */
    fun endRestore() {
        isRestoring = false
        // restoringUntil zaman aşımı ile otomatik olarak guard kalkar
    }

    // ==================== UI Güncelleme ====================

    /**
     * CloudStream arayüzünü günceller.
     * Favoriler, kütüphane ve ana sayfa yenilenir.
     * Eğer ayarlar senkronize edildiyse, yeni ayarların/temaların uygulanması için
     * Activity recreate edilir (referans eklenti standardı).
     */
    private fun notifyUIUpdate(updatedTypes: List<SyncDataType> = emptyList()) {
        try {
            com.lagradost.cloudstream3.MainActivity.bookmarksUpdatedEvent.invoke(true)
        } catch (_: Throwable) {}
        try {
            com.lagradost.cloudstream3.MainActivity.reloadLibraryEvent.invoke(true)
        } catch (_: Throwable) {}
        try {
            com.lagradost.cloudstream3.MainActivity.reloadHomeEvent.invoke(true)
        } catch (_: Throwable) {}

        // Ayarlar veya arama geçmişi değiştiğinde UI'ın anında yeni verileri görmesi için Activity recreate edilir
        if (updatedTypes.contains(SyncDataType.SETTINGS) || updatedTypes.contains(SyncDataType.SEARCH_HISTORY)) {
            try {
                currentActivity?.let { act ->
                    if (act.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        Log.i(TAG, "Ayarlar veya arama geçmişi güncellendi -> Activity yeniden yükleniyor (recreate)")
                        act.recreate()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Activity recreate hatası: ${e.message}")
            }
        }
    }

    // ==================== Key Sınıflandırma ====================

    /**
     * SharedPreferences key'ini hangi SyncDataType'a ait olduğunu belirler.
     * DataExtractor'daki SyncDataType.fromKey ile aynı mantığı kullanır.
     */
    private fun classifyKey(key: String): SyncDataType {
        return SyncDataType.fromKey(key)
    }
}
