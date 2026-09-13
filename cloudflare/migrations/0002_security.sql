CREATE TABLE IF NOT EXISTS auth_attempts (
    client_key TEXT NOT NULL,
    window_id  INTEGER NOT NULL,
    attempts   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (client_key, window_id)
);

-- Version 3.1 stores hashes in the existing token column. Existing plaintext
-- sessions intentionally expire during this security upgrade.
DELETE FROM tokens;
