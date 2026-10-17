# Rules for AI assistants working on this repository

Read [ARCHITECTURE.md](ARCHITECTURE.md), [TESTING.md](TESTING.md) and [STATUS.md](STATUS.md)
before changing code.

## Keep the code readable

- Format with prettier before committing (see [DEVELOPMENT.md](DEVELOPMENT.md#code-style)).
  Never put several statements on one line or write very long lines to save space. The whole app
  was once a single 115 KB file of 2,000-character lines, and no assistant could edit it reliably.
- Put code in the feature class for its area. If a file passes about 700 lines, split it by screen
  in a separate commit that changes no behaviour.
- Do not add a new pattern (Fragments, Kotlin, a DI framework, XML layouts) without the owner
  agreeing first. Follow the existing screen pattern described in ARCHITECTURE.md.

## Verify, and report honestly

- Run the core checks locally. Open a pull request and read the CI result before saying a change
  works.
- Device tests are currently flaky. Do not call a red run "unrelated" without evidence: compare it
  with a run of unchanged `main`, and say plainly what you compared.
- Never claim something was tested on a real TV or a real provider account unless it was. The
  fixtures do not prove live provider behaviour.
- If you could not run something, say so in the PR description.

## Changing members used by tests

Some device tests reach private members by name through reflection. Before renaming or moving a
member, search `app/src/androidTest` for its name and update the test in the same commit. The list
is in [TESTING.md](TESTING.md#members-reached-by-reflection).

## Git and releases

- Work on a branch and open a pull request. Do not push directly to `main`.
- Keep a refactor and a behaviour change in separate commits so each can be reviewed.
- Merging does not publish an update. Only run the publish workflow when the owner asks, and never
  change the signing key, the package name `local.tv.home`, or the update feed paths.
- Never commit credentials, keystores, provider URLs or account details.

## Keep the docs current

When behaviour, structure or test expectations change, update the matching document in the same
pull request: README.md (overview), ARCHITECTURE.md (code map), TESTING.md, SECURITY.md,
STATUS.md (current state and known issues) and CHANGELOG.md (Unreleased section).
