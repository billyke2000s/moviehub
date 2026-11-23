// Soak test for a resolved Live TV stream. The quick probe decodes 6 seconds, which proves that
// *something* plays. That is not enough: tokens often expire after 30-60 seconds, a short advert
// can play before nothing, and some sites send scrapers a "go away" video instead of the channel.
//
// soak() keeps watching for `seconds` at real-time pace and fails the stream when:
//   - the picture or sound stops before the end (stream died, token expired),
//   - the picture freezes or has a long gap,
//   - an HLS playlist is a fixed-length clip, or stops moving forward, instead of a live channel,
//   - a saved frame matches a known block screen (pictures in the bait folder).
//
// The functions other than soak() are pure so they can be tested without a network.
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, rmSync } from "node:fs";
import { join } from "node:path";

// --- framemd5 output -----------------------------------------------------------------------

// Parses ffmpeg's framemd5 output: "#tb N: a/b" headers, then "stream, dts, pts, duration, size, hash".
export function parseFrames(text) {
    const timebase = {};
    const streams = { 0: [], 1: [] };
    for (const line of String(text || "").split("\n")) {
        const tb = /^#tb (\d+): (\d+)\/(\d+)/.exec(line);
        if (tb) {
            timebase[tb[1]] = Number(tb[2]) / Number(tb[3]);
            continue;
        }
        const m = /^([01]),\s*-?\d+,\s*(-?\d+),\s*(\d+),\s*\d+,\s*([0-9a-f]+)/.exec(line);
        if (!m) continue;
        const scale = timebase[m[1]] || 0;
        streams[m[1]].push({ time: Number(m[2]) * scale, duration: Number(m[3]) * scale, hash: m[4] });
    }
    return { video: streams[0], audio: streams[1] };
}

function covered(frames) {
    if (!frames.length) return 0;
    const last = frames[frames.length - 1];
    return last.time + last.duration - frames[0].time;
}

// Judges a soak run's frames. `limits` lets tests use short soaks.
export function analyseFrames(text, seconds, limits = {}) {
    const minCoverage = limits.minCoverage ?? 0.9;
    const maxGap = limits.maxGap ?? 3;
    const maxFreeze = limits.maxFreeze ?? Math.min(15, seconds / 2);
    const { video, audio } = parseFrames(text);
    const videoSeconds = covered(video);
    const audioSeconds = covered(audio);
    let longestGap = 0;
    let gapAt = 0;
    let longestFreeze = 0;
    let freezeAt = 0;
    let runStart = video.length ? video[0].time : 0;
    for (let i = 1; i < video.length; i++) {
        const gap = video[i].time - video[i - 1].time;
        if (gap > longestGap) {
            longestGap = gap;
            gapAt = video[i - 1].time - video[0].time;
        }
        if (video[i].hash !== video[i - 1].hash) runStart = video[i].time;
        const frozen = video[i].time + video[i].duration - runStart;
        if (frozen > longestFreeze) {
            longestFreeze = frozen;
            freezeAt = runStart - video[0].time;
        }
    }
    const result = {
        ok: false,
        reason: "",
        videoSeconds: round(videoSeconds),
        audioSeconds: round(audioSeconds),
        longestGap: round(longestGap),
        longestFreeze: round(longestFreeze),
    };
    if (!video.length || !audio.length) result.reason = "no picture or no sound decoded";
    else if (videoSeconds < seconds * minCoverage)
        result.reason = "picture stopped after " + round(videoSeconds) + " s of " + seconds + " s";
    else if (audioSeconds < seconds * minCoverage)
        result.reason = "sound stopped after " + round(audioSeconds) + " s of " + seconds + " s";
    else if (longestGap > maxGap)
        result.reason = "picture stopped for " + round(longestGap) + " s at " + round(gapAt) + " s";
    else if (longestFreeze >= maxFreeze)
        result.reason = "picture frozen for " + round(longestFreeze) + " s from " + round(freezeAt) + " s";
    else result.ok = true;
    return result;
}

function round(n) {
    return Math.round(n * 10) / 10;
}

// --- HLS playlists ---------------------------------------------------------------------------

