# Issue tracker: Local Markdown

Issues and specs for this repo live as Markdown files in `.scratch/`.

## Conventions

- One feature per directory: `.scratch/<feature-slug>/`
- The spec is `.scratch/<feature-slug>/spec.md`
- Implementation issues are one file per ticket at `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numbered from `01`
- Triage state is recorded as a `Status:` line near the top of each issue file
- Comments and conversation history append under a `## Comments` heading

## Publishing

When a skill says “publish to the issue tracker”, create the corresponding Markdown file under `.scratch/<feature-slug>/`, creating the directory if needed.

When a skill says “fetch the relevant ticket”, read the referenced Markdown file. The user will normally provide its path or issue number.

## Wayfinding operations

- Map: `.scratch/<effort>/map.md`
- Child ticket: `.scratch/<effort>/issues/NN-<slug>.md`
- Blocking: a `Blocked by: NN, NN` line near the top
- Frontier: open, unblocked, unclaimed files under the effort's `issues/` directory, lowest number first
- Claim: set `Status: claimed` before starting work
- Resolve: append an `## Answer`, set `Status: resolved`, and add the decision pointer to the map
