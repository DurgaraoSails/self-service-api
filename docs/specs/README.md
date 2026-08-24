# Specs

This directory holds one living markdown spec per non-trivial feature or system in `self-service-api`.

## Convention

- A spec is written **before** implementation starts, using `_TEMPLATE.md` as the starting structure.
- A spec is a **living document**: as implementation surfaces new decisions, tradeoffs, or corrections to the original plan, the spec is updated in place — not left stale and not rewritten only after the fact.
- File naming: `docs/specs/<feature-name>.md`, kebab-case, matching the feature it describes (e.g. `jwt-authentication.md`).
- Each spec's `Changelog` section records what changed and why, so the document's history stays legible without needing git blame.

## When to write one

Any change that involves an architectural decision, a new data model, a new API surface, or a security-relevant tradeoff gets a spec. Small, self-contained bug fixes or one-line changes don't need one.
