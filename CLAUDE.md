# realm-ledger

Player wallet and season leaderboards for a persistent-world strategy game. Java 21,
Spring Boot, MySQL, Redis.

This file is the contract for agent work in this repository. It is short on purpose:
every line here is a line that has to be true on every run, and a long file is a file
nobody re-reads.

## The loop

Work is driven by specs, not by prose in a chat. Nothing gets implemented that does not
have a spec with numbered acceptance criteria in `specs/`.

```
/new-spec <slug>      write the spec, fill in acceptance criteria, resolve open questions
/implement-spec <id>  implement it, one criterion at a time
/verify-spec <id>     an adversarial pass that only sees the spec and the diff
make verify           the gate CI runs: layering, self-check, spec coverage, tests
```

Never skip `/verify-spec` because the tests are green. Green tests mean the code does
what the tests say, not what the spec says.

## Architecture, and the one rule that matters

```
core/         pure domain. No framework, no I/O, no clock, no logging.
port/         interfaces the domain needs from the outside world.
application/  orchestration. Framework-free; wired explicitly in config/DomainConfig.
adapter/      Spring, JPA, Redis, HTTP. All framework code lives here and nowhere else.
```

`core/`, `port/` and `application/` must compile and test with no Spring, JPA, Jackson or
Hibernate on the classpath. A `PostToolUse` hook enforces this and will block an edit that
breaks it. If the hook fires, the fix is a port interface, never a suppression.

That rule is what makes `make core-check` possible: a full run of the domain rules in
about a second, with no Maven, no Docker and no network. Use it constantly.

## Working rules

- **One criterion at a time.** Implement `WD-3`, run `core_check`, then move to `WD-4`.
  Do not write the whole spec and then test it; a large diff that fails somewhere is
  worse than no diff.
- **Every test carries a `@SpecRef`.** The criterion it proves. `make spec-gate` fails
  the build when an implemented spec has a criterion no test claims. A test that proves
  something not in a spec needs no annotation, and that is fine.
- **Tests assert intended behaviour, not observed behaviour.** If a test is written by
  reading the implementation, it locks in whatever bug the implementation has. Write the
  assertion from the spec text, then run it, then fix whichever side is wrong.
- **Do not touch the migration history.** Applied migrations are immutable. A schema
  change is a new `V<n>__` file.
- **Do not weaken an invariant to make a test pass.** The unique index on
  `idempotency_key`, the append-only ledger, and the derived balance are not negotiable.
  If a criterion seems to require weakening one, stop and say so.
- **Ask before inventing a requirement.** If a spec has an open question, it is not ready.
  Say which question blocks you rather than picking an answer silently.

## Scope discipline

Read only what the task needs. Start from `spec_read`, then the package the spec names,
then the tests for it. Do not read the whole `src/` tree to "get oriented"; the
architecture is described above and that is the orientation.

For a change in `core/`: `core/<package>/` and its test package. That is the whole
context. Adding the adapter and config packages to that has never once helped.

## Commands

```
make core-check    fast, dependency-free domain check (~1s)   <- default inner loop
make spec-gate     acceptance criteria vs @SpecRef annotations
make layering      the architecture guard the hook runs
make test          full Maven suite (slow, needs deps; Docker tests are tagged out)
make verify        everything CI runs
```

An MCP server in `tools/mcp_server.py` exposes `spec_list`, `spec_read`, `spec_coverage`,
`core_check`, `run_tests` and `changed_files`. Prefer `spec_coverage` over reasoning about
coverage by reading files: it parses, you would guess.

## Conventions

- Java 21. Records for value objects, sealed interfaces where the set of outcomes is
  closed, exhaustive switch instead of `default`.
- Package-private constructors on Spring components; no field injection anywhere.
- Comments explain why, not what. A comment restating the line above it gets deleted.
- No new dependency without saying, in the PR, what it replaces and why the JDK is not
  enough.
