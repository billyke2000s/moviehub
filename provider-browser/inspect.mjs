#!/usr/bin/env node
/**
 * Disposable CI Playwright browser inspection of public MovieHub provider pages.
 * No media URLs, secrets, cookies, request bodies, screenshots or raw HTML retained.
 * This does not bypass a challenge/DRM or claim that any media was decoded.
 */
import { chromium } from "playwright";
import { lookup } from "node:dns/promises";
import { readFileSync, writeFileSync, mkdirSync, appendFileSync } from "node:fs";
import { join } from "node:path";
import { publicUrl, publicIPv4, observation, signatures, compare, readTargets } from "./protocol.mjs";

const CONTROL_SELECTOR = [
    "button[data-server]", "button[data-url]", "button[data-src]",
    "a[data-server]", "a[data-url]", "a[data-src]",
    "[class~='server'] button", "[class~='server'] a",
    ".server-list button", ".server-list a", ".servers button", ".servers a",
].join(", ");
const dnsCache = new Map();

function option(name, fallback = "") {
    const args = process.argv.slice(2);
    const index = args.indexOf(name);
    return index >= 0 ? args[index + 1] : fallback;
}

async function publicDns(host) {
    if (!dnsCache.has(host)) {
        dnsCache.set(host, lookup(host, { family: 4, all: true }).then(answers => {
            if (!answers.length || answers.some(a => !publicIPv4(a.address)))
                throw new Error("Public DNS required");
        }));
    }
    return dnsCache.get(host);
}

async function inspect(browser, target) {
    const context = await browser.newContext({
        acceptDownloads: false, serviceWorkers: "block", permissions: [],
        ignoreHTTPSErrors: false,
    });
    const page = await context.newPage();
    const found = [];
    const dedup = new Set();
    const failures = [];
    const report = {
        id: target.id, kind: target.kind, outcome: "not_loaded",
        playerControls: 0, iframeCount: 0, clicked: 0,
        signatures: [], changes: null, mediaEvidence: 0,
        candidateHostChanges: [],
    };
    // WebSockets bypass normal HTTP request routing; deny them completely.
    await context.routeWebSocket("**/*", socket => socket.close());
    // The runner is disposable; DNS validation is not DNS pinning.
    // Production deployment also requires network-level egress isolation.
    await context.route("**/*", async route => {
        const request = route.request();
        try {
            const url = publicUrl(request.url());
            if (!["GET", "HEAD", "POST"].includes(request.method()))
                throw new Error("Unexpected HTTP method");
            await publicDns(url.hostname);
            // Inspect player-network discovery, not actual full video downloads.
            // A blocked media request still contributes a redacted route fingerprint.
            if (request.resourceType() === "media") {
                const item = observation(request.url(), request.method(), "media", null, "video/unknown");
                const key = JSON.stringify(item);
                if (!dedup.has(key) && found.length < 250) {
                    dedup.add(key);
                    found.push(item);
                }
                await route.abort();
                return;
            }
            await route.continue();
        } catch {
            await route.abort().catch(() => {});
        }
    });
    context.on("page", other => {
        if (other !== page) other.close().catch(() => {});
    });
    page.on("dialog", dialog => dialog.dismiss().catch(() => {}));
    page.on("response", response => {
        if (found.length >= 250) return;
        try {
            const request = response.request();
            const item = observation(
                response.url(), request.method(), request.resourceType(),
                response.status(), response.headers()["content-type"] || "",
            );
            const key = JSON.stringify(item);
            if (!dedup.has(key)) {
                dedup.add(key);
                found.push(item);
            }
        } catch {}
    });
    page.on("requestfailed", request => {
        if (failures.length >= 8) return;
        try { failures.push({host: publicUrl(request.url()).hostname, type: request.resourceType()}); }
        catch {}
    });
    try {
        const response = await page.goto(target.url, {
            waitUntil: "domcontentloaded", timeout: 12_000,
        });
        report.outcome = response && response.ok() ? "page_loaded" : "http_or_navigation_failure";
        await page.waitForTimeout(1_000);
        report.iframeCount = Math.min(100, await page.locator("iframe").count());
        report.playerControls = Math.min(100, await page.locator(CONTROL_SELECTOR).count());
        // Record only bounded, public route prefixes, never full data-url values.
        // A channel page can add/reorder players without changing its homepage.
        if (target.id.startsWith("dlhd-channel-")) {
            const prefixes = await page.locator("[data-url]").evaluateAll(elements =>
                elements.map(element => {
                    try {
                        const path = new URL(element.getAttribute("data-url"), location.href).pathname;
                        return /^\\/([a-z0-9-]{1,24})\\/stream-\\d{1,8}\\.php$/i.exec(path)?.[1] || null;
                    } catch { return null; }
                }).filter(Boolean)
            );
            report.playerPrefixes = [...new Set(prefixes)].slice(0, 16);
        }
        if (target.kind === "player") {
            const clicks = Math.min(report.playerControls, target.maxClicks);
            for (let i = 0; i < clicks; i++) {
                try {
                    await page.locator(CONTROL_SELECTOR).nth(i).click({timeout: 2_500});
                    report.clicked++;
                    await page.waitForTimeout(600);
                } catch {
                    // One missing server control must not suppress other observations.
                }
            }
        }
    } catch (error) {
        report.outcome = error?.name === "TimeoutError" ? "timeout" : "browser_navigation_failed";
    } finally {
        report.signatures = signatures(found);
        report.mediaEvidence = found.filter(e =>
            ["hls", "dash", "video", "audio"].includes(e.media)).length;
        report.networkFailures = failures;
        await context.close().catch(() => {});
    }
    return report;
}

