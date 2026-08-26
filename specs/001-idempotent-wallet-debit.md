---
id: 001
title: Idempotent wallet debit
status: implemented
owner: bojan
---

## Context

Players spend soft currency on troops, buildings and speed-ups. The client retries on any
network failure, and a mobile client on a poor connection retries a lot. A retry that
debits twice is the single worst bug this service can have: it is unrecoverable from the
player's point of view and it goes straight to support.

The wallet is an append-only ledger (`wallet_ledger_entry`) with balance derived by
folding entries. There is no stored balance column and there must never be one.

## Acceptance criteria

### WD-1 — A debit within the balance is recorded

Given a player with a balance of 500, when a debit of 200 arrives with a fresh
idempotency key, then exactly one new DEBIT entry is written, the response is `201`, and
the reported balance is 300.

### WD-2 — A debit above the balance is refused and writes nothing

Given a player with a balance of 500, when a debit of 501 arrives, then no entry is
written, the response is `422` with code `INSUFFICIENT_FUNDS`, and the body reports the
available balance so the client can render a useful message.

### WD-3 — A debit of exactly the balance succeeds

Given a player with a balance of 500, when a debit of 500 arrives, then it is applied and
the resulting balance is 0. The boundary is inclusive.

### WD-4 — Repeating the same request returns the original result

Given a debit that was already applied with key `K`, when the identical request arrives
again with key `K`, then no new entry is written, the response is `200` with header
`Idempotent-Replay: true`, and the body is the original entry. A difference in the
free-text `reason` field does not make it a different request.

### WD-5 — Reusing a key for a different request is refused

Given key `K` was used for a debit of 120, when a debit of 300 arrives with key `K`, then
the response is `409` with code `IDEMPOTENCY_KEY_REUSED` and nothing is written.
Answering with the original result would hide a client bug.

### WD-6 — Concurrent retries produce exactly one entry

When N identical debit requests with the same key run concurrently, then exactly one is
applied, the remaining N-1 return a replay, and the ledger contains exactly one new entry.
This must hold without a distributed lock and without `SELECT ... FOR UPDATE`.

### WD-7 — A zero debit is refused

When a debit of 0 arrives, then the response is `400` with code `ZERO_AMOUNT`. A zero
debit is always a client bug and silently accepting it burns an idempotency key.

### WD-8 — Balance is derived, never stored

Balance is computed by folding the player's entries. A history that folds to a negative
balance is an upstream corruption and must fail loudly rather than clamp to zero.

### WD-9 — A missing idempotency key is refused

When a debit arrives with no `Idempotency-Key` header, then the response is `400`. The
server must not invent a key, because an invented key makes every retry a new debit.

## Out of scope

- Credits. Currency is granted by a separate flow and is not part of this spec.
- Currency types beyond the single soft currency.
- Expiring old idempotency keys. The index will need a retention policy eventually; that
  is a separate spec with its own migration.
- Any read-your-writes guarantee across replicas.

## Invariants

- The ledger is append-only. No row is ever updated or deleted by application code.
- The unique index on `idempotency_key` is a correctness constraint, not an optimisation.
  No change may drop it or make it non-unique.
- Nothing in `core/` or `application/` may import a Spring, JPA or Jackson type.

## Open questions

None. This spec is closed.
