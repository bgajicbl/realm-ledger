package io.realmledger.core.wallet;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable line in a player's wallet ledger.
 *
 * <p>Balance is derived by folding entries, never stored as a mutable column. That
 * costs a read but removes the whole class of bugs where a cached balance and its
 * history disagree after a partial failure.
 */
public record LedgerEntry(
        String entryId,
        PlayerId playerId,
        EntryType type,
        Amount amount,
        IdempotencyKey idempotencyKey,
        String reason,
        Instant occurredAt) {

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (entryId.isBlank()) {
            throw new IllegalArgumentException("entryId must not be blank");
        }
    }

    public boolean isDebit() {
        return type == EntryType.DEBIT;
    }

    /** Signed contribution of this entry to the running balance. */
    public long signedUnits() {
        return isDebit() ? -amount.units() : amount.units();
    }
}
