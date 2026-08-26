---
description: Implement one spec, criterion by criterion, with the fast check between each
argument-hint: <spec id>, e.g. 002
---

Implement spec $ARGUMENTS.

Use the `spec-implementer` agent for the implementation work.

Before starting:

1. `spec_read` the spec. If **Open questions** is non-empty, stop and list what needs
   deciding. Do not resolve it yourself.
2. `spec_coverage` for this spec, so you know which criteria already have tests.
3. Read only the packages the spec names.

Then, for each uncovered criterion, in order:

1. Write the test first, with `@SpecRef("<id>")`, asserting what the **spec text** says.
   Do not read the implementation to decide what to assert.
2. Run `core_check`. It should fail for the right reason. If it passes, the test is not
   testing anything and you have to fix the test before the code.
3. Implement the smallest change that makes it pass.
4. Run `core_check` again. Move on only when green.

When every criterion is covered:

- Run `spec_coverage` and confirm nothing is `UNCOVERED`.
- Run `run_tests` once.
- Set the spec's `status` to `implemented` and resolve or delete **Open questions**.
- Summarise, in the final message: what you changed, which criteria each change serves,
  anything in the spec you found ambiguous, and anything you would push back on. That
  last part is not optional; if you have nothing, say so explicitly.

Do not open a pull request. Do not commit. A human reads the diff first.
