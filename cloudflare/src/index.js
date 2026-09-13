/**
 * Movie Hub — Cloudflare Worker
 * =============================
 *
 * The same accounts server as the FastAPI version, rebuilt to run on Cloudflare
 * so there's no VPS to secure. Uses:
 *   • D1  — the SQLite database (accounts, profiles, keys, progress)
 *   • R2  — object storage for profile pictures
 *
 * Cloudflare provides HTTPS, the domain (*.workers.dev or your own), DDoS
 * protection, and uptime. Nothing for you to secure at the OS level.
 *
 * SECURITY IN THE CODE (same guarantees as the VPS version):
 *   • passwords hashed with PBKDF2 (Web Crypto — bcrypt isn't available in
 *     Workers, so we use a strong salted PBKDF2 with a high iteration count,
 *     which is the standard Workers approach and equally non-reversible)
 *   • login returns a token; the token is sent after that, not the password
 *   • login + register rate-limited per IP
 *   • an app-secret header (X-App-Key) — only the add-on can talk to the API
 *   • uploaded images verified as real images (magic-byte check) and stored
 *     under random names in R2; the add-on re-encodes them first (stripping all
 *     metadata/GPS), so the two ends together give the same guarantee as the
 *     VPS Pillow re-encode. Old images are deleted on replace/delete — no
 *     pile-up, and nothing currently in use is ever removed.
 *
 * Bindings (set in wrangler.toml):
 *   DB          — D1 database
 *   AVATARS     — R2 bucket
 *   APP_SECRET  — secret (wrangler secret put APP_SECRET)
 */

// ── small helpers ────────────────────────────────────────────────────────────

const JSON_HEADERS = { "Content-Type": "application/json" };

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
function err(message, status = 400) {
  return json({ ok: false, error: message }, status);
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let out = 0;
  for (let i = 0; i < a.length; i++) out |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return out === 0;
}

// ── password hashing (PBKDF2 via Web Crypto) ─────────────────────────────────

const PBKDF2_ITERATIONS = 600000;

async function hashPassword(password, saltHex, iterations = PBKDF2_ITERATIONS) {
  const saltBytes = saltHex ? hexToBytes(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: saltBytes.buffer, iterations, hash: "SHA-256" },
    key, 256
  );
  const hashHex = bytesToHex(new Uint8Array(bits));
  const sHex = bytesToHex(saltBytes);
  return `pbkdf2$${iterations}$${sHex}$${hashHex}`;
}

async function verifyPassword(password, stored) {
  try {
    const [, rounds, sHex, expected] = stored.split("$");
    const iterations = Number(rounds);
    if (!Number.isSafeInteger(iterations) || iterations < 100000) return false;
    const recomputed = await hashPassword(password, sHex, iterations);
    const [, , , got] = recomputed.split("$");
    return timingSafeEqual(got, expected);
  } catch (_) {
    return false;
  }
}

function bytesToHex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}
function randomToken(n = 32) {
  return bytesToHex(crypto.getRandomValues(new Uint8Array(n)));
}


// ── credential encryption (AES-GCM, key from ENC_KEY worker secret) ──────────
// Keys are stored encrypted at rest. Only the Worker (with ENC_KEY) can decrypt,
// and only hands a decrypted value back to an authenticated device.

async function _encKey(env) {
  if (typeof env.ENC_KEY !== "string" || env.ENC_KEY.length === 0) {
    throw new Error("ENC_KEY is missing");
  }
  const raw = new TextEncoder().encode(env.ENC_KEY.padEnd(32, "0").slice(0, 32));
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

async function encryptValue(env, plaintext) {
  if (!plaintext) return "";
  const key = await _encKey(env);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key,
             new TextEncoder().encode(plaintext));
  return bytesToHex(iv) + ":" + bytesToHex(new Uint8Array(ct));
}

async function decryptValue(env, stored) {
  if (!stored || !stored.includes(":")) return "";
  try {
    const [ivHex, ctHex] = stored.split(":");
    const key = await _encKey(env);
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: hexToBytes(ivHex) }, key, hexToBytes(ctHex));
    return new TextDecoder().decode(pt);
  } catch (_) {
    return "";
  }
}

async function decryptCompatible(env, stored) {
  if (!stored) return "";
  // Version 1 stored these two account fields as plaintext. Reading them here
  // keeps existing installs working; the next save converts them to AES-GCM.
  return stored.includes(":") ? await decryptValue(env, stored) : stored;
}

