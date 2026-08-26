package io.realmledger.port;

import io.realmledger.core.wallet.IdempotencyKey;
import io.realmledger.core.wallet.LedgerEntry;
import io.realmledger.core.wallet.PlayerId;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the append-only wallet ledger.
 *
 * <p>The contract every implementation must honour: {@link #append} is guarded by a
 * unique index on {@code idempotency_key}, and a violation surfaces as
 * {@link DuplicateIdempotencyKeyException} rather than a vendor-specific exception.
 * That is what lets the application layer resolve the concurrent-retry race without
 * knowing whether it is talking to MySQL, H2 or a test double.
 */
public interface LedgerEntryStore {

    /** All entries for a player. Order is not guaranteed; the fold does not depend on it. */
    List<LedgerEntry> historyOf(PlayerId playerId);

    Optional<LedgerEntry> findByIdempotencyKey(IdempotencyKey key);

    /**
     * Appends one entry.
     *
     * @throws DuplicateIdempotencyKeyException if the key is already present
     */
    LedgerEntry append(LedgerEntry entry);
}