// Reads what matters from an HLS playlist: is it a master list, is it live, where is it up to.
export function playlistInfo(text, base) {
    const body = String(text || "");
    const lines = body.split(/\r?\n/).map((l) => l.trim());
    const info = {
        hls: lines[0] === "#EXTM3U",
        master: false,
        variants: [],
        live: true,
        sequence: null,
        segments: 0,
    };
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            info.master = true;
            const next = lines.slice(i + 1).find((l) => l && !l.startsWith("#"));
            if (next) info.variants.push(base ? new URL(next, base).href : next);
        } else if (line === "#EXT-X-ENDLIST" || line === "#EXT-X-PLAYLIST-TYPE:VOD") {
            info.live = false;
        } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
            info.sequence = Number(line.slice("#EXT-X-MEDIA-SEQUENCE:".length));
        } else if (line.startsWith("#EXTINF")) {
            info.segments++;
        }
    }
    return info;
}

// Did a live playlist move forward between two reads?
export function playlistMoved(before, after) {
    if (before.sequence !== null && after.sequence !== null) return after.sequence > before.sequence;
    return after.segments > before.segments;
}

// --- Picture fingerprints ----------------------------------------------------------------------

// Difference hash from a 9x8 greyscale picture (72 bytes): 64 bits as 16 hex digits.
// Returns null for a nearly flat picture (black or blank), which proves nothing either way.
export function differenceHash(gray) {
    if (!gray || gray.length < 72) return null;
    let min = 255;
    let max = 0;
    for (const v of gray) {
        min = Math.min(min, v);
        max = Math.max(max, v);
    }
    if (max - min < 16) return null;
    let bits = "";
    for (let row = 0; row < 8; row++)
        for (let col = 0; col < 8; col++) bits += gray[row * 9 + col] < gray[row * 9 + col + 1] ? "1" : "0";
    let hex = "";
    for (let i = 0; i < 64; i += 4) hex += parseInt(bits.slice(i, i + 4), 2).toString(16);
    return hex;
}

export function hamming(a, b) {
    let distance = 0;
    for (let i = 0; i < a.length; i++) {
        let x = parseInt(a[i], 16) ^ parseInt(b[i], 16);
        while (x) {
            distance += x & 1;
            x >>= 1;
        }
    }
    return distance;
}

// Hash of a picture file (any format ffmpeg reads), or null.
export function pictureHash(path) {
    const p = spawnSync(
        "ffmpeg",
        [
            "-v",
            "error",
            "-i",
            path,
            "-frames:v",
            "1",
            "-vf",
            "scale=9:8,format=gray",
            "-f",
            "rawvideo",
            "pipe:1",
        ],
        { maxBuffer: 1024 * 1024, timeout: 30000 },
    );
    return p.status === 0 ? differenceHash(p.stdout) : null;
}

const PICTURE = /\.(jpe?g|png|webp|bmp)$/i;
export const BAIT_DISTANCE = 8;

// Known block screens: every picture in `dir`, as { name, hash }.
export function baitHashes(dir) {
    if (!dir || !existsSync(dir)) return [];
    return readdirSync(dir)
        .filter((name) => PICTURE.test(name))
        .map((name) => ({ name, hash: pictureHash(join(dir, name)) }))
        .filter((b) => b.hash);
}

export function matchBait(hash, baits) {
    if (!hash) return null;
    for (const bait of baits) if (hamming(hash, bait.hash) <= BAIT_DISTANCE) return bait.name;
    return null;
}

// --- The soak itself -----------------------------------------------------------------------

function requestHeaders(stream) {
    const headers = { ...(stream.headers || {}) };
    if (stream.userAgent) headers["User-Agent"] = stream.userAgent;
    if (stream.referer) headers["Referer"] = stream.referer;
    return headers;
}

async function readPlaylist(url, headers) {
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(16000) });
    if (res.status >= 400) throw new Error("HTTP " + res.status);
    return playlistInfo(await res.text(), url);
}

// Finds the media playlist to watch, or explains why the playlist was not checked.
async function mediaPlaylist(url, headers) {
    const first = await readPlaylist(url, headers);
    if (!first.hls) return null;
    if (!first.master) return { url, info: first };
    if (!first.variants.length) return null;
    return { url: first.variants[0], info: await readPlaylist(first.variants[0], headers) };
}

// Runs a command without blocking (spawnSync would stop this process answering other requests for
// the whole soak). Kills it after `timeout` ms; output up to then is kept.
function run(command, args, timeout) {
    return new Promise((resolve) => {
        const child = spawn(command, args, { stdio: ["ignore", "pipe", "pipe"] });
        let stdout = "";
        let stderr = "";
        child.stdout.setEncoding("utf8").on("data", (d) => (stdout += d));
        child.stderr.setEncoding("utf8").on("data", (d) => (stderr = (stderr + d).slice(-8000)));
        const timer = setTimeout(() => child.kill("SIGKILL"), timeout);
        child.on("error", (e) => (stderr += String(e)));
        child.on("close", (status) => {
            clearTimeout(timer);
            resolve({ status, stdout, stderr });
        });
    });
}

