# AI working rules

These are the owner's standing project instructions for MovieHub. Read them with the root
[AGENTS.md](../AGENTS.md). They apply to every AI-assisted change, including fixes, optimisation,
documentation, pull requests and releases. The latest explicit user instruction defines the task;
older plans and examples do not grant new permission.

These documents improve consistency; they are not a claim that an AI cannot make a mistake.
The APK inspector, tests, signing checks and reviewable diffs provide additional concrete checks.

## 1. Identify the requested job

| User intent                             | Required response                                                                           | Do not assume                                                 |
| --------------------------------------- | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| Explain, compare, investigate, evaluate | Inspect and report evidence; keep implementation read-only                                  | Permission to rewrite, merge or publish                       |
| Fix, add, optimise, implement           | Prepare and validate the smallest complete change on a branch                               | Permission to redesign unrelated behaviour                    |
| Continue                                | Resume the existing authorised scope and constraints                                        | A new objective or reset of previous corrections              |
| Merge                                   | Merge the identified work, reconciling overlaps and checking the combined result            | Permission to publish an APK                                  |
| Publish                                 | Use the authorised app/feed and existing signed release process                             | Permission to replace signing identity or publish another app |
| Stop                                    | Stop mutations and task-owned pending publication where possible; preserve and report state | Permission to finish an external action anyway                |

Before editing, give a short work brief: intended result, acceptance criteria and files/components
likely to change. Use the [task template](AI_TASK_TEMPLATE.md) in the plan or PR body; do not create
a new permanent planning document for every small request. An explanation-only request needs findings,
not an implementation plan disguised as permission.

Reuse current authorisation. If the user already asked for a concrete action, do the necessary
preparation and validation without repeatedly asking. When a newly discovered choice would expand
scope materially, explain the trade-off and ask about that choice. Keep unrelated findings separate.

## 2. Preserve the product the owner requested

- Inspect existing behaviour before editing it. Trace callers, UI entry points and stored state.
- Keep the present native Android screen pattern and UI unless the task explicitly changes them.
- Do not add menus, source grouping, decoder selectors, buttons or extra navigation as an incidental fix.
- Preserve accounts, favourites, history, progress, preferences, launcher role and database migrations.
- Keep each distinct source usable as its own entity. Shared URLs can require different provider
  identities, cookies and headers; deduplicating solely by URL loses information.
- Preserve the requested resolution/audio/site information in source rows. Report unavailable or
  provider-reported metadata honestly; do not invent verified audio or quality badges.
- Keep provider URLs and the supported provider set unless the task authorises source changes.
- A player replacement, provider-runtime rewrite or new framework is a separate product decision.
  Similar visible features in another app do not prove its backend is a drop-in replacement.

Examples: “Why is it large?” calls for measurement and explanation. “Add audio information” does
not authorise a source-menu redesign. “Optimise it” does not authorise removing compatibility.
“Can both ARM variants go in one APK?” is a question until the owner requests that packaging change.

## 3. Architecture compatibility is not a size shortcut

The current standard release contract is one APK containing:

- `armeabi-v7a` for 32-bit ARM Android firmware;
- `arm64-v8a` for 64-bit ARM Android firmware.

`moviehubAbi=arm` selects both. Single-ABI diagnostic builds remain available, and
`moviehubAbi=x86_64` is reserved for emulator builds. Verify the current Gradle configuration rather
than copying an old command. Do not publish an emulator APK to the normal updater feed.

The owner has an S905X5M box with 32-bit Android firmware. Version 1.0.17 incorrectly removed ARM32
libraries based on the processor model; the owner could not install it. Version 1.0.18 restored both
ARM ABIs. A 64-bit CPU does not establish the firmware's supported app ABIs. Use device-reported
ABIs or the owner's actual firmware information when diagnosing compatibility.

Before publishing, inspect the built signed APK, not just `abiFilters` source text. The existing
inspector requires both ARM directories, matching native-library coverage and stripped debug sections.
Changes to these checks must preserve this contract unless the owner explicitly changes the target.

