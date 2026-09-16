package com.cloudsync

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cloudsync.core.SyncManager
import com.cloudsync.core.SyncScheduler
import com.cloudsync.models.SyncConfig
import com.cloudsync.models.SyncDataType
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * CloudSync - CloudStream Senkronizasyon Eklentisi
 *
 * Bu eklenti, CloudStream verilerini Supabase üzerinden
 * birden fazla cihaz arasında senkronize etmenizi sağlar.
 *
 * Plugin CloudStream tarafından yüklendiğinde load() çağrılır.
 * Ayarlar sayfası registerSettings() ile kaydedilir.
 */
@CloudstreamPlugin
class CloudSyncPlugin : Plugin() {

    companion object {
        private const val TAG = "CloudSync"

        // UI Renkleri
        private const val COLOR_PRIMARY = "#6366F1"     // Indigo
        private const val COLOR_SUCCESS = "#22C55E"     // Green
        private const val COLOR_WARNING = "#F59E0B"     // Amber
        private const val COLOR_DANGER = "#EF4444"      // Red
        private const val COLOR_BG_DARK = "#1A1B2E"     // Dark bg
        private const val COLOR_CARD_BG = "#252742"     // Card bg
        private const val COLOR_TEXT = "#E2E8F0"        // Light text
        private const val COLOR_TEXT_DIM = "#94A3B8"    // Dim text
    }

    private var activity: AppCompatActivity? = null
    private var syncManager: SyncManager? = null
    private var syncScheduler: SyncScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun load(context: Context) {
        activity = context as? AppCompatActivity
        syncManager = SyncManager(context)
        syncScheduler = SyncScheduler(context).also { scheduler ->
            // Otomatik senkronizasyon aktifse zamanlayıcıyı başlat
            if (SyncConfig.isAutoSyncEnabled(context)) {
                scheduler.start(syncManager!!)
            }
        }

        // Uygulama açılışında senkronize et (ayar aktifse)
        if (SyncConfig.isSyncOnLaunchEnabled(context) && SyncConfig.isConfigured(context)) {
            scope.launch(Dispatchers.IO) {
                Log.i(TAG, "Açılışta otomatik senkronizasyon başlatılıyor...")
                syncManager?.syncAll()
            }
        }

        // Ayarlar butonunu kaydet
        openSettings = { context ->
            (context as? AppCompatActivity)?.let { activity ->
                showSyncDialog(activity)
            }
        }

        Log.i(TAG, "CloudSync eklentisi yüklendi!")
    }

    /**
     * Ana senkronizasyon dialog'unu gösterir.
     */
    private fun showSyncDialog(activity: AppCompatActivity) {
        val context = activity as Context
        val scrollView = ScrollView(context).apply {
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor(COLOR_BG_DARK))
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        // ====== BAŞLIK ======
        mainLayout.addView(createHeader(context))

        // ====== DURUM KARTI ======
        mainLayout.addView(createStatusCard(context))

        // ====== SENKRON BUTONLARI ======
        mainLayout.addView(createSyncButtons(context, activity))

        // ====== AYARLAR BÖLÜMÜ ======
        mainLayout.addView(createSettingsSection(context, activity))

        // ====== VERİ TİPLERİ ======
        mainLayout.addView(createDataTypesSection(context))

        // ====== TEHLİKELİ BÖLGE ======
        mainLayout.addView(createDangerZone(context, activity))

        scrollView.addView(mainLayout)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(scrollView)
            .show()
            .apply {
                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
    }

    // ==================== UI Bileşenleri ====================

    private fun createHeader(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))

