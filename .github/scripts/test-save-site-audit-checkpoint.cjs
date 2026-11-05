const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const save = require("./save-site-audit-checkpoint.cjs");

test("one-site checkpoint preserves other rows and retries concurrent branch updates", async () => {
    const cwd = process.cwd();
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "moviehub-checkpoint-"));
    const otherRow = "| BnWMovies (`free99:bnwmovies_com`) | | | | |";
    let attempt = 0;
    const trees = [];
    const updates = [];
    try {
        fs.mkdirSync(path.join(directory, "site-audit"));
        const media = {
            complete: true,
            candidatesTested: 1,
            technicalPasses: 1,
            lookupOutcomes: { complete: 1 },
        };
        fs.writeFileSync(
            path.join(directory, "site-audit/checkpoint.json"),
            JSON.stringify({
                site: "free99:anymovies_co",
                sourceCommit: "a".repeat(40),
                media: { movies: media, series: media },
            }),
        );
        process.chdir(directory);
        const github = {
            rest: {
                repos: {
                    getContent: async () => ({
                        data: {
                            content: Buffer.from(
                                "# Checklist\n| AnyMovies (`free99:anymovies_co`) | | | | |\n" +
                                    otherRow +
                                    "\n" +
                                    (attempt ? "Concurrent manual note\n" : ""),
                            ).toString("base64"),
                        },
                    }),
                },
                git: {
                    getRef: async () => ({ data: { object: { sha: `parent-${attempt}` } } }),
                    getCommit: async () => ({ data: { tree: { sha: `base-${attempt}` } } }),
                    createTree: async (args) => {
                        trees.push(args);
                        return { data: { sha: "tree" } };
                    },
                    createCommit: async () => ({ data: { sha: "checkpoint" } }),
                    updateRef: async (args) => {
                        updates.push(args);
                        if (attempt++ === 0)
                            throw Object.assign(new Error("Concurrent update"), { status: 409 });
                    },
                },
            },
        };
        await save({
            github,
            context: {
                ref: "refs/heads/fix/all-vod-site-audit",
                repo: { owner: "owner", repo: "repo" },
                runId: 123,
            },
            core: { notice: () => {} },
        });
        assert.equal(trees.length, 2);
        assert.equal(trees[1].base_tree, "base-1");
        const checklist = trees[1].tree.find((entry) => entry.path === "SITE_FIX_STATUS.md").content;
        assert.ok(checklist.includes(otherRow));
        assert.ok(checklist.includes("Concurrent manual note"));
        assert.ok(checklist.includes("content review pending"));
        assert.ok(!checklist.includes("should be all good g"));
        assert.ok(updates.every((update) => update.force === false));
        assert.equal(trees[1].tree.length, 2);
    } finally {
        process.chdir(cwd);
        fs.rmSync(directory, { recursive: true, force: true });
    }
});
