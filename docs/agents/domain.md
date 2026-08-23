# Domain Docs

This project uses a single domain context.

## Before exploring

- Read the root `CONTEXT.md` and use its canonical terms.
- Read ADRs under `docs/adr/` that affect the area being changed.
- If either location is absent, proceed without proposing speculative documentation.

## Vocabulary

Use the glossary's preferred terms in specs, issue titles, tests and implementation. Avoid synonyms explicitly listed under `_Avoid_`. If a necessary product concept is missing, record the gap for domain modeling instead of silently inventing competing terminology.

## Decisions

Surface any conflict with an existing ADR explicitly. Do not silently override or contradict a recorded decision.

## Layout

```text
/
├── CONTEXT.md
├── docs/
│   └── adr/
└── src/
```
