// Sustained diagnostic only. Resolve using the actual bundled interpreter, then decode BOTH
// tracks through production ProviderRelay/PlaylistSession/ProviderPngSegments on the JVM.
// No provider URLs, tokens, pages or captured media are printed or uploaded.
import { spawn, spawnSync } from "node:child_process";
import { createInterface } from "node:readline";
import { writeFileSync } from "node:fs";

const seconds = Math.max(30, Math.min(150, Number(process.env.STREAM_SECONDS || 150)));
process.env.MOVIEHUB_PROBE_LIBRARY = "1";
const { run } = await import("./live-probe.mjs");
const report = [];
const stats = () => ({
    hosts: new Set(),
    requests: 0,
    steps: 0,
    slowestStep: 0,
    maxResponse: 0,
    aggregate: 0,
    httpErrors: [],
    networkErrors: [],
});

// Compare the publicly advertised HLS source with the Android production relay.
// The existing quick sweep could return ffmpeg exit 0 despite zero decoded tracks.
// Never retain direct URLs, signed query strings or ffmpeg stderr.
function directDecode(stream) {
    const args = ["-v", "error", "-rw_timeout", "20000000", "-allowed_extensions", "ALL"];
    if (stream.userAgent) args.push("-user_agent", stream.userAgent);
    const headers = Object.entries(stream.headers || {})
        .filter(([key]) => !/^user-agent$/i.test(key))
        .map(([key, value]) => key + ": " + value)
        .join("\r\n");
    if (headers) args.push("-headers", headers + "\r\n");
    args.push("-i", stream.url, "-t", "6", "-map", "0:v:0", "-map", "0:a:0", "-f", "framemd5", "pipe:1");
    const p = spawnSync("ffmpeg", args, {
        encoding: "utf8",
        timeout: 45000,
        maxBuffer: 4 * 1024 * 1024,
    });
    const frames = [0, 0];
    for (const line of (p.stdout || "").split("\n")) {
        const m = /^([01]),\s*\d+,/.exec(line);
        if (m) frames[Number(m[1])]++;
    }
    return { exit: p.status, frames, playable: p.status === 0 && frames.every((n) => n >= 10) };
}

