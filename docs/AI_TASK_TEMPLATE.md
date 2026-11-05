# AI task brief and PR template

Use this structure in a plan or PR body, scaled to the task. It is a working aid, not a requirement
for the owner to write specifications or approve routine implementation details.

## Intended result

What should the user be able to do after the change? Use a concrete trigger and outcome.

## Acceptance criteria

- Requested behaviour and any exact UI/data requirements.
- Compatibility and existing functionality that must be preserved.
- How the result will be checked.

## Scope

Name the components/files likely to change and explain their connection to the request.
List unrelated findings separately. Do not include a redesign, engine replacement or release
unless it is part of the authorised goal.

## Implementation and evidence

Explain the final change and why it solves the problem. Distinguish facts, measurements,
estimates and assumptions. Link the exact source commit, CI run or public artifact when relevant.

## Validation

Record commands/checks, actual results and expected test counts for filtered runs.
For performance or size claims, specify the quantity and measurement conditions.
For release work, verify version, package, signing identity, both ARM ABIs and public checksum.

## Delivery state

Say whether this is local work, an open PR, a merged change or a published version.
Mention a material remaining blocker plainly; do not invent tests or add recurring status labels.
