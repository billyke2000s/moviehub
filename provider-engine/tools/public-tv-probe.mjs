// Public TV catalogue drift probe. Never accepts private playlists/accounts or executes
// remote scripts. Every output is a count/status only: no session tokens or stream URLs.
import { writeFileSync, mkdirSync } from "node:fs";
import { randomUUID } from "node:crypto";

const LIMIT = 1024 * 1024;
const SOURCES = [
    ["free-tv", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"],
    ["iptv-org-uk", "https://iptv-org.github.io/iptv/countries/uk.m3u"],
    ["iptv-org-us", "https://iptv-org.github.io/iptv/countries/us.m3u"],
    ["iptv-org-all", "https://iptv-org.github.io/iptv/index.m3u"],
];

function hostname(url) {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.port)
        throw new Error("Untrusted catalogue URL");
    return parsed.hostname;
}

function plutoEndpoint(value) {
    const parsed = new URL(value);
    if (
        parsed.protocol !== "https:" ||
        parsed.username ||
        parsed.password ||
        parsed.port ||
        parsed.search ||
        parsed.hash ||
        !(parsed.pathname === "/" || parsed.pathname === "") ||
        !parsed.hostname.endsWith(".pluto.tv")
    ) throw new Error("Pluto returned an untrusted endpoint");
    return value.replace(/\/+$/, "");
}

function parseM3u(sample) {
    const clean = sample.replace(/^\uFEFF/, "");
    if (!clean.startsWith("#EXTM3U")) throw new Error("Not an M3U playlist");
    const lines = clean.split(/\r?\n/);
    const entries = lines.filter((line) => line.startsWith("#EXTINF:")).length;
    if (!entries) throw new Error("M3U contains no channel entries");
    return { channelEntriesInSample: entries, sampleOnly: sample.length >= LIMIT };
}

async function text(url, headers = {}, max = LIMIT) {
    hostname(url);
    const response = await fetch(url, {
        redirect: "manual",
        headers,
        signal: AbortSignal.timeout(14000),
    });
    if (response.status !== 200) throw new Error("HTTP " + response.status);
    const reader = response.body.getReader();
    let total = 0;
    const chunks = [];
    try {
        while (total < max) {
            const { value, done } = await reader.read();
            if (done) break;
            if (value.length + total > max) {
                chunks.push(value.slice(0, max - total));
                total = max;
                break;
            }
            chunks.push(value);
            total += value.length;
        }
    } finally {
        await reader.cancel();
    }
    const bytes = Buffer.concat(chunks);
    return bytes.toString("utf8");
}

async function pluto() {
    const boot = "https://boot.pluto.tv/v4/start?appName=web&appVersion=9.0.0" +
        "&deviceVersion=145.0&deviceModel=web&deviceMake=firefox" +
        "&deviceType=web&clientModelNumber=1.0.0&serverSideAds=true" +
        "&clientID=" + randomUUID();
    const session = JSON.parse(await text(boot, {}, 1024 * 128));
    if (!session.sessionToken || !session.servers?.channels)
        throw new Error("Pluto bootstrap missing expected fields");
    const base = plutoEndpoint(session.servers.channels);
    const result = JSON.parse(await text(base + "/v2/guide/channels", {
        Authorization: "Bearer " + session.sessionToken,
    }, 4 * LIMIT));
    if (!Array.isArray(result.data) || !result.data.length)
        throw new Error("Pluto channel catalogue empty");
    if (result.meta?.totalCount > result.data.length)
        throw new Error("Pluto channel catalogue incomplete");
    return { channels: result.data.length, status: "catalogue_ok" };
}

async function main() {
    const results = [];
    for (const [id, url] of SOURCES) {
        try {
            const sample = await text(url);
            results.push({ id, status: "catalogue_ok", ...parseM3u(sample) });
        } catch (error) {
            results.push({ id, status: "catalogue_failed", reason: error.name === "TimeoutError"
                ? "timeout" : String(error.message).slice(0, 100) });
        }
    }
    try {
        results.push({ id: "pluto-tv", ...(await pluto()) });
    } catch (error) {
        results.push({ id: "pluto-tv", status: "catalogue_failed",
            reason: String(error.message).slice(0, 100) });
    }
    mkdirSync("build/public-tv", { recursive: true });
    writeFileSync("build/public-tv/catalogues.json", JSON.stringify(results, null, 2));
    const rows = results.map(({ id, status, channelEntriesInSample, channels, reason }) =>
        [id, status, channelEntriesInSample ?? channels ?? "-", (reason || "").replaceAll("|", "/")]);
    const summary = [
        "## Public TV catalogue audit",
        "",
        "US GitHub runner; catalogue access does not prove individual stream playback.",
        "Only a bounded sample of large M3U files is counted. Private Xtream/M3U and Nuvio",
        "accounts are deliberately not submitted to this workflow.",
        "",
        "| Provider | Catalogue | Entries in sample | Note |",
        "| --- | --- | ---: | --- |",
        ...rows.map((row) => "| " + row.join(" | ") + " |"),
        "",
    ].join("\n");
    writeFileSync("build/public-tv/catalogues.md", summary);
    if (process.env.GITHUB_STEP_SUMMARY) {
        const { appendFileSync } = await import("node:fs");
        appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary);
    }
    console.log(summary);
}

function selfTest() {
    const sample = "#EXTM3U\n#EXTINF:-1,One\nhttps://example.org/a.m3u8\n";
    if (parseM3u(sample).channelEntriesInSample !== 1) throw new Error("M3U regression");
    for (const invalid of ["https://pluto.tv.evil.example/", "http://foo.pluto.tv/",
        "https://user:pass@boot.pluto.tv/", "https://boot.pluto.tv/?secret=1"])
        try { plutoEndpoint(invalid); throw new Error("Unsafe Pluto origin accepted"); }
        catch (e) { if (e.message === "Unsafe Pluto origin accepted") throw e; }
    if (plutoEndpoint("https://api.pluto.tv/") !== "https://api.pluto.tv")
        throw new Error("Valid Pluto origin rejected");
    console.log("Public catalogue parser and Pluto-origin fixtures passed");
}

if (process.argv.includes("--self-test")) selfTest();
else await main();
