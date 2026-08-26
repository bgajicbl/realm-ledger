---
description: Adversarially check a diff against its spec, seeing only the spec and the diff
argument-hint: <spec id>, e.g. 002
---

Verify spec $ARGUMENTS against the working tree.

Use the `spec-verifier` agent. Give it only the spec and the diff. Do not pass it the
implementation conversation, the reasoning that produced the change, or a summary of what
was intended: those are exactly what stop a reviewer from seeing what is actually there.

The verifier's job is to find the gap, not to approve. Report its verdict verbatim.

If the verdict is anything other than `PASS`, do not fix the findings in this command.
List them and stop. Deciding what to do about a finding is the human's call.
