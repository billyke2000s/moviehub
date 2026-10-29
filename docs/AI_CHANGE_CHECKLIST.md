# AI change checklist

Use with [AGENTS.md](../AGENTS.md) and [AI working rules](AI_WORKING_RULES.md).
Record the relevant evidence in the PR body; do not turn this into a new user approval ritual.

## Before editing

- Identify whether the request asks for explanation, implementation, merge or publication.
- Restate the intended result and acceptance criteria in plain language.
- Read current architecture, status, testing and the code paths involved.
- Inspect the branch, working tree and existing user changes.
- List the expected affected components. Separate unrelated findings.
- Reuse existing authorisation and incorporate the latest user corrections.

## Before submitting

- Review every changed file against the acceptance criteria.
- Explain any file or behaviour outside the expected scope; remove accidental changes.
- Preserve UI/navigation, provider identities, request headers and saved user data.
- Keep production ARM32 and ARM64 in one APK; preserve the explicit emulator override.
- Keep package name, signing identity, update paths and database migration compatibility.
- Format changed code and run `git diff --check`.
- Run meaningful checks appropriate to the change and record actual outcomes.
- Update matching docs and version notes without overwriting historical evidence.
- Open a PR with a concrete description and validation results.

## Additional checks by change type

| Change                      | Required evidence                                                                                                     |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Documentation               | Facts match code/current release, new links resolve, diff has no accidental truncation                                |
| Source metadata or identity | Distinct source/header cases, genuine episode evidence, honest reported/measured labels                               |
| Playback/session/relay      | Relevant core and Android playback, resume, cancellation and failover cases                                           |
| Packaging                   | Actual ABI directories and binaries, Python ABI payloads where relevant, APK inspection and signing compatibility     |
| Optimisation                | Features preserved; measurement states APK size, installed storage, RAM or timings explicitly                         |
| Merge                       | Expected PR heads and combined tree; both changes' documentation and test lists retained                              |
| Publication                 | Authorised app/version, standard publisher success, increasing code, signature/package/ABI checks and public checksum |

## Before claiming completion

- Inspect final CI results; do not use a superseded green run for changed app code.
- Verify requested test filters actually ran and report failures/skips accurately.
- Investigate failures rather than dismissing or silently weakening checks.
- Confirm external actions in the repository/workflow/release state.
- Verify the published manifest and actual APK when publication is requested.
- State what was tested, with no invented provider or device results.
- Report the real state: local, PR open, merged, or published.

## Stop and scope changes

When the owner says stop, stop task-owned mutations and cancel pending publication where possible.
Preserve work and explain any action that already completed. Resume only within the owner's new
direction. A request for an explanation during work is not automatically a replacement objective;
answer it, respect any new constraints and retain the original authorised goal unless cancelled.