// ── rate limiting (per IP, in-memory per isolate — good enough for home use) ──

const RL = new Map();
function rateLimit(ip) {
  const now = Date.now();
  const WINDOW = 60000, MAX = 8;
  const hits = (RL.get(ip) || []).filter((t) => now - t < WINDOW);
  if (hits.length >= MAX) return false;
  hits.push(now);
  RL.set(ip, hits);
  return true;
}

// ── auth guards ──────────────────────────────────────────────────────────────

function requireAppKey(request, env) {
  const key = (request.headers.get("X-App-Key") || "").trim();
  const expected = (env.APP_SECRET || "").trim();
  return expected.length > 0 && key.length > 0 && timingSafeEqual(key, expected);
}

async function accountFromToken(request, env) {
  const auth = request.headers.get("Authorization") || "";
  const token = auth.replace("Bearer ", "").trim();
  if (!token) return null;
  const row = await env.DB.prepare(
    `SELECT a.* FROM accounts a JOIN tokens t ON t.account_id = a.id WHERE t.token = ?`
  ).bind(token).first();
  return row || null;
}

// ── account shape returned to the add-on (never the password hash) ───────────

async function accountPublic(env, acc) {
  const { results } = await env.DB.prepare(
    `SELECT id, name, avatar FROM profiles WHERE account_id = ? ORDER BY created_at`
  ).bind(acc.id).all();
  const tmdb = await decryptCompatible(env, acc.tmdb_key || "");
  const premiumize = await decryptCompatible(env, acc.premiumize_key || "");
  return {
    username: acc.username,
    has_keys: !!(tmdb && premiumize),
    keys: { tmdb, premiumize },
    profiles: results || [],
  };
}

async function issueLogin(env, username) {
  const acc = await env.DB.prepare(`SELECT * FROM accounts WHERE username = ?`)
    .bind(username).first();
  const token = randomToken();
  await env.DB.prepare(`INSERT INTO tokens (token, account_id, created_at) VALUES (?, ?, ?)`)
    .bind(token, acc.id, Date.now()).run();
  return { token, account: await accountPublic(env, acc) };
}

// ── image sanitising (magic-byte verify; add-on already re-encoded) ──────────

function detectImageType(bytes) {
  // PNG
  if (bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 &&
      bytes[2] === 0x4e && bytes[3] === 0x47) return "image/png";
  // JPEG
  if (bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 &&
      bytes[2] === 0xff) return "image/jpeg";
  // GIF
  if (bytes.length > 6 && bytes[0] === 0x47 && bytes[1] === 0x49 &&
      bytes[2] === 0x46) return "image/gif";
  // WEBP (RIFF....WEBP)
  if (bytes.length > 12 && bytes[0] === 0x52 && bytes[1] === 0x49 &&
      bytes[8] === 0x57 && bytes[9] === 0x45) return "image/webp";
  return null;
}

