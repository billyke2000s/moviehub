-- Run once to add the encrypted credential vault + synced prefs:
--   wrangler d1 execute moviehub --remote --file=migrate-vault.sql
CREATE TABLE IF NOT EXISTS vault (
    profile_id INTEGER NOT NULL,
    key_name   TEXT NOT NULL,
    enc_value  TEXT NOT NULL,
    PRIMARY KEY (profile_id, key_name)
);
CREATE TABLE IF NOT EXISTS prefs (
    profile_id INTEGER NOT NULL,
    pref_name  TEXT NOT NULL,
    pref_value TEXT DEFAULT '',
    PRIMARY KEY (profile_id, pref_name)
);
