import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
const api = (path) =>
    JSON.parse(execFileSync("gh", ["api", path], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }));
const root = "TheBinaryNinja/masqueradarr";
try {
    const commit = api(`repos/${root}/commits/HEAD`).sha;
    const tree = api(`repos/${root}/git/trees/${commit}`);
    const server = tree.tree.find((e) => e.path === "server");
    let repo = root,
        ref = commit,
        path = "server/src/sources/adapters/freelivesports/config.ts";
    if (server?.type === "commit") {
        // Resolve the pinned submodule, never guess a different public fork or substitute an old snapshot.
        repo = "iFlip721/masqueradarr-source";
        ref = server.sha;
        path = "src/sources/adapters/freelivesports/config.ts";
    }
    const license = api(`repos/${repo}/contents/LICENSE?ref=${ref}`);
    const licenseText = Buffer.from(license.content, "base64").toString("utf8").trim().replace(/\r\n/g, "\n");
    const auditedLicense = readFileSync("provider-engine/upstream/LICENSE", "utf8")
        .trim()
        .replace(/\r\n/g, "\n");
    if (licenseText !== auditedLicense) throw new Error("Upstream licence requires compatibility review");
    const file = api(`repos/${repo}/contents/${path}?ref=${ref}`);
    const source = Buffer.from(file.content, "base64").toString("utf8");
    if (source.length > 128 * 1024) throw new Error("Provider source exceeds runtime capability");
    writeFileSync("provider-engine/upstream/freelivesports.ts", source);
    writeFileSync(process.env.GITHUB_ENV, `UPSTREAM_SHA=${commit}\n`, { flag: "a" });
    console.log(`Retrieved FreeLiveSports module from pinned source ${ref}`);
} catch (error) {
    console.error(
        "Upstream provider update blocked: pinned server submodule/module is unavailable, or its licence/dependencies require review. Existing installed/released packages are retained. Authorised access to iFlip721/masqueradarr-source is required.",
    );
    process.exitCode = 1;
}
