# Architecture

## Layers

```
adapter/web            HTTP. Maps DebitDecision onto status codes. Owns @Transactional.
adapter/persistence    JPA and Redis. Translates vendor errors into port exceptions.
        |
        v  implements
port/                  Interfaces the domain needs: LedgerEntryStore, LeaderboardStore.
        ^
        |  depends on
application/           Orchestration. WalletService, LeaderboardService. No framework.
core/                  Pure domain. WalletLedger, Ranker, value objects. No framework.
```

Dependencies point inwards only. `core/` knows about nothing. `adapter/` knows about
everything.

## Why the domain is framework-free

Three reasons, in order of how often they actually pay off.

**The inner loop.** `make core-check` compiles and exercises every domain rule in about a
second with plain `javac`, no Maven repository and no Docker. That is fast enough to run
after every single edit, which changes how the agent loop behaves: it can be told to
verify after each criterion instead of batching a large change and hoping.

**Testability.** The interesting wallet cases are combinations of history and command:
exact-balance debit, replay, key reuse with a different amount, a history that folds
negative. As a pure function those are four lines each. Behind a Spring context and a
database they are fixtures, and fixtures are where test suites go to become slow and
then get skipped.

**Blast radius.** Swapping Redis for a sharded leaderboard, or MySQL for something else,
touches `adapter/` and nothing else. That claim is only true if it is enforced, which is
why it is a hook and not a paragraph.

## The wallet

Append-only ledger, balance derived by folding entries. There is no balance column and
there must not be one: a stored balance and its history can disagree after a partial
failure, and reconciling them afterwards is a support ticket per player.

Idempotency is a unique index on `idempotency_key`, not a lock:

```
request A          request B (same key, concurrent)
   |                   |
   read history        read history          both see no such key
   decide Applied      decide Applied        both intend to write
   INSERT  -> ok       INSERT  -> unique violation
   201                 read back A's entry -> 200 Idempotent-Replay: true
```

The loser of the race does not retry and does not fail. It reads the winner's entry and
answers with it. This is correct for any number of concurrent retries, needs no
distributed lock, and costs one extra read on the rare path.

**No transaction wraps this.** Putting one around the debit endpoint breaks the race it
exists to handle: the loser marks the shared transaction rollback-only, the read-back runs
against a poisoned persistence context, and a successful retry returns 500. Nothing here
needs multi-statement atomicity, because the unique index is the concurrency control. See
`docs/RUNS.md`, entry 4.

Reusing a key with a *different* payload is a `409`, not a replay. Answering with the
original result would be technically idempotent and would hide a client bug that
otherwise shows up in a dashboard.

## The leaderboard

One Redis sorted set per season. `ZREVRANGE` for a window, `ZREVRANK` for a single
player's position, so neither read pulls the set into the application.

Ranks are computed by `core/leaderboard/Ranker` rather than taken from Redis, so the tie
rule lives in one place and does not depend on backend behaviour. Standard competition
ranking (`1, 2, 2, 4`), with ties ordered by ascending player id so that paging is stable.
Without that tiebreak, two players on the same score can swap between calls and a page
boundary silently duplicates or skips one of them.

**Known limit.** One key per season is one shard. Past roughly a million active players in
a season this needs bucketing by region or score band with a merge on read, and the merge
is the genuinely hard part. Not solved here, and pretending otherwise would be worse than
saying so.

## Known cost, not yet paid down

A debit folds the player's history twice: once inside `WalletService.debit` to make the
decision, and once again to report the resulting balance in the response. On a wallet with
a long history that is two unbounded scans per write. The fix is to carry the post-debit
balance out of `debit` rather than recomputing it, which means widening the service's
return type. Not done, because it ripples through the tests and the trade is not obviously
worth it at this size — but it is the first thing to fix if a player's history can grow
without bound, and a retention or snapshot policy is the real answer beyond that.

## Deliberate omissions

This is a small repository built to carry a workflow, not a production service. Missing on
purpose, and worth naming rather than hiding:

- Authentication and authorisation. Every endpoint is open.
- Rate limiting and load shedding.
- Idempotency key retention. The unique index grows without bound.
- Metrics beyond the Actuator defaults; no tracing.
- Anything about credits: currency arrives by a flow that does not exist here.
