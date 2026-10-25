// Diagnostic only: plays one ZLive channel in headless Chromium and records the real player's
// requests to ZLive hosts (method, path, header names, body shape) plus media segment sizes.
// Output is encrypted by the workflow; nothing here is shown in public logs.
import { chromium } from "playwright";
import { writeFileSync } from "node:fs";
const slug = process.argv[2] || "boomerang-uk";
const log = [];
const browser = await chromium.launch({ args: ["--autoplay-policy=no-user-gesture-required"] });
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
page.on("request", (r) => {
    const u = new URL(r.url());
    if (/\.(png|jpg|svg|woff2?|css|ico)$/.test(u.pathname)) return;
    log.push({ t: Date.now(), dir: "req", method: r.method(), url: r.url(), headers: r.headers(), body: r.postData() || "" });
});
page.on("response", async (r) => {
    const u = new URL(r.url());
    if (/\.(png|jpg|svg|woff2?|css|ico)$/.test(u.pathname)) return;
    let body = "", size = 0;
    try {
        const b = await r.body();
        size = b.length;
        const ct = r.headers()["content-type"] || "";
        if (/json|text|mpegurl|javascript/.test(ct) && size < 200000) body = b.toString("utf8");
        else body = "HEX " + b.subarray(0, 16).toString("hex");
    } catch (_) {}
    log.push({ t: Date.now(), dir: "res", status: r.status(), url: r.url(), headers: r.headers(), size, body });
});
page.on("websocket", (ws) => {
    log.push({ t: Date.now(), dir: "ws", url: ws.url() });
    ws.on("framesent", (f) => log.push({ t: Date.now(), dir: "ws-out", url: ws.url(), body: String(f.payload).slice(0, 4000) }));
    ws.on("framereceived", (f) => log.push({ t: Date.now(), dir: "ws-in", url: ws.url(), body: String(f.payload).slice(0, 4000) }));
});
await page.goto("https://zlive.st/watch/" + slug, { waitUntil: "domcontentloaded", timeout: 45000 });
await page.waitForTimeout(8000);
// Click anything that looks like a play control, then let it play.
for (const sel of ["video", "button[aria-label*=lay i]", "[class*=play i]"]) {
    try { await page.click(sel, { timeout: 2000 }); } catch (_) {}
}
await page.waitForTimeout(20000);
const video = await page.evaluate(() => {
    const v = document.querySelector("video");
    return v ? { w: v.videoWidth, h: v.videoHeight, t: v.currentTime, paused: v.paused, ready: v.readyState, src: v.currentSrc.slice(0, 60) } : null;
});
await page.screenshot({ path: "diag-shot.png" });
writeFileSync("diag-browser.json", JSON.stringify({ video, cookies: await page.context().cookies(), log }, null, 1));
await browser.close();
console.log("video", JSON.stringify(video), "entries", log.length);
