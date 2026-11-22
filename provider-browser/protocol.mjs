// Browser observations are deliberately metadata-only: never persist media URLs,
// bearer tokens, cookies, request bodies, screenshots or complete query strings.
import { isIP } from "node:net";

const ROUTE_WORDS = new Set([
    "api", "v1", "v2", "v3", "v4", "watch", "embed", "player", "stream",
    "streams", "resolve", "master.m3u8", "index.m3u8", "hls", "dash",
    "live", "channel", "channels", "source", "sources", "ajax", "play",
    "assets", "js", "tv", "movie", "vod", "playlist", "watch.php",
    "index.php", "stream.php", "get", "video", "manifest", "manifest.mpd",
]);
const PRIVATE_SUFFIX = /(?:^|\.)(?:local|localhost|internal|test|invalid|example|arpa)$/i;

export function publicUrl(raw) {
    const url = new URL(raw);
    const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, "");
    if (
        url.protocol !== "https:" || url.username || url.password ||
        url.port || !host.includes(".") || PRIVATE_SUFFIX.test(host) ||
        isIP(host) || host.length > 253 || !/^[a-z0-9.-]+$/.test(host)
    ) throw new Error("Untrusted browser destination");
    return url;
}

export function publicIPv4(raw) {
    const parts = raw.split(".").map(Number);
    if (parts.length !== 4 || parts.some(x => !Number.isInteger(x) || x < 0 || x > 255))
        return false;
    const [a,b,c] = parts;
    // Narrow on purpose: skip reserved, local, shared, documentation and
    // benchmarking networks rather than let headless Chromium visit them.
    if (
        a === 0 || a === 10 || a === 127 || a >= 224 ||
        a === 100 && b >= 64 && b <= 127 ||
        a === 169 && b === 254 ||
        a === 172 && b >= 16 && b <= 31 ||
        a === 192 && (b === 168 || b === 0 || b === 88 && c === 99) ||
        a === 198 && (b === 18 || b === 19 || b === 51 && c === 100) ||
        a === 203 && b === 0 && c === 113 ||
        a === 192 && b === 0 && c === 2
    ) return false;
    return true;
}

export function routePattern(raw) {
    const url = publicUrl(raw);
    return (url.pathname.split("/").filter(Boolean).slice(0, 6).map(part => {
        let value;
        try { value = decodeURIComponent(part).toLowerCase(); }
        catch { return ":id"; }
        return ROUTE_WORDS.has(value) ? value : ":id";
    }).join("/") || "/");
}

export function mediaKind(type = "") {
    const t = String(type).split(";")[0].trim().toLowerCase();
    if (t.includes("mpegurl") || t.includes("m3u8")) return "hls";
    if (t.includes("dash+xml")) return "dash";
    if (t.startsWith("video/")) return "video";
    if (t.startsWith("audio/")) return "audio";
    if (t.includes("json")) return "json";
    if (t.includes("javascript")) return "javascript";
    if (t.includes("html")) return "html";
    if (t.startsWith("image/")) return "image";
    return "other";
}

export function observation(raw, method, resource, status, contentType) {
    const url = publicUrl(raw);
    return {
        host: url.hostname.toLowerCase(),
        route: routePattern(raw),
        method: ["GET", "POST"].includes(method) ? method : "OTHER",
        resource: ["document","script","xhr","fetch","media","iframe"].includes(resource) ? resource : "other",
        status: Number.isInteger(status) ? Math.floor(status / 100) + "xx" : "failed",
        media: mediaKind(contentType),
    };
}

export function signatures(entries) {
    return [...new Set(entries.map(e => [
        e.host, e.route, e.method, e.resource, e.status, e.media,
    ].join(" | ")))].sort().slice(0, 300);
}

export function compare(before = [], after = []) {
    const previous = new Set(before);
    const current = new Set(after);
    return {
        added: [...current].filter(x => !previous.has(x)).slice(0, 100),
        missing: [...previous].filter(x => !current.has(x)).slice(0, 100),
    };
}

export function readTargets(config) {
    if (!Array.isArray(config) || config.length > 200) throw new Error("Invalid browser inventory");
    const seen = new Set();
    return config.map(target => {
        if (
            typeof target.id !== "string" || !/^[a-z0-9:_-]{1,72}$/.test(target.id) ||
            seen.has(target.id) || !["player","landing"].includes(target.kind)
        ) throw new Error("Invalid browser target");
        publicUrl(target.url);
        seen.add(target.id);
        return {
            id: target.id,
            url: target.url,
            kind: target.kind,
            maxClicks: target.kind === "player" ? Math.min(4, Math.max(0, target.maxClicks || 0)) : 0,
        };
    });
}