## 4. Measure the right size and performance quantities

| Quantity                  | What the evidence must describe                                                          |
| ------------------------- | ---------------------------------------------------------------------------------------- |
| APK/download size         | The actual APK file size and its compression settings                                    |
| Installed storage         | APKs plus extracted libraries, compiled code, app data and caches on the measured device |
| RAM                       | Measured process memory under a stated workload                                          |
| Startup or playback speed | Measured timings under comparable conditions                                             |

Compressed and uncompressed library sizes are not comparable numbers. Native compression can
reduce the APK download while retaining extracted copies on the device. Do not describe that as
an installed-storage or RAM improvement without measuring those quantities.

Label predictions as estimates. After building, replace estimates with measured results where
available. Do not claim a universal reduction from one device or one fixture. Do not remove codecs,
Python, VLC, architectures or features simply because another app is smaller.

Before calling code dead, check production references, manifest entries, JNI, Python/Java bridges,
reflection, persisted data and migrations. Keep a pure relocation/refactor separate from behaviour
changes so its preservation of functionality can be reviewed.

## 5. Playback and provider changes require evidence

- Read the existing source/session, relay, failover, resume and episode code before changing it.
- Preserve healthy playback while preparing backups; retain cancellation and stale-result guards.
- Keep original provider lookup evidence and request headers. Never invent an episode filename
  and then use that invented filename as proof of a match.
- A successful HTTP response or provider search is not decoded playback. A video clock is not
  proof of decoded video and audio. A short fixture is not proof of a full-length provider movie.
- Keep current buffering, waiting and source-selection settings unless changing them is requested.
  The source code and targeted tests define exact thresholds; do not copy stale historical notes.
- Do not remove URL validation, credential-origin restrictions or transport limits to make a link work.
- Distinguish a changed website/parser, expired link, decoder failure and incompatible APK rather
  than treating every failure as the same problem.

See [architecture](../ARCHITECTURE.md), [security](../SECURITY.md) and
[testing](../TESTING.md) for implementation details and commands.

## 6. Validation and factual reporting

Run checks appropriate to the change. Code changes need relevant core/behaviour checks and the
required Android CI. Packaging changes need actual APK inspection and signing compatibility.
Markdown-only changes need factual, link and diff checks; do not rerun long playback suites without
a reason. Follow [the checklist](AI_CHANGE_CHECKLIST.md).

Read the final commit's CI results. A green Gradle command can run zero requested test cases;
verify expected filters, counts, failures and skips. Treat a failing device test as a problem,
inspect its logs and compare with unchanged main before declaring it unrelated. Never weaken a
test or release gate merely to get a green result.

State exactly what ran and what it established. Keep provider-reported metadata separate from
measured metadata, and automation results separate from owner-reported observations. Do not
invent physical-TV installation or playback results. List the actual validation performed without
adding the recurring TV-installation status label the owner asked to remove.

Use plain language in updates and final reports. State what changed, why, what was checked and
whether the work is local, pushed, merged or published. Do not announce success after an attempted
tool call until the remote state or public artifact confirms it.

## 7. Release and documentation boundaries

Use a branch and PR; preserve unrelated work. Never push directly to main. Resolve combined
documentation and test-list conflicts without discarding either feature's checks.

Merging and publishing are separate actions. Publish only within the owner's current authorisation.
Use the existing normal or experimental publisher for the requested app, its permanent signing
identity and its feed. Keep `local.tv.home`, existing accounts/data and updater paths intact.
Never commit signing keys, credentials, account details or private provider links.

The release version code must exceed the published baseline. Verify package, signing continuity,
ABI coverage and public checksum before reporting publication. A numbered release and updater feed
must contain the same APK. Ordinary documentation edits must not trigger a new app publication.

Keep current release notes, build commands and validation evidence current in the same PR.
Mark dated development notes as history instead of presenting old pending work as current status.
Do not change an old report into proof of testing that never occurred. Tool-specific instruction
files point here and to AGENTS.md; maintain one canonical policy rather than divergent copies.
