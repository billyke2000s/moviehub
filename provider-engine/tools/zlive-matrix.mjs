// Diagnostic only: which request difference makes ZLive return the real stream instead of a decoy?
import crypto from "node:crypto";
const SEED = "Asq3YkZdzBICwscY4OO1PFAes1Mv1vJ8vxZbzYQeHZE";
const API = "https://iptv.zlive.st";
const MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const HINTS = { "sec-ch-ua": '"Chromium";v="131", "Not_A Brand";v="24", "Google Chrome";v="131"', "sec-ch-ua-mobile": "?0", "sec-ch-ua-platform": '"macOS"' };
const FETCH = { "sec-fetch-dest": "empty", "sec-fetch-mode": "cors", "sec-fetch-site": "same-site", accept: "*/*", "accept-language": "en-GB,en;q=0.9" };
function seal(ticket, payload) {
    const d = new Date().toISOString().slice(0, 10);
    let k = crypto.createHash("sha256").update(SEED + ":" + d).digest();
    k = crypto.createHmac("sha256", k).update("k6").digest();
    k = crypto.createHmac("sha256", k).update(ticket + "|v6").digest();
    const iv = crypto.randomBytes(12), c = crypto.createCipheriv("aes-256-gcm", k, iv);
    c.setAAD(Buffer.from(ticket + "|" + d));
    const a = Buffer.concat([c.update(JSON.stringify(payload)), c.final()]);
    return JSON.stringify({ a: a.toString("base64"), b: iv.toString("base64"), c: c.getAuthTag().toString("base64"), d, e: ticket });
}
async function attempt(name, o) {
    const H = { "User-Agent": MAC, Referer: "https://zlive.st/", ...(o.origin ? { Origin: "https://zlive.st" } : {}), ...(o.hints ? HINTS : {}), ...(o.fetch ? FETCH : {}) };
    const ticket = async () => (await (await fetch(API + "/session/ticket", { headers: H })).json()).w;
    const post = async (path, t, p) => fetch(API + path, { method: "POST", headers: { ...H, "Content-Type": "application/json" }, body: seal(t, p) });
    try {
        if (o.warm) {
            await fetch(API + "/channels.json", { headers: { ...H, "Content-Type": "application/json" } });
            const t0 = await ticket();
            await (await post("/streams", t0, { u: Math.floor(Date.now() / 1000) })).text();
        }
        if (o.wait) await new Promise((r) => setTimeout(r, o.wait));
        const t = await ticket();
        const r = await post("/resolve", t, { s: "boomerang-uk", u: Math.floor(Date.now() / 1000) });
        const loc = (await r.json()).location;
        const pl = await (await fetch(loc, { headers: H })).text();
        const seg = pl.split("\n").find((l) => l && !l.startsWith("#"));
        const sb = Buffer.from(await (await fetch(new URL(seg, loc), { headers: H })).arrayBuffer());
        const disc = (pl.match(/DISCONTINUITY-SEQUENCE:(\d+)/) || [])[1] || "-";
        const verdict = sb.length > 1000000 ? "REAL" : "decoy?";
        console.log(`::notice title=${name}::${verdict} · host ${new URL(loc).hostname} · first chunk ${sb.length}B · discontinuity-seq ${disc} · tag ${pl.includes("Celestiality") ? "Celestiality" : "-"}`);
    } catch (e) {
        console.log(`::error title=${name}::${e.message}`);
    }
}
await attempt("A exp-14 as is", { origin: true });
await attempt("B + browser hints", { origin: true, hints: true });
await attempt("C + hints + fetch headers", { origin: true, hints: true, fetch: true });
await attempt("D warm-up (channels + streams)", { origin: true, warm: true });
await attempt("E warm-up + hints + fetch", { origin: true, hints: true, fetch: true, warm: true });
await attempt("F warm-up + 1.5s wait", { origin: true, warm: true, wait: 1500 });
await attempt("G no Origin", { origin: false });
await attempt("A again", { origin: true });
