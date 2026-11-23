// Tests for tools/soak.mjs. The first group is pure. The second group makes real videos with ffmpeg,
// serves them from a local web server and runs the actual soak against them (skipped without ffmpeg).
import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
    analyseFrames,
    baitHashes,
    differenceHash,
    hamming,
    playlistInfo,
    playlistMoved,
    soak,
} from "../tools/soak.mjs";

function frames({ seconds, fps = 25, hash = (i) => "a" + i.toString(16), audioSeconds = seconds, gapAt }) {
    let text = "#tb 0: 1/" + fps + "\n#tb 1: 1/44100\n";
    for (let i = 0; i < seconds * fps; i++) {
        if (gapAt && i >= gapAt[0] * fps && i < gapAt[1] * fps) continue;
        text += "0, " + i + ", " + i + ", 1, 1000, " + hash(i) + "\n";
    }
    for (let i = 0; i < (audioSeconds * 44100) / 1024; i++)
        text += "1, " + i * 1024 + ", " + i * 1024 + ", 1024, 2048, b" + i.toString(16) + "\n";
    return text;
}

test("a stream that keeps playing passes", () => {
    const r = analyseFrames(frames({ seconds: 60 }), 60);
    assert.equal(r.ok, true, r.reason);
    assert.ok(r.videoSeconds >= 59);
});

test("a stream that stops after 7 seconds fails", () => {
    const r = analyseFrames(frames({ seconds: 7 }), 60);
    assert.equal(r.ok, false);
    assert.match(r.reason, /picture stopped after 7 s of 60 s/);
});

test("sound that stops fails even when the picture carries on", () => {
    const r = analyseFrames(frames({ seconds: 60, audioSeconds: 10 }), 60);
    assert.match(r.reason, /sound stopped/);
});

test("a long gap in the picture fails", () => {
    const r = analyseFrames(frames({ seconds: 60, gapAt: [20, 30] }), 60);
    assert.match(r.reason, /picture stopped for 10 s at 20 s/);
});

test("a frozen picture fails", () => {
    const r = analyseFrames(
        frames({ seconds: 60, hash: (i) => (i < 250 ? "a" + i.toString(16) : "ffff") }),
        60,
    );
    assert.match(r.reason, /picture frozen for 50 s from 10 s/);
});

test("no frames at all fails", () => {
    assert.match(analyseFrames("", 60).reason, /no picture or no sound/);
});

test("playlists: live, fixed-length, master and moving forward", () => {
    const live = playlistInfo("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:5\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n");
    assert.equal(live.hls, true);
    assert.equal(live.live, true);
    assert.equal(live.sequence, 5);
    assert.equal(playlistInfo("#EXTM3U\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n").live, false);
    assert.equal(playlistInfo("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n").live, false);
    const master = playlistInfo(
        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n",
        "https://h/x/m.m3u8",
    );
    assert.equal(master.master, true);
    assert.deepEqual(master.variants, ["https://h/x/low/index.m3u8"]);
    assert.equal(playlistInfo("<html>blocked</html>").hls, false);
    assert.equal(playlistMoved({ sequence: 5, segments: 3 }, { sequence: 9, segments: 3 }), true);
    assert.equal(playlistMoved({ sequence: 5, segments: 3 }, { sequence: 5, segments: 3 }), false);
});

test("picture hashes: similar pictures are close, flat pictures are ignored", () => {
    const gradient = Buffer.from(Array.from({ length: 72 }, (_, i) => (i % 9) * 20 + (i % 3)));
    const noisy = Buffer.from(gradient.map((v, i) => v + (i % 2)));
    const reversed = Buffer.from(Array.from({ length: 72 }, (_, i) => 200 - (i % 9) * 20));
    assert.ok(hamming(differenceHash(gradient), differenceHash(noisy)) <= 8);
    assert.ok(hamming(differenceHash(gradient), differenceHash(reversed)) > 8);
    assert.equal(differenceHash(Buffer.alloc(72, 10)), null);
});

// --- Real soaks against local videos -----------------------------------------------------------

// Needs ffmpeg with the x264 encoder (to make the test videos); skipped otherwise, never failed.
const encoders = spawnSync("ffmpeg", ["-hide_banner", "-encoders"], { encoding: "utf8" });
const hasFfmpeg = encoders.status === 0 && /libx264/.test(encoders.stdout);
const SOAK = 12;

function ffmpeg(args) {
    const p = spawnSync("ffmpeg", ["-v", "error", "-y", ...args], { encoding: "utf8" });
    if (p.status !== 0) throw new Error(p.stderr);
}