// ─────────────────────────────────────────────────────────────────────────────
// ROUTER
// ─────────────────────────────────────────────────────────────────────────────

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;
    const ip = request.headers.get("CF-Connecting-IP") || "?";

    try {
      // public health check
      if (path === "/health") return json({ ok: true });

      // avatar serving is public (inert image data), everything else needs app key

      if (path.startsWith("/avatar/") && method === "GET") {
        return await serveAvatar(env, path.slice("/avatar/".length));
      }

      if (!requireAppKey(request, env)) return err("Those connection details were not accepted.", 403);

      // ── auth ──
      if (path === "/register" && method === "POST") {
        if (!rateLimit(ip)) return err("Too many attempts — wait a minute.", 429);
        const body = await request.json();
        return await register(env, body);
      }
      if (path === "/login" && method === "POST") {
        if (!rateLimit(ip)) return err("Too many attempts — wait a minute.", 429);
        const body = await request.json();
        return await login(env, body);
      }

      // everything below needs a valid token
      const acc = await accountFromToken(request, env);
      if (!acc) {
        return err("Session expired — please log in again.", 401);
      }

      if (path === "/logout" && method === "POST") return await logout(request, env);
      if (path === "/me" && method === "GET") return json({ account: await accountPublic(env, acc) });
      if (path === "/keys" && method === "POST") return await saveKeys(env, acc, await request.json());

      if (path === "/profiles" && method === "POST") return await addProfile(env, acc, await request.json());
      const pMatch = path.match(/^\/profiles\/(\d+)$/);
      if (pMatch && method === "DELETE") return await deleteProfile(env, acc, +pMatch[1]);
      const aMatch = path.match(/^\/profiles\/(\d+)\/avatar$/);
      if (aMatch && method === "POST") return await setAvatar(env, acc, +aMatch[1], request);

      const gMatch = path.match(/^\/progress\/(\d+)$/);
      if (gMatch && method === "GET") return await getProgress(env, acc, +gMatch[1]);
      if (path === "/progress" && method === "POST") return await saveProgress(env, acc, await request.json());

      const wMatch = path.match(/^\/watchlist\/(\d+)$/);
      if (wMatch && method === "GET") return await getWatchlist(env, acc, +wMatch[1]);
      if (path === "/watchlist" && method === "POST") return await addWatchlist(env, acc, await request.json());
      if (path === "/watchlist" && method === "DELETE") return await removeWatchlist(env, acc, await request.json());

      if (path === "/trakt-token" && method === "POST") return await saveTraktToken(env, acc, await request.json());
      const tMatch = path.match(/^\/trakt-token\/(\d+)$/);
      if (tMatch && method === "GET") return await getTraktToken(env, acc, +tMatch[1]);

      const nMatch = path.match(/^\/notif-state\/(\d+)$/);
      if (nMatch && method === "GET") return await getNotifState(env, acc, +nMatch[1]);
      if (path === "/notif-check" && method === "POST") return await setNotifCheck(env, acc, await request.json());
      if (path === "/notif-dismiss" && method === "POST") return await dismissNotif(env, acc, await request.json());
      const vMatch = path.match(/^\/vault\/(\d+)$/);
      if (vMatch && method === "GET") return await getVault(env, acc, +vMatch[1]);
      if (path === "/vault" && method === "POST") return await setVault(env, acc, await request.json());
      const prMatch = path.match(/^\/prefs\/(\d+)$/);
      if (prMatch && method === "GET") return await getPrefs(env, acc, +prMatch[1]);
      if (path === "/prefs" && method === "POST") return await setPrefs(env, acc, await request.json());


      return err("Not found.", 404);
    } catch (e) {
      console.error("Movie Hub request failed", e);
      return err("The server could not complete that request.", 500);
    }
  },
};

// ── handlers ─────────────────────────────────────────────────────────────────

async function register(env, body) {
  const username = (body.username || "").toLowerCase().trim();
  const password = body.password || "";
  if (!/^[a-z0-9._-]{3,32}$/.test(username)) {
    return err("Use 3–32 letters, numbers, dots, dashes or underscores.");
  }
  if (password.length < 10 || password.length > 128) {
    return err("Password must be 10–128 characters.");
  }
  const exists = await env.DB.prepare(`SELECT 1 FROM accounts WHERE username = ?`)
    .bind(username).first();
  if (exists) return err("That username is taken.", 409);
  const pw = await hashPassword(password);
  await env.DB.prepare(
    `INSERT INTO accounts (username, pw_hash, tmdb_key, premiumize_key, created_at) VALUES (?, ?, '', '', ?)`
  ).bind(username, pw, Date.now()).run();
  return json(await issueLogin(env, username));
}

async function login(env, body) {
  const username = (body.username || "").toLowerCase().trim();
  const acc = await env.DB.prepare(`SELECT * FROM accounts WHERE username = ?`)
    .bind(username).first();
  const supplied = body.password || "";
  // Perform the same expensive derivation for unknown accounts so response
  // timing does not reveal whether a username exists.
  if (!acc) {
    await hashPassword(supplied, "00000000000000000000000000000000");
    return err("Wrong username or password.", 401);
  }
  if (!(await verifyPassword(supplied, acc.pw_hash))) {
    return err("Wrong username or password.", 401);
  }
  const rounds = Number((acc.pw_hash || "").split("$")[1] || 0);
  if (rounds < PBKDF2_ITERATIONS) {
    const upgraded = await hashPassword(supplied);
    await env.DB.prepare(`UPDATE accounts SET pw_hash = ? WHERE id = ?`)
      .bind(upgraded, acc.id).run();
  }
  return json(await issueLogin(env, username));
}

