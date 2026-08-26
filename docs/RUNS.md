# Run journal

One entry per agent run. What was asked, what came back, what was wrong with it, what
changed as a result.

This file is the point. A workflow you cannot criticise is a workflow you have not really
used, and the entries where the agent was wrong are worth more than the ones where it was
right. Write the failure down even when it is embarrassing, especially when it is
embarrassing.

**Format.** Keep it short. If an entry takes more than ten lines, the run was too big.

```
## <n> — <date> — <what was asked>

**Asked:** the command and the spec
**Got:** what came back
**Wrong:** what was wrong with it, or "nothing"
**Changed:** what changed in the harness as a result, or "nothing"
**Cost:** rough, if you tracked it
```

---

## 1 — 2026-08-26 — Harness construction, not an agent run

**Asked:** nothing; this entry records how the repository was built, so the journal does
not start with a fabricated success.

**Got:** the service, the specs, the gate, the MCP server and the hooks were written and
verified directly. `make core-check` (21 assertions), `make layering` and `make spec-gate`
all pass. `mvn test` has not been run in the environment this was authored in, because
Maven Central was unreachable there.

**Wrong:** nothing yet, because the loop has not been run in anger.

**Changed:** n/a.

> **Everything below this line is yours to write.** Run `/implement-spec 002`, then
> `/verify-spec 002`, and record honestly what happened. Spec 002 is deliberately left
> unimplemented with three uncovered criteria and one open question, so the first real run
> has something to bite on. If the agent resolves that open question without asking, that
> is your first genuine entry and it is a good one.

---

## 2 — 2026-08-26 — Compile the Spring layer without a Maven repository

**Asked:** verify the Spring, JPA and test-framework sources compile, in an environment
where Maven Central was blocked and none of those jars were available.

**Got:** signature-faithful stubs for every external API these files touch, compiled
against the real sources with the pom's exact flags (`--release 21 -Xlint:all -Werror`),
with a control file first proving `this-escape` and `serial` lint actually fire. Zero
errors, zero warnings, in both source roots.

**Wrong:** nothing at compile level. But this proves only that it builds, which is
precisely the weaker of the two claims. See run 4 for what it did not prove.

**Changed:** nothing. Still worth one `make verify` on a machine with a warm Maven
repository before showing this to anyone; `flyway-mysql` carries no explicit version and
relies on the Boot BOM, and that is the one line that would fail at resolution rather than
at runtime.

---

## 3 — 2026-08-26 — Spec gate false alarm on `--spec`

Not an agent run, but a real defect found by running the tool, and it belongs here because
of what it nearly cost.

**Asked:** `python3 tools/spec_gate.py --spec 002`

**Got:** the three genuinely uncovered criteria, plus **19 dangling-reference errors**
naming every `WD-*` annotation in the repository.

**Wrong:** the filter was applied before the set of known criterion ids was built, so
scoping to one spec made every other spec's criteria look nonexistent. The failure output
was mostly noise wrapped around the three lines that mattered.

**Changed:** ids are now resolved against every spec and only the *reported* set is
filtered. Worth keeping in view: the bug did not make the gate miss anything, it made the
gate cry wolf, and a gate that cries wolf gets ignored within a week. False alarms are the
more dangerous failure mode for anything that sits in a pipeline.


---

## 4 — 2026-08-26 — Adversarial review found the headline criterion broken in production

The most useful run so far, and it found a bug in the thing this repository exists to
demonstrate.

**Asked:** a reviewing agent, given only the sources and the target library versions, to
find anything that would fail on a machine that could actually build them. Explicitly not
given the reasoning behind any of it.

**Got:** six defects. Five were ordinary (a `CHAR` column that fails `ddl-auto: validate`;
`TIMESTAMP` instead of `DATETIME` for an `Instant`; `save` doing a `merge` and a
guaranteed-miss SELECT before every insert because the id is application-assigned; an
exposed `prometheus` endpoint with no registry on the classpath; dead H2 and test-profile
config nothing loaded).

The sixth was not ordinary. **`@Transactional` on the debit endpoint breaks WD-6, the one
criterion the whole design is built around.** The loser of the unique-index race marks the
shared transaction rollback-only, so the read-back runs against a poisoned persistence
context and the commit throws `UnexpectedRollbackException`. A successful retry returns
500. The reviewer also pre-empted the obvious wrong fix: `REQUIRES_NEW` on the append
would make it worse, because MySQL's REPEATABLE READ snapshot would then hide the
committed row from the balance re-read and break WD-1 instead.

**Wrong — and this is the part worth keeping:** *the spec gate said this spec was fully
covered.* All nine criteria had tests, all nine passed. WD-6 had two of them. But
`WalletServiceTest` used an in-memory store and `WalletControllerTest` mocked the service
outright, so nothing in the suite ever put the race through JPA. The gate proved a
criterion had a test. It cannot prove the test exercises the thing that breaks.

**Changed:**

- Removed `@Transactional` from the debit path, with the reasoning written into the
  class javadoc rather than left in a commit message.
- `VARCHAR(36)` and `DATETIME(6)` in `V1`, with a comment saying why.
- `LedgerEntryEntity implements Persistable<String>` with `isNew()` always true.
- Added the Prometheus registry; deleted the dead H2 dependency and test profile.
- Added `JpaLedgerEntryStoreIT`: `@DataJpaTest` against a real MySQL via Testcontainers,
  tagged `docker`, running 16 concurrent appends of one key. That test fails on all three
  of the schema and transaction defects above. It is the test that should have existed
  before the gate was ever green.

**The lesson for the harness, not just the code:** a coverage gate measures whether a
criterion was *attempted*, not whether it was *established*. Both agents in this workflow
saw the same in-memory fake and neither had any reason to doubt it. The counterweight is
the adversarial pass, and it only worked because the reviewer was denied the reasoning
that made the fake look sufficient. That is the argument for keeping the verifier blind,
stated better than I could have stated it in advance.