test("real soaks with ffmpeg", { skip: !hasFfmpeg && "ffmpeg not installed" }, async (t) => {
    const work = mkdtempSync(join(tmpdir(), "soak-test-"));
    const sine = ["-f", "lavfi", "-i", "sine=frequency=440"];
    const encode = [
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-shortest",
        "-movflags",
        "+faststart",
    ];
    const moving = ["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25"];
    ffmpeg([...moving, ...sine, "-t", "40", ...encode, join(work, "good.mp4")]);
    ffmpeg([...moving, ...sine, "-t", "5", ...encode, join(work, "short.mp4")]);
    ffmpeg([
        "-f",
        "lavfi",
        "-i",
        "smptebars=size=320x180:rate=25",
        ...sine,
        "-t",
        "30",
        ...encode,
        join(work, "still.mp4"),
    ]);
    // Bait: the bars with noise, so the picture moves (no freeze) but looks like the block screen.
    ffmpeg([
        "-f",
        "lavfi",
        "-i",
        "smptebars=size=320x180:rate=25",
        ...sine,
        "-vf",
        "noise=alls=12:allf=t",
        "-t",
        "30",
        ...encode,
        join(work, "bait.mp4"),
    ]);
    const baitDir = join(work, "baits");
    spawnSync("mkdir", ["-p", baitDir]);
    ffmpeg(["-f", "lavfi", "-i", "smptebars=size=640x360", "-frames:v", "1", join(baitDir, "blocked.png")]);
    // Segments for a simulated live channel.
    const hlsDir = join(work, "hls");
    spawnSync("mkdir", ["-p", hlsDir]);
    ffmpeg([
        "-i",
        join(work, "good.mp4"),
        "-c",
        "copy",
        "-f",
        "hls",
        "-hls_time",
        "2",
        "-hls_list_size",
        "0",
        "-hls_segment_filename",
        join(hlsDir, "s%03d.ts"),
        join(hlsDir, "all.m3u8"),
    ]);
    const segments = readdirSync(hlsDir)
        .filter((n) => n.endsWith(".ts"))
        .sort();
    let started = Date.now();

    function livePlaylist(moving) {
        const k = moving ? Math.min(Math.floor((Date.now() - started) / 2000), segments.length - 3) : 0;
        let body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:" + k + "\n";
        for (const name of segments.slice(k, k + 3)) body += "#EXTINF:2.0,\n" + name + "\n";
        return body;
    }

    const server = createServer((req, res) => {
        const path = req.url.split("?")[0];
        if (path === "/live/index.m3u8") return res.end(livePlaylist(true));
        if (path === "/stuck/index.m3u8") return res.end(livePlaylist(false));
        if (path === "/clip/index.m3u8") return res.end(readFileSync(join(hlsDir, "all.m3u8")));
        const name = path.split("/").pop();
        try {
            res.end(readFileSync(path.endsWith(".ts") ? join(hlsDir, name) : join(work, name)));
        } catch {
            res.statusCode = 404;
            res.end();
        }
    });
    await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
    const base = "http://127.0.0.1:" + server.address().port;
    const baits = baitHashes(baitDir);
    const run = (path, name) =>
        soak({ url: base + path }, { seconds: SOAK, baits, frameDir: join(work, "frames"), frameName: name });

    try {
        assert.equal(baits.length, 1);

        const good = await run("/good.mp4", "good");
        assert.equal(good.ok, true, good.reason);
        assert.ok(good.wallSeconds >= SOAK * 0.9, "watched at real-time pace: " + good.wallSeconds + " s");
        assert.ok(good.wallSeconds < SOAK + 8, "stopped when the soak ended: " + good.wallSeconds + " s");
        assert.ok(good.frames.length >= 1);

        const short = await run("/short.mp4", "short");
        assert.equal(short.ok, false);
        assert.match(short.reason, /picture stopped after/);

        const still = await run("/still.mp4", "still");
        assert.equal(still.ok, false);
        assert.match(still.reason, /picture frozen/);

        const bait = await run("/bait.mp4", "bait");
        assert.equal(bait.ok, false);
        assert.match(bait.reason, /known block screen \(blocked.png\)/);

        const clip = await run("/clip/index.m3u8", "clip");
        assert.equal(clip.ok, false);
        assert.match(clip.reason, /fixed-length clip/);

        started = Date.now();
        const live = await run("/live/index.m3u8", "live");
        assert.equal(live.ok, true, live.reason);
        assert.equal(live.playlist, "live and moving");

        const stuck = await run("/stuck/index.m3u8", "stuck");
        assert.equal(stuck.ok, false);
        assert.match(stuck.reason, /picture stopped after|stopped moving/);
        writeFileSync(
            join(work, "results.json"),
            JSON.stringify({ good, short, still, bait, clip, live, stuck }),
        );
        t.diagnostic("results: " + join(work, "results.json"));
    } finally {
        server.close();
    }
});