            addView(TextView(context).apply {
                text = "☁️ CloudSync"
                textSize = 24f
                setTextColor(Color.parseColor(COLOR_PRIMARY))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(TextView(context).apply {
                text = "v1.0 • Açık Kaynak Senkronizasyon"
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_TEXT_DIM))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun createStatusCard(context: Context): LinearLayout {
        val isConfigured = SyncConfig.isConfigured(context)
        val lastSync = SyncConfig.getLastSyncTime(context)
        val statusColor = if (isConfigured) COLOR_SUCCESS else COLOR_WARNING

        return createCard(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(context).apply {
                    text = if (isConfigured) "●" else "○"
                    textSize = 16f
                    setTextColor(Color.parseColor(statusColor))
                    setPadding(0, 0, dp(8), 0)
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                    addView(TextView(context).apply {
                        text = if (isConfigured) "Bağlı" else "Yapılandırılmamış"
                        textSize = 15f
                        setTextColor(Color.parseColor(COLOR_TEXT))
                        setTypeface(null, Typeface.BOLD)
                    })

                    addView(TextView(context).apply {
                        text = if (lastSync > 0) {
                            "Son sync: ${formatTime(lastSync)}"
                        } else {
                            "Henüz senkronize edilmedi"
                        }
                        textSize = 12f
                        setTextColor(Color.parseColor(COLOR_TEXT_DIM))
                    })

                    if (isConfigured) {
                        addView(TextView(context).apply {
                            text = "Cihaz: ${SyncConfig.getDeviceId(context)}"
                            textSize = 11f
                            setTextColor(Color.parseColor(COLOR_TEXT_DIM))
                        })
                    }
                })
            })
        }
    }

    private fun createSyncButtons(
        context: Context,
        activity: AppCompatActivity
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))

            // Ana Sync Butonu
            addView(createButton(context, "🔄 Tam Senkronizasyon", COLOR_PRIMARY) {
                performSync(activity, "full")
            })

            // Yükle / İndir
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)

                addView(createButton(context, "⬆ Yükle", COLOR_SUCCESS).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        marginEnd = dp(4)
                    }
                    setOnClickListener { performSync(activity, "upload") }
                })

                addView(createButton(context, "⬇ İndir", "#3B82F6").apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        marginStart = dp(4)
                    }
                    setOnClickListener { performSync(activity, "download") }
                })
            })
        }
    }

    private fun createSettingsSection(
        context: Context,
        activity: AppCompatActivity
    ): LinearLayout {
        return createCard(context).apply {
            addView(createSectionTitle(context, "⚙️ Supabase Ayarları"))

            // URL
            addView(createSettingRow(context, "API URL", SyncConfig.getSupabaseUrl(context).ifEmpty { "Girilmedi" }) {
                showInputDialog(activity, "Supabase URL", SyncConfig.getSupabaseUrl(context), "https://xxxxx.supabase.co") { value ->
                    SyncConfig.setSupabaseUrl(context, value)
                    showSyncDialog(activity) // Yenile
                }
            })

            // Key
            addView(createSettingRow(context, "API Key", maskString(SyncConfig.getSupabaseKey(context))) {
                showInputDialog(activity, "Supabase Anon Key", SyncConfig.getSupabaseKey(context), "eyJhbGc...") { value ->
                    SyncConfig.setSupabaseKey(context, value)
                    showSyncDialog(activity)
                }
            })

            // User ID
            addView(createSettingRow(context, "Kullanıcı ID", SyncConfig.getUserId(context).ifEmpty { "Girilmedi" }) {
                showInputDialog(activity, "Kullanıcı ID", SyncConfig.getUserId(context), "Tüm cihazlarda aynı ID kullanın") { value ->
                    SyncConfig.setUserId(context, value)
                    showSyncDialog(activity)
                }
            })

            // Bağlantı Testi
            addView(createButton(context, "🔗 Bağlantıyı Test Et", COLOR_PRIMARY) {
                testConnection(activity)
            }.apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            })

            // Otomatik sync ayarları
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(12)
                }
                setBackgroundColor(Color.parseColor("#374151"))
            })

            addView(createSectionTitle(context, "🔄 Otomatik Senkronizasyon"))

            addView(createToggleRow(context, "Otomatik Sync", SyncConfig.isAutoSyncEnabled(context)) { enabled ->
                SyncConfig.setAutoSync(context, enabled)
                if (enabled) {
                    syncScheduler?.start(syncManager!!)
                } else {
                    syncScheduler?.stop()
                }
            })

            addView(createToggleRow(context, "Açılışta Sync", SyncConfig.isSyncOnLaunchEnabled(context)) { enabled ->
                SyncConfig.setSyncOnLaunch(context, enabled)
            })

            addView(createToggleRow(context, "Kapanışta Sync", SyncConfig.isSyncOnExitEnabled(context)) { enabled ->
                SyncConfig.setSyncOnExit(context, enabled)
            })

            // Sync aralığı
            addView(createSettingRow(
                context,
                "Sync Aralığı",
                "${SyncConfig.getSyncIntervalMinutes(context)} dakika"
            ) {
                showIntervalDialog(activity)
            })
        }
    }

    private fun createDataTypesSection(context: Context): LinearLayout {
        return createCard(context).apply {
            addView(createSectionTitle(context, "📦 Senkronize Edilecek Veriler"))

            val stats = syncManager?.getLocalStats() ?: emptyMap()

            for (dataType in SyncDataType.entries) {
                val count = stats[dataType] ?: 0
                val enabled = SyncConfig.isSyncEnabled(context, dataType)

                addView(createToggleRow(
                    context,
                    "${dataType.displayName} ($count öğe)",
                    enabled
                ) { isChecked ->
                    SyncConfig.setSyncEnabled(context, dataType, isChecked)
                })
            }
        }
    }

    private fun createDangerZone(
        context: Context,
        activity: AppCompatActivity
    ): LinearLayout {
        return createCard(context).apply {
            addView(createSectionTitle(context, "⚠️ Tehlikeli Bölge"))

            addView(createButton(context, "🗑 Bulut Verilerini Sil", COLOR_DANGER) {
                AlertDialog.Builder(activity)
                    .setTitle("Emin misiniz?")
                    .setMessage("Buluttaki TÜM senkronizasyon verileriniz silinecek. Bu işlem geri alınamaz!")
                    .setPositiveButton("Sil") { _, _ ->
                        scope.launch {
                            val result = syncManager?.deleteCloudData()
                            activity.runOnUiThread {
                                Toast.makeText(
                                    context,
                                    result?.message ?: "İşlem tamamlandı",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            })

            addView(createButton(context, "🔧 Ayarları Sıfırla", COLOR_WARNING) {
                AlertDialog.Builder(activity)
                    .setTitle("Emin misiniz?")
                    .setMessage("CloudSync eklentisinin tüm ayarları sıfırlanacak.")
                    .setPositiveButton("Sıfırla") { _, _ ->
                        SyncConfig.clearAll(context)
                        syncScheduler?.stop()
                        Toast.makeText(context, "Ayarlar sıfırlandı", Toast.LENGTH_SHORT).show()
                        showSyncDialog(activity)
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            })
        }
    }

    // ==================== UI Yardımcıları ====================

    private fun createCard(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_CARD_BG))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun createSectionTitle(context: Context, title: String): TextView {
        return TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor(COLOR_TEXT))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun createButton(
        context: Context,
        text: String,
        color: String,
        onClick: (() -> Unit)? = null
    ): Button {
        return Button(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(color))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            )
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun createSettingRow(
        context: Context,
        label: String,
        value: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(context).apply {
                this.text = label
                textSize = 13f
                setTextColor(Color.parseColor(COLOR_TEXT))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(TextView(context).apply {
                this.text = value
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_TEXT_DIM))
                maxLines = 1
            })

            addView(TextView(context).apply {
                this.text = " ›"
                textSize = 14f
                setTextColor(Color.parseColor(COLOR_TEXT_DIM))
            })
        }
    }

    private fun createToggleRow(
        context: Context,
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))

            addView(TextView(context).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor(COLOR_TEXT))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(Switch(context).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            })
        }
    }

    // ==================== Dialog'lar ====================

    private fun showInputDialog(
        activity: AppCompatActivity,
        title: String,
        currentValue: String,
        hint: String,
        onSave: (String) -> Unit
    ) {
        val input = EditText(activity).apply {
            setText(currentValue)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    onSave(value)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showIntervalDialog(activity: AppCompatActivity) {
        val options = arrayOf("5 dakika", "15 dakika", "30 dakika", "60 dakika", "120 dakika")
        val values = intArrayOf(5, 15, 30, 60, 120)
        val current = SyncConfig.getSyncIntervalMinutes(activity)
        val currentIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("Senkronizasyon Aralığı")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                SyncConfig.setSyncIntervalMinutes(activity, values[which])
                if (syncScheduler?.isSchedulerRunning() == true) {
                    syncScheduler?.stop()
                    syncScheduler?.start(syncManager!!)
                }
                dialog.dismiss()
                showSyncDialog(activity)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ==================== Sync İşlemleri ====================

    private fun performSync(activity: AppCompatActivity, mode: String) {
        if (!SyncConfig.isConfigured(activity)) {
            Toast.makeText(activity, "⚠ Önce Supabase ayarlarını yapılandırın!", Toast.LENGTH_LONG).show()
            return
        }

        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Senkronize Ediliyor...")
            .setMessage("Lütfen bekleyin...")
            .setCancelable(false)
            .show()

        scope.launch {
            val result = when (mode) {
                "upload" -> syncManager?.uploadOnly()
                "download" -> syncManager?.downloadOnly()
                else -> syncManager?.syncAll()
            }

            activity.runOnUiThread {
                progressDialog.dismiss()

                val icon = if (result?.success == true) "✅" else "❌"
                Toast.makeText(
                    activity,
                    "$icon ${result?.message ?: "İşlem tamamlandı"}",
                    Toast.LENGTH_LONG
                ).show()

                // Dialog'u yenile (son sync zamanı güncellenmiş olacak)
                if (result?.success == true) {
                    showSyncDialog(activity)
                }
            }
        }
    }

    private fun testConnection(activity: AppCompatActivity) {
        if (!SyncConfig.isConfigured(activity)) {
            Toast.makeText(activity, "⚠ Önce Supabase ayarlarını girin!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "Bağlantı test ediliyor...", Toast.LENGTH_SHORT).show()

        scope.launch {
            val result = syncManager?.testConnection()
            activity.runOnUiThread {
                if (result?.isSuccess == true) {
                    Toast.makeText(activity, "✅ Bağlantı başarılı!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        activity,
                        "❌ Bağlantı hatası: ${result?.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ==================== Yardımcı Fonksiyonlar ====================

    private fun dp(dp: Int): Int {
        val context = activity ?: return dp
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun maskString(str: String): String {
        if (str.isEmpty()) return "Girilmedi"
        if (str.length <= 8) return "****"
        return str.take(4) + "..." + str.takeLast(4)
    }

    private fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "Az önce"
            diff < 3_600_000 -> "${diff / 60_000} dakika önce"
            diff < 86_400_000 -> "${diff / 3_600_000} saat önce"
            else -> "${diff / 86_400_000} gün önce"
        }
    }
}