async function logout(request, env) {
  const token = (request.headers.get("Authorization") || "").replace("Bearer ", "").trim();
  await env.DB.prepare(`DELETE FROM tokens WHERE token = ?`).bind(token).run();
  return json({ ok: true });
}

async function saveKeys(env, acc, body) {
  const tmdb = await encryptValue(env, (body.tmdb || "").trim());
  const premiumize = await encryptValue(env, (body.premiumize || "").trim());
  await env.DB.prepare(`UPDATE accounts SET tmdb_key = ?, premiumize_key = ? WHERE id = ?`)
    .bind(tmdb, premiumize, acc.id).run();
  const fresh = await env.DB.prepare(`SELECT * FROM accounts WHERE id = ?`).bind(acc.id).first();
  return json({ account: await accountPublic(env, fresh) });
}

async function addProfile(env, acc, body) {
  const name = (body.name || "").trim();
  if (!name || name.length > 40) return err("Profile name must be 1–40 characters.");
  await env.DB.prepare(`INSERT INTO profiles (account_id, name, avatar, created_at) VALUES (?, ?, '', ?)`)
    .bind(acc.id, name, Date.now()).run();
  const fresh = await env.DB.prepare(`SELECT * FROM accounts WHERE id = ?`).bind(acc.id).first();
  return json({ account: await accountPublic(env, fresh) });
}

async function deleteProfile(env, acc, profileId) {
  const prof = await env.DB.prepare(`SELECT * FROM profiles WHERE id = ? AND account_id = ?`)
    .bind(profileId, acc.id).first();
  if (!prof) return err("No such profile.", 404);
  if (prof.avatar) await env.AVATARS.delete(prof.avatar).catch(() => {});
  await env.DB.prepare(`DELETE FROM progress WHERE profile_id = ?`).bind(profileId).run();
  await env.DB.prepare(`DELETE FROM profiles WHERE id = ?`).bind(profileId).run();
  const fresh = await env.DB.prepare(`SELECT * FROM accounts WHERE id = ?`).bind(acc.id).first();
  return json({ account: await accountPublic(env, fresh) });
}

async function setAvatar(env, acc, profileId, request) {
  const prof = await env.DB.prepare(`SELECT * FROM profiles WHERE id = ? AND account_id = ?`)
    .bind(profileId, acc.id).first();
  if (!prof) return err("No such profile.", 404);

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length === 0) return err("No image received.");
  if (bytes.length > 8 * 1024 * 1024) return err("That image is over 8 MB.", 413);

  const type = detectImageType(bytes);
  if (!type) return err("That file isn't a valid image.");

  // random name, keyed so we can find/replace it
  const ext = type === "image/png" ? "png" : type === "image/gif" ? "gif"
            : type === "image/webp" ? "webp" : "jpg";
  const name = randomToken(16) + "." + ext;

  await env.AVATARS.put(name, bytes, { httpMetadata: { contentType: type } });

  const old = prof.avatar;
  await env.DB.prepare(`UPDATE profiles SET avatar = ? WHERE id = ?`).bind(name, profileId).run();
  if (old && old !== name) await env.AVATARS.delete(old).catch(() => {});

  const fresh = await env.DB.prepare(`SELECT * FROM accounts WHERE id = ?`).bind(acc.id).first();
  return json({ avatar: name, account: await accountPublic(env, fresh) });
}

async function serveAvatar(env, name) {
  if (name.includes("/") || name.includes("..")) return err("Bad name.", 400);
  const obj = await env.AVATARS.get(name);
  if (!obj) return err("Not found.", 404);
  const headers = new Headers();
  headers.set("Content-Type", obj.httpMetadata?.contentType || "image/jpeg");
  headers.set("Cache-Control", "public, max-age=86400");
  return new Response(obj.body, { headers });
}


async function getProgress(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const { results } = await env.DB.prepare(
    `SELECT media_id, title, position, duration, completed, updated_at FROM progress WHERE profile_id = ?`
  ).bind(profileId).all();
  const out = {};
  for (const r of results || []) out[r.media_id] = r;
  return json({ progress: out });
}

async function saveProgress(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(
    `INSERT INTO progress (profile_id, media_id, title, position, duration, completed, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(profile_id, media_id) DO UPDATE SET
       title=excluded.title, position=excluded.position, duration=excluded.duration,
       completed=excluded.completed, updated_at=excluded.updated_at`
  ).bind(profileId, body.media_id, body.title || "", body.position | 0,
         body.duration | 0, body.completed ? 1 : 0, Date.now()).run();
  return json({ ok: true });
}


