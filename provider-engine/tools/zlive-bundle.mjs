// Diagnostic only: prints the parts of ZLive's public player code that build the stream request,
// as GitHub annotations (part N of the matches per run step). Prints no tokens or stream URLs.
const part = Number(process.argv[2] || 0);
const UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const H = { "User-Agent": UA, Referer: "https://zlive.st/" };
const page = await (await fetch("https://zlive.st/watch/boomerang-uk", { headers: H })).text();
const srcs = [...page.matchAll(/<script[^>]+src="([^"]+)"/g)].map((m) => new URL(m[1], "https://zlive.st/").href);
let js = "";
for (const s of srcs) js += "\n/*FILE " + new URL(s).pathname + "*/\n" + (await (await fetch(s, { headers: H })).text());
const keys = ["k6", "v6", "session/ticket", "resolve", "encrypt", "importKey", "AES", "location", "subtle", "HMAC", "SHA-256", "m3u8", "iptv."];
const hits = [];
for (const k of keys) {
    let i = -1;
    while ((i = js.indexOf(k, i + 1)) !== -1 && hits.length < 200) hits.push([i, k]);
}
hits.sort((a, b) => a[0] - b[0]);
// Merge overlapping windows so each annotation is one readable region.
const windows = [];
for (const [i, k] of hits) {
    const a = Math.max(0, i - 700), b = Math.min(js.length, i + 700);
    const last = windows[windows.length - 1];
    if (last && a <= last[1]) { last[1] = Math.max(last[1], b); last[2].add(k); }
    else windows.push([a, b, new Set([k])]);
}
if (part === 0) console.log(`::notice title=summary::${srcs.map((s) => new URL(s).pathname).join(", ")} · ${js.length} chars · ${windows.length} regions · keys found: ${keys.filter((k) => js.includes(k)).join(", ")}`);
const per = 9;
windows.slice(part * per, part * per + per).forEach(([a, b, ks], n) => {
    const text = js.slice(a, b).replace(/\s+/g, " ");
    for (let off = 0, c = 0; off < text.length && c < 1; off += 3500, c++)
        console.log(`::notice title=region ${part * per + n} [${[...ks].join(",")}] @${a}::${text.slice(off, off + 3500)}`);
});
