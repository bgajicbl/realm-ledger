---
id: 002
title: Season leaderboard paging and player rank
status: draft
owner: bojan
---

## Context

Each season has a leaderboard held in a Redis sorted set, one key per season. The ranking
rules (`core/leaderboard/Ranker`) and the service (`application/LeaderboardService`)
already exist and are tested. What is missing is the behaviour around the edges of a page
and around a player who is not on the board at all, which is exactly where the current
implementation is likely to be wrong.

This spec is deliberately left unimplemented. It is the one to run the workflow on.

## Acceptance criteria

### LB-1 — Ties share a rank and the next rank skips

Two players on the same score receive the same rank, and the following player receives the
rank they would have had with no tie: `1, 2, 2, 4`, not `1, 2, 2, 3`.

### LB-2 — Tie order is stable across calls

Two players on the same score are always returned in the same order, so paging cannot
duplicate or skip a player at a page boundary. Order within a tie is by ascending player
id.

### LB-3 — A page past the end of the board is empty, not an error

When `offset` is beyond the number of players in the season, then the response is `200`
with an empty player list. An empty board and an out-of-range page are the same answer.

### LB-4 — Page size is bounded

When `limit` exceeds the maximum page size, then the response is `400`. An unbounded limit
lets one client pull an entire season's board and stall the event loop for everyone.

### LB-5 — A player with no score has no rank

When the rank of a player who never scored in this season is requested, then the response
is `404`, not rank 0 and not the last rank. A player who has not played is absent, not
last.

## Out of scope

- Sharding a season across multiple Redis keys. Needed above roughly a million active
  players per season; the merge-on-read is the hard part and it deserves its own spec.
- Historic seasons after their key has expired.
- Any anti-cheat validation of submitted scores.

## Invariants

- Ranks are 1-based everywhere they cross the API boundary.
- The tie rule lives in `Ranker` and nowhere else. No adapter may re-implement it.
- A read never mutates the sorted set.

## Open questions

- Should `LB-3` distinguish "season does not exist" from "page is past the end"? Currently
  both return an empty page. Decide before implementing, because the answer changes
  whether the store needs an existence check.
