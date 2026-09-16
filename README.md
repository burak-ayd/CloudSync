# ☁️ CloudSync - CloudStream Senkronizasyon Eklentisi

**Açık kaynak**, çoklu cihaz senkronizasyon eklentisi. Telefon, tablet ve TV arasında CloudStream verilerinizi senkronize edin.

## ✨ Özellikler

- 🔖 **Favoriler/Kaydedilenler** senkronizasyonu
- ▶️ **Kaldığın yerden devam et** (izleme pozisyonu)
- 🔍 **Arama geçmişi** senkronizasyonu
- 📦 **Eklenti depoları** senkronizasyonu
- ⚙️ **Uygulama ayarları** senkronizasyonu
- 🔄 **Otomatik** veya **manuel** senkronizasyon
- ⚡ **Timestamp tabanlı** çakışma çözümleme
- 🔒 **Güvenli**: Hassas veriler (token, şifre) asla senkronize edilmez

## ☁️ Desteklenen Bulut Servisleri

| Servis | Durum | Açıklama |
|--------|-------|----------|
| Supabase | ✅ Aktif | Ücretsiz tier, kolay kurulum |
| Google Drive | 🔜 Planlanan | v2.0 |
| Firebase | 🔜 Planlanan | v3.0 |

## 🚀 Kurulum

### 1. Supabase Projesi Oluşturun

1. [supabase.com](https://supabase.com) adresine gidin ve ücretsiz hesap oluşturun
2. "New Project" butonuna tıklayın
3. Proje adı ve şifre belirleyin (bölge olarak size en yakın olanı seçin)
4. Proje oluşturulduktan sonra **SQL Editor**'a gidin
5. [`supabase_setup.sql`](supabase_setup.sql) dosyasındaki SQL'i kopyalayıp çalıştırın

### 2. API Bilgilerini Alın

1. Supabase Dashboard'da **Settings** > **API** bölümüne gidin
2. **Project URL**'i not alın (örn: `https://xxxxx.supabase.co`)
3. **anon/public** key'i not alın (uzun bir JWT token)

### 3. CloudSync Eklentisini Yükleyin

1. Bu repo'yu CloudStream eklenti deposu olarak ekleyin
2. CloudSync eklentisini yükleyin
3. Eklenti ayarlarını açın (⚙️ ikonu)
4. Supabase URL ve API Key'i girin
5. Bir **Kullanıcı ID** belirleyin (tüm cihazlarınızda aynı ID'yi kullanın!)

### 4. Senkronize Edin!

- **Tam Sync**: Her iki yönde senkronize eder (yükle + indir + çakışma çöz)
- **Yükle**: Sadece bu cihazdaki verileri buluta yükler
- **İndir**: Sadece buluttaki verileri bu cihaza indirir

## ⚙️ Ayarlar

### Otomatik Senkronizasyon

- **Otomatik Sync**: Belirli aralıklarla arka planda senkronize eder
- **Açılışta Sync**: Uygulama her açıldığında otomatik senkronize eder
- **Kapanışta Sync**: Uygulama kapanırken otomatik senkronize eder
- **Sync Aralığı**: 5, 15, 30, 60 veya 120 dakika

### Veri Tipleri

Her veri tipini ayrı ayrı açıp kapatabilirsiniz:
- Favoriler/Kaydedilenler
- Kaldığın Yerden Devam
- Arama Geçmişi
- Eklentiler & Depolar
- Uygulama Ayarları

## 🔒 Güvenlik

- Supabase bilgileri sadece cihazınızda saklanır
- Hesap token'ları, şifreler ve cihaza özel ayarlar **asla** senkronize edilmez
- Kendi Supabase projenizi kullanırsınız, verileriniz sadece sizin kontrolünüzde

### Senkronize Edilmeyen Veriler

- AniList/MAL/Kitsu/Simkl token'ları
- Biyometrik kilit ayarları
- İndirme yolu ayarları
- Yedekleme yolu ayarları
- Plugin binary dosyaları

## 🏗️ Derleme (Build)

```bash
# Eklentiyi derle
./gradlew CloudSync:make

# Cihaza yükle (ADB ile)
./gradlew CloudSync:deployWithAdb
```

## 📁 Proje Yapısı

```
CloudSync/
├── build.gradle.kts                        # Plugin build yapılandırması
├── src/main/
│   ├── AndroidManifest.xml                 # İzinler
│   └── kotlin/com/cloudsync/
│       ├── CloudSyncPlugin.kt              # Ana plugin + Ayarlar UI
│       ├── core/
│       │   ├── SyncManager.kt              # Senkronizasyon yöneticisi
│       │   ├── DataExtractor.kt            # SharedPrefs veri çıkarma/yazma
│       │   ├── ConflictResolver.kt         # Çakışma çözümleme (LWW)
│       │   └── SyncScheduler.kt            # Otomatik sync zamanlayıcı
│       ├── models/
│       │   ├── SyncData.kt                 # Veri modelleri
│       │   └── SyncConfig.kt               # Yapılandırma yönetimi
│       └── providers/
│           ├── SyncProvider.kt             # Provider arayüzü
│           └── SupabaseProvider.kt         # Supabase implementasyonu
├── supabase_setup.sql                      # Veritabanı kurulum SQL'i
└── README.md                               # Bu dosya
```

## 🤝 Katkıda Bulunma

1. Fork'layın
2. Feature branch oluşturun (`git checkout -b feature/google-drive-support`)
3. Değişiklikleri commit edin (`git commit -m 'feat: Google Drive desteği eklendi'`)
4. Push edin (`git push origin feature/google-drive-support`)
5. Pull Request açın

### Yeni Bulut Servisi Ekleme

Yeni bir bulut servisi eklemek için:

1. `SyncProvider` interface'ini implemente eden yeni bir sınıf oluşturun
2. `SyncManager`'da provider seçim mantığını ekleyin
3. `CloudSyncPlugin`'da ayarlar UI'ına yeni seçenekler ekleyin

## 📄 Lisans

MIT License - Açık kaynak, istediğiniz gibi kullanabilirsiniz.

## 🙏 Teşekkürler

- [CloudStream](https://github.com/recloudstream/cloudstream) - Harika medya oynatıcı
- [Supabase](https://supabase.com) - Ücretsiz ve açık kaynak backend