async function getWatchlist(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const { results } = await env.DB.prepare(
    `SELECT media_id, media_type, tmdb_id, title, poster, added_at FROM watchlist WHERE profile_id = ? ORDER BY added_at DESC`
  ).bind(profileId).all();
  return json({ watchlist: results || [] });
}

async function addWatchlist(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(
    `INSERT INTO watchlist (profile_id, media_id, media_type, tmdb_id, title, poster, added_at)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(profile_id, media_id) DO UPDATE SET title=excluded.title, poster=excluded.poster`
  ).bind(profileId, body.media_id, body.media_type || "movie", String(body.tmdb_id || ""),
         body.title || "", body.poster || "", Date.now()).run();
  return json({ ok: true });
}

async function removeWatchlist(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(`DELETE FROM watchlist WHERE profile_id = ? AND media_id = ?`)
    .bind(profileId, body.media_id).run();
  return json({ ok: true });
}


async function saveTraktToken(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(`UPDATE profiles SET trakt_token = ? WHERE id = ?`)
    .bind(body.token || "", profileId).run();
  return json({ ok: true });
}

async function getTraktToken(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const row = await env.DB.prepare(`SELECT trakt_token FROM profiles WHERE id = ?`)
    .bind(profileId).first();
  return json({ token: (row && row.trakt_token) || "" });
}


async function getNotifState(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const row = await env.DB.prepare(`SELECT last_notif_check FROM profiles WHERE id = ?`)
    .bind(profileId).first();
  const { results } = await env.DB.prepare(
    `SELECT notif_id FROM notif_dismissed WHERE profile_id = ?`).bind(profileId).all();
  return json({
    last_check: (row && row.last_notif_check) || 0,
    dismissed: (results || []).map(r => r.notif_id),
  });
}

async function setNotifCheck(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(`UPDATE profiles SET last_notif_check = ? WHERE id = ?`)
    .bind(body.ts | 0, profileId).run();
  return json({ ok: true });
}

async function dismissNotif(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  await env.DB.prepare(
    `INSERT OR IGNORE INTO notif_dismissed (profile_id, notif_id) VALUES (?, ?)`)
    .bind(profileId, String(body.notif_id || "")).run();
  return json({ ok: true });
}


async function getVault(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const { results } = await env.DB.prepare(
    `SELECT key_name, enc_value FROM vault WHERE profile_id = ?`).bind(profileId).all();
  const out = {};
  for (const r of results || []) out[r.key_name] = await decryptValue(env, r.enc_value);
  return json({ vault: out });
}

async function setVault(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const items = body.items || {};
  for (const k of Object.keys(items)) {
    const enc = await encryptValue(env, String(items[k] || ""));
    await env.DB.prepare(
      `INSERT INTO vault (profile_id, key_name, enc_value) VALUES (?, ?, ?)
       ON CONFLICT(profile_id, key_name) DO UPDATE SET enc_value=excluded.enc_value`
    ).bind(profileId, k, enc).run();
  }
  return json({ ok: true });
}

async function getPrefs(env, acc, profileId) {
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const { results } = await env.DB.prepare(
    `SELECT pref_name, pref_value FROM prefs WHERE profile_id = ?`).bind(profileId).all();
  const out = {};
  for (const r of results || []) out[r.pref_name] = r.pref_value;
  return json({ prefs: out });
}

async function setPrefs(env, acc, body) {
  const profileId = +body.profile_id;
  if (!(await ownsProfile(env, acc, profileId))) return err("That profile isn't yours.", 403);
  const items = body.items || {};
  for (const k of Object.keys(items)) {
    await env.DB.prepare(
      `INSERT INTO prefs (profile_id, pref_name, pref_value) VALUES (?, ?, ?)
       ON CONFLICT(profile_id, pref_name) DO UPDATE SET pref_value=excluded.pref_value`
    ).bind(profileId, k, String(items[k] || "")).run();
  }
  return json({ ok: true });
}

async function ownsProfile(env, acc, profileId) {
  const row = await env.DB.prepare(`SELECT 1 FROM profiles WHERE id = ? AND account_id = ?`)
    .bind(profileId, acc.id).first();
  return !!row;
}
