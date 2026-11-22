import test from "node:test";
import assert from "node:assert/strict";
import {
    publicUrl, publicIPv4, routePattern, mediaKind, observation,
    signatures, compare, readTargets,
} from "../protocol.mjs";

test("refuse local, credentials, IP and insecure destinations", () => {
    for (const url of [
        "http://example.org/watch", "https://127.0.0.1/admin",
        "https://[::1]/", "https://localhost/", "https://host.local/",
        "https://host.example/", "https://user:password@example.org/path",
        "https://example.org:8443/", "https://169.254.169.254/latest/",
    ]) assert.throws(() => publicUrl(url));
    assert.equal(publicUrl("https://primewire1.buzz/watch/the-odyssey").hostname, "primewire1.buzz");
    for (const ip of ["10.0.0.1", "172.31.2.3", "192.168.1.1", "100.64.0.1",
        "127.0.0.1", "169.254.1.1", "198.18.0.1", "203.0.113.9"])
        assert.equal(publicIPv4(ip), false);
    assert.equal(publicIPv4("8.8.8.8"), true);
});

test("erase identifying path segments and all query values", () => {
    const raw = "https://cdn.example.net/api/v2/resolve/abcd-secret?token=never-log";
    assert.equal(routePattern(raw), "api/v2/resolve/:id");
    assert.ok(!JSON.stringify(observation(raw, "GET", "xhr", 200, "application/json")).includes("never-log"));
    assert.ok(!JSON.stringify(observation(raw, "GET", "xhr", 200, "application/json")).includes("abcd-secret"));
});

test("compare player fingerprints without carrying URLs or tokens", () => {
    const a = observation("https://old.example.net/watch/abc?key=x", "GET", "document", 200, "text/html");
    const b = observation("https://new.example.net/master.m3u8?token=secret", "GET", "media", 206, "application/vnd.apple.mpegurl");
    const diff = compare(signatures([a]), signatures([a,b]));
    assert.equal(diff.added.length, 1);
    assert.equal(diff.missing.length, 0);
    assert.equal(mediaKind("application/dash+xml"), "dash");
    assert.ok(!JSON.stringify(diff).includes("secret"));
});

test("only explicit trusted browser targets are accepted", () => {
    assert.deepEqual(readTargets([{
        id: "primewire", url: "https://primewire1.buzz/watch/the-odyssey",
        kind: "player", maxClicks: 99,
    }])[0].maxClicks, 4);
    assert.throws(() => readTargets([{id: "x", url: "http://localhost", kind: "landing"}]));
    assert.throws(() => readTargets([
        {id:"x",url:"https://example.org",kind:"landing"},
        {id:"x",url:"https://example.org",kind:"landing"},
    ]));
});
