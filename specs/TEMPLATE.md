---
id: NNN
title: Short imperative title
status: draft
owner: your-name
---

## Context

Two or three sentences. What exists today, what is missing, and why it matters now.
Link the code that already exists so the implementer does not go looking for it.

## Acceptance criteria

Each criterion gets a stable id and must be testable by a machine. If you cannot see the
assertion while writing it, the criterion is too vague and the agent will interpret it
into something you did not ask for.

Rules that keep this format honest:

- One observable behaviour per criterion. "Handles retries correctly" is three criteria.
- State the expected outcome, including the failure outcome, not just the happy path.
- Ids are permanent. Never renumber; retire a criterion by striking it, not by reusing it.

### XX-1 — Behaviour in one line

Given / when / then, in prose. Name the status code, the value, or the count that a test
would assert on.

### XX-2 — Next behaviour

...

## Out of scope

The list that stops scope creep. An agent will happily build the thing you did not ask
for and it will look reasonable in review, so write this section even when it feels
obvious.

## Invariants

Properties that must hold after this change and that no test in this spec is allowed to
weaken. These are the things a reviewing agent should try hardest to break.

## Open questions

Anything you have not decided. If this section is non-empty, the spec is not ready to
implement: the agent will resolve the question by guessing and will not tell you it did.
