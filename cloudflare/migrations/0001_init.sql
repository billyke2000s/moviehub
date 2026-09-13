-- Movie Hub — D1 schema
-- Apply with: wrangler d1 execute moviehub --file=schema.sql

CREATE TABLE IF NOT EXISTS accounts (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    username       TEXT UNIQUE NOT NULL,
    pw_hash        TEXT NOT NULL,
    tmdb_key       TEXT DEFAULT '',
    premiumize_key TEXT DEFAULT '',
    created_at     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS profiles (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    name       TEXT NOT NULL,
    avatar     TEXT DEFAULT '',
    trakt_token TEXT DEFAULT '',
    last_notif_check INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
    token      TEXT PRIMARY KEY,
    account_id INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS progress (
    profile_id INTEGER NOT NULL,
    media_id   TEXT NOT NULL,
    title      TEXT DEFAULT '',
    position   INTEGER DEFAULT 0,
    duration   INTEGER DEFAULT 0,
    completed  INTEGER DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (profile_id, media_id)
);

CREATE INDEX IF NOT EXISTS idx_profiles_account ON profiles(account_id);
CREATE INDEX IF NOT EXISTS idx_tokens_account ON tokens(account_id);

CREATE TABLE IF NOT EXISTS auth_attempts (
    client_key TEXT NOT NULL,
    window_id  INTEGER NOT NULL,
    attempts   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (client_key, window_id)
);

CREATE TABLE IF NOT EXISTS watchlist (
    profile_id INTEGER NOT NULL,
    media_id   TEXT NOT NULL,
    media_type TEXT DEFAULT 'movie',
    tmdb_id    TEXT DEFAULT '',
    title      TEXT DEFAULT '',
    poster     TEXT DEFAULT '',
    added_at   INTEGER NOT NULL,
    PRIMARY KEY (profile_id, media_id)
);

-- Optional Trakt token per profile (added; safe to re-run)
-- SQLite can't ADD COLUMN IF NOT EXISTS, so this is applied via migration below.

CREATE TABLE IF NOT EXISTS notif_dismissed (
    profile_id INTEGER NOT NULL,
    notif_id   TEXT NOT NULL,
    PRIMARY KEY (profile_id, notif_id)
);

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
