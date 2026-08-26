# The workflow

What this repository is actually for. The service is real enough to have real problems,
but the point is the loop that builds it.

## The problem it solves

Agents are good at producing code that passes the tests they also wrote. That is a closed
loop with nothing outside it, and it fails in a specific way: the criterion that was
hardest to satisfy is the one that quietly ends up with no test, and the diff looks
complete. Nobody notices until production does.

Everything here exists to break that loop open.

## The four moving parts

**1. A spec with numbered criteria.** `specs/NNN-*.md`. Each criterion has a permanent id
(`WD-4`) and describes one observable behaviour, including its failure path. If the
criterion has no assertion you can name while writing it, it is not a criterion yet.

The **Open questions** section is load-bearing. While it is non-empty the spec must not be
implemented, because an agent resolves ambiguity by choosing, and it does not tell you it
chose.

**2. An implementer that works one criterion at a time.** `/implement-spec`. Test first,
from the spec text, then `core_check`, then the smallest change. The reason for test-first
here is not ideology: a test written after reading the implementation asserts what the
code does, which is worthless when the code is wrong.

**3. A verifier that only sees the spec and the diff.** `/verify-spec`. It does not get
the implementation conversation. A reviewer told the intent reads the intent into the
code; withholding it is the only way to get an actual second opinion rather than an
agreeable one. It is instructed to bias toward `FAIL`.

**4. A gate that does not care who wrote the code.** `make verify`:

| Gate | Catches | Time |
|---|---|---|
| `layering` | Framework imports leaking into the domain | instant |
| `core-check` | Broken domain rules | ~1s |
| `spec-gate` | A criterion with no test; a test citing a criterion that does not exist | instant |
| `test` | Everything else | minutes |
| migration guard (CI) | An applied migration being edited | instant |

The first three run before Maven touches the network, so the common failures fail in
seconds.

## Custom tooling, and what earns its place

`tools/mcp_server.py` exposes six tools. The rule for adding one: **a tool earns its place
when it turns a judgement call into a fact.**

- `spec_coverage` qualifies. "Which criteria does this change satisfy" needs the spec and
  the test annotations cross-referenced. Ask a model to do that by reading files and it
  will be right most of the time, and "most of the time" is the failure this workflow
  exists to remove.
- `run_tests` qualifies for a duller reason: raw Maven output is thousands of lines of
  which about four matter, and pushing the rest through the context window is slow and a
  good way to lose the four.
- `core_check` qualifies because it makes a fast loop available at all.
- A tool that wrapped `cat` would not qualify. The agent already has a shell.

`tools/hooks/check_layering.sh` is the other half. Told to "add a repository lookup", an
agent annotates the application service with `@Service` and injects a Spring repository,
because that is what the surrounding ecosystem looks like. Saying "do not" in `CLAUDE.md`
worked about four times in five. A `PostToolUse` hook that exits 2 works every time, and
it works for humans too. **When a rule matters, encode it; do not re-explain it.**

## Context discipline

The repository is small. A real one is not, and the technique has to survive that, so it
is used here anyway:

- Start from `spec_read`, not from browsing the tree.
- Read the package the spec names and its tests. Nothing else.
- `changed_files` scopes review to the diff.
- `CLAUDE.md` carries the architecture so that "get oriented" never means reading `src/`.

The failure this prevents is subtle. An agent given the whole repository does not get
confused; it gets *plausible*. It picks up a pattern from an unrelated package and applies
it somewhere it does not belong, and the result reviews well.

## What this workflow does not do

Worth saying plainly, because the gaps are where the interesting questions are:

- **It does not review quality.** The gate proves a criterion has a test, not that the
  test is good. A human still reads the diff.
- **It does not scale to a large diff.** Everything here assumes changes small enough for
  a person to actually read. That is a constraint, not an oversight: an 800-line agent
  diff means the task was scoped wrong, and no amount of tooling fixes that after the
  fact.
- **It has no opinion on cost.** No token budget, no model routing. The obvious next step
  is a cheaper model for the mechanical steps and the expensive one for the verifier.
- **The verifier can be wrong.** It is one adversarial pass, not a panel. On something
  where a false pass really hurts, three verifiers with different lenses and a majority
  vote would be the honest design.
