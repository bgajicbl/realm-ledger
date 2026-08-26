---
description: Draft a new spec from the template and interrogate it before writing any code
argument-hint: <slug>, e.g. wallet-refund
---

Draft a new spec for: $ARGUMENTS

1. Read `specs/TEMPLATE.md` and the most recent implemented spec in `specs/` so the new
   one matches their shape and level of detail.
2. Pick the next spec number and a two-letter criterion prefix that is not already in use.
3. Write the spec to `specs/<NNN>-<slug>.md` with `status: draft`.
4. Then stop and challenge your own draft, out loud, against these:
   - Which criterion could two people read differently? Rewrite it.
   - Which criterion has no assertion you could name? It is not testable; split or cut it.
   - What is the failure path for each happy path? A spec that only describes success is
     half a spec.
   - What would a careless implementation break that no criterion forbids? That belongs
     in **Invariants**.
   - What is genuinely undecided? That belongs in **Open questions**, and while that
     section is non-empty the spec must not be implemented.

Do not write any production code in this command. The output is one markdown file and a
list of the questions you could not answer.
