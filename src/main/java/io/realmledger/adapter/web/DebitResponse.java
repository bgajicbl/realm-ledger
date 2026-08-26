package io.realmledger.adapter.web;

import java.time.Instant;

/** Result of a wallet debit. */
public record DebitResponse(
        String entryId, long playerId, long amount, long balance, Instant occurredAt) {
}