async function main() {
    const targetsFile = option("--targets");
    if (!targetsFile) throw new Error("Missing --targets JSON inventory");
    const targets = readTargets(JSON.parse(readFileSync(targetsFile, "utf8")));
    const output = option("--output", "build/provider-browser");
    const baseline = option("--baseline");
    let previous = {};
    if (baseline) try {
        const old = JSON.parse(readFileSync(baseline, "utf8"));
        previous = Object.fromEntries((old.targets || []).map(t => [t.id, t.signatures || []]));
    } catch {
        // No prior successful run or an incompatible artifact: establish a baseline.
    }
    mkdirSync(output, {recursive:true});
    const results = [];
    const browser = await chromium.launch({headless: true, timeout: 20_000});
    try {
        for (const target of targets) {
            let item;
            try { item = await inspect(browser, target); }
            catch {
                item = { id: target.id, kind: target.kind, outcome: "inspection_failed",
                    playerControls: 0, iframeCount: 0, clicked: 0, signatures: [],
                    changes: null, mediaEvidence: 0, candidateHostChanges: [] };
            }
            if (Object.hasOwn(previous, target.id)) {
                item.changes = compare(previous[target.id], item.signatures);
                item.candidateHostChanges = item.changes.added.filter(value =>
                    / (?:hls|dash|video|audio|json)$/.test(value)).slice(0, 12);
            }
            results.push(item);
            console.log(item.id + ": " + item.outcome + ", " +
                item.playerControls + " controls, " + item.mediaEvidence + " media signatures");
        }
    } finally {
        await browser.close();
    }
    const report = {
        generatedAt: new Date().toISOString(),
        status: "diagnostic_only_not_verified_playback",
        targets: results,
    };
    writeFileSync(join(output, "report.json"), JSON.stringify(report, null, 2));
    const lines = [
        "## Playwright website protocol inspection", "",
        "Site/network-shape evidence only. No signed URLs, login data, bodies or screenshots.",
        "HTTP media signatures do not prove that a stream plays.", "",
        "| Target | Result | Controls | Iframes | Media signatures | New / missing patterns |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
        ...results.map(item => "| " + item.id + " | " + item.outcome + " | " +
            item.playerControls + " | " + item.iframeCount + " | " +
            item.mediaEvidence + " | " +
            (item.changes ? item.changes.added.length + " / " + item.changes.missing.length :
                "no baseline") + " |"),
        "", "### New media/API route shapes (review before changing extractors)", "",
    ];
    for (const item of results) {
        if (!item.candidateHostChanges?.length) continue;
        lines.push("- " + item.id + ": " + item.candidateHostChanges.length + " changed signatures");
        lines.push(...item.candidateHostChanges.slice(0,5).map(s => "  - " + s.replaceAll("|", "/")));
    }
    if (!results.some(item => item.candidateHostChanges?.length))
        lines.push("No new media/API patterns (or no prior baseline).");
    const text = lines.join("\n") + "\n";
    writeFileSync(join(output, "report.md"), text);
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, text);
}

await main();
