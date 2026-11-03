// Called by actions/github-script after exactly one site's movie/series checks.
module.exports = async ({ github, context, core }) => {
    const fs = require("node:fs");
    const report = JSON.parse(fs.readFileSync("site-audit/checkpoint.json", "utf8"));
    const site = report.site;
    if (!/^(free99|scrubs|native):[a-z0-9_]+$/.test(site)) throw new Error("Invalid site ID");
    if (!context.ref.startsWith("refs/heads/")) throw new Error("Audit branch required");
    const branch = context.ref.slice("refs/heads/".length);
    if (branch !== "fix/all-vod-site-audit") throw new Error("Unexpected audit branch");
    const { owner, repo } = context.repo;
    const reportPath = `docs/site-audits/runs/${context.runId}/${site.replace(":", "-")}.json`;
    const summarize = (media) => {
        if (!media?.complete) return "Incomplete check; see evidence";
        const lookups = Object.entries(media.lookupOutcomes)
            .map(([status, count]) => `${count} ${status}`)
            .join(", ");
        return `${media.candidatesTested} sources tested; ${media.technicalPasses} technical passes; ${lookups}`;
    };
    // A concurrent human repair may push while an audit is finishing. Re-read the
    // current tree on conflict; never force-push or replace unrelated changes.
    for (let attempt = 0; attempt < 4; attempt++) {
        const ref = await github.rest.git.getRef({ owner, repo, ref: `heads/${branch}` });
        const parent = ref.data.object.sha;
        const commit = await github.rest.git.getCommit({ owner, repo, commit_sha: parent });
        const file = await github.rest.repos.getContent({
            owner,
            repo,
            path: "SITE_FIX_STATUS.md",
            ref: parent,
        });
        const checklist = Buffer.from(file.data.content, "base64").toString("utf8");
        let matches = 0;
        const updated = checklist
            .split("\n")
            .map((line) => {
                if (!line.startsWith("| ") || !line.includes(`(\`${site}\`)`)) return line;
                matches++;
                const label = line.split("|")[1].trim();
                const status = Object.values(report.media).every((media) => media.complete)
                    ? "Checked; repair/content review pending"
                    : "Incomplete; needs follow-up";
                return `| ${label} | ${summarize(report.media.movies)} | ${summarize(report.media.series)} | ${status} | [Evidence](${reportPath}) at ${report.sourceCommit.slice(0, 7)} |`;
            })
            .join("\n");
        if (matches !== 1) throw new Error("Expected exactly one checklist row");
        const tree = await github.rest.git.createTree({
            owner,
            repo,
            base_tree: commit.data.tree.sha,
            tree: [
                {
                    path: reportPath,
                    mode: "100644",
                    type: "blob",
                    content: JSON.stringify(report, null, 4) + "\n",
                },
                { path: "SITE_FIX_STATUS.md", mode: "100644", type: "blob", content: updated },
            ],
        });
        const saved = await github.rest.git.createCommit({
            owner,
            repo,
            message: `audit: save ${site} playback checkpoint`,
            tree: tree.data.sha,
            parents: [parent],
        });
        try {
            await github.rest.git.updateRef({
                owner,
                repo,
                ref: `heads/${branch}`,
                sha: saved.data.sha,
                force: false,
            });
            core.notice(`Saved ${site} checkpoint at ${saved.data.sha}; content review remains pending.`);
            return;
        } catch (error) {
            if (![409, 422].includes(error.status) || attempt === 3) throw error;
        }
    }
};