// Watches `stream` ({url, userAgent, referer, headers}) for `seconds`. Saves a frame every
// 20 seconds as `<framePrefix>-NN.jpg` for the evidence folder and the bait check.
export async function soak(stream, options) {
    const seconds = options.seconds;
    const baits = options.baits || [];
    const headers = requestHeaders(stream);
    const result = { ok: false, seconds, reason: "", playlist: "not HLS, not checked", frames: [] };

    let playlist = null;
    try {
        playlist = await mediaPlaylist(stream.url, headers);
    } catch (e) {
        result.playlist = "not checked (" + (e.cause?.code || e.message) + ")";
    }
    if (playlist && !playlist.info.live) {
        result.reason = "the playlist is a fixed-length clip, not a live channel";
        result.playlist = "fixed-length";
        return result;
    }

    mkdirSync(options.frameDir, { recursive: true });
    const prefix = join(options.frameDir, options.frameName);
    for (const name of readdirSync(options.frameDir))
        if (name.startsWith(options.frameName + "-") && name.endsWith(".jpg"))
            rmSync(join(options.frameDir, name));
    const args = ["-v", "error", "-rw_timeout", "15000000"];
    if (stream.userAgent) args.push("-user_agent", stream.userAgent);
    const extra = [];
    if (stream.referer) extra.push("Referer: " + stream.referer);
    for (const [k, v] of Object.entries(stream.headers || {}))
        if (!/^(user-agent|referer)$/i.test(k)) extra.push(k + ": " + v);
    if (extra.length) args.push("-headers", extra.join("\r\n") + "\r\n");
    // Give up on a live playlist that stops getting new segments, instead of waiting forever.
    if (playlist) args.push("-m3u8_hold_counters", "6");
    args.push("-re", "-i", stream.url);
    args.push("-t", String(seconds), "-map", "0:v:0", "-map", "0:a:0");
    args.push("-flush_packets", "1", "-f", "framemd5", "pipe:1");
    // A picture at 0, 20, 40 … s. select (not fps, which waits for later frames) plus a frame count,
    // so this output finishes with the soak instead of keeping ffmpeg reading afterwards.
    args.push("-t", String(seconds), "-map", "0:v:0", "-fps_mode", "passthrough");
    args.push("-vf", "select='isnan(prev_selected_t)+gte(t-prev_selected_t\\,20)',scale=320:-2");
    args.push("-frames:v", String(Math.ceil(seconds / 20)), "-q:v", "5", "-y", prefix + "-%02d.jpg");
    const started = Date.now();
    const p = await run("ffmpeg", args, (seconds + 60) * 1000);
    result.wallSeconds = round((Date.now() - started) / 1000);
    const analysed = analyseFrames(p.stdout, seconds, options.limits);
    Object.assign(result, analysed, { ok: false });

    const prefixName = options.frameName + "-";
    result.frames = readdirSync(options.frameDir)
        .filter((name) => name.startsWith(prefixName) && name.endsWith(".jpg"))
        .sort()
        .map((name) => join(options.frameDir, name));

    if (!analysed.ok) {
        const error = (p.stderr || "")
            .split("\n")
            .map((l) => l.replace(/https?:\/\/[^\s'"]+/g, "<url>").trim())
            .filter(Boolean)
            .slice(-1)[0];
        result.reason = analysed.reason + (error ? " (" + error.slice(0, 120) + ")" : "");
        return result;
    }
    for (const frame of result.frames) {
        const bait = matchBait(pictureHash(frame), baits);
        if (bait) {
            result.reason = "the picture matches a known block screen (" + bait + ")";
            return result;
        }
    }
    if (playlist) {
        try {
            const after = await readPlaylist(playlist.url, headers);
            if (!after.live) {
                result.reason = "the playlist ended during the soak";
                result.playlist = "ended";
                return result;
            }
            if (!playlistMoved(playlist.info, after)) {
                result.reason = "the live playlist stopped moving forward";
                result.playlist = "stuck";
                return result;
            }
            result.playlist = "live and moving";
        } catch (e) {
            result.playlist = "live at start; not re-checked (" + (e.cause?.code || e.message) + ")";
        }
    }
    result.ok = true;
    return result;
}
