// Diagnostic only: replays ZLive's public web-player steps for one channel and reports each
// step as a GitHub annotation (run logs are not readable from every client). Prints hosts,
// statuses and shapes, never full stream URLs or tokens.
import crypto from "node:crypto";

const slug = process.argv[2] || "boomerang-uk";
const UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Origin: "https://zlive.st", Referer: "https://zlive.st/" };
const SEED = "Asq3YkZdzBICwscY4OO1PFAes1Mv1vJ8vxZbzYQeHZE";
const note = (t, m) => console.log(`::notice title=${t}::${String(m).replace(/\r?\n/g, " ⏎ ").slice(0, 3000)}`);
const err = (t, m) => console.log(`::error title=${t}::${String(m).replace(/\r?\n/g, " ⏎ ").slice(0, 3000)}`);
const shape = (v) =>
    v && typeof v === "object"
        ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, Array.isArray(x) ? `array(${x.length})` : typeof x === "string" ? `string(${x.length})` : typeof x]))
        : typeof v;

async function get(url, opts = {}) {
    const r = await fetch(url, { redirect: "manual", ...opts, headers: { ...H, ...(opts.headers || {}) } });
    const text = await r.text();
    return { status: r.status, text, headers: Object.fromEntries(r.headers), url };
}

// 1. Site bundle: does it still use the protocol exp-14 implements?
try {
    const page = await get("https://zlive.st/watch/" + slug);
    const scripts = [...page.text.matchAll(/<script[^>]+src="([^"]+)"/g)].map((m) => new URL(m[1], "https://zlive.st/").href);
    note("1 page", `HTTP ${page.status}, scripts: ${scripts.map((s) => new URL(s).pathname).join(", ") || "none"}`);
    for (const s of scripts.filter((u) => new URL(u).hostname.endsWith("zlive.st")).slice(0, 4)) {
        const js = await get(s);
        const hits = ["/session/ticket", "/resolve", "channels.json", SEED, '"k6"', "|v6", "AES-GCM", "/watch/"]
            .map((k) => `${k.length > 20 ? "seed" : k}=${js.text.includes(k) ? "yes" : "no"}`)
            .join(" ");
        const endpoints = [...new Set([...js.text.matchAll(/["'`](\/(?:api|session|resolve|v\d|play|stream|channels?)[A-Za-z0-9_./-]*)["'`]/g)].map((m) => m[1]))].slice(0, 25);
        const hosts = [...new Set([...js.text.matchAll(/https?:\/\/([a-z0-9.-]+\.[a-z]{2,})/gi)].map((m) => m[1]))].slice(0, 20);
        note("1 bundle " + new URL(s).pathname, `HTTP ${js.status} ${js.text.length}B · ${hits} · endpoints: ${endpoints.join(" ")} · hosts: ${hosts.join(" ")}`);
    }
} catch (e) {
    err("1 page", e.message);
}

// 2. Catalogue entry for the channel.
let entry = null;
try {
    const cat = await get("https://iptv.zlive.st/channels.json");
    const rows = JSON.parse(cat.text);
    entry = rows.find((r) => r.id === slug) || rows.find((r) => String(r.id).endsWith(slug)) || null;
    const near = rows.filter((r) => /boomerang/i.test(r.id + r.name)).map((r) => r.id);
    note("2 catalogue", `HTTP ${cat.status}, ${rows.length} rows; matching ids: ${near.join(", ") || "none"}; first row shape ${JSON.stringify(shape(rows[0]))}`);
    if (entry) note("2 entry", JSON.stringify({ ...entry, sources: (entry.sources || []).map((s) => (typeof s === "object" ? shape(s) : typeof s)) }));
} catch (e) {
    err("2 catalogue", e.message);
}

// 3. Ticket -> encrypted POST /resolve, exactly as zlive.js does.
const id = entry ? entry.id : slug;
try {
    const t = await get("https://iptv.zlive.st/session/ticket");
    note("3 ticket", `HTTP ${t.status}, body shape ${t.text.startsWith("{") ? JSON.stringify(shape(JSON.parse(t.text))) : t.text.slice(0, 120)}`);
    const ticket = JSON.parse(t.text).w;
    const day = new Date().toISOString().slice(0, 10);
    let key = crypto.createHash("sha256").update(SEED + ":" + day).digest();
    key = crypto.createHmac("sha256", key).update("k6").digest();
    key = crypto.createHmac("sha256", key).update(ticket + "|v6").digest();
    const iv = crypto.randomBytes(12);
    const c = crypto.createCipheriv("aes-256-gcm", key, iv);
    c.setAAD(Buffer.from(ticket + "|" + day));
    const enc = Buffer.concat([c.update(JSON.stringify({ s: id, u: Math.floor(Date.now() / 1000) })), c.final()]);
    const body = { a: enc.toString("base64"), b: iv.toString("base64"), c: c.getAuthTag().toString("base64"), d: day, e: ticket };
    const r = await get("https://iptv.zlive.st/resolve", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    let parsed = null;
    try { parsed = JSON.parse(r.text); } catch (_) {}
    note("3 resolve " + id, `HTTP ${r.status}, ${parsed ? "json " + JSON.stringify(shape(parsed)) : "text: " + r.text.slice(0, 200)}${parsed && parsed.error ? " error=" + parsed.error : ""}${parsed && parsed.location ? " host=" + new URL(parsed.location).hostname : ""}`);
    // 4. Fetch the media playlist the app would play.
    if (parsed && parsed.location) {
        const m = await get(parsed.location);
        note("4 media", `HTTP ${m.status} ${m.headers["content-type"] || ""}, starts: ${m.text.slice(0, 160)}`);
        const seg = m.text.split("\n").find((l) => l && !l.startsWith("#"));
        if (seg) {
            const segUrl = new URL(seg.trim(), parsed.location).href;
            const s = await fetch(segUrl, { headers: H });
            const buf = Buffer.from(await s.arrayBuffer());
            note("5 first entry", `HTTP ${s.status} ${s.headers.get("content-type") || ""} ${buf.length}B, first bytes ${buf.subarray(0, 8).toString("hex")} (${new URL(segUrl).pathname.split("/").pop().slice(0, 40)})`);
        }
    }
} catch (e) {
    err("3 resolve", e.stack || e.message);
}
