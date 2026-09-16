-- =============================================================
-- CloudSync - Supabase Kurulum SQL'i
-- =============================================================
-- Bu SQL'i Supabase Dashboard > SQL Editor'da çalıştırın.
-- Tüm tabloları ve gerekli politikaları oluşturur.
-- =============================================================

-- 1. Senkronizasyon verileri tablosu
CREATE TABLE IF NOT EXISTS sync_data (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    data_type TEXT NOT NULL,
    data_key TEXT NOT NULL,
    data_value TEXT,
    updated_at TIMESTAMPTZ DEFAULT NOW(),

    -- Her kullanıcı için aynı veri tipi + key kombinasyonu tekil olmalı
    UNIQUE(user_id, data_type, data_key)
);

-- 2. Senkronizasyon log tablosu
CREATE TABLE IF NOT EXISTS sync_log (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    action TEXT NOT NULL,
    data_type TEXT,
    items_count INTEGER DEFAULT 0,
    synced_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. İndeksler (performans için)
CREATE INDEX IF NOT EXISTS idx_sync_data_user_id ON sync_data(user_id);
CREATE INDEX IF NOT EXISTS idx_sync_data_user_type ON sync_data(user_id, data_type);
CREATE INDEX IF NOT EXISTS idx_sync_data_updated ON sync_data(updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_sync_log_user_id ON sync_log(user_id);
CREATE INDEX IF NOT EXISTS idx_sync_log_synced ON sync_log(synced_at DESC);

-- 4. updated_at otomatik güncelleme fonksiyonu
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_sync_data_updated_at
    BEFORE UPDATE ON sync_data
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- 5. Row Level Security (Opsiyonel - Güvenlik)
-- NOT: CloudSync, Supabase anon key ile çalışır.
-- Eğer RLS kullanmak isterseniz aşağıdaki satırları aktif edin.
-- Aksi takdirde anon key'e sahip herkes verilere erişebilir.
-- Kişisel kullanım için bu bir sorun değildir.

ALTER TABLE sync_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_log ENABLE ROW LEVEL SECURITY;

-- Anon key ile erişime izin ver (kişisel kullanım için)
CREATE POLICY "Allow all access" ON sync_data FOR ALL USING (true);
CREATE POLICY "Allow all access" ON sync_log FOR ALL USING (true);

-- =============================================================
-- Kurulum tamamlandı! ✅
-- Şimdi Supabase Dashboard'dan:
-- 1. Settings > API > URL'i kopyalayın
-- 2. Settings > API > anon/public key'i kopyalayın
-- 3. CloudSync eklenti ayarlarına yapıştırın
-- =============================================================
