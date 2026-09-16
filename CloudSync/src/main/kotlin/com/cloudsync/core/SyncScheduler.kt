package com.cloudsync.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cloudsync.models.SyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Otomatik senkronizasyon zamanlayıcısı.
 *
 * Kullanıcı isterse belirli aralıklarla otomatik senkronizasyon başlatır.
 * Handler tabanlı çalışır (WorkManager veya AlarmManager CloudStream plugin
 * ortamında kullanılamayacağı için).
 */
class SyncScheduler(private val context: Context) {

    companion object {
        private const val TAG = "CloudSync.Scheduler"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncManager: SyncManager? = null
    private var isRunning = false

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!SyncConfig.isAutoSyncEnabled(context)) {
                stop()
                return
            }

            Log.i(TAG, "Otomatik senkronizasyon başlatılıyor...")
            scope.launch {
                try {
                    syncManager?.syncAll()
                } catch (e: Exception) {
                    Log.e(TAG, "Otomatik senkronizasyon hatası", e)
                }
            }

            // Sonraki çalıştırmayı planla
            val intervalMs = SyncConfig.getSyncIntervalMinutes(context) * 60 * 1000L
            handler.postDelayed(this, intervalMs)
        }
    }

    /**
     * Otomatik senkronizasyonu başlatır.
     */
    fun start(manager: SyncManager) {
        if (isRunning) {
            Log.d(TAG, "Zamanlayıcı zaten çalışıyor")
            return
        }

        if (!SyncConfig.isAutoSyncEnabled(context)) {
            Log.d(TAG, "Otomatik senkronizasyon devre dışı")
            return
        }

        syncManager = manager
        isRunning = true

        val intervalMs = SyncConfig.getSyncIntervalMinutes(context) * 60 * 1000L
        handler.postDelayed(syncRunnable, intervalMs)

        Log.i(TAG, "Otomatik senkronizasyon başlatıldı: her ${SyncConfig.getSyncIntervalMinutes(context)} dakikada bir")
    }

    /**
     * Otomatik senkronizasyonu durdurur.
     */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(syncRunnable)
        Log.i(TAG, "Otomatik senkronizasyon durduruldu")
    }

    /**
     * Zamanlayıcının çalışıp çalışmadığını döndürür.
     */
    fun isSchedulerRunning(): Boolean = isRunning
}
