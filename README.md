# realm-ledger

A small Java 21 service — player wallet and season leaderboards for a persistent-world
strategy game — built to carry an agentic development workflow that is actually enforced.

The service is real enough to have real problems: idempotent debits under concurrent
retries, a derived balance over an append-only ledger, tie-stable leaderboard paging on
Redis sorted sets. The workflow around it is the part worth reading.

## The short version

Work is driven by specs with numbered acceptance criteria. An implementer agent works one
criterion at a time, test first. A verifier agent then checks the diff against the spec
**without** seeing the reasoning that produced it. A gate in CI fails the build when an
implemented spec has a criterion no test claims — whoever, or whatever, wrote the code.

```
specs/001-idempotent-wallet-debit.md    implemented, 9 criteria, all covered
specs/002-season-leaderboard-page.md    draft, 3 criteria uncovered  <- run the loop on this
```

Read [`docs/WORKFLOW.md`](docs/WORKFLOW.md) for why each part exists, and
[`docs/RUNS.md`](docs/RUNS.md) for what has gone wrong.

## Try it

Requires JDK 21 and Python 3.10+. The first three targets need neither Maven nor Docker
nor network.

```bash
make core-check    # compiles and runs the pure domain with javac; ~1s, 21 assertions
make layering      # architecture guard: no Spring/JPA/Jackson inside the domain
make spec-gate     # acceptance criteria vs @SpecRef annotations in the tests
make test          # full Maven suite
make verify        # everything CI runs
```

See the gate do its job:

```bash
python3 tools/spec_gate.py --spec 002     # exits 1: LB-3, LB-4, LB-5 have no test
```

See the hook do its job — add `import org.springframework.stereotype.Service;` to
`application/WalletService.java` and run `make layering`. It exits 2 and explains itself.

## The custom tooling

`tools/mcp_server.py` — a dependency-free MCP server over stdio:

| Tool | What it answers |
|---|---|
| `spec_list` | Which specs exist, and their status |
| `spec_read` | One spec verbatim, so the agent does not paraphrase a criterion |
| `spec_coverage` | Which criteria have a test, which tests cite a criterion that is gone |
| `core_check` | Do the domain rules still hold (~1s) |
| `run_tests` | The full suite, filtered down to the lines that matter |
| `changed_files` | The diff, so review can be scoped to it |

```bash
claude mcp add realm-ledger -- python3 tools/mcp_server.py
```

The rule for adding a tool: it earns its place when it turns a judgement call into a fact.
`spec_coverage` does. A wrapper around `cat` would not.

`tools/hooks/check_layering.sh` — a `PostToolUse` hook that blocks an edit importing
Spring, JPA or Jackson into `core/`, `port/` or `application/`. It exists because asking
nicely in `CLAUDE.md` worked about four times out of five.

## Layout

```
CLAUDE.md                  the contract for agent work here
.claude/commands/          /new-spec, /implement-spec, /verify-spec
.claude/agents/            spec-implementer, spec-verifier
.claude/settings.json      hooks
specs/                     acceptance criteria with permanent ids
tools/spec_gate.py         the gate
tools/mcp_server.py        the custom tools
tools/hooks/               the architecture guard
tools/core-selfcheck/      dependency-free domain check
docs/WORKFLOW.md           why each part exists, and what it does not do
docs/ARCHITECTURE.md       the service itself
docs/RUNS.md               the honest journal
```

## Status

The domain core, the gate, the hook and the MCP server are verified working. The Spring
layer compiles clean under `--release 21 -Xlint:all -Werror` (verified against
signature-faithful stubs, since Maven Central was unreachable where this was authored) but
`mvn test` has not been run end to end. Do that first on a machine with a warm Maven
repository.

`docs/RUNS.md` entry 4 is the one to read: an adversarial review found that
`@Transactional` on the debit endpoint broke WD-6 — the criterion the whole design exists
for — while the spec gate reported that criterion fully covered. Both facts were true at
once, and the gap between them is the interesting part of this repository.