async function inspect(provider, row, forceRenew = false) {
    const result = {
        provider,
        channel: String(row.name).slice(0, 80),
        seconds,
        forcedRenewal: forceRenew,
    };
    let relay;
    const resolutionStats = stats();
    try {
        const stream = await run(provider, "resolve", row, resolutionStats);
        if (provider === "freeshot" && !forceRenew) result.direct = directDecode(stream);
        relay = spawn(
            "java",
            [
                "-Xmx192m",
                // Desktop HttpURLConnection otherwise silently drops Origin; Android sends it.
                "-Dsun.net.http.allowRestrictedHeaders=true",
                "-cp",
                process.env.JSON_JAR + ":build/provider-tests",
                "local.tv.home.ProviderRelayProbe",
            ],
            { stdio: ["pipe", "pipe", "pipe"] },
        );
        const messages = createInterface({ input: relay.stdout });
        const ready = new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("Relay deadline")), 20000);
            messages.once("line", (line) => {
                clearTimeout(timer);
                try {
                    resolve(JSON.parse(line).relay);
                } catch {
                    reject(new Error("Relay protocol"));
                }
            });
            relay.once("exit", () => {
                clearTimeout(timer);
                reject(new Error("Relay exited"));
            });
        });
        const relayErrors = {};
        relay.stderr.on("data", (bytes) => {
            for (const line of bytes.toString().split("\n")) {
                const kind = /: ([A-Za-z0-9_]+)$/.exec(line.trim());
                if (kind) relayErrors[kind[1]] = (relayErrors[kind[1]] || 0) + 1;
            }
        });
        if (forceRenew && stream.playlistSession) stream.playlistSession.expiresAtMs = 0;
        relay.stdin.write(JSON.stringify(stream) + "\n");
        const url = await ready;
        // Trace HLS master -> media playlist -> first segment through the exact
        // production relay, never logging URLs, query values or media bytes.
        const relayStages = [];
        try {
            let child = url;
            const origin = new URL(url).origin;
            for (let depth = 0; depth < 3; depth++) {
                const response = await fetch(child, { signal: AbortSignal.timeout(12000) });
                const type = response.headers.get("content-type") || "";
                const stage = { status: response.status, mimeHls: /mpegurl/i.test(type) };
                if (!stage.mimeHls && depth >= 2) {
                    const reader = response.body?.getReader();
                    const chunks = [];
                    let size = 0;
                    if (reader)
                        while (size < 32768) {
                            const { value, done } = await reader.read();
                            if (done) break;
                            const chunk = value.slice(0, 32768 - size);
                            chunks.push(chunk);
                            size += chunk.length;
                        }
                    const bytes = Buffer.concat(
                        chunks.map((v) => Buffer.from(v)),
                        size,
                    );
                    const packets = Math.floor(bytes.length / 188);
                    let aligned = 0;
                    const pids = new Set();
                    for (let index = 0; index < packets; index++) {
                        const at = index * 188;
                        if (bytes[at] !== 0x47) break;
                        aligned++;
                        pids.add(((bytes[at + 1] & 0x1f) << 8) | bytes[at + 2]);
                    }
                    stage.firstSegment = {
                        received: bytes.length > 0,
                        ts: bytes.length > 0 && bytes[0] === 0x47,
                        tsAlignedPackets: aligned,
                        tsAvailablePackets: packets,
                        distinctPids: pids.size,
                        hasPat: pids.has(0),
                        mp4: bytes.length > 8 && String.fromCharCode(...bytes.slice(4, 8)) === "ftyp",
                        html: bytes.length > 0 && bytes[0] === 0x3c,
                    };
                    if (reader) await reader.cancel().catch(() => {});
                    relayStages.push(stage);
                    break;
                }
                const body = await response.text();
                stage.hls = body.trimStart().startsWith("#EXTM3U");
                stage.bytes = body.length;
                stage.variants = (body.match(/#EXT-X-STREAM-INF:/g) || []).length;
                stage.segments = (body.match(/#EXTINF:/g) || []).length;
                relayStages.push(stage);
                if (!stage.hls) break;
                const next = body
                    .split(/\r?\n/)
                    .map((line) => line.trim())
                    .find((line) => line && !line.startsWith("#"));
                if (!next) break;
                const nextUrl = new URL(next, child);
                if (nextUrl.origin !== origin) break;
                child = nextUrl.href;
            }
        } catch (error) {
            relayStages.push({ failure: error.constructor.name });
        }
        result.relayStages = relayStages;
        const tracks = [0, 1].map(() => ({ frames: 0, unique: 0, start: null, end: null, maxGap: 0 }));
        const bases = [];
        const hashes = [new Set(), new Set()];
        let warnings = 0;
        const decoderReasons = new Set();
        const decoder = spawn(
            "ffmpeg",
            [
                "-v",
                "warning",
                "-rw_timeout",
                "20000000",
                "-allowed_extensions",
                "ALL",
                "-i",
                url,
                "-t",
                String(seconds),
                "-map",
                "0:v:0",
                "-map",
                "0:a:0",
                "-f",
                "framemd5",
                "pipe:1",
            ],
            { stdio: ["ignore", "pipe", "pipe"] },
        );
        const lines = createInterface({ input: decoder.stdout });
        lines.on("line", (line) => {
            const base = /^#tb (\d+): (\d+)\/(\d+)/.exec(line);
            if (base) bases[Number(base[1])] = Number(base[2]) / Number(base[3]);
            if (line.startsWith("#")) return;
            const values = line.split(",").map((value) => value.trim());
            const index = Number(values[0]);
            if (values.length < 6 || !tracks[index] || !bases[index]) return;
            const track = tracks[index];
            const time = Number(values[2]) * bases[index];
            const end = time + Number(values[3]) * bases[index];
            if (track.end !== null) track.maxGap = Math.max(track.maxGap, time - track.end);
            if (track.start === null) track.start = time;
            track.end = end;
            track.frames++;
            hashes[index].add(values[5]);
        });
        decoder.stderr.on("data", (bytes) => {
            for (const line of bytes.toString().split("\n").filter(Boolean)) {
                warnings++;
                // Report error classes, never media URLs, tokens or response bodies.
                for (const [pattern, label] of [
                    [/HTTP error (4\d\d|5\d\d)/i, "upstream_http_error"],
                    [/403 Forbidden/i, "upstream_403"],
                    [/Unable to open resource|Failed to open/i, "resource_unavailable"],
                    [/Error when loading first segment/i, "first_segment_failed"],
                    [/Invalid data found when processing input/i, "invalid_media"],
                    [/Stream map .*matches no streams/i, "missing_required_track"],
                    [/connection refused|Connection timed out/i, "transport_failed"],
                    [/Invalid playlist|Error parsing playlist/i, "playlist_invalid"],
                ])
                    if (pattern.test(line)) decoderReasons.add(label);
            }
        });
        const code = await new Promise((resolve) => {
            const timer = setTimeout(() => decoder.kill("SIGKILL"), 240000);
            decoder.once("close", (value) => {
                clearTimeout(timer);
                resolve(value);
            });
        });
        result.exit = code;
        result.tracks = tracks.map((track, i) => ({
            frames: track.frames,
            unique: hashes[i].size,
            duration: track.end === null ? 0 : +(track.end - track.start).toFixed(3),
            maxGap: +track.maxGap.toFixed(3),
        }));
        result.warningLines = warnings;
        result.decoderReasons = [...decoderReasons].slice(0, 8);
        result.relayErrors = relayErrors;
        result.bothDecoded =
            code === 0 && result.tracks.every((track) => track.duration >= seconds - 5 && track.frames > 10);
    } catch (error) {
        result.failure = error.constructor.name;
        const safe = [
            "FreeShot page unavailable",
            "FreeShot player unavailable",
            "Invalid FreeShot player configuration",
            "FreeShot stream scope rejected",
            "Invalid FreeShot token",
            "Invalid FreeShot operation or channel",
            "operation over the app's time limit",
            "host not on the app's allow-list",
        ];
        result.reason = safe.find((value) => String(error.message).includes(value)) || "Unclassified";
        if (provider === "freeshot") {
            result.channelPath = row.channelId;
            result.trace = resolutionStats.iframeTrace || [];
        }
    } finally {
        if (relay) {
            relay.stdin.end("\n");
            setTimeout(() => relay.kill("SIGKILL"), 1000).unref();
        }
    }
    return result;
}

if (process.env.SPORTS_PROBE_ONLY !== "freeshot")
    try {
        const rows = await run("dlhd", "catalogue", null, stats());
        const selected = [
            rows.find((row) => /sky.*sports.*main.*event/i.test(row.name || "")),
            rows.find((row) => /sky.*sports.*football/i.test(row.name || "")),
        ].filter(Boolean);
        for (const row of selected) {
            const result = await inspect("dlhd", row);
            report.push(result);
            console.log(JSON.stringify(result));
        }
        if (!selected.length) report.push({ failure: "No matching catalogue rows" });
    } catch (error) {
        report.push({ failure: error.constructor.name });
    }
try {
    const rows = await run("freeshot", "catalogue", null, stats());
    const row =
        rows.find((value) => /sky.*sports.*action/i.test(value.name || "")) ||
        rows.find((value) => /sky.*sports.*main.*event/i.test(value.name || ""));
    if (!row) report.push({ provider: "freeshot", failure: "No known sports channel in catalogue" });
    else {
        // Direct decoding did not exercise the app's loopback relay. Test its
        // normal starting token separately from a forced token renewal.
        report.push(await inspect("freeshot", row, false));
        if (process.env.PROBE_RENEWAL === "1") report.push(await inspect("freeshot", row, true));
    }
} catch (error) {
    report.push({ provider: "freeshot", failure: error.constructor.name });
}
writeFileSync("build/provider-tests/dlhd-playback.json", JSON.stringify(report, null, 2));
console.log("DLHD_PLAYBACK_RESULT " + JSON.stringify(report));
